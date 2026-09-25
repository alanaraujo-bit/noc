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

import os
import sys

# O canal do protocolo é uma cópia do stdout original; o stdout "normal" passa a ser o stderr,
# para que um print() perdido de alguma biblioteca nunca corrompa o protocolo.
_proto_fd = os.dup(1)
os.dup2(2, 1)
_proto = os.fdopen(_proto_fd, "w", encoding="utf-8", buffering=1, newline="\n")
sys.stdout = sys.stderr

import gc
import io
import json
import queue
import secrets
import threading
import time
import traceback
import base64

WORKER_VERSION = "1.0.0"
_send_lock = threading.Lock()


def send(ev, **fields):
    msg = {"ev": ev}
    msg.update(fields)
    line = json.dumps(msg, ensure_ascii=False, separators=(",", ":"))
    with _send_lock:
        _proto.write(line + "\n")
        _proto.flush()


def log(text):
    # stderr: o Companion guarda só na memória (pode conter nomes de arquivos, nunca o prompt).
    sys.stderr.write(f"[worker] {text}\n")
    sys.stderr.flush()


# ------------------------------------------------------------------ imports pesados (com aviso de progresso)

send("booting", stage="torch")
import torch  # noqa: E402

torch.backends.cuda.matmul.allow_tf32 = True
torch.backends.cudnn.allow_tf32 = True

send("booting", stage="diffusers")
import diffusers  # noqa: E402
import transformers  # noqa: E402
from diffusers.utils import logging as dlogging  # noqa: E402
from transformers.utils import logging as tlogging  # noqa: E402
from PIL import Image  # noqa: E402
import safetensors.torch  # noqa: E402
from safetensors import safe_open  # noqa: E402

dlogging.set_verbosity_error()
tlogging.set_verbosity_error()
dlogging.disable_progress_bar()

FP8 = torch.float8_e4m3fn
BF16 = torch.bfloat16
GiB = 1024 ** 3

# Fatores latente -> RGB da VAE de 16 canais (FLUX/Z-Image): prévia instantânea sem decodificar a VAE.
LATENT_RGB = [
    [-0.0346, 0.0244, 0.0681], [0.0034, 0.0210, 0.0687], [0.0275, -0.0668, -0.0433], [-0.0174, 0.0160, 0.0617],
    [0.0859, 0.0721, 0.0329], [0.0004, 0.0383, 0.0115], [0.0405, 0.0861, 0.0915], [-0.0236, -0.0185, -0.0259],
    [-0.0245, 0.0250, 0.1180], [0.1008, 0.0755, -0.0421], [-0.0515, 0.0201, 0.0011], [0.0428, -0.0012, -0.0036],
    [0.0817, 0.0765, 0.0749], [-0.1264, -0.0522, -0.1103], [-0.0280, -0.0881, -0.0499], [-0.1262, -0.0982, -0.0778],
]
LATENT_RGB_BIAS = [-0.0329, -0.0718, -0.0851]


class Cancelled(Exception):
    pass


class WorkerError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def gpu_mem():
    if not torch.cuda.is_available():
        return {}
    free, total = torch.cuda.mem_get_info()
    return {
        "freeMb": free // (1024 * 1024),
        "totalMb": total // (1024 * 1024),
        "allocatedMb": torch.cuda.memory_allocated() // (1024 * 1024),
        "reservedMb": torch.cuda.memory_reserved() // (1024 * 1024),
    }


def rss_mb():
    try:
        import psutil
        mi = psutil.Process().memory_info()
        return {"rssMb": mi.rss // (1024 * 1024), "peakRssMb": getattr(mi, "peak_wset", mi.rss) // (1024 * 1024)}
    except Exception:
        pass
    try:
        import ctypes
        from ctypes import wintypes

        class PMC(ctypes.Structure):
            _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD), ("PeakWorkingSetSize", ctypes.c_size_t),
                        ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaPagedPoolUsage", ctypes.c_size_t), ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                        ("QuotaNonPagedPoolUsage", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t),
                        ("PeakPagefileUsage", ctypes.c_size_t)]
        pmc = PMC()
        pmc.cb = ctypes.sizeof(PMC)
        h = ctypes.windll.kernel32.GetCurrentProcess()
        ctypes.windll.psapi.GetProcessMemoryInfo(h, ctypes.byref(pmc), pmc.cb)
        return {"rssMb": pmc.WorkingSetSize // (1024 * 1024), "peakRssMb": pmc.PeakWorkingSetSize // (1024 * 1024)}
    except Exception:
        return {}


def free_cuda():
    gc.collect()
    if torch.cuda.is_available():
        torch.cuda.empty_cache()


# ------------------------------------------------------------------ fp8 (armazenamento) + bf16 (cálculo)

def _fp8_targets(model_cls, config):
    """Pergunta ao próprio diffusers quais parâmetros ficam em fp8: aplica o layerwise casting num modelo 'meta'."""
    from accelerate import init_empty_weights
    with init_empty_weights():
        meta = model_cls.from_config(config)
    meta.enable_layerwise_casting(storage_dtype=FP8, compute_dtype=BF16)
    # o modelo "meta" nasce em float32: o que não vira fp8 fica em bf16 (dtype de cálculo)
    return {n: (FP8 if p.dtype == FP8 else BF16) for n, p in meta.named_parameters()}


def build_cache(model_cls, src_dir, dst_dir, mode, progress):
    """
    Converte (uma vez) os pesos oficiais para o formato de carga rápida, tensor por tensor, sem carregar o
    modelo inteiro na RAM: "bf16" (tudo em bf16) ou "fp8" (camadas lineares em fp8, o resto em bf16).
    """
    config = model_cls.load_config(src_dir)
    targets = _fp8_targets(model_cls, config) if mode == "fp8" else {}
    shards = sorted(f for f in os.listdir(src_dir) if f.endswith(".safetensors"))
    if not shards:
        raise WorkerError("model_missing", "pesos do modelo não encontrados")
    tmp_dir = dst_dir + ".tmp"
    if os.path.isdir(tmp_dir):
        import shutil
        shutil.rmtree(tmp_dir, ignore_errors=True)
    os.makedirs(tmp_dir, exist_ok=True)
    out_idx = 0
    bucket, bucket_bytes = {}, 0
    total = len(shards)

    def flush():
        nonlocal out_idx, bucket, bucket_bytes
        if not bucket:
            return
        safetensors.torch.save_file(bucket, os.path.join(tmp_dir, f"part-{out_idx:03d}.safetensors"))
        out_idx += 1
        bucket, bucket_bytes = {}, 0

    for i, shard in enumerate(shards):
        progress(i / total)
        with safe_open(os.path.join(src_dir, shard), framework="pt", device="cpu") as f:
            for key in f.keys():
                t = f.get_tensor(key)
                want = targets.get(key)
                if want is None:
                    want = BF16 if t.is_floating_point() else t.dtype
                if t.is_floating_point() and t.dtype != want:
                    t = t.to(want)
                bucket[key] = t.contiguous()
                bucket_bytes += t.numel() * t.element_size()
                if bucket_bytes > 3 * GiB:
                    flush()
    flush()
    with open(os.path.join(src_dir, "config.json"), "r", encoding="utf-8") as fsrc:
        cfg_text = fsrc.read()
    with open(os.path.join(tmp_dir, "config.json"), "w", encoding="utf-8") as fdst:
        fdst.write(cfg_text)
    with open(os.path.join(tmp_dir, "noc-cache.json"), "w", encoding="utf-8") as fm:
        json.dump({"format": mode, "shards": out_idx, "source": shards}, fm)
    if os.path.isdir(dst_dir):
        import shutil
        shutil.rmtree(dst_dir, ignore_errors=True)
    os.replace(tmp_dir, dst_dir)
    progress(1.0)


_DT = {"BF16": torch.bfloat16, "F16": torch.float16, "F32": torch.float32, "F8_E4M3": torch.float8_e4m3fn,
       "I64": torch.int64, "I32": torch.int32, "U8": torch.uint8, "BOOL": torch.bool}


def fast_load(path, device="cuda", chunk=64 * 1024 * 1024, own=False):
    """
    Lê um .safetensors direto para a GPU com leituras sequenciais grandes (buffer fixado, cópia assíncrona).
    O mmap do safetensors no Windows fica em ~0,9 GB/s; assim o limite passa a ser o disco.
    """
    with open(path, "rb", buffering=0) as f:
        n = int.from_bytes(f.read(8), "little")
        header = json.loads(f.read(n))
        header.pop("__metadata__", None)
        base = 8 + n
        size = max((v["data_offsets"][1] for v in header.values()), default=0)
        blob = torch.empty(size, dtype=torch.uint8, device=device)
        pinned = [torch.empty(chunk, dtype=torch.uint8).pin_memory() for _ in range(2)]
        events = [None, None]
        stream = torch.cuda.Stream()
        off = 0
        i = 0
        f.seek(base)
        while off < size:
            buf = pinned[i % 2]
            if events[i % 2] is not None:
                events[i % 2].synchronize()
            want = min(chunk, size - off)
            got = f.readinto(memoryview(buf.numpy())[:want])
            if got != want:
                raise WorkerError("model_missing", "arquivo do modelo incompleto")
            with torch.cuda.stream(stream):
                blob[off:off + want].copy_(buf[:want], non_blocking=True)
                ev = torch.cuda.Event()
                ev.record(stream)
            events[i % 2] = ev
            off += want
            i += 1
        stream.synchronize()
    out = {}
    for name, v in header.items():
        a, b = v["data_offsets"]
        dt = _DT[v["dtype"]]
        esize = torch.empty(0, dtype=dt).element_size()
        raw = blob[a:b]
        t = raw.clone() if (a % esize) else raw  # alinhamento: cópia só neste caso raro
        t = t.view(dt).reshape(v["shape"])
        # own: cada tensor com memória própria (os ganchos de fp8 trocam os pesos; uma vista manteria o bloco inteiro vivo)
        out[name] = t.clone() if own else t
    del blob
    return out


def save_state(state, dst_dir, extra_meta):
    """Grava um state_dict em partes de ~3 GB (tensores maiores primeiro, alinhados), com escrita atômica da pasta."""
    tmp_dir = dst_dir + ".tmp"
    if os.path.isdir(tmp_dir):
        import shutil
        shutil.rmtree(tmp_dir, ignore_errors=True)
    os.makedirs(tmp_dir, exist_ok=True)
    items = sorted(state.items(), key=lambda kv: -kv[1].element_size())
    bucket, size, idx = {}, 0, 0
    for k, t in items:
        bucket[k] = t.detach().to("cpu").contiguous()
        size += t.numel() * t.element_size()
        if size > 3 * GiB:
            safetensors.torch.save_file(bucket, os.path.join(tmp_dir, f"part-{idx:03d}.safetensors"))
            bucket, size, idx = {}, 0, idx + 1
    if bucket:
        safetensors.torch.save_file(bucket, os.path.join(tmp_dir, f"part-{idx:03d}.safetensors"))
        idx += 1
    with open(os.path.join(tmp_dir, "noc-cache.json"), "w", encoding="utf-8") as fm:
        json.dump(dict(extra_meta, shards=idx), fm)
    if os.path.isdir(dst_dir):
        import shutil
        shutil.rmtree(dst_dir, ignore_errors=True)
    os.replace(tmp_dir, dst_dir)


def load_dir_to_cuda(cache_dir, own=False):
    state = {}
    for f in sorted(os.listdir(cache_dir)):
        if f.endswith(".safetensors"):
            state.update(fast_load(os.path.join(cache_dir, f), own=own))
    return state


def load_cached_model(model_cls, cache_dir, mode):
    from accelerate import init_empty_weights
    config = model_cls.load_config(cache_dir)
    with init_empty_weights():
        model = model_cls.from_config(config)
    state = load_dir_to_cuda(cache_dir, own=mode == "fp8")
    model.load_state_dict(state, strict=True, assign=True)
    del state
    model.to("cuda")  # buffers não persistentes nasceram na CPU
    if mode == "fp8":
        model.enable_layerwise_casting(storage_dtype=FP8, compute_dtype=BF16)
    model.eval()
    model.requires_grad_(False)
    return model


def load_transformer(model_cls, path, cache, mode, progress):
    """Transformer pela cópia otimizada (criada na primeira vez a partir dos pesos oficiais)."""
    cdir = os.path.join(cache, f"transformer-{mode}")
    if not os.path.isfile(os.path.join(cdir, "noc-cache.json")):
        progress("optimizing")
        build_cache(model_cls, os.path.join(path, "transformer"), cdir, mode,
                    lambda p: send("load_progress", stage="optimizing", progress=round(p, 3)))
        progress("transformer")
    return load_cached_model(model_cls, cdir, mode)


def build_qwen_te_fp8(te_dir, cache):
    """Converte (uma vez) o Qwen3 do Z-Image para lineares em fp8: 4,4 GB em vez de 8. Na CPU: não disputa a GPU."""
    from transformers import Qwen3Model
    from diffusers.hooks import apply_layerwise_casting
    cdir = os.path.join(cache, "text_encoder-fp8")
    if os.path.isfile(os.path.join(cdir, "noc-cache.json")):
        return cdir
    te = Qwen3Model.from_pretrained(te_dir, torch_dtype=BF16, use_safetensors=True)
    apply_layerwise_casting(te, storage_dtype=FP8, compute_dtype=BF16, skip_modules_pattern=("norm", "embed", "shared"))
    save_state(te.state_dict(), cdir, {"format": "fp8-te"})
    del te
    gc.collect()
    return cdir


def load_qwen_te_fp8(te_dir, cache):
    """Qwen3 (codificador de texto do Z-Image) com lineares em fp8, lido da cópia otimizada direto para a GPU."""
    from transformers import AutoConfig, Qwen3Model
    from accelerate import init_empty_weights
    from diffusers.hooks import apply_layerwise_casting
    cdir = build_qwen_te_fp8(te_dir, cache)
    cfg = AutoConfig.from_pretrained(te_dir)
    with init_empty_weights():
        te = Qwen3Model(cfg)
    apply_layerwise_casting(te, storage_dtype=FP8, compute_dtype=BF16, skip_modules_pattern=("norm", "embed", "shared"))
    te.load_state_dict(load_dir_to_cuda(cdir, own=True), strict=True, assign=True)
    te.to("cuda")  # buffers não persistentes (ex.: frequências do RoPE) nasceram na CPU
    te.eval()
    te.requires_grad_(False)
    return te


def te_to_fp8(module):
    """Codificador de texto na GPU com pesos das camadas lineares em fp8 (cálculo em bf16). Embeddings e normas ficam em bf16."""
    from diffusers.hooks import apply_layerwise_casting
    module.to("cuda")
    apply_layerwise_casting(module, storage_dtype=FP8, compute_dtype=BF16, skip_modules_pattern=("norm", "embed", "shared"))
    free_cuda()
    return module


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
        raise NotImplementedError

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

    def run(self, embeds, width, height, steps, guidance, generator, callback):
        return self.pipe(
            prompt_embeds=embeds["prompt_embeds"], width=width, height=height, num_inference_steps=steps,
            guidance_scale=0.0, generator=generator, callback_on_step_end=callback,
            callback_on_step_end_tensor_inputs=["latents"], output_type="pil",
        ).images[0]


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

    def run(self, embeds, width, height, steps, guidance, generator, callback):
        return self.pipe(
            prompt_embeds=embeds["prompt_embeds"], pooled_prompt_embeds=embeds["pooled_prompt_embeds"],
            width=width, height=height, num_inference_steps=steps, guidance_scale=guidance, generator=generator,
            callback_on_step_end=callback, callback_on_step_end_tensor_inputs=["latents"], output_type="pil",
        ).images[0]


ENGINES = {"zimage": ZImageEngine, "flux": FluxEngine}


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


class Rewriter:
    """Usa o mesmo Qwen3-4B que o Z-Image já usa como codificador de texto (compartilha os pesos quando carregado)."""

    def __init__(self):
        self.tokenizer = None
        self.own_model = None

    def _causal_lm(self, qwen_dir, shared_model):
        from transformers import AutoConfig, Qwen3ForCausalLM
        cfg = AutoConfig.from_pretrained(qwen_dir)
        with torch.device("meta"):
            lm = Qwen3ForCausalLM(cfg)
        lm.model = shared_model
        lm.lm_head.weight = shared_model.embed_tokens.weight  # tie_word_embeddings
        lm.eval()
        return lm

    def rewrite(self, text, mode, qwen_dir, tokenizer_dir, engine):
        from transformers import AutoTokenizer, Qwen3Model
        if self.tokenizer is None:
            self.tokenizer = AutoTokenizer.from_pretrained(tokenizer_dir)
        loaded_here = False
        if isinstance(engine, ZImageEngine) and engine.pipe is not None:
            base = engine.pipe.text_encoder
            moved = engine.te_mode == "swap"
            if moved:
                base.to("cuda")
        else:
            base = Qwen3Model.from_pretrained(qwen_dir, torch_dtype=BF16, use_safetensors=True, device_map="cuda")
            base.requires_grad_(False)
            moved = False
            loaded_here = True
        try:
            lm = self._causal_lm(qwen_dir, base)
            messages = [
                {"role": "system", "content": REWRITE_ENHANCE if mode == "enhance" else REWRITE_TRANSLATE},
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
            return result.strip().strip('"').strip()
        finally:
            if moved:
                base.to("cpu")
            if loaded_here:
                del base
            free_cuda()


# ------------------------------------------------------------------ estado e comandos

class State:
    engine = None
    rewriter = Rewriter()
    cancel_ids = set()
    cancel_lock = threading.Lock()
    current_id = None


def is_cancelled(job_id):
    with State.cancel_lock:
        return job_id in State.cancel_ids


def latent_preview(pipe, latents, width, height):
    """Prévia real do estado atual (latente -> RGB por combinação linear), sem custo de VAE."""
    with torch.no_grad():
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


def cmd_load(c):
    kind = c.get("engine")
    if kind not in ENGINES:
        raise WorkerError("bad_request", "motor desconhecido")
    precision = c.get("transformer", "bf16")
    te_mode = c.get("text_encoder", "gpu")
    path = c["path"]
    cache = c.get("cache") or os.path.join(path, ".noc-cache")
    cur = State.engine
    if cur is not None and cur.name == kind and cur.transformer_precision == precision and cur.te_mode == te_mode and cur.path == path:
        send("loaded", id=c.get("id"), engine=kind, seconds=0, reused=True, mem=gpu_mem(), **rss_mb())
        return
    if cur is not None:
        State.engine = None
        cur.pipe = None
        del cur
        free_cuda()
    if not os.path.isdir(path):
        raise WorkerError("model_missing", "pasta do modelo não encontrada")
    torch.cuda.reset_peak_memory_stats()
    eng = ENGINES[kind]()
    eng.load(path, precision, te_mode, cache, lambda stage: send("load_progress", id=c.get("id"), stage=stage))
    State.engine = eng
    free_cuda()
    send("loaded", id=c.get("id"), engine=kind, seconds=round(eng.load_seconds, 2), transformer=precision, textEncoder=te_mode,
         mem=gpu_mem(), peakAllocatedMb=torch.cuda.max_memory_allocated() // (1024 * 1024), **rss_mb())


def _fault_oom():
    """Só para testes automatizados: se o arquivo indicado em NOC_IMAGE_FAULT_OOM existir, consome-o e simula falta de VRAM."""
    f = os.environ.get("NOC_IMAGE_FAULT_OOM")
    if f and os.path.isfile(f):
        os.remove(f)
        raise torch.OutOfMemoryError("CUDA out of memory (simulado)")


def cmd_generate(c):
    job = c["id"]
    _fault_oom()
    eng = State.engine
    if eng is None or eng.pipe is None:
        raise WorkerError("not_loaded", "motor não carregado")
    width = int(c.get("width", 1024))
    height = int(c.get("height", 1024))
    steps = int(c.get("steps", 9))
    guidance = float(c.get("guidance", 0.0))
    count = max(1, min(4, int(c.get("count", 1))))
    first = max(0, min(count, int(c.get("first", 0))))  # retomada: imagens anteriores já estão salvas
    seed = c.get("seed")
    seed = int(seed) if seed is not None else secrets.randbelow(2 ** 31 - 1)
    preview = bool(c.get("preview", False))
    preview_every = max(1, int(c.get("previewEvery", 4)))
    out = c["out"]
    prompt = c["prompt"]
    State.current_id = job
    t0 = time.time()
    torch.cuda.reset_peak_memory_stats()
    send("stage", id=job, stage="encoding")
    embeds = eng.encode_prompt(prompt)
    if is_cancelled(job):
        raise Cancelled()
    encode_ms = int((time.time() - t0) * 1000)
    results = []
    step_times = []
    for idx in range(first, count):
        s = seed + idx
        gen = torch.Generator("cuda").manual_seed(s)
        last = [time.time()]
        first_step_ms = [None]
        gen_t0 = time.time()

        def cb(pipe, i, t, kw, _idx=idx, _last=last, _first=first_step_ms):
            now = time.time()
            dt = now - _last[0]
            _last[0] = now
            step_times.append(dt)
            if _first[0] is None:
                _first[0] = int((now - gen_t0) * 1000)
            ev = {"id": job, "image": _idx, "count": count, "step": i + 1, "steps": steps, "stepMs": int(dt * 1000)}
            if preview and (i + 1) % preview_every == 0 and (i + 1) < steps and (i + 1) >= steps * 0.3:
                try:
                    p = latent_preview(pipe, kw["latents"], width, height)
                    if p:
                        ev["preview"] = p
                except Exception as e:  # prévia é opcional; nunca derruba a geração
                    log(f"prévia falhou: {type(e).__name__}")
            if i + 1 == steps:
                # antes da VAE: devolve ao driver o que a fragmentação do cache reservou (pico menor na decodificação)
                torch.cuda.empty_cache()
                ev["denoisePeakMb"] = torch.cuda.max_memory_allocated() // (1024 * 1024)
                # pouca VRAM livre (outros programas usando a GPU): decodifica em blocos em vez de estourar a memória
                free_now = torch.cuda.mem_get_info()[0]
                tiled = free_now < 3 * GiB
                if tiled:
                    pipe.vae.enable_tiling()
                else:
                    pipe.vae.disable_tiling()
                ev["vaeTiled"] = tiled
            send("progress", **ev)
            if is_cancelled(job):
                pipe._interrupt = True
            return kw

        send("stage", id=job, stage="generating", image=idx, count=count)
        with torch.inference_mode():
            img = eng.run(embeds, width, height, steps, guidance, gen, cb)
        if is_cancelled(job):
            raise Cancelled()
        send("stage", id=job, stage="saving", image=idx, count=count)
        base = f"{out}-{idx}"
        png, thumb = save_image(img, base)
        gen_ms = int((time.time() - gen_t0) * 1000)
        results.append({"index": idx, "seed": s, "png": png, "thumb": thumb, "width": img.width, "height": img.height, "ms": gen_ms,
                        "firstStepMs": first_step_ms[0]})
        send("image", id=job, **results[-1])
        del img
        free_cuda()
    total_ms = int((time.time() - t0) * 1000)
    mean_step = (sum(step_times) / len(step_times)) if step_times else None
    send("done", id=job, images=results, stats={
        "totalMs": total_ms, "encodeMs": encode_ms, "steps": steps, "width": width, "height": height,
        "meanStepMs": int(mean_step * 1000) if mean_step else None,
        "peakAllocatedMb": torch.cuda.max_memory_allocated() // (1024 * 1024),
        "peakReservedMb": torch.cuda.max_memory_reserved() // (1024 * 1024),
        "engine": eng.name, "transformer": eng.transformer_precision, "textEncoder": eng.te_mode,
    }, mem=gpu_mem(), **rss_mb())


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
    text = State.rewriter.rewrite(c["text"], c.get("mode", "translate"), c["path"], c.get("tokenizer") or c["path"], State.engine)
    send("rewritten", id=job, text=text, ms=int((time.time() - t0) * 1000))


def cmd_unload(c):
    eng = State.engine
    State.engine = None
    if eng is not None:
        eng.pipe = None
        del eng
    free_cuda()
    send("unloaded", id=c.get("id"), mem=gpu_mem(), **rss_mb())


def cmd_status(c):
    eng = State.engine
    send("status", id=c.get("id"), engine=eng.name if eng else None,
         transformer=eng.transformer_precision if eng else None, textEncoder=eng.te_mode if eng else None,
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


def reader(q):
    for line in sys.stdin:
        line = line.strip()
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
