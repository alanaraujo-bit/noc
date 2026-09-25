# Noc Image Worker — motor SDXL (RealVisXL, NoobAI, Pony e qualquer checkpoint SDXL em .safetensors).
#
# Um checkpoint = um arquivo .safetensors. A configuração dos componentes (tokenizers, scheduler, formatos) vem de um
# pacote local de configs do SDXL base: nada é buscado na rede e nenhum código vem junto com o modelo.
# Cada checkpoint traz o próprio preset (sampler, scheduler, CFG, v-prediction...) — o Companion manda tudo no pedido.

import inspect
import json
import math
import os
import re
import time

import torch
from PIL import Image, ImageFilter, ImageOps

from noc_common import send, log, WorkerError, free_cuda, fast_load, GiB  # noqa: F401

# Fatores latente -> RGB da VAE de 4 canais do SDXL (prévia instantânea sem decodificar a VAE).
SDXL_LATENT_RGB = [[0.3651, 0.4232, 0.4341], [-0.2533, -0.0042, 0.1068], [0.1076, 0.1111, -0.0362], [-0.3165, -0.2492, -0.2188]]
SDXL_LATENT_RGB_BIAS = [0.1084, -0.0175, -0.0011]

# ------------------------------------------------------------------ samplers

def _sampler_classes():
    from diffusers import (EulerDiscreteScheduler, EulerAncestralDiscreteScheduler, DPMSolverMultistepScheduler,
                           DPMSolverSinglestepScheduler, DDIMScheduler, UniPCMultistepScheduler, HeunDiscreteScheduler,
                           LMSDiscreteScheduler, KDPM2AncestralDiscreteScheduler)
    return {
        # nome estável (vai no pedido e na metadata) -> (classe, argumentos)
        "euler": (EulerDiscreteScheduler, {}),
        "euler_a": (EulerAncestralDiscreteScheduler, {}),
        "dpmpp_2m": (DPMSolverMultistepScheduler, {"algorithm_type": "dpmsolver++", "solver_order": 2}),
        "dpmpp_2m_sde": (DPMSolverMultistepScheduler, {"algorithm_type": "sde-dpmsolver++", "solver_order": 2}),
        "dpmpp_3m_sde": (DPMSolverMultistepScheduler, {"algorithm_type": "sde-dpmsolver++", "solver_order": 3}),
        "dpmpp_sde": (DPMSolverSinglestepScheduler, {"algorithm_type": "dpmsolver++"}),
        "ddim": (DDIMScheduler, {}),
        "unipc": (UniPCMultistepScheduler, {}),
        "heun": (HeunDiscreteScheduler, {}),
        "lms": (LMSDiscreteScheduler, {}),
        "dpm2_a": (KDPM2AncestralDiscreteScheduler, {}),
    }


SAMPLERS = ["euler", "euler_a", "dpmpp_2m", "dpmpp_2m_sde", "dpmpp_3m_sde", "dpmpp_sde", "ddim", "unipc", "heun", "lms", "dpm2_a"]
SCHEDULES = ["normal", "karras", "exponential", "beta", "sgm_uniform"]


def make_scheduler(base_config, sampler, schedule, prediction, zsnr):
    classes = _sampler_classes()
    if sampler not in classes:
        raise WorkerError("bad_request", f"sampler desconhecido: {sampler}")
    cls, args = classes[sampler]
    accepted = set(inspect.signature(cls.__init__).parameters)
    cfg = {k: v for k, v in dict(base_config).items() if not k.startswith("_")}
    cfg.update(args)
    cfg["prediction_type"] = prediction
    if zsnr and "rescale_betas_zero_snr" in accepted:
        cfg["rescale_betas_zero_snr"] = True
        cfg["timestep_spacing"] = "trailing"  # zero terminal SNR pede o último passo no fim da escala
    for k in ("use_karras_sigmas", "use_exponential_sigmas", "use_beta_sigmas"):
        cfg.pop(k, None)
    if schedule == "karras" and "use_karras_sigmas" in accepted:
        cfg["use_karras_sigmas"] = True
    elif schedule == "exponential" and "use_exponential_sigmas" in accepted:
        cfg["use_exponential_sigmas"] = True
    elif schedule == "beta" and "use_beta_sigmas" in accepted:
        cfg["use_beta_sigmas"] = True
    elif schedule == "sgm_uniform":
        cfg["timestep_spacing"] = "trailing"
    if sampler in ("dpmpp_2m", "dpmpp_2m_sde", "dpmpp_3m_sde") and "euler_at_final" in accepted:
        cfg["euler_at_final"] = True  # evita artefatos no último passo com poucos passos
    if sampler.startswith("dpmpp") and zsnr and "final_sigmas_type" in accepted:
        cfg["final_sigmas_type"] = "zero"
    return cls.from_config(cfg)

# ------------------------------------------------------------------ prompt longo com pesos (estilo A1111)

_RE_ATTN = re.compile(r"\\\(|\\\)|\\\[|\\\]|\\\\|\\|\(|\[|:\s*([+-]?[.\d]+)\s*\)|\)|\]|[^\\()\[\]:]+|:")
_RE_BREAK = re.compile(r"\s*\bBREAK\b\s*", re.S)


def parse_attention(text):
    """
    Divide o texto em (trecho, peso) na sintaxe do A1111: (a) = ×1.1, [a] = ÷1.1, (a:1.3) = ×1.3, \\( = parêntese literal.
    "BREAK" força um bloco novo de 75 tokens (peso -1 marca a quebra).
    """
    res = []
    round_brackets, square_brackets = [], []

    def multiply_range(start, mult):
        for p in range(start, len(res)):
            res[p][1] *= mult

    for m in _RE_ATTN.finditer(text):
        t = m.group(0)
        weight = m.group(1)
        if t.startswith("\\"):
            res.append([t[1:], 1.0])
        elif t == "(":
            round_brackets.append(len(res))
        elif t == "[":
            square_brackets.append(len(res))
        elif weight is not None and round_brackets:
            try:
                multiply_range(round_brackets.pop(), float(weight))
            except ValueError:
                pass
        elif t == ")" and round_brackets:
            multiply_range(round_brackets.pop(), 1.1)
        elif t == "]" and square_brackets:
            multiply_range(square_brackets.pop(), 1 / 1.1)
        else:
            parts = _RE_BREAK.split(t)
            for i, part in enumerate(parts):
                if i > 0:
                    res.append(["BREAK", -1])
                res.append([part, 1.0])
    for pos in round_brackets:
        multiply_range(pos, 1.1)
    for pos in square_brackets:
        multiply_range(pos, 1 / 1.1)
    if not res:
        res = [["", 1.0]]
    # junta trechos vizinhos com o mesmo peso
    i = 0
    while i + 1 < len(res):
        if res[i][1] == res[i + 1][1] and res[i][1] != -1:
            res[i][0] += res[i + 1][0]
            res.pop(i + 1)
        else:
            i += 1
    return res


def _chunks(tokenizer, text):
    """Tokens + pesos em blocos de até 75 (quebra preferencialmente logo depois de uma vírgula, como o A1111)."""
    comma = tokenizer.convert_tokens_to_ids(",</w>")
    chunks, cur, curw = [], [], []
    last_comma = -1

    def close():
        nonlocal cur, curw, last_comma
        chunks.append((cur, curw))
        cur, curw, last_comma = [], [], -1

    for seg, w in parse_attention(text):
        if w == -1:  # BREAK
            close()
            continue
        ids = tokenizer(seg, add_special_tokens=False, truncation=False).input_ids
        for tid in ids:
            if len(cur) == 75:
                if 0 <= last_comma and len(cur) - last_comma <= 20:
                    keep = last_comma + 1
                    rest, restw = cur[keep:], curw[keep:]
                    cur, curw = cur[:keep], curw[:keep]
                    close()
                    cur, curw = rest, restw
                else:
                    close()
            if tid == comma:
                last_comma = len(cur)
            cur.append(tid)
            curw.append(w)
    if cur or not chunks:
        close()
    return chunks


def _encode_chunks(tokenizer, encoder, chunks, n, clip_skip, device, want_pooled):
    """Codifica n blocos (completa com blocos vazios) e aplica os pesos, preservando a média (como o A1111)."""
    bos, eos = tokenizer.bos_token_id, tokenizer.eos_token_id
    pad = tokenizer.pad_token_id if tokenizer.pad_token_id is not None else eos
    outs, pooled = [], None
    for i in range(n):
        ids, ws = chunks[i] if i < len(chunks) else ([], [])
        ids = [bos] + ids[:75] + [eos]
        ws = [1.0] + ws[:75] + [1.0]
        ids += [pad] * (77 - len(ids))
        ws += [1.0] * (77 - len(ws))
        t = torch.tensor([ids], device=device)
        out = encoder(t, output_hidden_states=True)
        # clip_skip None = penúltima camada (padrão do SDXL; é o "clip skip 2" das interfaces)
        h = out.hidden_states[-2] if clip_skip is None else out.hidden_states[-(clip_skip + 2)]
        if want_pooled and pooled is None:
            pooled = out[0]
        w = torch.tensor(ws, device=device, dtype=h.dtype).view(1, -1, 1)
        if any(x != 1.0 for x in ws):
            before = h.float().mean()
            h = h * w
            after = h.float().mean()
            if after.abs() > 1e-6:
                h = h * (before / after).to(h.dtype)
        outs.append(h)
    return torch.cat(outs, dim=1), pooled


def encode_sdxl(pipe, prompt, negative, clip_skip=None):
    """
    Codificação do SDXL sem o limite de 77 tokens do diffusers: blocos de 75 tokens concatenados, pesos (a:1.2),
    BREAK, e o prompt negativo com o mesmo número de blocos. Devolve os 4 tensores que os pipelines SDXL aceitam.
    """
    dev = torch.device("cuda")
    tok1, tok2 = pipe.tokenizer, pipe.tokenizer_2
    te1, te2 = pipe.text_encoder, pipe.text_encoder_2
    pc1, nc1 = _chunks(tok1, prompt), _chunks(tok1, negative or "")
    pc2, nc2 = _chunks(tok2, prompt), _chunks(tok2, negative or "")
    n = max(len(pc1), len(nc1), len(pc2), len(nc2))
    with torch.inference_mode():
        p1, _ = _encode_chunks(tok1, te1, pc1, n, clip_skip, dev, False)
        p2, pp = _encode_chunks(tok2, te2, pc2, n, clip_skip, dev, True)
        n1, _ = _encode_chunks(tok1, te1, nc1, n, clip_skip, dev, False)
        n2, npool = _encode_chunks(tok2, te2, nc2, n, clip_skip, dev, True)
    dtype = pipe.unet.dtype
    return {
        "prompt_embeds": torch.cat([p1, p2], dim=-1).to(dtype),
        "negative_prompt_embeds": torch.cat([n1, n2], dim=-1).to(dtype),
        "pooled_prompt_embeds": pp.to(dtype),
        "negative_pooled_prompt_embeds": npool.to(dtype),
        "chunks": n,
    }

# ------------------------------------------------------------------ imagens auxiliares (máscaras, recortes, expansão)

def snap8(v):
    return max(64, int(round(v / 8.0)) * 8)


def fit_area(w, h, area=1024 * 1024):
    """Mesmo formato, área próxima de `area` (o SDXL rende melhor perto de 1 megapixel), múltiplos de 8."""
    k = math.sqrt(area / float(w * h))
    return snap8(w * k), snap8(h * k)


def load_rgb(path):
    img = Image.open(path)
    img = ImageOps.exif_transpose(img)
    if img.mode in ("RGBA", "LA", "P"):
        bg = Image.new("RGB", img.size, (255, 255, 255))
        rgba = img.convert("RGBA")
        bg.paste(rgba, mask=rgba.split()[-1])
        return bg
    return img.convert("RGB")


def load_mask(path, size):
    """Máscara: branco = mudar. Aceita PNG com alfa (pintado no celular) ou tons de cinza."""
    m = Image.open(path)
    if m.mode in ("RGBA", "LA"):
        m = m.split()[-1]
    m = m.convert("L")
    if m.size != size:
        m = m.resize(size, Image.NEAREST)
    return m.point(lambda v: 255 if v >= 128 else 0)


def mask_bbox(mask, pad, size):
    box = mask.getbbox()
    if box is None:
        return None
    x0, y0, x1, y1 = box
    W, H = size
    return max(0, x0 - pad), max(0, y0 - pad), min(W, x1 + pad), min(H, y1 + pad)


def expand_box_to_aspect(box, size, min_side=512):
    """Aumenta o recorte para no mínimo min_side e sem sair da imagem (contexto em volta da área pintada)."""
    x0, y0, x1, y1 = box
    W, H = size
    w, h = x1 - x0, y1 - y0
    tw, th = max(w, min(min_side, W)), max(h, min(min_side, H))
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    x0 = int(max(0, min(W - tw, cx - tw / 2)))
    y0 = int(max(0, min(H - th, cy - th / 2)))
    return x0, y0, int(min(W, x0 + tw)), int(min(H, y0 + th))


def feather(mask, radius):
    return mask.filter(ImageFilter.GaussianBlur(radius)) if radius > 0 else mask


def outpaint_canvas(img, left, top, right, bottom):
    """
    Tela maior com a imagem no lugar e a área nova pré-preenchida (bordas esticadas + desfoque forte),
    o que dá ao modelo cores coerentes para continuar a cena. Devolve (tela, máscara da área nova).
    """
    import numpy as np
    W, H = img.size
    nw, nh = W + left + right, H + top + bottom
    arr = np.asarray(img)
    padded = np.pad(arr, ((top, bottom), (left, right), (0, 0)), mode="edge")
    canvas = Image.fromarray(padded)
    blur = canvas.filter(ImageFilter.GaussianBlur(max(8, min(nw, nh) // 24)))
    canvas = blur.copy()
    canvas.paste(img, (left, top))
    mask = Image.new("L", (nw, nh), 255)
    # a borda original entra um pouco na máscara (costura), com transição suave
    seam = 24
    inner = Image.new("L", (max(1, W - 2 * seam * (left > 0 or right > 0)), max(1, H - 2 * seam * (top > 0 or bottom > 0))), 0)
    mask.paste(0, (left + (seam if left else 0), top + (seam if top else 0), left + W - (seam if right else 0), top + H - (seam if bottom else 0)))
    del inner
    return canvas, mask

# ------------------------------------------------------------------ motor

class SdxlEngine:
    name = "sdxl"

    def __init__(self):
        self.pipe = None
        self.path = None
        self.config_dir = None
        self.dtype = torch.bfloat16
        self.load_seconds = None
        self.transformer_precision = "bf16"  # compatibilidade com o status comum
        self.te_mode = "gpu"
        self.base_sched = None
        self.variants = {}
        self.loras = {}          # nome do adapter -> caminho carregado
        self.lora_cache = {}     # caminho -> state dict na CPU (recarregar sem reler o disco)
        self.controlnet = None
        self.controlnet_path = None
        self.ip_loaded = None
        self.info = {}

    # -------------------------------------------------------------- carga

    def load(self, path, config_dir, dtype, vae_path, progress):
        from diffusers import StableDiffusionXLPipeline, AutoencoderKL
        t0 = time.time()
        if not os.path.isfile(path):
            raise WorkerError("model_missing", "arquivo do checkpoint não encontrado")
        if not os.path.isfile(os.path.join(config_dir, "model_index.json")):
            raise WorkerError("model_missing", "configuração do SDXL não encontrada")
        self.dtype = torch.float16 if dtype == "fp16" else torch.bfloat16
        progress("checkpoint")
        pipe = StableDiffusionXLPipeline.from_single_file(
            path, config=config_dir, torch_dtype=self.dtype, use_safetensors=True, local_files_only=True)
        if vae_path:
            progress("vae")
            if os.path.isdir(vae_path):
                pipe.vae = AutoencoderKL.from_pretrained(vae_path, torch_dtype=self.dtype, local_files_only=True)
            else:
                pipe.vae = AutoencoderKL.from_single_file(vae_path, config=os.path.join(config_dir, "vae"), torch_dtype=self.dtype, local_files_only=True)
        progress("gpu")
        pipe.to("cuda")
        pipe.set_progress_bar_config(disable=True)
        for m in (pipe.unet, pipe.text_encoder, pipe.text_encoder_2, pipe.vae):
            m.requires_grad_(False)
            m.eval()
        self.pipe = pipe
        self.base_sched = dict(pipe.scheduler.config)
        self.path = path
        self.config_dir = config_dir
        self.variants = {}
        self.load_seconds = time.time() - t0

    def unload_extras(self):
        self.clear_loras()
        self.drop_ip_adapter()
        self.controlnet = None
        self.controlnet_path = None
        self.variants = {}
        free_cuda()

    # -------------------------------------------------------------- LoRAs

    def clear_loras(self):
        if self.pipe is not None and self.loras:
            try:
                self.pipe.unload_lora_weights()
            except Exception as e:  # noqa: BLE001
                log(f"descarregar LoRAs falhou: {type(e).__name__}: {e}")
        self.loras = {}

    def apply_loras(self, loras):
        """loras = [{"path","weight","name"}]. Sempre recomeça do zero: nada vaza de uma tarefa para a outra."""
        self.clear_loras()
        if not loras:
            return []
        names, weights, applied = [], [], []
        for i, l in enumerate(loras):
            p = l["path"]
            if not os.path.isfile(p) or not p.lower().endswith(".safetensors"):
                raise WorkerError("lora_missing", "arquivo do LoRA não encontrado")
            name = f"l{i}"
            sd = self.lora_cache.get(p)
            if sd is None:
                import safetensors.torch
                sd = safetensors.torch.load_file(p, device="cpu")
                if len(self.lora_cache) > 6:
                    self.lora_cache.pop(next(iter(self.lora_cache)))
                self.lora_cache[p] = sd
            try:
                self.pipe.load_lora_weights(dict(sd), adapter_name=name)
            except Exception as e:  # noqa: BLE001
                self.clear_loras()
                raise WorkerError("lora_incompatible", f"LoRA incompatível com este modelo ({type(e).__name__})")
            self.loras[name] = p
            names.append(name)
            weights.append(float(l.get("weight", 1.0)))
            applied.append({"name": l.get("name"), "weight": float(l.get("weight", 1.0))})
        self.pipe.set_adapters(names, adapter_weights=weights)
        return applied

    # -------------------------------------------------------------- IP-Adapter (imagem de referência)

    def ensure_ip_adapter(self, ip):
        key = (ip["dir"], ip["weight"])
        if self.ip_loaded == key:
            return
        self.drop_ip_adapter()
        self.pipe.load_ip_adapter(ip["dir"], subfolder=ip.get("subfolder", "sdxl_models"), weight_name=ip["weight"],
                                  image_encoder_folder=ip.get("encoder", "models/image_encoder"), local_files_only=True)
        self.pipe.image_encoder.to("cuda", dtype=self.dtype)
        self.ip_loaded = key

    def drop_ip_adapter(self):
        if self.pipe is not None and self.ip_loaded is not None:
            try:
                self.pipe.unload_ip_adapter()
            except Exception as e:  # noqa: BLE001
                log(f"descarregar IP-Adapter falhou: {type(e).__name__}")
            self.ip_loaded = None
            self.variants = {}
            free_cuda()

    # -------------------------------------------------------------- ControlNet Union

    def ensure_controlnet(self, cn_dir, cn_weights, cn_config):
        if self.controlnet is not None and self.controlnet_path == cn_dir:
            return self.controlnet
        from diffusers import ControlNetUnionModel
        from accelerate import init_empty_weights
        with open(os.path.join(cn_dir, cn_config), "r", encoding="utf-8") as f:
            cfg = json.load(f)
        with init_empty_weights():
            cn = ControlNetUnionModel.from_config(cfg)
        state = fast_load(os.path.join(cn_dir, cn_weights))
        state = {k: (v.to(self.dtype) if v.is_floating_point() else v) for k, v in state.items()}
        cn.load_state_dict(state, strict=True, assign=True)
        del state
        cn.to("cuda")
        cn.eval()
        cn.requires_grad_(False)
        self.controlnet = cn
        self.controlnet_path = cn_dir
        self.variants = {k: v for k, v in self.variants.items() if "cn" not in k}
        free_cuda()
        return cn

    # -------------------------------------------------------------- pipelines derivados (mesmos pesos na VRAM)

    def pipeline(self, op, control):
        key = op + ("-cn" if control else "")
        p = self.variants.get(key)
        if p is not None:
            return p
        import diffusers as d
        if control:
            cls = {"txt2img": d.StableDiffusionXLControlNetUnionPipeline,
                   "img2img": d.StableDiffusionXLControlNetUnionImg2ImgPipeline,
                   "inpaint": d.StableDiffusionXLControlNetUnionInpaintPipeline}[op]
            p = cls.from_pipe(self.pipe, controlnet=self.controlnet)
        else:
            cls = {"txt2img": d.StableDiffusionXLPipeline, "img2img": d.StableDiffusionXLImg2ImgPipeline,
                   "inpaint": d.StableDiffusionXLInpaintPipeline}[op]
            p = self.pipe if op == "txt2img" else cls.from_pipe(self.pipe)
        p.set_progress_bar_config(disable=True)
        self.variants[key] = p
        return p

    # -------------------------------------------------------------- execução

    def set_scheduler(self, pipe, sampler, schedule, prediction, zsnr):
        pipe.scheduler = make_scheduler(self.base_sched, sampler, schedule, prediction, zsnr)

    def encode_prompt(self, prompt, negative="", clip_skip=None):
        return encode_sdxl(self.pipe, prompt, negative, clip_skip)

    def preview(self, latents):
        with torch.no_grad():
            lat = latents[0].float()
            if lat.shape[0] != 4:
                return None
            f = torch.tensor(SDXL_LATENT_RGB, device=lat.device, dtype=lat.dtype)
            b = torch.tensor(SDXL_LATENT_RGB_BIAS, device=lat.device, dtype=lat.dtype)
            rgb = torch.einsum("chw,cr->hwr", lat, f) + b
            return ((rgb + 1.0) / 2.0).clamp(0, 1).mul(255).byte().cpu().numpy()

    def run(self, embeds, op, width, height, steps, cfg, generator, callback, init=None, mask=None, strength=None,
            control=None, reference=None, guidance_rescale=0.0, sampler="euler", schedule="normal",
            prediction="epsilon", zsnr=False):
        pipe = self.pipeline(op, control is not None)
        self.set_scheduler(pipe, sampler, schedule, prediction, zsnr)
        kw = dict(
            prompt_embeds=embeds["prompt_embeds"], negative_prompt_embeds=embeds["negative_prompt_embeds"],
            pooled_prompt_embeds=embeds["pooled_prompt_embeds"], negative_pooled_prompt_embeds=embeds["negative_pooled_prompt_embeds"],
            num_inference_steps=steps, guidance_scale=cfg, generator=generator, callback_on_step_end=callback,
            callback_on_step_end_tensor_inputs=["latents"], output_type="pil",
        )
        if guidance_rescale:
            kw["guidance_rescale"] = guidance_rescale
        if op == "txt2img":
            kw.update(width=width, height=height)
        elif op == "img2img":
            kw.update(image=init, strength=strength)
        elif op == "inpaint":
            kw.update(image=init, mask_image=mask, strength=strength, width=width, height=height)
        if control is not None:
            kw.update(control_image=[control["image"]], control_mode=[control["mode"]],
                      controlnet_conditioning_scale=control.get("scale", 0.8),
                      control_guidance_end=control.get("end", 1.0))
            if op == "img2img":
                kw.update(width=width, height=height)
        if reference is not None:
            kw["ip_adapter_image"] = reference["image"]
            self.pipe.set_ip_adapter_scale(reference.get("scale", 0.6))
        return pipe(**kw).images[0]
