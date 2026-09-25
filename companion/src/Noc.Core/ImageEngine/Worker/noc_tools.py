# Noc Image Worker — ferramentas de edição: ampliação (Real-ESRGAN), detecção de rostos/mãos (Grounding DINO),
# mapas de controle (pose, profundidade, bordas) e utilidades de recorte/colagem.
#
# Tudo roda com pesos .safetensors lidos localmente. A rede Real-ESRGAN é implementada aqui (sem pacote de terceiros);
# os pesos oficiais (.pth) são convertidos uma única vez com o carregador restrito do PyTorch (weights_only=True:
# só tensores, nenhum código) para .safetensors, conferidos antes pelo SHA-256 fixado no Companion.

import math
import os

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image, ImageDraw, ImageFilter

from noc_common import log, WorkerError, free_cuda

# ------------------------------------------------------------------ Real-ESRGAN (RRDBNet)

class _RDB(nn.Module):
    def __init__(self, nf=64, gc=32):
        super().__init__()
        self.conv1 = nn.Conv2d(nf, gc, 3, 1, 1)
        self.conv2 = nn.Conv2d(nf + gc, gc, 3, 1, 1)
        self.conv3 = nn.Conv2d(nf + 2 * gc, gc, 3, 1, 1)
        self.conv4 = nn.Conv2d(nf + 3 * gc, gc, 3, 1, 1)
        self.conv5 = nn.Conv2d(nf + 4 * gc, nf, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, True)

    def forward(self, x):
        x1 = self.lrelu(self.conv1(x))
        x2 = self.lrelu(self.conv2(torch.cat((x, x1), 1)))
        x3 = self.lrelu(self.conv3(torch.cat((x, x1, x2), 1)))
        x4 = self.lrelu(self.conv4(torch.cat((x, x1, x2, x3), 1)))
        x5 = self.conv5(torch.cat((x, x1, x2, x3, x4), 1))
        return x5 * 0.2 + x


class _RRDB(nn.Module):
    def __init__(self, nf, gc=32):
        super().__init__()
        self.rdb1, self.rdb2, self.rdb3 = _RDB(nf, gc), _RDB(nf, gc), _RDB(nf, gc)

    def forward(self, x):
        return self.rdb3(self.rdb2(self.rdb1(x))) * 0.2 + x


class RRDBNet(nn.Module):
    def __init__(self, scale=4, num_block=23, nf=64, gc=32):
        super().__init__()
        self.scale = scale
        cin = 3 * (4 if scale == 2 else 16 if scale == 1 else 1)
        self.conv_first = nn.Conv2d(cin, nf, 3, 1, 1)
        self.body = nn.Sequential(*[_RRDB(nf, gc) for _ in range(num_block)])
        self.conv_body = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_up1 = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_up2 = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_hr = nn.Conv2d(nf, nf, 3, 1, 1)
        self.conv_last = nn.Conv2d(nf, 3, 3, 1, 1)
        self.lrelu = nn.LeakyReLU(0.2, True)

    def forward(self, x):
        if self.scale == 2:
            x = F.pixel_unshuffle(x, 2)
        elif self.scale == 1:
            x = F.pixel_unshuffle(x, 4)
        feat = self.conv_first(x)
        feat = feat + self.conv_body(self.body(feat))
        feat = self.lrelu(self.conv_up1(F.interpolate(feat, scale_factor=2, mode="nearest")))
        feat = self.lrelu(self.conv_up2(F.interpolate(feat, scale_factor=2, mode="nearest")))
        return self.conv_last(self.lrelu(self.conv_hr(feat)))


ESRGAN_ARCH = {  # arquivo oficial -> (escala, blocos)
    "RealESRGAN_x4plus": (4, 23),
    "RealESRGAN_x2plus": (2, 23),
    "RealESRGAN_x4plus_anime_6B": (4, 6),
}


def esrgan_safetensors(pth_path, cache_dir):
    """Converte (uma vez) o .pth oficial para .safetensors. weights_only=True: o carregador recusa qualquer objeto que não seja tensor."""
    import safetensors.torch
    name = os.path.splitext(os.path.basename(pth_path))[0]
    dst = os.path.join(cache_dir, name + ".safetensors")
    if os.path.isfile(dst):
        return dst
    os.makedirs(cache_dir, exist_ok=True)
    sd = torch.load(pth_path, map_location="cpu", weights_only=True)
    if isinstance(sd, dict) and ("params_ema" in sd or "params" in sd):
        sd = sd.get("params_ema") or sd["params"]
    sd = {k: v.contiguous() for k, v in sd.items() if isinstance(v, torch.Tensor)}
    tmp = dst + ".tmp"
    safetensors.torch.save_file(sd, tmp)
    os.replace(tmp, dst)
    return dst


class Upscaler:
    def __init__(self):
        self.model = None
        self.key = None

    def load(self, weights, name):
        if self.key == weights:
            return self.model
        import safetensors.torch
        scale, blocks = ESRGAN_ARCH.get(name, (4, 23))
        net = RRDBNet(scale=scale, num_block=blocks)
        net.load_state_dict(safetensors.torch.load_file(weights), strict=True)
        net.eval().requires_grad_(False).to("cuda", dtype=torch.float16)
        self.model, self.key = net, weights
        return net

    def unload(self):
        self.model = self.key = None
        free_cuda()

    def upscale(self, img, weights, name, tile=512, pad=16, cancelled=lambda: False):
        """Amplia em blocos (cabe em qualquer VRAM). Devolve a imagem na escala nativa do modelo (2x ou 4x)."""
        net = self.load(weights, name)
        scale = ESRGAN_ARCH.get(name, (4, 23))[0]
        x = torch.from_numpy(np.asarray(img.convert("RGB"))).permute(2, 0, 1).float().div(255).unsqueeze(0).to("cuda", torch.float16)
        _, _, h, w = x.shape
        out = torch.zeros((1, 3, h * scale, w * scale), device="cuda", dtype=torch.float16)
        with torch.inference_mode():
            for y0 in range(0, h, tile):
                for x0 in range(0, w, tile):
                    if cancelled():
                        from noc_common import Cancelled
                        raise Cancelled()
                    y1, x1 = min(h, y0 + tile), min(w, x0 + tile)
                    py0, px0, py1, px1 = max(0, y0 - pad), max(0, x0 - pad), min(h, y1 + pad), min(w, x1 + pad)
                    part = x[:, :, py0:py1, px0:px1]
                    # a rede 2x trabalha em 1/2 da resolução: dimensões pares
                    if scale == 2 and (part.shape[2] % 2 or part.shape[3] % 2):
                        part = F.pad(part, (0, part.shape[3] % 2, 0, part.shape[2] % 2), mode="reflect")
                    res = net(part)
                    oy, ox = (y0 - py0) * scale, (x0 - px0) * scale
                    out[:, :, y0 * scale:y1 * scale, x0 * scale:x1 * scale] = res[:, :, oy:oy + (y1 - y0) * scale, ox:ox + (x1 - x0) * scale]
        arr = out.clamp(0, 1).mul(255).round().byte().squeeze(0).permute(1, 2, 0).cpu().numpy()
        del x, out
        return Image.fromarray(arr)

# ------------------------------------------------------------------ detecção (rostos, mãos, qualquer coisa por texto)

class Detector:
    def __init__(self):
        self.model = None
        self.proc = None
        self.dir = None

    def load(self, model_dir):
        if self.dir == model_dir:
            return
        from transformers import AutoProcessor, AutoModelForZeroShotObjectDetection
        self.proc = AutoProcessor.from_pretrained(model_dir, local_files_only=True)
        self.model = AutoModelForZeroShotObjectDetection.from_pretrained(model_dir, local_files_only=True, use_safetensors=True)
        self.model.eval().requires_grad_(False).to("cuda")
        self.dir = model_dir

    def unload(self):
        self.model = self.proc = self.dir = None
        free_cuda()

    def detect(self, img, labels, model_dir, threshold=0.3):
        """labels = ["face", "hand"] → [(label, score, (x0,y0,x1,y1))] em pixels, sem duplicatas sobrepostas."""
        self.load(model_dir)
        text = ". ".join(labels) + "."
        inputs = self.proc(images=img, text=text, return_tensors="pt").to("cuda")
        with torch.inference_mode():
            out = self.model(**inputs)
        res = self.proc.post_process_grounded_object_detection(
            out, inputs["input_ids"], threshold=threshold, text_threshold=threshold, target_sizes=[img.size[::-1]])[0]
        found = []
        names = res.get("text_labels") or res.get("labels")
        for score, name, box in zip(res["scores"].tolist(), names, res["boxes"].tolist()):
            name = str(name).strip()
            lab = next((l for l in labels if l in name), name)
            x0, y0, x1, y1 = [float(v) for v in box]
            if (x1 - x0) * (y1 - y0) > 0.6 * img.width * img.height:
                continue  # "rosto" que é a imagem inteira: falso positivo
            found.append((lab, score, (x0, y0, x1, y1)))
        found.sort(key=lambda f: -f[1])
        kept = []
        for f in found:
            if all(_iou(f[2], k[2]) < 0.5 for k in kept):
                kept.append(f)
        return kept


def _iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0

# ------------------------------------------------------------------ mapas de controle

def canny_map(img, low=100, high=200):
    import cv2
    a = np.asarray(img.convert("RGB"))
    e = cv2.Canny(a, low, high)
    return Image.fromarray(np.stack([e] * 3, axis=-1))


class Depth:
    def __init__(self):
        self.model = self.proc = self.dir = None

    def unload(self):
        self.model = self.proc = self.dir = None
        free_cuda()

    def map(self, img, model_dir):
        from transformers import DPTImageProcessorPil, AutoModelForDepthEstimation
        if self.dir != model_dir:
            # processador em PIL: o "rápido" exigiria o torchvision (mais 1 GB de dependência só para isto)
            self.proc = DPTImageProcessorPil.from_pretrained(model_dir, local_files_only=True)
            self.model = AutoModelForDepthEstimation.from_pretrained(model_dir, local_files_only=True, use_safetensors=True).eval().to("cuda")
            self.dir = model_dir
        inputs = self.proc(images=img, return_tensors="pt").to("cuda")
        with torch.inference_mode():
            pred = self.model(**inputs).predicted_depth
        pred = F.interpolate(pred.unsqueeze(1), size=img.size[::-1], mode="bicubic", align_corners=False)[0, 0]
        pred = (pred - pred.min()) / max(1e-6, float(pred.max() - pred.min()))
        a = (pred.clamp(0, 1) * 255).byte().cpu().numpy()
        return Image.fromarray(np.stack([a] * 3, axis=-1))


# OpenPose (18 pontos) a partir dos 17 do COCO: pescoço = meio dos ombros
_COCO_TO_OP = [0, None, 6, 8, 10, 5, 7, 9, 12, 14, 16, 11, 13, 15, 2, 1, 4, 3]
_LIMBS = [(1, 2), (1, 5), (2, 3), (3, 4), (5, 6), (6, 7), (1, 8), (8, 9), (9, 10), (1, 11), (11, 12), (12, 13),
          (1, 0), (0, 14), (14, 16), (0, 15), (15, 17)]
_COLORS = [(255, 0, 0), (255, 85, 0), (255, 170, 0), (255, 255, 0), (170, 255, 0), (85, 255, 0), (0, 255, 0),
           (0, 255, 85), (0, 255, 170), (0, 255, 255), (0, 170, 255), (0, 85, 255), (0, 0, 255), (85, 0, 255),
           (170, 0, 255), (255, 0, 255), (255, 0, 170), (255, 0, 85)]


class Pose:
    def __init__(self):
        self.det = self.det_proc = self.pose = self.pose_proc = None
        self.dirs = None

    def unload(self):
        self.det = self.det_proc = self.pose = self.pose_proc = self.dirs = None
        free_cuda()

    def _load(self, det_dir, pose_dir):
        if self.dirs == (det_dir, pose_dir):
            return
        from transformers import AutoProcessor, RTDetrForObjectDetection, VitPoseForPoseEstimation
        self.det_proc = AutoProcessor.from_pretrained(det_dir, local_files_only=True)
        self.det = RTDetrForObjectDetection.from_pretrained(det_dir, local_files_only=True, use_safetensors=True).eval().to("cuda")
        self.pose_proc = AutoProcessor.from_pretrained(pose_dir, local_files_only=True)
        self.pose = VitPoseForPoseEstimation.from_pretrained(pose_dir, local_files_only=True, use_safetensors=True).eval().to("cuda")
        self.dirs = (det_dir, pose_dir)

    def map(self, img, det_dir, pose_dir):
        """Esqueleto no padrão OpenPose (fundo preto, membros coloridos): é o que o ControlNet de pose espera."""
        self._load(det_dir, pose_dir)
        inputs = self.det_proc(images=img, return_tensors="pt").to("cuda")
        with torch.inference_mode():
            out = self.det(**inputs)
        res = self.det_proc.post_process_object_detection(out, target_sizes=torch.tensor([(img.height, img.width)]), threshold=0.4)[0]
        boxes = res["boxes"][res["labels"] == 0].cpu().numpy()  # 0 = pessoa
        canvas = Image.new("RGB", img.size, (0, 0, 0))
        if len(boxes) == 0:
            return canvas, 0
        boxes[:, 2] -= boxes[:, 0]
        boxes[:, 3] -= boxes[:, 1]  # xywh
        pin = self.pose_proc(img, boxes=[boxes], return_tensors="pt").to("cuda")
        with torch.inference_mode():
            pout = self.pose(**pin)
        people = self.pose_proc.post_process_pose_estimation(pout, boxes=[boxes])[0]
        d = ImageDraw.Draw(canvas)
        stick = max(2, int(min(img.size) / 170))
        for person in people:
            kp = person["keypoints"].cpu().numpy()
            sc = person["scores"].cpu().numpy()
            pts = []
            for coco in _COCO_TO_OP:
                if coco is None:
                    ok = sc[5] > 0.3 and sc[6] > 0.3
                    pts.append(((kp[5] + kp[6]) / 2) if ok else None)
                else:
                    pts.append(kp[coco] if sc[coco] > 0.3 else None)
            for i, (a, b) in enumerate(_LIMBS):
                if pts[a] is None or pts[b] is None:
                    continue
                d.line([tuple(pts[a]), tuple(pts[b])], fill=tuple(int(c * 0.6) for c in _COLORS[i % 18]), width=stick * 2)
            for i, p in enumerate(pts):
                if p is not None:
                    d.ellipse([p[0] - stick, p[1] - stick, p[0] + stick, p[1] + stick], fill=_COLORS[i])
        return canvas, len(people)

# ------------------------------------------------------------------ recortes para o detail pass e o refino em blocos

def crop_for_detail(img, box, pad_ratio=0.35, target=1024):
    """Recorte quadrado com margem em volta da região, e a escala para gerar perto de `target` px."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    side = max(w, h) * (1 + 2 * pad_ratio)
    side = max(side, 64)
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    W, H = img.size
    side = min(side, W, H)
    cx0 = int(max(0, min(W - side, cx - side / 2)))
    cy0 = int(max(0, min(H - side, cy - side / 2)))
    crop = (cx0, cy0, int(cx0 + side), int(cy0 + side))
    return crop


def ellipse_mask(size, box_in_crop, blur):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).ellipse(box_in_crop, fill=255)
    return m.filter(ImageFilter.GaussianBlur(blur)) if blur else m


def tiles(w, h, tile=1024, overlap=128):
    """Blocos que cobrem a imagem com sobreposição (para refinar imagens grandes sem estourar a VRAM)."""
    def starts(n):
        if n <= tile:
            return [0]
        k = math.ceil((n - overlap) / (tile - overlap))
        step = (n - tile) / (k - 1)
        return [int(round(i * step)) for i in range(k)]
    return [(x, y, min(w, x + tile), min(h, y + tile)) for y in starts(h) for x in starts(w)]


def tile_weight(tw, th, overlap, left, top, right, bottom):
    """Peso de mistura do bloco: rampa nas bordas que encostam em outro bloco."""
    wx = np.ones(tw, dtype=np.float32)
    wy = np.ones(th, dtype=np.float32)
    ramp = np.linspace(0, 1, overlap, dtype=np.float32)
    if left:
        wx[:overlap] = ramp
    if right:
        wx[-overlap:] = ramp[::-1]
    if top:
        wy[:overlap] = ramp
    if bottom:
        wy[-overlap:] = ramp[::-1]
    return np.outer(wy, wx)[..., None]
