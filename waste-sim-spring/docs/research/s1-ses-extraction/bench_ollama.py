# -*- coding: utf-8 -*-
"""로컬 모델 속도 측정. **창만 바꾸고 나머지는 고정한다.**

모델·양자화·입력·출력 한도를 고정하지 않으면 창의 효과를 잴 수 없다. 그래서 여기서
바꾸는 것은 `num_ctx` 하나뿐이다.

매 측정 전에 모델을 내린다(`keep_alive: 0`). 그래야 `load_duration` 이 그 창에서의
적재 시간을 뜻한다 — 이미 올라와 있는 모델을 재면 0이 나온다.

기록하는 것(요청받은 표 그대로):
  설정 창 · 실제 창(/api/ps) · 전체 적재량 · GPU 적재량 · 적재 시간 ·
  입력 토큰/시간/속도 · 출력 토큰/시간/속도 · 전체 시간 · 종료 상태

사용:
  python bench_ollama.py --model qwen3-coder:30b --windows 8192,16384,32768
  python bench_ollama.py --model qwen3-coder:30b --windows 32768 --full-prompt
"""
from __future__ import annotations

import argparse
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request

KIT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, KIT)

HOST = "http://127.0.0.1:11434"


def post(path, body, timeout):
    req = urllib.request.Request(HOST + path, data=json.dumps(body, ensure_ascii=False).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def ps():
    try:
        with urllib.request.urlopen(HOST + "/api/ps", timeout=20) as r:
            return (json.load(r).get("models") or [])
    except Exception:
        return []


def unload(model):
    try:
        post("/api/generate", {"model": model, "prompt": "", "keep_alive": 0}, 180)
    except Exception:
        pass
    for _ in range(30):
        if not any(m.get("name") == model for m in ps()):
            return True
        time.sleep(2)
    return False


def build_prompt(run_id, approx_tokens=None):
    """실제 코드로 만든다 — 합성 문자열로 재면 토크나이즈 특성이 달라진다."""
    from sesx import snapshot as snap_mod
    snap = snap_mod.Snapshot(os.path.join(KIT, "exp", run_id, "snapshot"))
    code = snap.bundle(line_numbers=True)
    if approx_tokens:
        code = code[:approx_tokens * 3]      # 대략 3바이트/토큰. 정확도는 실측 토큰 수로 본다
    return code


def measure(model, num_ctx, prompt, num_predict, temperature, timeout):
    unload(model)
    body = {"model": model, "stream": False, "format": "json",
            "options": {"num_ctx": num_ctx, "num_predict": num_predict,
                        "temperature": temperature},
            "messages": [{"role": "system",
                          "content": "아래 코드를 읽고 JSON 하나로만 답하라."},
                         {"role": "user",
                          "content": prompt + "\n\n=====\n"
                          '{"observations": [{"name": "...", "what_changes": "..."}]}'}]}
    row = {"num_ctx": num_ctx, "num_predict": num_predict, "temperature": temperature,
           "prompt_chars": len(prompt)}
    t0 = time.time()
    try:
        d = post("/api/chat", body, timeout)
        row["status"] = "완료" if d.get("done_reason") == "stop" else f"중단({d.get('done_reason')})"
        row["prompt_tokens"] = d.get("prompt_eval_count")
        row["prompt_s"] = round(d.get("prompt_eval_duration", 0) / 1e9, 1)
        row["output_tokens"] = d.get("eval_count")
        row["output_s"] = round(d.get("eval_duration", 0) / 1e9, 1)
        row["load_s"] = round(d.get("load_duration", 0) / 1e9, 1)
        row["prompt_tok_s"] = (round(row["prompt_tokens"] / row["prompt_s"], 1)
                               if row["prompt_s"] else None)
        row["output_tok_s"] = (round(row["output_tokens"] / row["output_s"], 1)
                               if row["output_s"] else None)
    except (urllib.error.URLError, TimeoutError) as e:
        row["status"] = f"시간 초과({timeout}초)" if isinstance(e, TimeoutError) or \
                        "timed out" in str(e) else f"실패({type(e).__name__})"
        row["error"] = str(e)[:200]
    except urllib.error.HTTPError as e:
        row["status"] = f"HTTP {e.code}"
        row["error"] = e.read().decode("utf-8", "replace")[:200]
    row["total_s"] = round(time.time() - t0, 1)
    loaded = next((m for m in ps() if m.get("name") == model), None)
    row["ps_context_length"] = (loaded or {}).get("context_length")
    row["size_total_gb"] = round((loaded or {}).get("size", 0) / 1e9, 1)
    row["size_vram_gb"] = round((loaded or {}).get("size_vram", 0) / 1e9, 1)
    return row


def table(rows):
    head = ("| 설정 창 | 실제 창 | 전체 적재 | GPU 적재 | 적재 | 입력 tok | 입력 s | 입력 tok/s "
            "| 출력 tok | 출력 s | 출력 tok/s | 전체 s | 종료 |")
    sep = "|" + "---|" * 13
    out = [head, sep]
    for r in rows:
        out.append("| {num_ctx} | {ps_context_length} | {size_total_gb}GB | {size_vram_gb}GB "
                   "| {load_s}s | {prompt_tokens} | {prompt_s} | {prompt_tok_s} "
                   "| {output_tokens} | {output_s} | {output_tok_s} | {total_s} | {status} |"
                   .format(**{k: r.get(k) for k in
                              ("num_ctx", "ps_context_length", "size_total_gb", "size_vram_gb",
                               "load_s", "prompt_tokens", "prompt_s", "prompt_tok_s",
                               "output_tokens", "output_s", "output_tok_s", "total_s",
                               "status")}))
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="qwen3-coder:30b")
    ap.add_argument("--run-id", default="q3-1", help="스냅샷을 빌려올 실행 디렉터리")
    ap.add_argument("--windows", default="8192,16384,32768")
    ap.add_argument("--approx-tokens", type=int, default=4000,
                    help="입력 크기를 이만큼으로 자른다. --full-prompt 면 무시")
    ap.add_argument("--full-prompt", action="store_true", help="스냅샷 전문을 그대로 쓴다")
    ap.add_argument("--num-predict", type=int, default=256)
    ap.add_argument("--temperature", type=float, default=0.2)
    ap.add_argument("--timeout", type=int, default=1800)
    ap.add_argument("--out", default=None)
    a = ap.parse_args()

    prompt = build_prompt(a.run_id, None if a.full_prompt else a.approx_tokens)
    print(f"입력: {len(prompt):,}자 ({'전문' if a.full_prompt else f'~{a.approx_tokens}토큰으로 자름'})")
    rows = []
    for w in [int(x) for x in a.windows.split(",")]:
        print(f"  창 {w} 측정 중...", flush=True)
        r = measure(a.model, w, prompt, a.num_predict, a.temperature, a.timeout)
        rows.append(r)
        print(f"    {r['status']} · 전체 {r['total_s']}초 · 적재 {r.get('load_s')}초 "
              f"· 입력 {r.get('prompt_tokens')}tok/{r.get('prompt_s')}s "
              f"· 출력 {r.get('output_tokens')}tok/{r.get('output_s')}s", flush=True)

    out = a.out or os.path.join(KIT, "exp", "bench",
                                f"{a.model.replace(':', '-')}-{'full' if a.full_prompt else 'small'}.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    meta = {"model": a.model, "fixed": {"num_predict": a.num_predict,
                                        "temperature": a.temperature,
                                        "prompt_chars": len(prompt),
                                        "input": "전문" if a.full_prompt else f"~{a.approx_tokens}토큰",
                                        "timeout_s": a.timeout},
            "at": time.time(), "rows": rows}
    io.open(out, "w", encoding="utf-8").write(json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
    print("\n" + table(rows))
    print(f"\n기록: {out}")


if __name__ == "__main__":
    main()
