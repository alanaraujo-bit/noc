# Noc Image Worker
#
# Processo filho do Noc Companion. Fala só por stdin/stdout (uma mensagem JSON por linha): não abre porta,
# não acessa a rede (HF_HUB_OFFLINE) e só carrega pesos .safetensors. Tudo que é log vai para stderr.
#
# Comandos (Companion -> worker):
#   {"cmd":"hello"}                                   -> {"ev":"hello", versões, GPU}
#   {"cmd":"load","engine":"zimage|flux","path":...,"transformer":"bf16|fp8","text_encoder":"auto|gpu|swap","cache":...}
#   {"cmd":"generate","id":...,"prompt":...,"width":..,"height":..,"steps":..,"guidance":..,"seed":..,"count":..,"out":...,"preview":bool}
#   {"cmd":"rewrite","id":...,"text":...,"mode":"translate|enhance","path":<pasta do Qwen3 do Z-Image>}
#   {"cmd":"cancel","id":...}  (atendido na hora, mesmo no meio de uma geração)
#   {"cmd":"unload"} / {"cmd":"status"} / {"cmd":"shutdown"}
# Eventos (worker -> Companion): sempre {"ev":...}; respostas de comando levam "id" quando o comando tinha.

from noc_common import *  # noqa: E402,F401,F403  (canal do protocolo primeiro, antes de qualquer biblioteca)
import noc_common  # noqa: E402
import re  # noqa: E402
import numpy as np  # noqa: E402
from PIL import ImageFilter  # noqa: E402

WORKER_VERSION = "2.0.0"

# ------------------------------------------------------------------ motores

class Engine:
    name = ""

    def __init__(self):
        self.pipe = None
        self.te_mode = "gpu"      # gpu | swap
        self.transformer_precision = "bf16"
        self.load_seconds = None
        self.path = None

    def text_encoders(self):
        return []

    def encode(self, prompt):
        raise NotImplementedError

    def run(self, embeds, width, height, steps, guidance, generator, callback):
        return self.run_op(embeds, "txt2img", width, height, steps, guidance, generator, callback)

    # pipelines de edição derivados do principal (derive_pipe: os mesmos pesos na VRAM, nada duplicado)
    EDIT_PIPES = {}

    def edit_pipe(self, op):
        if op == "txt2img":
            return self.pipe
        if not hasattr(self, "_variants"):
            self._variants = {}
        p = self._variants.get(op)
        if p is None:
            import diffusers as d
            cls = getattr(d, self.EDIT_PIPES[op])
            p = derive_pipe(cls, self.pipe)
            self._variants[op] = p
        return p

    def extra_kwargs(self, embeds):
        return {"prompt_embeds": embeds["prompt_embeds"]}

    def run_op(self, embeds, op, width, height, steps, guidance, generator, callback, init=None, mask=None, strength=None, **_):
        pipe = self.edit_pipe(op)
        kw = dict(self.extra_kwargs(embeds), width=width, height=height, num_inference_steps=steps, guidance_scale=guidance,
                  generator=generator, callback_on_step_end=callback, callback_on_step_end_tensor_inputs=["latents"], output_type="pil")
        if op in ("img2img", "inpaint"):
            kw.update(image=init, strength=strength)
        if op == "inpaint":
            kw["mask_image"] = mask
        return pipe(**kw).images[0]

    def preview(self, latents):
        return None

    # text encoders fora da GPU (modo "swap"): sobem só para codificar o prompt
    def _te_to(self, device):
        for te in self.text_encoders():
            te.to(device)

    def encode_prompt(self, prompt):
        if self.te_mode == "swap":
            self._te_to("cuda")
        try:
            with torch.inference_mode():
                return self.encode(prompt)
        finally:
            if self.te_mode == "swap":
                self._te_to("cpu")
                free_cuda()


class ZImageEngine(Engine):
    name = "zimage"

    def load(self, path, transformer_precision, te_mode, cache, progress):
        from diffusers import ZImagePipeline, ZImageTransformer2DModel
        from transformers import Qwen3Model
        self.path = path
        t0 = time.time()
        progress("text_encoder")
        if te_mode == "fp8":
            te = load_qwen_te_fp8(os.path.join(path, "text_encoder"), cache)
        else:
            te = Qwen3Model.from_pretrained(os.path.join(path, "text_encoder"), torch_dtype=BF16, use_safetensors=True)
            te.requires_grad_(False)
        progress("transformer")
        transformer = load_transformer(ZImageTransformer2DModel, path, cache, transformer_precision, progress)
        transformer.requires_grad_(False)
        progress("pipeline")
        pipe = ZImagePipeline.from_pretrained(path, transformer=transformer, text_encoder=te, torch_dtype=BF16,
                                              use_safetensors=True)
        pipe.vae.to("cuda")
        pipe.set_progress_bar_config(disable=True)
        self.pipe = pipe
        self.transformer_precision = transformer_precision
        self.te_mode = te_mode
        if te_mode == "gpu":
            te.to("cuda")
        self.load_seconds = time.time() - t0

    def text_encoders(self):
        return [self.pipe.text_encoder]

    def encode(self, prompt):
        embeds, _ = self.pipe.encode_prompt(prompt=prompt, device=torch.device("cuda"), do_classifier_free_guidance=False)
        return {"prompt_embeds": [e.to("cuda") for e in embeds]}

    EDIT_PIPES = {"img2img": "ZImageImg2ImgPipeline", "inpaint": "ZImageInpaintPipeline"}

    def run_op(self, embeds, op, width, height, steps, guidance, generator, callback, **kw):
        return super().run_op(embeds, op, width, height, steps, 0.0, generator, callback, **kw)


class FluxEngine(Engine):
    name = "flux"

    def load(self, path, transformer_precision, te_mode, cache, progress):
        from diffusers import FluxPipeline, FluxTransformer2DModel
        from transformers import T5EncoderModel, CLIPTextModel
        self.path = path
        t0 = time.time()
        progress("text_encoder")
        clip = CLIPTextModel.from_pretrained(os.path.join(path, "text_encoder"), torch_dtype=BF16, use_safetensors=True)
        t5 = T5EncoderModel.from_pretrained(os.path.join(path, "text_encoder_2"), torch_dtype=BF16, use_safetensors=True)
        clip.requires_grad_(False)
        t5.requires_grad_(False)
        if te_mode == "fp8":
            te_to_fp8(t5)
        progress("transformer")
        transformer = load_transformer(FluxTransformer2DModel, path, cache, transformer_precision, progress)
        progress("pipeline")
        pipe = FluxPipeline.from_pretrained(path, transformer=transformer, text_encoder=clip, text_encoder_2=t5,
                                            torch_dtype=BF16, use_safetensors=True)
        pipe.vae.to("cuda")
        pipe.set_progress_bar_config(disable=True)
        self.pipe = pipe
        self.transformer_precision = transformer_precision
        self.te_mode = te_mode
        clip.to("cuda")  # CLIP é pequeno (0,25 GB): sempre na GPU
        if te_mode == "gpu":
            t5.to("cuda")
        self.load_seconds = time.time() - t0

    def text_encoders(self):
        return [self.pipe.text_encoder_2]

    def encode(self, prompt):
        pe, pooled, _ = self.pipe.encode_prompt(prompt=prompt, prompt_2=prompt, device=torch.device("cuda"),
                                                num_images_per_prompt=1, max_sequence_length=512)
        return {"prompt_embeds": pe.to("cuda"), "pooled_prompt_embeds": pooled.to("cuda")}

    EDIT_PIPES = {"img2img": "FluxImg2ImgPipeline", "inpaint": "FluxInpaintPipeline"}

    def extra_kwargs(self, embeds):
        return {"prompt_embeds": embeds["prompt_embeds"], "pooled_prompt_embeds": embeds["pooled_prompt_embeds"]}


from noc_sdxl import SdxlEngine  # noqa: E402

ENGINES = {"zimage": ZImageEngine, "flux": FluxEngine, "sdxl": SdxlEngine}


# ------------------------------------------------------------------ reescrita do prompt (Qwen3-4B do Z-Image)

REWRITE_TRANSLATE = (
    "You turn a user's image request into a prompt for an image generator. Write the prompt in English.\n"
    "Rules: translate faithfully and keep every subject, object, color, place, style, count and detail the user asked for. "
    "Remove request phrasing such as 'create an image of' or 'gere uma imagem de'. "
    "Keep any text that should appear inside the image (usually in quotes) exactly as written, in its original language, inside quotes. "
    "Do not add people, and do not add gender, age, ethnicity or body details that were not requested. "
    "Output only the prompt, one paragraph, no explanations."
)
REWRITE_ENHANCE = (
    "You turn a user's image request into a rich prompt for an image generator. Write the prompt in English.\n"
    "Keep every element the user asked for and never change the main subject, the requested style or the requested mood. "
    "Enrich it with concrete visual details: composition and framing, camera and lens, lighting, materials and textures, "
    "atmosphere, color palette and level of detail, all consistent with the request. "
    "Remove request phrasing such as 'create an image of'. "
    "Keep any text that should appear inside the image (usually in quotes) exactly as written, in its original language, inside quotes. "
    "Do not add people who were not requested, and never add gender, age, ethnicity or body details that were not requested. "
    "At most 110 words. Output only the prompt, one paragraph, no explanations."
)


REWRITE_TAGS = (
    "You convert a user's image request (often in Portuguese) into Danbooru tags for an anime illustration model.\n"
    "Rules:\n"
    "- Output ONE line of comma-separated English Danbooru tags, lowercase, spaces instead of underscores.\n"
    "- Use real Danbooru tags (1girl, 1boy, 2girls, solo, silver hair, long hair, reading, book, library, bookshelf, indoors, "
    "armor, knight, dragon, fighting, smile, school uniform, cherry blossoms, night, city...).\n"
    "- Start with the subject count that matches the request exactly: one girl/woman -> 1girl, solo; one boy/man -> 1boy, solo; "
    "two boys -> 2boys; a girl and a boy -> 1girl, 1boy; robots, animals, objects, food or landscapes without people -> no humans.\n"
    "- Translate only what was requested. Do NOT add art styles (realistic, 3d, anime style, fantasy art), lighting, mood, "
    "camera, color palette or quality tags unless the user asked for them. Never add clothing, body, age or ethnicity details "
    "that were not requested.\n"
    "- Keep text that must appear in the image in quotes.\n"
    "- Between 5 and 25 tags, never repeat a tag, no explanations.\n"
    "Example: garota de cabelo prateado lendo um livro numa biblioteca antiga -> 1girl, solo, silver hair, reading, book, library, bookshelf, indoors\n"
    "Example: um cavaleiro de armadura dourada lutando contra um dragão -> 1boy, knight, golden armor, fighting, dragon, battle\n"
    "Example: um gato dormindo numa janela chuvosa -> no humans, cat, sleeping, window, rain\n"
    "Example: dois garotos jogando futebol na praia -> 2boys, playing soccer, soccer ball, beach, outdoors"
)
REWRITE_PONY = (
    "You turn a user's image request (often in Portuguese) into a prompt for an anime/cartoon image model that understands "
    "English sentences and Danbooru tags. Output one line: a short English sentence describing exactly what was requested, "
    "then a comma, then up to 15 comma-separated lowercase Danbooru tags for the key details. The first tag is the subject count "
    "matching the request exactly (one girl -> 1girl, solo; two boys -> 2boys; a girl and a boy -> 1girl, 1boy; robots, animals, "
    "objects or landscapes without people -> no humans), then subject, clothing, action and setting. Translate only what was requested: do NOT add art styles, lighting, mood, camera or "
    "quality tags unless asked, and never add clothing, body, age or ethnicity details that were not requested. Keep text "
    "that must appear in the image in quotes. No score, rating or source tags, no explanations.\n"
    "Example: garota de cabelo prateado lendo um livro numa biblioteca -> A girl with silver hair reading a book in a library, "
    "1girl, solo, silver hair, reading, book, library, bookshelf, indoors"
)
REWRITE_MODES = {"translate": REWRITE_TRANSLATE, "enhance": REWRITE_ENHANCE, "tags": REWRITE_TAGS, "pony": REWRITE_PONY}


class Rewriter:
    """
    Usa o Qwen3-4B que o Z-Image já traz como codificador de texto. Com o Z-Image carregado, compartilha os pesos;
    com outro motor, sobe a cópia fp8 (4,4 GB) e pode mantê-la residente enquanto o motor SDXL trabalha (keep).
    """

    def __init__(self):
        self.tokenizer = None
        self.own_model = None
        self.own_key = None

    def _causal_lm(self, qwen_dir, shared_model):
        from transformers import AutoConfig, Qwen3ForCausalLM
        cfg = AutoConfig.from_pretrained(qwen_dir)
        with torch.device("meta"):
            lm = Qwen3ForCausalLM(cfg)
        lm.model = shared_model
        lm.lm_head.weight = shared_model.embed_tokens.weight  # tie_word_embeddings
        lm.eval()
        return lm

    def release(self):
        if self.own_model is not None:
            self.own_model = None
            self.own_key = None
            free_cuda()

    @property
    def resident(self):
        return self.own_model is not None

    def rewrite(self, text, mode, qwen_dir, tokenizer_dir, engine, cache=None, keep=False):
        from transformers import AutoTokenizer, Qwen3Model
        if self.tokenizer is None:
            self.tokenizer = AutoTokenizer.from_pretrained(tokenizer_dir)
        loaded_here = False
        moved = False
        if isinstance(engine, ZImageEngine) and engine.pipe is not None:
            self.release()
            base = engine.pipe.text_encoder
            moved = engine.te_mode == "swap"
            if moved:
                base.to("cuda")
        elif self.own_model is not None and self.own_key == qwen_dir:
            base = self.own_model
        else:
            if cache and os.path.isfile(os.path.join(cache, "text_encoder-fp8", "noc-cache.json")):
                base = load_qwen_te_fp8(qwen_dir, cache)
            else:
                base = Qwen3Model.from_pretrained(qwen_dir, torch_dtype=BF16, use_safetensors=True, device_map="cuda")
                base.requires_grad_(False)
            if keep:
                self.own_model, self.own_key = base, qwen_dir
            else:
                loaded_here = True
        try:
            lm = self._causal_lm(qwen_dir, base)
            messages = [
                {"role": "system", "content": REWRITE_MODES.get(mode, REWRITE_TRANSLATE)},
                {"role": "user", "content": text},
            ]
            prompt = self.tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True, enable_thinking=False)
            ids = self.tokenizer(prompt, return_tensors="pt").to("cuda")
            with torch.inference_mode():
                out = lm.generate(**ids, max_new_tokens=320, do_sample=False, repetition_penalty=1.05,
                                  pad_token_id=self.tokenizer.eos_token_id)
            result = self.tokenizer.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=True).strip()
            if "</think>" in result:
                result = result.split("</think>", 1)[1].strip()
            result = result.strip().strip('"').strip()
            if mode in ("tags", "pony"):
                # uma linha só: tags em linhas separadas (ou com "- ") viram lista separada por vírgulas
                parts = [p.strip(" -*\t") for p in re.split(r"[\n,]+", result)]
                result = ", ".join(" ".join(p.split()) for p in parts if p.strip(" -*\t"))
            return result
        finally:
            if moved:
                base.to("cpu")
            if loaded_here:
                del base
            free_cuda()


# ------------------------------------------------------------------ estado e comandos

import noc_tools  # noqa: E402
from noc_sdxl import (load_rgb, load_mask, mask_bbox, expand_box_to_aspect, fit_area, feather, outpaint_canvas, snap8)  # noqa: E402


class State:
    engine = None
    rewriter = Rewriter()
    upscaler = noc_tools.Upscaler()
    detector = noc_tools.Detector()
    depth = noc_tools.Depth()
    pose = noc_tools.Pose()
    cancel_ids = set()
    cancel_lock = threading.Lock()
    current_id = None


def is_cancelled(job_id):
    with State.cancel_lock:
        return job_id in State.cancel_ids


def latent_preview(eng, pipe, latents, width, height):
    """Prévia real do estado atual (latente -> RGB por combinação linear), sem custo de VAE."""
    with torch.no_grad():
        rgb = eng.preview(latents) if isinstance(eng, SdxlEngine) else None
        if rgb is None:
            lat = latents
            if lat.dim() == 3:  # FLUX: latentes empacotados (B, seq, 64)
                lat = pipe._unpack_latents(lat, height, width, pipe.vae_scale_factor)
            if lat.dim() == 5:
                lat = lat[:, :, 0]
            lat = lat[0].float()
            if lat.shape[0] != 16:
                return None
            f = torch.tensor(LATENT_RGB, device=lat.device, dtype=lat.dtype)
            b = torch.tensor(LATENT_RGB_BIAS, device=lat.device, dtype=lat.dtype)
            rgb = torch.einsum("chw,cr->hwr", lat, f) + b
            rgb = ((rgb + 1.0) / 2.0).clamp(0, 1).mul(255).byte().cpu().numpy()
    img = Image.fromarray(rgb)
    img.thumbnail((192, 192))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=70)
    return base64.b64encode(buf.getvalue()).decode("ascii")


def save_image(img, out_base):
    png = out_base + ".png"
    thumb = out_base + ".thumb.jpg"
    tmp = png + ".tmp"
    img.save(tmp, format="PNG", compress_level=4)  # sem metadados de texto: o prompt nunca vai dentro do arquivo
    os.replace(tmp, png)
    t = img.copy()
    t.thumbnail((640, 640), Image.LANCZOS)
    ttmp = thumb + ".tmp"
    t.convert("RGB").save(ttmp, format="JPEG", quality=85, optimize=True)
    os.replace(ttmp, thumb)
    return png, thumb


def save_aux(img, out_base, suffix):
    """Imagem auxiliar (mapa de pose/profundidade/bordas usado): o celular pode mostrar o que guiou a geração."""
    path = f"{out_base}.{suffix}.jpg"
    t = img.copy()
    t.thumbnail((768, 768))
    t.convert("RGB").save(path + ".tmp", format="JPEG", quality=85)
    os.replace(path + ".tmp", path)
    return path


def cmd_load(c):
    kind = c.get("engine")
    if kind not in ENGINES:
        raise WorkerError("bad_request", "motor desconhecido")
    if kind == "sdxl":
        return cmd_load_sdxl(c)
    precision = c.get("transformer", "bf16")
    te_mode = c.get("text_encoder", "gpu")
    path = c["path"]
    cache = c.get("cache") or os.path.join(path, ".noc-cache")
    cur = State.engine
    if cur is not None and cur.name == kind and cur.transformer_precision == precision and cur.te_mode == te_mode and cur.path == path:
        send("loaded", id=c.get("id"), engine=kind, seconds=0, reused=True, mem=gpu_mem(), **rss_mb())
        return
    drop_engine()
    if not os.path.isdir(path):
        raise WorkerError("model_missing", "pasta do modelo não encontrada")
    torch.cuda.reset_peak_memory_stats()
    guard_vram()
    eng = ENGINES[kind]()
    eng.load(path, precision, te_mode, cache, lambda stage: send("load_progress", id=c.get("id"), stage=stage))
    State.engine = eng
    free_cuda()
    send("loaded", id=c.get("id"), engine=kind, seconds=round(eng.load_seconds, 2), transformer=precision, textEncoder=te_mode,
         mem=gpu_mem(), peakAllocatedMb=torch.cuda.max_memory_allocated() // (1024 * 1024), **rss_mb())


def drop_engine():
    cur = State.engine
    if cur is None:
        return
    State.engine = None
    if isinstance(cur, SdxlEngine):
        cur.unload_extras()
    cur.pipe = None
    if hasattr(cur, "_variants"):
        cur._variants = {}
    del cur
    free_cuda()


def cmd_load_sdxl(c):
    path = c["path"]
    dtype = c.get("dtype", "bf16")
    vae = c.get("vae")
    cur = State.engine
    if isinstance(cur, SdxlEngine) and cur.path == path and cur.vae_path == vae and ("fp16" if cur.dtype == torch.float16 else "bf16") == dtype:
        send("loaded", id=c.get("id"), engine="sdxl", seconds=0, reused=True, mem=gpu_mem(), **rss_mb())
        return
    drop_engine()
    torch.cuda.reset_peak_memory_stats()
    guard_vram()
    eng = SdxlEngine()
    eng.load(path, c["config"], dtype, vae, lambda stage: send("load_progress", id=c.get("id"), stage=stage))
    eng.vae_path = vae
    State.engine = eng
    free_cuda()
    send("loaded", id=c.get("id"), engine="sdxl", seconds=round(eng.load_seconds, 2), transformer=dtype, textEncoder="gpu",
         mem=gpu_mem(), peakAllocatedMb=torch.cuda.max_memory_allocated() // (1024 * 1024), **rss_mb())


def _fault_oom():
    """Só para testes automatizados: se o arquivo indicado em NOC_IMAGE_FAULT_OOM existir, consome-o e simula falta de VRAM."""
    f = os.environ.get("NOC_IMAGE_FAULT_OOM")
    if f and os.path.isfile(f):
        os.remove(f)
        raise torch.OutOfMemoryError("CUDA out of memory (simulado)")


class Runner:
    """Uma geração (um comando generate): prepara as entradas, gera cada imagem e aplica os pós-processos."""

    def __init__(self, c):
        self.c = c
        self.job = c["id"]
        self.eng = State.engine
        self.sdxl = isinstance(self.eng, SdxlEngine)
        self.step_times = []
        self.phase = "generating"
        self.aux = []

    # -------------------------------------------------------------- progresso

    def callback(self, idx, count, steps, preview, preview_every, width, height, t0, first_ms, phase="generating", part=None):
        last = [time.time()]

        def cb(pipe, i, t, kw):
            now = time.time()
            dt = now - last[0]
            last[0] = now
            self.step_times.append(dt)
            if first_ms[0] is None:
                first_ms[0] = int((now - t0) * 1000)
            real_steps = getattr(pipe, "_num_timesteps", None) or steps
            ev = {"id": self.job, "image": idx, "count": count, "step": i + 1, "steps": real_steps, "stepMs": int(dt * 1000), "phase": phase}
            if part is not None:
                ev["part"], ev["parts"] = part
            if preview and phase == "generating" and (i + 1) % preview_every == 0 and (i + 1) < real_steps and (i + 1) >= real_steps * 0.3:
                try:
                    p = latent_preview(self.eng, pipe, kw["latents"], width, height)
                    if p:
                        ev["preview"] = p
                except Exception as e:  # prévia é opcional; nunca derruba a geração
                    log(f"prévia falhou: {type(e).__name__}")
            if i + 1 == real_steps:
                torch.cuda.empty_cache()
                ev["denoisePeakMb"] = torch.cuda.max_memory_allocated() // (1024 * 1024)
                free_now = torch.cuda.mem_get_info()[0]
                tiled = free_now < 3 * GiB
                try:
                    if tiled:
                        pipe.vae.enable_tiling()
                    else:
                        pipe.vae.disable_tiling()
                except Exception:  # noqa: BLE001
                    pass
                ev["vaeTiled"] = tiled
            send("progress", **ev)
            if is_cancelled(self.job):
                pipe._interrupt = True
            return kw
        return cb

    def check(self):
        if is_cancelled(self.job):
            raise Cancelled()

    # -------------------------------------------------------------- partes

    def sched(self):
        c = self.c
        return dict(sampler=c.get("sampler", "euler"), schedule=c.get("schedule", "normal"),
                    prediction=c.get("prediction", "epsilon"), zsnr=bool(c.get("zsnr", False)),
                    guidance_rescale=float(c.get("guidanceRescale", 0.0)))

    def gen(self, embeds, op, w, h, steps, guidance, seed, cb, init=None, mask=None, strength=None, control=None, reference=None):
        g = torch.Generator("cuda").manual_seed(seed)
        with torch.inference_mode():
            if self.sdxl:
                return self.eng.run(embeds, op, w, h, steps, guidance, g, cb, init=init, mask=mask, strength=strength,
                                    control=control, reference=reference, **self.sched())
            return self.eng.run_op(embeds, op, w, h, steps, guidance, g, cb, init=init, mask=mask, strength=strength)

    def control_map(self, spec, source, size):
        """Mapa de controle a partir da imagem de referência (pose, profundidade, bordas) ou já pronto."""
        kind = spec["type"]
        img = source.resize(size, Image.LANCZOS) if source.size != size else source
        if not spec.get("preprocess", True) or kind in ("tile", "repaint"):
            return img, kind
        send("stage", id=self.job, stage="analyzing", what=kind)
        aux = spec.get("aux", {})
        if kind == "pose":
            m, people = State.pose.map(img, aux["detector"], aux["pose"])
            State.pose.unload()
            if people == 0:
                raise WorkerError("no_pose", "Não encontrei ninguém na imagem de referência para copiar a pose.")
            return m, kind
        if kind == "depth":
            m = State.depth.map(img, aux["depth"])
            State.depth.unload()
            return m, kind
        if kind in ("canny", "lineart", "edges"):
            return noc_tools.canny_map(img), kind
        raise WorkerError("bad_request", "controle desconhecido")

    def detail(self, img, spec, embeds, steps, guidance, seed, idx, count):
        """Detail pass: acha rostos/mãos, refaz cada um em alta resolução e cola de volta com transição suave."""
        labels = spec.get("targets") or ["face"]
        send("stage", id=self.job, stage="detecting", image=idx, count=count)
        found = State.detector.detect(img, labels, spec["detector"], threshold=float(spec.get("threshold", 0.3)))
        State.detector.unload()
        found = found[: int(spec.get("max", 6))]
        send("stage", id=self.job, stage="detailing", image=idx, count=count, regions=len(found))
        strength = float(spec.get("strength", 0.4))
        d_steps = max(steps, int(round(8 / max(0.05, strength)))) if not self.sdxl else steps
        for n, (label, score, box) in enumerate(found):
            self.check()
            crop_box = noc_tools.crop_for_detail(img, box, pad_ratio=0.35 if label == "face" else 0.5)
            crop = img.crop(crop_box)
            side = crop.size[0]
            gen_side = 1024 if self.sdxl else 1024
            tw = th = snap8(gen_side)
            k = tw / side
            bx = [(box[0] - crop_box[0]) * k, (box[1] - crop_box[1]) * k, (box[2] - crop_box[0]) * k, (box[3] - crop_box[1]) * k]
            mask = noc_tools.ellipse_mask((tw, th), [bx[0] - 0.12 * (bx[2] - bx[0]), bx[1] - 0.12 * (bx[3] - bx[1]),
                                                    bx[2] + 0.12 * (bx[2] - bx[0]), bx[3] + 0.12 * (bx[3] - bx[1])], blur=0)
            cb = self.callback(idx, count, d_steps, False, 99, tw, th, time.time(), [None], phase="detail", part=(n + 1, len(found)))
            out = self.gen(embeds, "inpaint", tw, th, d_steps, guidance, seed + 101 + n, cb,
                           init=crop.resize((tw, th), Image.LANCZOS), mask=mask, strength=strength)
            out = out.resize(crop.size, Image.LANCZOS)
            blend = noc_tools.ellipse_mask(crop.size, [(b - (crop_box[i % 2])) for i, b in enumerate(box)], blur=max(4, side // 24))
            # a elipse de mistura cobre a região detectada com folga
            bm = Image.new("L", crop.size, 0)
            ex = 0.1 * (box[2] - box[0])
            ey = 0.1 * (box[3] - box[1])
            from PIL import ImageDraw
            ImageDraw.Draw(bm).ellipse([box[0] - crop_box[0] - ex, box[1] - crop_box[1] - ey, box[2] - crop_box[0] + ex, box[3] - crop_box[1] + ey], fill=255)
            bm = bm.filter(ImageFilter.GaussianBlur(max(4, side // 24)))
            del blend
            img = img.copy()
            img.paste(out, crop_box[:2], bm)
        return img, [{"label": f[0], "score": round(f[1], 3)} for f in found]

    def upscale(self, img, spec, embeds, steps, guidance, seed, idx, count):
        """Ampliação 2x/4x (Real-ESRGAN) e, no modo Alta Definição, refino em blocos com o próprio modelo."""
        factor = int(spec.get("factor", 2))
        send("stage", id=self.job, stage="upscaling", image=idx, count=count, factor=factor)
        weights = noc_tools.esrgan_safetensors(spec["model"], spec["cache"])
        name = os.path.splitext(os.path.basename(spec["model"]))[0]
        up = State.upscaler.upscale(img, weights, name, cancelled=lambda: is_cancelled(self.job))
        State.upscaler.unload()
        target = (img.width * factor, img.height * factor)
        if up.size != target:
            up = up.resize(target, Image.LANCZOS)
        refine = spec.get("refine")
        if not refine:
            return up
        strength = float(refine.get("strength", 0.3))
        r_steps = int(refine.get("steps", steps))
        tile = int(refine.get("tile", 1024))
        overlap = int(refine.get("overlap", 128))
        boxes = noc_tools.tiles(up.width, up.height, tile, overlap)
        acc = np.zeros((up.height, up.width, 3), dtype=np.float32)
        wsum = np.zeros((up.height, up.width, 1), dtype=np.float32)
        send("stage", id=self.job, stage="refining", image=idx, count=count, tiles=len(boxes))
        for n, (x0, y0, x1, y1) in enumerate(boxes):
            self.check()
            part = up.crop((x0, y0, x1, y1))
            tw, th = snap8(part.width), snap8(part.height)
            cb = self.callback(idx, count, r_steps, False, 99, tw, th, time.time(), [None], phase="refine", part=(n + 1, len(boxes)))
            ctl = None
            if self.sdxl and refine.get("cn"):
                cnspec = refine["cn"]
                self.eng.ensure_controlnet(cnspec["dir"], cnspec["weights"], cnspec["config"])
                ctl = {"image": part.resize((tw, th)), "mode": 6, "scale": float(cnspec.get("scale", 0.6))}
            out = self.gen(embeds, "img2img", tw, th, r_steps, guidance, seed + 1000 + n, cb,
                           init=part.resize((tw, th), Image.LANCZOS), strength=strength, control=ctl)
            out = np.asarray(out.resize(part.size, Image.LANCZOS)).astype(np.float32)
            wgt = noc_tools.tile_weight(part.width, part.height, min(overlap, part.width // 2, part.height // 2),
                                        x0 > 0, y0 > 0, x1 < up.width, y1 < up.height)
            acc[y0:y1, x0:x1] += out * wgt
            wsum[y0:y1, x0:x1] += wgt
        res = (acc / np.maximum(wsum, 1e-6)).clip(0, 255).astype(np.uint8)
        return Image.fromarray(res)

    # -------------------------------------------------------------- fluxo

    def run(self):
        c, eng, job = self.c, self.eng, self.job
        op = c.get("op", "txt2img")
        width = int(c.get("width", 1024))
        height = int(c.get("height", 1024))
        steps = int(c.get("steps", 9))
        guidance = float(c.get("guidance", 0.0))
        count = max(1, min(4, int(c.get("count", 1))))
        first = max(0, min(count, int(c.get("first", 0))))
        seed = c.get("seed")
        seed = int(seed) if seed is not None else secrets.randbelow(2 ** 31 - 1)
        preview = bool(c.get("preview", False))
        preview_every = max(1, int(c.get("previewEvery", 4)))
        strength = float(c.get("strength", 0.6))
        out = c["out"]
        t0 = time.time()
        torch.cuda.reset_peak_memory_stats()
        guard_vram()

        # entradas
        init = load_rgb(c["init"]) if c.get("init") else None
        mask = None
        if op in ("img2img", "inpaint", "outpaint", "post") and init is None:
            raise WorkerError("bad_request", "falta a imagem de origem")
        placed = None  # (x, y, w, h) da imagem original dentro da tela (outpaint)
        if op == "outpaint":
            e = c.get("expand") or {}
            l, t, r, b = (max(0, int(e.get(k, 0))) for k in ("left", "top", "right", "bottom"))
            if l + t + r + b == 0:
                raise WorkerError("bad_request", "nada para expandir")
            orig = init
            init, mask = outpaint_canvas(init, l, t, r, b)
            placed = (l, t, orig.width, orig.height)
            op = "inpaint"
            strength = 1.0
            c = dict(c, onlyMasked=False)
        elif op == "inpaint":
            mask = load_mask(c["mask"], init.size)
            if mask.getbbox() is None:
                raise WorkerError("bad_request", "a máscara está vazia")

        # LoRAs e referência (SDXL)
        applied = []
        if self.sdxl:
            applied = eng.apply_loras(c.get("loras") or [])
        elif c.get("loras"):
            raise WorkerError("lora_incompatible", "Este motor não usa LoRAs.")
        reference = None
        ref_spec = c.get("reference")
        if ref_spec:
            if not self.sdxl:
                raise WorkerError("bad_request", "Imagem de referência só nos modelos SDXL.")
            send("stage", id=job, stage="reference")
            eng.ensure_ip_adapter(ref_spec["ip"])
            reference = {"image": load_rgb(ref_spec["image"]), "scale": float(ref_spec.get("scale", 0.6))}
        control = None
        ctl_spec = c.get("control")

        try:
            send("stage", id=job, stage="encoding")
            if eng is None or eng.pipe is None:
                embeds = {}
            elif self.sdxl:
                embeds = eng.encode_prompt(c["prompt"], c.get("negative", ""), c.get("clipSkip"))
            else:
                embeds = eng.encode_prompt(c["prompt"])
            self.check()
            encode_ms = int((time.time() - t0) * 1000)
            results = []
            for idx in range(first, count):
                s = seed + idx
                first_ms = [None]
                gen_t0 = time.time()
                extra = {}
                send("stage", id=job, stage="generating", image=idx, count=count)
                if op == "post":
                    img = init
                elif op == "txt2img":
                    if ctl_spec:
                        src = load_rgb(ctl_spec["image"])
                        cmap, kind = self.control_map(ctl_spec, src, (width, height))
                        eng.ensure_controlnet(ctl_spec["cn"]["dir"], ctl_spec["cn"]["weights"], ctl_spec["cn"]["config"])
                        control = {"image": cmap, "mode": ctl_spec["mode"], "scale": float(ctl_spec.get("scale", 0.8)),
                                   "end": float(ctl_spec.get("end", 1.0))}
                        if idx == first:
                            self.aux.append(save_aux(cmap, f"{out}-{idx}", "control"))
                    cb = self.callback(idx, count, steps, preview, preview_every, width, height, gen_t0, first_ms)
                    img = self.gen(embeds, "txt2img", width, height, steps, guidance, s, cb, control=control, reference=reference)
                elif op == "img2img":
                    w, h = (width, height) if c.get("keepSize") is False else fit_area(*init.size, area=min(init.width * init.height, width * height) if c.get("keepArea") else width * height)
                    src = init.resize((w, h), Image.LANCZOS)
                    ctl = None
                    if ctl_spec:
                        cmap, kind = self.control_map(ctl_spec, load_rgb(ctl_spec["image"]) if ctl_spec.get("image") else init, (w, h))
                        eng.ensure_controlnet(ctl_spec["cn"]["dir"], ctl_spec["cn"]["weights"], ctl_spec["cn"]["config"])
                        ctl = {"image": cmap, "mode": ctl_spec["mode"], "scale": float(ctl_spec.get("scale", 0.8)), "end": float(ctl_spec.get("end", 1.0))}
                    cb = self.callback(idx, count, steps, preview, preview_every, w, h, gen_t0, first_ms)
                    img = self.gen(embeds, "img2img", w, h, steps, guidance, s, cb, init=src, strength=strength, control=ctl, reference=reference)
                    if c.get("restoreSize", True) and img.size != init.size:
                        img = img.resize(init.size, Image.LANCZOS)
                else:  # inpaint (também o outpaint)
                    img = self.inpaint(embeds, init, mask, steps, guidance, s, strength, idx, count, preview, preview_every, gen_t0, first_ms,
                                       only_masked=c.get("onlyMasked", True), reference=reference, ctl_spec=ctl_spec, placed=placed)
                self.check()
                for p in c.get("post") or []:
                    if p["kind"] == "detail":
                        img, regions = self.detail(img, p, embeds, steps if p.get("steps") is None else int(p["steps"]), guidance, s, idx, count)
                        extra["detail"] = regions
                    elif p["kind"] == "upscale":
                        img = self.upscale(img, p, embeds, steps, guidance, s, idx, count)
                        extra["upscale"] = int(p.get("factor", 2))
                    self.check()
                send("stage", id=job, stage="saving", image=idx, count=count)
                png, thumb = save_image(img, f"{out}-{idx}")
                gen_ms = int((time.time() - gen_t0) * 1000)
                results.append(dict({"index": idx, "seed": s, "png": png, "thumb": thumb, "width": img.width, "height": img.height,
                                     "ms": gen_ms, "firstStepMs": first_ms[0]}, **extra))
                send("image", id=job, **results[-1])
                del img
                free_cuda()
        finally:
            if self.sdxl:
                eng.clear_loras()
                if reference is not None:
                    eng.drop_ip_adapter()
        total_ms = int((time.time() - t0) * 1000)
        mean_step = (sum(self.step_times) / len(self.step_times)) if self.step_times else None
        send("done", id=job, images=results, loras=applied, aux=self.aux, stats={
            "totalMs": total_ms, "encodeMs": encode_ms, "steps": steps, "width": width, "height": height,
            "meanStepMs": int(mean_step * 1000) if mean_step else None,
            "peakAllocatedMb": torch.cuda.max_memory_allocated() // (1024 * 1024),
            "peakReservedMb": torch.cuda.max_memory_reserved() // (1024 * 1024),
            "engine": eng.name if eng else None, "transformer": eng.transformer_precision if eng else None, "textEncoder": eng.te_mode if eng else None,
            "chunks": embeds.get("chunks") if isinstance(embeds, dict) else None,
        }, mem=gpu_mem(), **rss_mb())

    def inpaint(self, embeds, img, mask, steps, guidance, seed, strength, idx, count, preview, preview_every, t0, first_ms,
                only_masked=True, reference=None, ctl_spec=None, placed=None):
        """
        Inpaint "só a área pintada": recorta a região (com contexto em volta), gera perto de 1 megapixel e cola de volta com
        borda suave — detalhe alto mesmo em áreas pequenas. Máscara grande (ou outpaint) usa a imagem inteira.
        """
        W, H = img.size
        box = mask_bbox(mask, 48, img.size)
        area = (box[2] - box[0]) * (box[3] - box[1]) if box else W * H
        if not only_masked or area > 0.6 * W * H:
            box = (0, 0, W, H)
        else:
            box = expand_box_to_aspect(box, img.size, min_side=min(512, W, H))
        crop = img.crop(box)
        mcrop = mask.crop(box)
        tw, th = fit_area(crop.width, crop.height, 1024 * 1024)
        blur = max(4, int(min(tw, th) / 128))
        if placed is not None:
            blur = max(blur, int(min(tw, th) / 24))  # expansão: transição larga entre o original e o novo
        ctl = None
        if ctl_spec and self.sdxl:
            src = load_rgb(ctl_spec["image"]) if ctl_spec.get("image") else crop
            cmap, kind = self.control_map(ctl_spec, src.crop(box) if ctl_spec.get("image") is None and src is not crop else src, (tw, th))
            self.eng.ensure_controlnet(ctl_spec["cn"]["dir"], ctl_spec["cn"]["weights"], ctl_spec["cn"]["config"])
            ctl = {"image": cmap, "mode": ctl_spec["mode"], "scale": float(ctl_spec.get("scale", 0.8)), "end": float(ctl_spec.get("end", 1.0))}
        cb = self.callback(idx, count, steps, preview, preview_every, tw, th, t0, first_ms)
        gen_mask = feather(mcrop.resize((tw, th), Image.LANCZOS), blur)
        out = self.gen(embeds, "inpaint", tw, th, steps, guidance, seed, cb, init=crop.resize((tw, th), Image.LANCZOS),
                       mask=gen_mask, strength=strength, control=ctl, reference=reference)
        out = out.resize(crop.size, Image.LANCZOS)
        paste_mask = feather(mcrop, max(3, int(blur * crop.width / tw)))
        res = img.copy()
        res.paste(out, box[:2], paste_mask)
        if placed is not None:
            # a imagem original fica intacta; só a costura mistura
            pass
        return res


def _plain_post(c):
    """Só ampliação (sem refino nem detail pass): não precisa de modelo de difusão carregado."""
    posts = c.get("post") or []
    return c.get("op") == "post" and posts and all(p.get("kind") == "upscale" and not p.get("refine") for p in posts)


def cmd_generate(c):
    _fault_oom()
    eng = State.engine
    if (eng is None or eng.pipe is None) and not _plain_post(c):
        raise WorkerError("not_loaded", "motor não carregado")
    State.current_id = c["id"]
    Runner(c).run()


def cache_ready(cache, name):
    return os.path.isfile(os.path.join(cache, name, "noc-cache.json"))


def cmd_optimize(c):
    """
    Cria as cópias otimizadas (carga rápida) sem carregar o motor. Roda num processo próprio, com prioridade
    baixa, e sai no fim (a conversão incha a memória do processo; o worker de geração começa limpo).
    """
    kind = c.get("engine")
    path, cache = c["path"], c["cache"]
    mode = c.get("transformer") or ("bf16" if kind == "zimage" else "fp8")
    job = c.get("id")
    t0 = time.time()
    if kind == "zimage":
        from diffusers import ZImageTransformer2DModel as cls
    elif kind == "flux":
        from diffusers import FluxTransformer2DModel as cls
    else:
        raise WorkerError("bad_request", "motor desconhecido")
    tname = f"transformer-{mode}"
    if not cache_ready(cache, tname):
        send("optimize_progress", id=job, part="transformer", progress=0)
        build_cache(cls, os.path.join(path, "transformer"), os.path.join(cache, tname), mode,
                    lambda p: send("optimize_progress", id=job, part="transformer", progress=round(p, 3)))
        gc.collect()
    if kind == "zimage" and not cache_ready(cache, "text_encoder-fp8"):
        send("optimize_progress", id=job, part="text_encoder", progress=0)
        build_qwen_te_fp8(os.path.join(path, "text_encoder"), cache)
        send("optimize_progress", id=job, part="text_encoder", progress=1)
    send("optimized", id=job, seconds=round(time.time() - t0, 1))


def cmd_rewrite(c):
    job = c.get("id")
    t0 = time.time()
    guard_vram()
    text = State.rewriter.rewrite(c["text"], c.get("mode", "translate"), c["path"], c.get("tokenizer") or c["path"], State.engine,
                                  cache=c.get("cache"), keep=bool(c.get("keep", False)))
    send("rewritten", id=job, text=text, ms=int((time.time() - t0) * 1000), resident=State.rewriter.resident)


def release_aux():
    State.rewriter.release()
    State.upscaler.unload()
    State.detector.unload()
    State.depth.unload()
    State.pose.unload()
    if isinstance(State.engine, SdxlEngine):
        State.engine.unload_extras()
    free_cuda()


def cmd_unload(c):
    release_aux()
    drop_engine()
    free_cuda()
    send("unloaded", id=c.get("id"), mem=gpu_mem(), **rss_mb())


def cmd_unload_aux(c):
    release_aux()
    send("unloaded_aux", id=c.get("id"), mem=gpu_mem(), **rss_mb())


def cmd_status(c):
    eng = State.engine
    send("status", id=c.get("id"), engine=eng.name if eng else None,
         transformer=eng.transformer_precision if eng else None, textEncoder=eng.te_mode if eng else None,
         rewriter=State.rewriter.resident, controlnet=bool(getattr(eng, "controlnet", None)),
         mem=gpu_mem(), **rss_mb())


def classify(e):
    if isinstance(e, WorkerError):
        return e.code, str(e)
    if isinstance(e, torch.OutOfMemoryError) or "out of memory" in str(e).lower():
        return "oom", "CUDA out of memory"
    if isinstance(e, (FileNotFoundError,)):
        return "model_missing", type(e).__name__
    msg = str(e)
    if "CUDA" in msg or "cuda" in msg or "cuDNN" in msg:
        return "cuda_error", type(e).__name__ + ": " + msg[:300]
    if isinstance(e, (OSError, EnvironmentError)) and ("safetensors" in msg or "No such file" in msg or "does not appear" in msg):
        return "model_missing", type(e).__name__
    return "pipeline_error", type(e).__name__ + ": " + msg[:300]


def handle(c):
    kind = c.get("cmd")
    try:
        if kind == "load":
            cmd_load(c)
        elif kind == "generate":
            cmd_generate(c)
        elif kind == "rewrite":
            cmd_rewrite(c)
        elif kind == "optimize":
            cmd_optimize(c)
        elif kind == "unload":
            cmd_unload(c)
        elif kind == "unload_aux":
            cmd_unload_aux(c)
        elif kind == "status":
            cmd_status(c)
        elif kind == "hello":
            send("hello", id=c.get("id"), worker=WORKER_VERSION, torch=torch.__version__, diffusers=diffusers.__version__,
                 transformers=transformers.__version__, cuda=torch.version.cuda, gpu=torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
                 cudaAvailable=torch.cuda.is_available(), mem=gpu_mem(), **rss_mb())
        else:
            raise WorkerError("bad_request", "comando desconhecido")
    except Cancelled:
        free_cuda()
        send("cancelled", id=c.get("id"), mem=gpu_mem())
    except Exception as e:  # noqa: BLE001
        code, message = classify(e)
        log(f"{kind} falhou: {code} {type(e).__name__}")
        log("".join(traceback.format_exception(type(e), e, e.__traceback__))[-4000:])
        free_cuda()
        send("error", id=c.get("id"), cmd=kind, code=code, message=message, mem=gpu_mem())
    finally:
        if kind == "generate":
            State.current_id = None
            with State.cancel_lock:
                State.cancel_ids.discard(c.get("id"))


def _stdin_lines():
    """
    Linhas do stdin sem nunca ficar bloqueado dentro do ReadFile. No Windows, enquanto uma thread espera num pipe
    síncrono, outra thread que carrega certas DLLs (ex.: o OpenBLAS do scipy, que o transformers importa) fica presa
    até chegar o próximo comando — medido: a carga do SDXL travava para sempre. Então: PeekNamedPipe e lê só o que já chegou.
    """
    if os.name != "nt":
        yield from sys.stdin.buffer
        return
    import ctypes
    import msvcrt
    from ctypes import wintypes
    fd = sys.stdin.fileno()
    handle = msvcrt.get_osfhandle(fd)
    peek = ctypes.windll.kernel32.PeekNamedPipe
    peek.argtypes = [wintypes.HANDLE, ctypes.c_void_p, wintypes.DWORD, ctypes.c_void_p, ctypes.POINTER(wintypes.DWORD), ctypes.c_void_p]
    peek.restype = wintypes.BOOL
    avail = wintypes.DWORD(0)
    buf = b""
    while True:
        if not peek(handle, None, 0, None, ctypes.byref(avail), None):
            break  # pipe fechado: o Companion foi embora
        if avail.value == 0:
            time.sleep(0.02)
            continue
        chunk = os.read(fd, avail.value)
        if not chunk:
            break
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            yield line
    if buf:
        yield buf


def reader(q):
    for raw in _stdin_lines():
        line = raw.decode("utf-8", errors="replace").strip()
        if not line:
            continue
        try:
            c = json.loads(line)
        except json.JSONDecodeError:
            continue
        if c.get("cmd") == "cancel":
            with State.cancel_lock:
                State.cancel_ids.add(c.get("id"))
            send("cancel_ack", id=c.get("id"), running=State.current_id == c.get("id"))
            continue
        q.put(c)
    q.put({"cmd": "shutdown"})  # stdin fechou: o Companion foi embora


def main():
    q = queue.Queue()
    threading.Thread(target=reader, args=(q,), daemon=True).start()
    send("ready", worker=WORKER_VERSION, torch=torch.__version__, cuda=torch.version.cuda,
         cudaAvailable=torch.cuda.is_available(), gpu=torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
         mem=gpu_mem())
    while True:
        c = q.get()
        if c.get("cmd") == "shutdown":
            break
        if c.get("cmd") == "generate" and is_cancelled(c.get("id")):
            send("cancelled", id=c.get("id"), mem=gpu_mem())
            continue
        handle(c)
    send("bye")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        send("fatal", code="worker_crash", message=type(e).__name__)
        raise
