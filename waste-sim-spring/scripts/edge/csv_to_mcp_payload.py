#!/usr/bin/env python3
"""측정 CSV → MCP `calibrate_edge_thermal_model` 호출 페이로드 변환기.

measure_throttling.py가 남긴 CSV(+메타데이터 JSON)를 읽어
  1) 캘리브레이션 도구가 그대로 먹는 JSON-RPC 요청을 만들고,
  2) --post 를 주면 MCP 서버(기본 http://localhost:8090/mcp)로 바로 보내 결과를 출력한다.

샘플이 너무 촘촘하면(1초 간격 1시간 = 3600점) 그대로 보내도 되지만, --every 로 솎아내면
요청이 가벼워진다 — 지수 곡선 적합에는 초반(시정수 1~2배 구간)의 조밀함이 중요하므로
기본값은 초반을 그대로 두고 뒤로 갈수록 성기게 뽑는다.

사용 예:
    python3 csv_to_mcp_payload.py runs/pi5-passive-....csv --load-end 1020 > payload.json
    python3 csv_to_mcp_payload.py runs/pi5-passive-....csv --post
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import urllib.request

WANTED = ["t_sec", "soc_temp_c", "power_w", "clock_mhz", "fps", "throttled"]


def load_rows(path: str):
    with open(path, newline="", encoding="utf-8") as f:
        return [r for r in csv.DictReader(f)]


def trim_baseline(rows: list[dict]):
    """유휴 기준선(BASELINE) 구간을 잘라내고 부하 시작을 t=0으로 다시 매긴다.

    캘리브레이션은 "부하를 건 순간부터의 상승 곡선"을 지수함수로 맞추는 것이라,
    앞에 붙은 평평한 유휴 구간을 함께 넣으면 적합이 망가진다. 대신 기준선의 평균 온도는
    시작 온도(T0)로서 곡선 자체에 이미 담겨 있으므로 잘라내도 정보 손실이 없다."""
    idx = next((i for i, r in enumerate(rows) if (r.get("phase") or "").upper() == "LOAD"), None)
    if idx is None:
        return rows, 0.0
    t0 = float(rows[idx]["t_sec"])
    out = []
    for r in rows[idx:]:
        r = dict(r)
        r["t_sec"] = f'{float(r["t_sec"]) - t0:.1f}'
        out.append(r)
    return out, t0


def thin(rows: list[dict], every: int, adaptive: bool) -> list[dict]:
    """샘플 솎아내기. adaptive=True면 초반 2분은 전부, 이후는 every 간격으로."""
    if every <= 1:
        return rows
    out = []
    for i, r in enumerate(rows):
        try:
            t = float(r.get("t_sec") or 0)
        except ValueError:
            continue
        if adaptive and t <= 120:
            out.append(r)
        elif i % every == 0:
            out.append(r)
    return out


def to_csv_text(rows: list[dict]) -> str:
    lines = [",".join(WANTED)]
    for r in rows:
        if not (r.get("t_sec") and r.get("soc_temp_c")):
            continue
        lines.append(",".join((r.get(c) or "") for c in WANTED))
    return "\n".join(lines)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv_path")
    ap.add_argument("--meta", help="메타데이터 JSON 경로(기본: 같은 이름의 .json)")
    ap.add_argument("--board", help="메타데이터가 없을 때 직접 지정(pi4|pi5)")
    ap.add_argument("--ambient", type=float, help="주변 온도 ℃(메타데이터에 있으면 생략 가능)")
    ap.add_argument("--label", help="조건 라벨")
    ap.add_argument("--load-end", type=float, help="부하 종료 시각(초). 생략하면 CSV의 phase 열에서 자동 판정")
    ap.add_argument("--keep-baseline", action="store_true",
                    help="유휴 기준선 구간을 자르지 않고 그대로 보낸다(기본은 잘라내고 부하 시작을 t=0으로)")
    ap.add_argument("--every", type=int, default=5, help="N개마다 1개만 남긴다(기본 5)")
    ap.add_argument("--no-adaptive", action="store_true", help="초반 조밀 유지 없이 균일하게 솎아낸다")
    ap.add_argument("--post", action="store_true", help="MCP 서버로 바로 호출")
    ap.add_argument("--url", default="http://localhost:8090/mcp")
    args = ap.parse_args()

    rows = load_rows(args.csv_path)
    if not rows:
        print("CSV가 비어 있다.", file=sys.stderr)
        return 1

    meta_path = args.meta or os.path.splitext(args.csv_path)[0] + ".json"
    meta = {}
    if os.path.exists(meta_path):
        with open(meta_path, encoding="utf-8") as f:
            meta = json.load(f)

    board = args.board or meta.get("board")
    ambient = args.ambient if args.ambient is not None else meta.get("ambient_temp_c")
    label = args.label or meta.get("label") or os.path.basename(args.csv_path)
    if not board or ambient is None or (isinstance(ambient, float) and ambient != ambient):
        print("board와 ambient(주변 온도)가 필요하다 — 메타데이터 JSON이 없으면 "
              "--board / --ambient 로 직접 넣을 것.", file=sys.stderr)
        return 2

    shift = 0.0
    if not args.keep_baseline:
        rows, shift = trim_baseline(rows)
        if shift > 0:
            print(f"[i] 유휴 기준선 {shift:.0f}초를 잘라내고 부하 시작을 t=0으로 맞췄다.", file=sys.stderr)

    load_end = args.load_end
    if load_end is not None:
        load_end -= shift
    if load_end is None:
        last_load = [r for r in rows if (r.get("phase") or "").upper() == "LOAD"]
        if last_load:
            load_end = float(last_load[-1]["t_sec"])
        else:
            print("[!] phase 열이 없어 부하 종료 시각을 모른다 — --load-end 로 직접 넣을 것.", file=sys.stderr)

    payload = {
        "board": board,
        "ambientTempC": float(ambient),
        "label": label,
        "samplesCsv": to_csv_text(thin(rows, args.every, not args.no_adaptive)),
    }
    if load_end is not None:
        payload["loadEndSeconds"] = float(load_end)

    request = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
               "params": {"name": "calibrate_edge_thermal_model", "arguments": payload}}

    if not args.post:
        json.dump(request, sys.stdout, ensure_ascii=False, indent=2)
        print()
        return 0

    data = json.dumps(request).encode("utf-8")
    req = urllib.request.Request(args.url, data=data,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    # MCP CallToolResult → content[0].text 안에 도구 결과 JSON이 문자열로 들어 있다
    try:
        text = body["result"]["content"][0]["text"]
        print(json.dumps(json.loads(text), ensure_ascii=False, indent=2))
        if body["result"].get("isError"):
            return 3
    except (KeyError, IndexError, json.JSONDecodeError):
        print(json.dumps(body, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
