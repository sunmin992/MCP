"""E3 — 에이전트 한 번이 쓴 비용. 서브에이전트 기록(*.jsonl)에서 실행 id 를 찾아 잰다.

  모델 호출   assistant 메시지의 서로 다른 message.id 수
  새 입력     input_tokens + cache_creation_input_tokens (message.id 별로 한 번)
  캐시 읽기   cache_read_input_tokens (message.id 별로 한 번)
  출력        message.id 별 output_tokens 의 최댓값 — 기록이 스트리밍 중간 값을 남기기도 해 보조로만 쓴다
  MCP 호출    Bash 도구 호출 가운데 명령에 mcp.sh 가 든 것
  걸린 시간   첫 기록과 마지막 기록의 시각 차(초)
python e3.py <subagents 폴더>   → e3.json
"""
import json, glob, os, re, sys
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
SUBS = sys.argv[1:] or [os.path.expanduser(
    "~/.claude/projects/C--Dev-MCP/0d0588b1-2503-4c5e-8c1a-a280e6fd1cb4/subagents")]
ID = re.compile(r"prompts[\\/]+(V2-[A-Z]\d-[AB]-r\d)\.md")


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


out = {}
for path in [p for sub in SUBS for p in glob.glob(os.path.join(sub, "agent-*.jsonl"))]:
    lines = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
    first = next((l for l in lines if l.get("type") == "user"), None)
    m = first and ID.search(json.dumps(first["message"]["content"], ensure_ascii=False))
    if not m:
        continue
    usage, mcp, times = {}, 0, []
    for l in lines:
        if "timestamp" in l:
            times.append(ts(l["timestamp"]))
        msg = l.get("message") or {}
        if l.get("type") == "assistant" and msg.get("id"):
            u = msg.get("usage") or {}
            prev = usage.get(msg["id"])
            usage[msg["id"]] = {
                "new": u.get("input_tokens", 0) + u.get("cache_creation_input_tokens", 0),
                "cacheRead": u.get("cache_read_input_tokens", 0),
                "out": max(u.get("output_tokens", 0), prev["out"] if prev else 0),
            }
            for c in msg.get("content") or []:
                if c.get("type") == "tool_use" and c.get("name") == "Bash" and "mcp.sh" in c.get("input", {}).get("command", ""):
                    mcp += 1
    rid = m.group(1)
    if rid in out:  # 같은 id 를 다시 돌렸으면 나중 것
        if out[rid]["start"] > min(times).isoformat():
            continue
    out[rid] = {
        "modelCalls": len(usage),
        "inputNew": sum(u["new"] for u in usage.values()),
        "inputCacheRead": sum(u["cacheRead"] for u in usage.values()),
        "outputTokens": sum(u["out"] for u in usage.values()),
        "mcpCalls": mcp,
        "seconds": round((max(times) - min(times)).total_seconds(), 1),
        "start": min(times).isoformat(),
        "transcript": os.path.basename(path),
    }
json.dump(dict(sorted(out.items())), open(os.path.join(HERE, "e3.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(len(out), "runs")
