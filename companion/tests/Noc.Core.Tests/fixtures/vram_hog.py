# Ocupa VRAM de propósito (teste de "baixa VRAM"): python vram_hog.py <GiB>. Fica até o stdin fechar.
import sys
import torch

gib = float(sys.argv[1])
x = torch.empty(int(gib * 1024 ** 3), dtype=torch.uint8, device="cuda")
x.fill_(1)
torch.cuda.synchronize()
print("ok", flush=True)
sys.stdin.read()
