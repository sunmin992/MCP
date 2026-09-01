#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""response_filtered.csv(장량동 인근 교통량) → TrafficProfile JSON.

대응 B: 기존 시드(jangryang-weekday.json)는 건드리지 않고, 실데이터를
별도 id 'jangryang-weekday-real'로 저장한다. link_id가 비어 있어
지점명(begin/end 노드명) 키워드로 4개 시뮬 노드에 귀속한다.

사용:
  python scripts/preprocess_response_filtered.py [csv] [--id ID] [--out PATH]
기본:
  csv = response_filtered.csv
  ID  = jangryang-weekday-real
  out = src/main/resources/traffic/jangryang-weekday-real.json
"""
import sys, json, os, csv

args = [a for a in sys.argv[1:] if not a.startswith("--")]
opts = {sys.argv[i]: sys.argv[i + 1] for i in range(1, len(sys.argv) - 1) if sys.argv[i].startswith("--")}

SRC = args[0] if args else "response_filtered.csv"
PROFILE_ID = opts.get("--id", "jangryang-weekday-real")
OUT = opts.get("--out", "src/main/resources/traffic/jangryang-weekday-real.json")
K = 1.2                               # 피크 지연 강도(글로벌 최대 대비). 1+K = 최대 가중치
# 시뮬레이션상 골목(물리 속성, 이 CSV와 무관). V-T3(대형트럭 진입 불가) 판정에 쓰인다.
#
# 주의 — 이 값은 확정된 노드 좌표와 어긋난다. traffic/jangryang-nodes.json이 노드 위치를
# 못박은 뒤 OSM으로 확인해 보니 Node_C는 4차로 새천년대로와 만나는 교차로이고,
# Node_D는 6차로 삼흥로변이다. 둘 다 골목이 아니다.
#
# 그대로 두는 이유는 값을 바꾸면 동작이 바뀌기 때문이다 — 비우면 V-T3가 영영 발동하지
# 않고(SimulationConfigValidatorTest·TruckRouteSearchTest·JangnyangScenarioBuilderTest가
# 이 값에 의존한다), 5톤 차량이 지금은 못 도는 경로를 돌게 된다. 실측이 아니라 모델링
# 가정이라는 사실만 분명히 해 두고, 바꿀지는 별도 결정으로 남긴다.
ALLEY = ["Node_C", "Node_D"]

# 지점명 키워드 → 시뮬 노드. begin/end 노드명에 키워드가 있으면 귀속.
NODE_KEYWORDS = {
    "Node_B": ["양덕"],
    "Node_A": ["장성초등학교"],
    "Node_C": ["장성초등사거리", "창포"],
    "Node_D": ["두산위브", "포항온천"],
}
HOURS = ["hour_%02d" % h for h in range(24)]   # hour_00..hour_23 → index 0..23


def classify(begin, end):
    text = (begin or "") + " " + (end or "")
    for node, kws in NODE_KEYWORDS.items():
        if any(k in text for k in kws):
            return node
    return None


def main():
    rows = list(csv.DictReader(open(SRC, encoding="utf-8-sig")))
    agg = {n: [0.0] * 24 for n in NODE_KEYWORDS}
    cnt = {n: 0 for n in NODE_KEYWORDS}
    unmapped = 0
    for r in rows:
        node = classify(r.get("begin_node_nm"), r.get("end_node_nm"))
        if node is None:
            unmapped += 1
            continue
        cnt[node] += 1
        for h in range(24):
            try:
                agg[node][h] += float(r.get(HOURS[h]) or 0)
            except ValueError:
                pass
    node_vol = {n: [agg[n][h] / cnt[n] if cnt[n] else 0.0 for h in range(24)] for n in NODE_KEYWORDS}
    vmax = max((v for arr in node_vol.values() for v in arr), default=1.0) or 1.0
    node_hourly = {n: [round(1.0 + K * (node_vol[n][h] / vmax), 2) for h in range(24)] for n in NODE_KEYWORDS}
    global_hourly = [round(sum(node_hourly[n][h] for n in NODE_KEYWORDS) / len(NODE_KEYWORDS), 2) for h in range(24)]

    profile = {
        "id": PROFILE_ID,
        "congestionThresholdRed": 1.7,
        "hourlyWeight": global_hourly,
        "nodeHourlyWeight": node_hourly,
        "alleyNodeIds": ALLEY,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(profile, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("WROTE", OUT, "| id:", PROFILE_ID, "| 매핑:", {n: cnt[n] for n in NODE_KEYWORDS}, "| 미매핑:", unmapped)


if __name__ == "__main__":
    main()
