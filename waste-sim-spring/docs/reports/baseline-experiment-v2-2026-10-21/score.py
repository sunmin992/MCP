"""기준선 확장 실험 채점 — 2026-10-20 score.py 의 분류 규칙을 세 서버로 넓힌 것.

자(尺)는 요청이 가는 서버의 서브태스크 템플릿 결정 키(answerKey)다. 두 방식의 최종 설정을 이 키로 옮긴 뒤,
최종 설정에서 생성 조건이 서는 결정마다 분류한다.
  USER         요청 문장에 있거나 사용자에게 물어 받은 값과 같다
  USER_CAPPED  사용자가 범위 밖 값을 말했고, 알린 뒤 범위 안 값으로 고쳤다
  MISREAD      요청 문장에 있거나 물어 받은 값과 다르다(해석 오류)
  ASKED_DEF    물었고 사용자가 "기본값으로" 라고 답했다
  SILENT_*     묻지도 않았고 사용자가 말하지도 않았다 — 기본값이면 SILENT_DEFAULT, 다른 값이면 SILENT_GUESS
A 의 최종 설정은 서버가 보관한 시나리오(scenarios/<id>.json), B 는 보고의 plannedCalls 다.
python score.py   → metrics.json
"""
import json, glob, os
from collections import Counter
from math import gcd
from functools import reduce

HERE = os.path.dirname(os.path.abspath(__file__))
load = lambda p: json.load(open(os.path.join(HERE, p), encoding="utf-8"))
REQS = load("requests.json")
REQ = {r["id"]: r for r in REQS["requests"]}
PORT = {k: v["port"] for k, v in REQS["servers"].items()}
TPL = {s: {t["answerKey"]: t for t in load(f"schemas/templates-{p}.json")["templates"] if t["answerKey"] != "collectionTimesMinutes"}
       for s, p in PORT.items()}
SCHEMA = {s: load(f"schemas/schema-{p}.json")["inputSchema"]["properties"] for s, p in PORT.items()}
# 스키마 이름 → 템플릿 키 (같은 이름은 생략). 참/거짓 필드는 APPLY/IGNORE 로 옮긴다.
B2T = {"J": {"collectionTime": "collectionTimeMinutes", "trafficEnabled": "trafficMode"},
       "D": {"holidayEnabled": "holidayMode", "transferEnabled": "transferMode"},
       "P": {"congestionEnabled": "congestionMode"}}
NO_DEFAULT = {s: [k for k, t in tpl.items() if t["defaultValue"] is None] for s, tpl in TPL.items()}


def is_list_key(s, k):
    return TPL[s][k]["valueType"].endswith("_LIST")


def vals(s, k, v):
    """한 결정의 값들 — 목록이면 비교 값. 목록형 결정(구성 · 요일)은 목록의 목록일 때만 비교 값이다."""
    if v is None:
        return []
    if is_list_key(s, k):
        return v if v and isinstance(v[0], list) else [v]
    return v if isinstance(v, list) else [v]


def active(s, cond, cfg):
    """생성 조건 — 각 서버의 GenerateCondition 을 옮겼다. 비교 값 가운데 하나라도 조건을 세우면 참."""
    V = lambda k: vals(s, k, cfg.get(k))
    nums = lambda k: [x for x in V(k) if isinstance(x, (int, float)) and not isinstance(x, bool)]
    has = lambda k, x: any(str(e).upper() == x.upper() for v in V(k) for e in (v if isinstance(v, list) else [v]))
    if cond in ("ALWAYS", "SINGLE_COLLECTION_TIME"):
        return True
    if cond == "EFFECTIVE_TRUCKS_AT_LEAST_2":
        if s == "D":
            return any(min(z, t) >= 2 for z in nums("zoneCount") for t in nums("truckCount"))
        return any(t >= 2 for t in nums("truckCount"))
    table = {
        "TRAFFIC_APPLIED": lambda: has("trafficMode", "APPLY"),
        "ZONE_PROXY_OVER_4_BUILDINGS": lambda: has("travelTimeMode", "ZONE_PROXY_HYBRID") and any(n > 4 for n in nums("numBuildings")),
        "CONTIGUOUS_ZONE_RULE": lambda: has("zoneAssignmentRule", "CONTIGUOUS"),
        "COMMERCIAL_IN_MIX": lambda: has("housingMix", "Commercial"),
        "WEEKDAYS_SCHEDULE": lambda: has("scheduleMode", "WEEKDAYS"),
        "HOLIDAY_POLICY_NEEDED": lambda: has("holidayMode", "APPLY") and any(str(m).upper() != "DAILY" for m in V("scheduleMode")),
        "TRANSFER_APPLIED": lambda: has("transferMode", "APPLY"),
        "NOT_FREE": lambda: any(str(m).upper() != "FREE" for m in V("pricingPolicy")),
        "TIME_OF_DAY_PRICING": lambda: has("pricingPolicy", "TIME_OF_DAY"),
        "RESIDENT_IN_MIX": lambda: has("userMix", "Resident"),
        "MULTIPLE_LOTS": lambda: any(n >= 2 for n in nums("lotCount")),
        "REALTIME_GUIDANCE": lambda: any(n >= 2 for n in nums("lotCount")) and has("guidanceMode", "REALTIME_APP"),
        "CONGESTION_APPLIED": lambda: has("congestionMode", "APPLY"),
    }
    return table[cond]()


def hhmm(v):
    h, m = str(v).split(":")
    return int(h) * 60 + int(m)


def b_to_t(k, v):
    if k == "collectionTime":
        return hhmm(v)
    if k in ("trafficEnabled", "holidayEnabled", "transferEnabled", "congestionEnabled"):
        return "APPLY" if v else "IGNORE"
    return v


def ratio(mix):
    c = Counter(mix)
    g = reduce(gcd, c.values())
    return {k: n // g for k, n in c.items()}


def same(s, k, a, b):
    if a is None or b is None:
        return a is b
    if is_list_key(s, k):
        A, B = vals(s, k, a), vals(s, k, b)
        if len(A) != len(B):
            return False
        norm = (lambda x: sorted(x)) if k == "collectionDays" else ratio
        return sorted(map(lambda x: json.dumps(norm(x), sort_keys=True), A)) == sorted(map(lambda x: json.dumps(norm(x), sort_keys=True), B))
    A, B = vals(s, k, a), vals(s, k, b)
    f = lambda x: float(x) if isinstance(x, (int, float)) and not isinstance(x, bool) else str(x)
    return sorted(map(f, A), key=str) == sorted(map(f, B), key=str)


def out_of_range(s, k, v):
    t = TPL[s][k]
    for x in vals(s, k, v):
        for e in (x if isinstance(x, list) else [x]):
            if t["allowed"] and e not in t["allowed"]:
                return True
            if isinstance(e, (int, float)) and not isinstance(e, bool) and (
                    t["min"] is not None and e < t["min"] or t["max"] is not None and e > t["max"]):
                return True
    return False


def b_call_cfg(s, args):
    """B 호출 하나를 템플릿 키로. 뺀 인자는 스키마 초기값, 없으면 템플릿 기본값(엔진 초기값)."""
    cfg, omitted = {}, set()
    inv = {t: b for b, t in B2T[s].items()}
    for tk, t in TPL[s].items():
        bk = inv.get(tk, tk)
        if bk in args:
            cfg[tk] = b_to_t(bk, args[bk])
        else:
            omitted.add(tk)
            d = SCHEMA[s].get(bk, {}).get("default")
            cfg[tk] = b_to_t(bk, d) if d is not None else t["defaultValue"]
    return cfg, omitted


def final_config(s, run):
    if run["method"] == "A":
        sid = run.get("scenarioId")
        path = os.path.join(HERE, "scenarios", f"{sid}.json")
        if not sid or not os.path.exists(path):
            return None, set(), {}
        sc = json.load(open(path, encoding="utf-8"))
        # 비교 변수는 values 에 없을 수 있다 — 엔진에 갈 실행 설정(runs)을 템플릿 configField 로 거꾸로 읽는다
        calls = [({tk: (("APPLY" if r.get(t["configField"]) else "IGNORE") if t["configField"].endswith("Enabled")
                        else r.get(t["configField"])) for tk, t in TPL[s].items()}, set()) for r in sc.get("runs", [])]
        return merge(s, calls)[0], set(), sc.get("origins", {})
    calls = [b_call_cfg(s, c.get("arguments", {})) for c in run.get("plannedCalls", [])]
    if not calls:
        return None, set(), {}
    cfg, omitted = merge(s, calls)
    return cfg, omitted, {}


def merge(s, calls):
    """실행 설정 여러 벌 → 결정마다 값(같으면 하나, 다르면 비교 값 목록)."""
    cfg, omitted = {}, set()
    for tk, t in TPL[s].items():
        # 조건부 결정은 그 조건이 서는 호출에서만 본다(1대 호출의 배차 간격처럼 뜻 없는 값을 섞지 않는다)
        use = [c for c in calls if active(s, t["generateWhen"], c[0])] or calls
        vs = []
        for c, om in use:
            if tk in om:
                omitted.add(tk)
            if c[tk] not in vs:
                vs.append(c[tk])
        cfg[tk] = vs[0] if len(vs) == 1 else vs
    return cfg, omitted


def asked_keys(s, run):
    return {B2T[s].get(k, k) for q in run.get("questions", []) for k in q.get("keys", [])}


def b_violations(s, run):
    schema_v, domain_v, invented, outside = set(), set(), set(), set()
    told = REQ[run_req(run)]["stated"].get("_outsideTemplates", {})
    for c in run.get("plannedCalls", []):
        for k, v in c.get("arguments", {}).items():
            sch = SCHEMA[s].get(k)
            if sch is None:
                invented.add(k)
                continue
            items = v if isinstance(v, list) else [v]
            enum = sch.get("enum") or sch.get("items", {}).get("enum")
            for e in items:
                if enum and e not in enum:
                    schema_v.add(f"{k}={e}")
                if isinstance(e, (int, float)) and not isinstance(e, bool):
                    if "minimum" in sch and e < sch["minimum"] or "maximum" in sch and e > sch["maximum"] \
                            or "exclusiveMinimum" in sch and e <= sch["exclusiveMinimum"]:
                        schema_v.add(f"{k}={e}")
            tk = B2T[s].get(k, k)
            if tk in TPL[s]:
                if out_of_range(s, tk, b_to_t(k, v)):
                    domain_v.add(f"{k}={v}")
            elif told.get(k) != v and sch.get("default") != v:
                outside.add(f"{k}={v}")
    return sorted(schema_v), sorted(domain_v), sorted(invented), sorted(outside)


def run_req(run):
    return run["id"].split("-")[1]


def score(run):
    rid = run_req(run)
    req = REQ[rid]
    s = req["server"]
    rep = run["id"].rsplit("-", 1)[1]
    out = {"id": run["id"], "req": rid, "server": s, "method": run["method"], "rep": rep,
           "questions": len(run.get("questions", [])), "serverCalled": run.get("serverCalled"),
           "state": run.get("scenarioState") if run["method"] == "A" else "PLANNED"}
    if run["method"] == "A":
        out["rejections"] = len(run.get("rejections", []))
        out["wrongServer"] = run.get("serverCalled") not in (REQS["servers"][s]["serverId"], None)
    else:
        sv, dv, inv, outside = b_violations(s, run)
        out.update(plannedRuns=len(run.get("plannedCalls", [])), schemaViolations=sv, domainViolations=dv,
                   invented=inv, outsideGuess=outside)
    cfg, omitted, origins = final_config(s, run)
    if cfg is None or run["method"] == "A" and out["wrongServer"]:
        out["config"] = None
        return out
    asked = asked_keys(s, run)
    cls = {}
    for k, t in TPL[s].items():
        if not active(s, t["generateWhen"], cfg):
            continue
        v = cfg.get(k)
        st = req["stated"].get(k)
        if (k in req["inUtterance"] or k in asked) and st is not None:
            cls[k] = "USER" if same(s, k, v, st) else "MISREAD"
            if cls[k] == "MISREAD" and out_of_range(s, k, st) and not out_of_range(s, k, v):
                cls[k] = "USER_CAPPED"
        elif k in asked:
            cls[k] = "ASKED_DEF"
        else:
            d = t["defaultValue"]
            cls[k] = "SILENT_DEFAULT" if v is None or (run["method"] == "B" and k in omitted) or same(s, k, v, d) else "SILENT_GUESS"
    c = Counter(cls.values())
    silent = [k for k, x in cls.items() if x.startswith("SILENT")]
    out.update(
        config=cfg, classes=cls,
        silentDefault=c["SILENT_DEFAULT"], silentGuess=c["SILENT_GUESS"], misread=c["MISREAD"],
        askedDefault=c["ASKED_DEF"], userCapped=c["USER_CAPPED"],
        noDefaultApplicable=[k for k in NO_DEFAULT[s] if k in cls],
        noDefaultSkipped=[k for k in NO_DEFAULT[s] if cls.get(k, "").startswith("SILENT")],
        finalOutOfRange=[k for k in cls if out_of_range(s, k, cfg.get(k))],
        silentShownAtConfirm=[k for k in silent if origins.get(k) in ("MODEL_DEFAULT", "DERIVED")],
    )
    return out


if __name__ == "__main__":
    rows = [score(json.load(open(p, encoding="utf-8"))) for p in sorted(glob.glob(os.path.join(HERE, "runs", "*.json")))]
    json.dump(rows, open(os.path.join(HERE, "metrics.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(len(rows), "runs scored")
