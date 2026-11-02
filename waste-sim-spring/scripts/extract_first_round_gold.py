"""V2 실험 A 조건 54회의 첫 조회(profileSent)를 뽑아 첫 조회 정답 모음 초안을 만든다.

expectedServers 는 요청 id 첫 글자(J/D/P)가 가리키는 서버다. goldProfile 은 profileSent 를
그대로 복사한 초안이고 confirmed=false — 연구자가 검토하기 전까지 확정 라벨이 아니다.
실행: python -I scripts/extract_first_round_gold.py
"""
import glob
import json
import os

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
BASE = os.path.join(ROOT, "docs", "reports", "baseline-experiment-v2-2026-10-21")
OUT = os.path.join(ROOT, "broker", "src", "test", "resources", "eval", "first-round-gold.json")

servers = json.load(open(os.path.join(BASE, "requests.json"), encoding="utf-8"))["servers"]
rows = []
for path in sorted(glob.glob(os.path.join(BASE, "runs", "V2-*-A-r*.json"))):
    run = json.load(open(path, encoding="utf-8"))
    run_id = os.path.basename(path)[:-5]
    req_id = run_id.split("-")[1]
    profile = run["brokerCalls"][0]["profileSent"]
    rows.append({
        "runId": run_id,
        "requestId": req_id,
        "profileSent": profile,
        "expectedServers": [servers[req_id[0]]["serverId"]],
        "goldProfile": json.loads(json.dumps(profile)),
        "confirmed": False,
        "labelNote": "초안: 요청 id 가 가리키는 서버. 연구자 검토 전",
    })

with open(OUT, "w", encoding="utf-8") as f:
    json.dump(rows, f, ensure_ascii=False, indent=1)
print(len(rows), "줄 →", OUT)
