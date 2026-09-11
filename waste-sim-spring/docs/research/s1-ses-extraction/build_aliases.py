"""이중언어 대응표를 만든다 — 추측이 아니라 세 근거에서 유도한다.

  ① 참조 evidence에 적어 둔 코드 위치 (예: 거주민.외출시각 -> OccupationType.leaveMeanMinutes:17)
  ② 9회 실행이 실제로 낸 이름 전수
  ③ 소유 관계 확인 — 속성 별칭은 소유 엔티티가 맞아야 값을 한다

대응이 분명하지 않은 것은 넣지 않는다. 별칭은 오답을 정답으로 만들 수 있으므로,
넣지 않은 것과 그 이유를 파일에 남긴다.
"""
import collections, glob, io, json, os, re, unicodedata

KIT = r"C:\Dev\MCP\waste-sim-spring\docs\research\s1-ses-extraction"
ref = json.load(io.open(os.path.join(KIT, "reference-ses.json"), encoding="utf-8"))
REF_ENTS = set(ref["entities"])
REF_ATTRS = {a for b in ref["entities"].values() for a in (b.get("attrs") or [])}

# ── 엔티티 대응 — 개념이 분명한 것만 ─────────────────────────────────────
ENT = {
    # 코드에 독립 클래스·enum으로 있고 참조에도 대응 노드가 있는 것
    "WasteType": "폐기물 유형",
    "TruckType": "수거차량",
    "VehicleType": "수거차량",
    "CollectionSite": "수거지점",
    "Building": "수거지점",
    "TrafficProfile": "교통 구역",
    # "TrafficZone" 은 넣을 수 없다 — 수거지점의 속성 trafficZone 과 정규화 키가 겹친다.
    # 별칭이 (문맥, 이름)이 아니라 이름 하나에만 걸리는 구조적 한계다. 속성 쪽을 살렸다
    # (evidence에서 유도된 것이고 등장 횟수도 많다). 엔티티 TrafficZone은 매칭되지 않고
    # 미매칭 목록에 남는다 — 오답을 정답으로 만들지는 않는다.
    "RoutePlan": "수거 경로",
    "Route": "수거 경로",
    "SimulationResult": "관측",
    "Scenario": "실험",
    "SimulationScenario": "실험",
    # enum 값 → 참조의 spec 자식
    "LARGE_5TON": "5톤 차량", "MEDIUM_2P5T": "2.5톤 차량", "SMALL_1TON": "1톤 차량",
    "LEGACY_CONSTANT": "구간 상수", "OSRM_HYBRID": "실제 도로 기반",
    "ZONE_PROXY_HYBRID": "교통구역 근사",
    "PAPER_BASELINE": "직업별 외출시각 기반", "POHANG_ACTUAL": "포항시 배출시간대 기반",
}

# ── 속성 대응 — 참조 evidence에서 유도. 파일 경로·메서드는 걸러낸다. ────────
ATTR_FROM_EVIDENCE = {}
for k, v in ref["evidence"].items():
    if "." not in k:
        continue
    attr = k.split(".", 1)[1]
    for m in re.finditer(r"\b([a-z][A-Za-z0-9_]*)\s*:\d+", v):
        f = m.group(1)
        if f in ("java", "py"):            # 파일 확장자가 잡힌 것
            continue
        if f in ("weightAt",):             # 메서드이지 필드가 아니다
            continue
        ATTR_FROM_EVIDENCE.setdefault(f, attr)

# evidence에 없지만 소유 관계 확인으로 분명한 것 (③)
ATTR_EXTRA = {
    "hourlyWeight": "시간대프로파일",       # TrafficProfile.hourlyWeight -> 교통 구역.시간대프로파일
    "nodeHourlyWeight": "혼잡계수",         # 구역별 시간대 가중치 = 혼잡계수의 실체
}
ATTR = {**ATTR_FROM_EVIDENCE, **ATTR_EXTRA}

# ── 넣지 않은 것과 이유 ─────────────────────────────────────────────────
SKIPPED = collections.OrderedDict([
    ("SimulationConfig", "참조에 대응 노드가 없다. 참조는 이 설정값들을 '무엇을 서술하는가'로 "
                         "거주민·수거지점·폐기물 유형·실험에 나눠 붙인다. 한 노드로 대응시키면 "
                         "분류 기준이 다른 것을 같다고 적는 셈이다"),
    ("OccupationType", "참조의 거주민은 배출하는 개체(DischargeEvt)이고 OccupationType은 그 "
                       "직업 축이다. 거주민으로 대응시키면 CP-5가 지키려는 구분(거주민 아래 "
                       "spec 축 2개)이 무너진다. 참조에 '직업'이라는 엔티티는 없다"),
    ("Subtask·SubtaskSet·SubtaskGroup·SubtaskSession·SubtaskAnswer·JangnyangSubtask*",
     "문항 수집 계층이다. 시뮬레이터를 구성하는 장치이고 시뮬레이션 대상이 아니다"),
    ("SimulationModelProvider·SubtaskError·CoordinateQuality·DataQualityFlag·AllowedRange",
     "인터페이스·오류 타입·품질 표시. 프롬프트 규칙 3이 제외하라고 한 실행 환경 쪽이다"),
    ("ScenarioResponse·TripMetric·ScenarioScale·ScenarioPreset·JangnyangScenarioSpec",
     "응답 DTO·운행 계측·규모 프리셋. 참조에 대응 노드가 없거나 관측의 하위로 흩어져 있어 "
     "1:1 대응이 성립하지 않는다"),
    ("SimulationResult의 속성 14개", "totalComplaints 등은 참조에서 관측의 <b>자식</b>(민원 통계 "
     "등)이 갖는다. SimulationResult를 관측에 대응시켜도 (엔티티,속성) 쌍이 맞지 않는다 — "
     "이름 문제가 아니라 구조 차이다"),
])

# ── 충돌 검사 ───────────────────────────────────────────────────────────
def norm(s):
    return re.sub(r"[\s·()\[\]{}.,:;/\\_-]", "", unicodedata.normalize("NFKC", str(s))).lower()


table = {**ENT, **ATTR}
bykey = collections.defaultdict(set)
for k, v in table.items():
    bykey[norm(k)].add(v)
clash = {k: v for k, v in bykey.items() if len(v) > 1}
assert not clash, f"충돌: {clash}"

# 대응 대상이 참조에 실제로 있는지
bad_e = [v for v in ENT.values() if v not in REF_ENTS]
bad_a = [v for v in ATTR.values() if v not in REF_ATTRS]
assert not bad_e, f"참조에 없는 엔티티: {bad_e}"
assert not bad_a, f"참조에 없는 속성: {bad_a}"

# ── 실제 실행에 등장하는 이름만 남긴다(쓰이지 않는 별칭은 넣지 않는다) ──────
seen = set()
for p in glob.glob(os.path.join(KIT, "runs", "gpt-4.1-mini-*.json")):
    d = json.load(io.open(p, encoding="utf-8"))
    for e, b in (d.get("entities") or {}).items():
        seen.add(e)
        for a in (b.get("attrs") or []):
            seen.add(a)
        for x in (b.get("decompositions") or []):
            seen.update(x.get("children") or [])
    # 계약 밖 최상위 키(C 조건 실행)도 훑는다. 실행마다 모양이 달라 방어적으로 읽는다 —
    # 값이 dict일 때도 list일 때도 있다.
    for key in ("aspects", "specializations", "multi-aspects"):
        blob = d.get(key) or {}
        if isinstance(blob, dict):
            for kk, vv in blob.items():
                seen.add(kk)
                if isinstance(vv, dict):
                    seen.update(vv.get("components") or vv.get("variants") or vv.get("children") or [])
                elif isinstance(vv, list):
                    seen.update(x for x in vv if isinstance(x, str))
        elif isinstance(blob, list):
            for vv in blob:
                if isinstance(vv, dict):
                    seen.update(str(x) for x in vv.values() if isinstance(x, str))
seen_n = {norm(s) for s in seen}
used = {k: v for k, v in table.items() if norm(k) in seen_n}
unused = sorted(set(table) - set(used))

out = collections.OrderedDict()
out["_comment"] = ("채점자가 만드는 사전이다. 추출기에 주지 않는다. "
                   "왼쪽(추출 결과의 이름) -> 오른쪽(참조의 이름).")
out["_built"] = ("2026-09-11 · 참조 evidence의 코드 위치와 9회 실행(C·Cs·Csr)의 이름 전수에서 "
                 "유도했다. 추측으로 넣은 것은 없다. 속성 별칭은 소유 엔티티가 맞아야 값을 "
                 "하므로 소유 관계를 확인한 것만 넣었다.")
out["_skipped"] = SKIPPED
# 기존 수기 항목(초기 키트 제공분)을 보존한다
prev = json.load(io.open(os.path.join(KIT, "aliases.json"), encoding="utf-8"))
for k, v in prev.items():
    if not k.startswith("_"):
        out.setdefault(k, v)
for k, v in sorted(used.items()):
    out.setdefault(k, v)
io.open(os.path.join(KIT, "aliases.json"), "w", encoding="utf-8").write(
    json.dumps(out, ensure_ascii=False, indent=2) + "\n")

print(f"등록 {len([k for k in out if not k.startswith('_')])}건 "
      f"(엔티티계 {len([k for k in used if k in ENT])} · 속성계 {len([k for k in used if k in ATTR])})")
print(f"실행에 안 나온 별칭은 제외: {unused}")
print(f"\n제외 사유 {len(SKIPPED)}건 기록")
