# Benchmark real do worker de imagem (o mesmo processo que o Companion usa), fora do Companion.
# Uso: python image_bench.py <worker.py> <engine> <model_dir> <cache_dir> <out_dir> [transformer=bf16|fp8] [te=gpu|swap] [plan]
# plan: lista "LxA:steps:n" separada por vírgula, ex.: 1024x1024:9:4,832x1216:9:2,1216x832:9:2
import json
import os
import subprocess
import sys
import threading
import time

worker, engine, model_dir, cache_dir, out_dir = sys.argv[1:6]
precision = sys.argv[6] if len(sys.argv) > 6 else "bf16"
te = sys.argv[7] if len(sys.argv) > 7 else "gpu"
plan = sys.argv[8] if len(sys.argv) > 8 else "1024x1024:9:4,832x1216:9:2,1216x832:9:2"
prompts = [
    "A brutalist concrete mansion on top of a mountain during a thunderstorm, cinematic lighting, rain, dramatic sky",
    "Photorealistic portrait of an elderly fisherman mending a net at golden hour, 85mm lens, shallow depth of field",
    "A glass perfume bottle on black marble, studio product photography, soft rim light, reflections",
    "Anime illustration of a girl riding a bicycle through a flower field, bright colors, clean line art",
]
os.makedirs(out_dir, exist_ok=True)
env = dict(os.environ, PYTHONUTF8="1", HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1")

# otimização (cópias de carga rápida) num processo próprio, como o Companion faz depois de instalar
o = subprocess.Popen([sys.executable, "-u", worker], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                     env=env, text=True, encoding="utf-8", bufsize=1)
t = time.time()
o.stdin.write(json.dumps({"cmd": "optimize", "id": "O", "engine": engine, "path": model_dir, "cache": cache_dir}) + "\n")
o.stdin.flush()
for line in o.stdout:
    e = json.loads(line)
    if e["ev"] in ("optimized", "error"):
        print("otimização:", e, f"{time.time() - t:.0f} s")
        break
o.stdin.close()
o.wait()

p = subprocess.Popen([sys.executable, "-u", worker], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                     env=env, text=True, encoding="utf-8", bufsize=1)
threading.Thread(target=lambda: [sys.stderr.write("  | " + l) for l in p.stderr], daemon=True).start()


def send(c):
    p.stdin.write(json.dumps(c) + "\n")
    p.stdin.flush()


def wait(evs, job=None):
    while True:
        line = p.stdout.readline()
        if not line:
            raise SystemExit("worker morreu")
        e = json.loads(line)
        if e["ev"] == "progress":
            if "preview" in e:
                e["preview"] = f"<{len(e['preview'])} b64>"
            continue
        print("  <", json.dumps(e)[:400])
        if e["ev"] in evs and (job is None or e.get("id") == job):
            return e
        if e["ev"] in ("error", "fatal"):
            return e


t = time.time()
wait({"ready"})
print(f"worker pronto em {time.time() - t:.1f} s")
send({"cmd": "load", "id": "L", "engine": engine, "path": model_dir, "cache": cache_dir, "transformer": precision, "text_encoder": te})
e = wait({"loaded"}, "L")
print(f"carregado: {e}")
results = []
n = 0
for item in plan.split(","):
    res, steps, count = item.split(":")
    w, h = map(int, res.split("x"))
    for i in range(int(count)):
        job = f"j{n}"
        prompt = prompts[n % len(prompts)]
        send({"cmd": "generate", "id": job, "prompt": prompt, "width": w, "height": h, "steps": int(steps),
              "guidance": 3.5 if engine == "flux" else 0.0, "seed": 1234 + n, "count": 1,
              "out": os.path.join(out_dir, f"{engine}-{precision}-{w}x{h}-{n}"), "preview": True})
        e = wait({"done"}, job)
        if e["ev"] == "done":
            s = e["stats"]
            results.append((f"{w}x{h}", steps, s["totalMs"], s["encodeMs"], s["meanStepMs"], s["peakAllocatedMb"], s["peakReservedMb"],
                            e["mem"]["freeMb"], e.get("rssMb")))
        n += 1
send({"cmd": "status", "id": "S"})
wait({"status"}, "S")
send({"cmd": "shutdown"})
print("\nres        steps  total_ms  encode_ms  step_ms  peak_alloc  peak_resv  free_after  rss")
for r in results:
    print("  ".join(str(x) for x in r))
