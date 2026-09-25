# Teste de prompts em português: direto vs. traduzido/melhorado pelo Qwen3 do Z-Image (mesma seed).
# Uso: python image_prompt_test.py <worker.py> <model_dir> <cache_dir> <out_dir>
import json
import os
import subprocess
import sys

worker, model_dir, cache_dir, out_dir = sys.argv[1:5]
os.makedirs(out_dir, exist_ok=True)
env = dict(os.environ, PYTHONUTF8="1", HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1")
p = subprocess.Popen([sys.executable, "-u", worker], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                     env=env, text=True, encoding="utf-8", bufsize=1)


def call(c, final):
    p.stdin.write(json.dumps(c, ensure_ascii=False) + "\n")
    p.stdin.flush()
    for line in p.stdout:
        e = json.loads(line)
        if e["ev"] in final or e["ev"] == "error":
            return e


call({"cmd": "hello", "id": "h"}, {"hello"})
print(call({"cmd": "load", "id": "L", "engine": "zimage", "path": model_dir, "cache": cache_dir, "transformer": "bf16", "text_encoder": "fp8"}, {"loaded"})["seconds"])
tests = [
    "Uma mansão brutalista extremamente moderna no meio de uma floresta montanhosa durante a noite, chuva forte, iluminação cinematográfica.",
    "Gere uma imagem de uma Ferrari vermelha em Tóquio à noite",
    "um café aconchegante com uma placa escrito \"Bom dia\" na parede",
    "uma mansão futurista na floresta",
]
te = os.path.join(model_dir, "text_encoder")
tok = os.path.join(model_dir, "tokenizer")
for i, t in enumerate(tests):
    tr = call({"cmd": "rewrite", "id": f"r{i}", "text": t, "mode": "translate", "path": te, "tokenizer": tok}, {"rewritten"})
    en = call({"cmd": "rewrite", "id": f"e{i}", "text": t, "mode": "enhance", "path": te, "tokenizer": tok}, {"rewritten"})
    print(f"\n[{i}] PT: {t}\n  traduzido ({tr.get('ms')} ms): {tr.get('text')}\n  melhorado ({en.get('ms')} ms): {en.get('text')}")
    for tag, prompt in (("pt", t), ("tr", tr.get("text")), ("en", en.get("text"))):
        r = call({"cmd": "generate", "id": f"g{i}{tag}", "prompt": prompt, "width": 1024, "height": 1024, "steps": 9, "seed": 777 + i,
                  "count": 1, "out": os.path.join(out_dir, f"p{i}-{tag}")}, {"done"})
        print("  ", tag, r.get("stats", {}).get("totalMs"), r.get("code"))
p.stdin.close()
p.wait()
