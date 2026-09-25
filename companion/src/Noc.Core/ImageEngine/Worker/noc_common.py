# Noc Image Worker — partes comuns: canal do protocolo, erros, memória da GPU e carga rápida de pesos.
# Importado primeiro pelo worker (antes do torch), para que nenhum print() de biblioteca corrompa o protocolo.

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


def guard_vram(margin_mb=320):
    """
    Trava de segurança: no Windows (WDDM) passar da VRAM não dá erro — o driver derrama para a RAM compartilhada,
    a geração fica dezenas de vezes mais lenta e pode travar o PC. Limita este processo ao que ele já reservou
    mais o que está livre agora na GPU: passar disso vira "out of memory" limpo, que o Companion sabe recuperar.
    """
    if not torch.cuda.is_available():
        return
    free, total = torch.cuda.mem_get_info()
    allowed = torch.cuda.memory_reserved() + free - margin_mb * 1024 * 1024
    torch.cuda.set_per_process_memory_fraction(max(0.05, min(0.98, allowed / total)))


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
