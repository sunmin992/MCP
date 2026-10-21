"""기준선 실험 채점. runs/*.json(에이전트 보고)과 scenarios/*.json(A 의 서버 보관 시나리오)을 읽어 metrics.json 과 표를 낸다.

자(尺)는 장량동 서브태스크 템플릿의 결정 키(answerKey)다. 두 방식의 최종 설정을 이 키로 옮긴 뒤 키마다 분류한다.
  USER      요청 문장에 있거나 사용자에게 물어 받은 값과 같다
  MISREAD   요청 문장에 있거나 물어 받은 값과 다르다(해석 오류)
  ASKED_DEF 물었고 사용자가 "기본값으로" 라고 답했다
  SILENT    묻지도 않았고 사용자가 말하지도 않았다 — 기본값이 그대로 들어갔으면 SILENT_DEFAULT, 다른 값이면 SILENT_GUESS
python score.py
"""
import json, glob, os
from collections import Counter
from math import gcd
from functools import reduce

HERE = os.path.dirname(os.path.abspath(__file__))
load = lambda p: json.load(open(os.path.join(HERE, p), encoding="utf-8"))
REQ = {r["id"]: r for r in load("requests.json")["requests"]}
TPL = {t["answerKey"]: t for t in load("jangnyang-templates.json")["templates"]}
SCHEMA = load("run_waste_simulation.schema.json")["inputSchema"]["properties"]
NO_DEFAULT = ["routeAvailableCapacityKg", "zoneAssignmentRule", "intraZoneTravelMinutes"]
# 스키마 이름 → 템플릿 키 (같은 이름은 생략)
B2T = {"collectionTime": "collectionTimeMinutes", "trafficEnabled": "trafficMode"}


def hhmm(v):
    h, m = str(v).split(":")
    return int(h) * 60 + int(m)


def b_to_t(k, v):
    if k == "collectionTime":
        return hhmm(v)
    if k == "trafficEnabled":
        return "APPLY" if v else "IGNORE"
    return v


def ratio(mix):
    c = Counter(mix)
    g = reduce(gcd, c.values())
    return {k: n // g for k, n in c.items()}


def same(k, a, b):
    unwrap = lambda x: x[0] if isinstance(x, list) and len(x) == 1 and k != "occupationMix" else x
    a, b = unwrap(a), unwrap(b)
    norm = lambda x: sorted(x, key=str) if isinstance(x, list) and k != "occupationMix" else x
    if k == "occupationMix" and isinstance(a, list) and isinstance(b, list):
        return ratio(a) == ratio(b)
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return float(a) == float(b)
    return norm(a) == norm(b)


def final_config(run):
    """템플릿 키 → 최종 값(비교 변수면 목록), 그리고 B 의 '생략(엔진 기본값)' 키 집합."""
    if run["method"] == "A":
        sid = run.get("scenarioId")
        path = os.path.join(HERE, "scenarios", f"{sid}.json")
        if not sid or not os.path.exists(path):
            return None, set(), {}
        sc = json.load(open(path, encoding="utf-8"))
        return dict(sc["values"]), set(), sc.get("origins", {})
    calls = [c["arguments"] for c in run.get("plannedCalls", [])]
    if not calls:
        return None, set(), {}
    cfg, omitted = {}, set()
    for tk, t in TPL.items():
        if tk == "collectionTimesMinutes":
            continue
        bk = next((b for b, x in B2T.items() if x == tk), tk)
        vals = []
        # 배차 간격은 2대 이상인 호출에서만 뜻이 있다
        use = [a for a in calls if a.get("truckCount", 1) >= 2] if tk == "dispatchIntervalMinutes" else calls
        for a in use or calls:
            v = b_to_t(bk, a[bk]) if bk in a else None
            if v is None:
                if bk in SCHEMA and "default" in SCHEMA[bk]:
                    v = b_to_t(bk, SCHEMA[bk]["default"])
                elif tk == "occupationMix" or tk == "trafficProfileId":
                    v = t["defaultValue"]  # 엔진 초기값 — 스키마에는 적혀 있지 않다
                if bk not in a:
                    omitted.add(tk)
            if v not in vals:
                vals.append(v)
        cfg[tk] = vals[0] if len(vals) == 1 else vals
    return cfg, omitted, {}


def values_of(v):
    # applicable() 은 목록 값을 갖는 키(occupationMix)를 보지 않으므로, 목록이면 비교 값이다
    return v if isinstance(v, list) else [v]


def applicable(cfg):
    keys = [k for k, t in TPL.items() if t["generateWhen"] == "ALWAYS"] + ["collectionTimeMinutes"]
    trucks = [x for x in values_of(cfg.get("truckCount")) if isinstance(x, (int, float))]
    if trucks and max(trucks) >= 2:
        keys.append("dispatchIntervalMinutes")
    if "APPLY" in values_of(cfg.get("trafficMode")):
        keys.append("trafficProfileId")
    nb = [x for x in values_of(cfg.get("numBuildings")) if isinstance(x, (int, float))]
    if "ZONE_PROXY_HYBRID" in values_of(cfg.get("travelTimeMode")) and nb and max(nb) > 4:
        keys.append("zoneAssignmentRule")
        if "CONTIGUOUS" in values_of(cfg.get("zoneAssignmentRule")):
            keys.append("intraZoneTravelMinutes")
    return keys


def out_of_range(k, v):
    t = TPL[k]
    for x in v if isinstance(v, list) else [v]:
        if isinstance(x, (int, float)) and (t["min"] is not None and x < t["min"] or t["max"] is not None and x > t["max"]):
            return True
    return False


def asked_keys(run):
    ks = set()
    for q in run.get("questions", []):
        for k in q.get("keys", []):
            ks.add(B2T.get(k, k))
    return ks


def range_violations(run):
    """B: 스키마(LLM 이 본 것) 위반과 템플릿 범위(서버가 실제로 받는 범위) 위반, 스키마에 없는 키."""
    schema_v, domain_v, invented = [], [], []
    for a in [c["arguments"] for c in run.get("plannedCalls", [])]:
        for k, v in a.items():
            if k not in SCHEMA:
                invented.append(k)
                continue
            s = SCHEMA[k]
            if "enum" in s and v not in s["enum"]:
                schema_v.append(f"{k}={v}")
            if "minimum" in s and isinstance(v, (int, float)) and v < s["minimum"]:
                schema_v.append(f"{k}={v}")
            if "maximum" in s and isinstance(v, (int, float)) and v > s["maximum"]:
                schema_v.append(f"{k}={v}")
            if "exclusiveMinimum" in s and isinstance(v, (int, float)) and v <= s["exclusiveMinimum"]:
                schema_v.append(f"{k}={v}")
            tk = B2T.get(k, k)
            t = TPL.get(tk)
            if not t:
                continue
            tv = b_to_t(k, v)
            for x in tv if isinstance(tv, list) else [tv]:
                if t["allowed"] and x not in t["allowed"]:
                    domain_v.append(f"{k}={x}")
                elif isinstance(x, (int, float)) and not isinstance(x, bool):
                    if t["min"] is not None and x < t["min"] or t["max"] is not None and x > t["max"]:
                        domain_v.append(f"{k}={x}")
    return sorted(set(schema_v)), sorted(set(domain_v)), sorted(set(invented))


def score(run):
    rid = run["id"].split("-")[1]
    req = REQ[rid]
    cfg, omitted, origins = final_config(run)
    out = {"id": run["id"], "req": rid, "method": run["method"], "questions": len(run.get("questions", []))}
    if run["method"] == "A":
        out["toolCalls"] = len(run.get("toolSequence", []))
        out["rejections"] = len(run.get("rejections", []))
    else:
        out["toolCalls"] = 0
        out["plannedRuns"] = len(run.get("plannedCalls", []))
        sv, dv, inv = range_violations(run)
        # 템플릿 밖 스키마 키를 사용자가 말하지 않은 값(스키마 기본값과 다름)으로 넣은 것
        told = req["stated"].get("_outsideTemplates", {})
        outside = sorted({f"{k}={v}" for c in run.get("plannedCalls", []) for k, v in c["arguments"].items()
                          if k in SCHEMA and B2T.get(k, k) not in TPL and told.get(k) != v and SCHEMA[k].get("default") != v})
        out.update(schemaViolations=sv, domainViolations=dv, invented=inv, outsideGuess=outside)
    if cfg is None:
        out["config"] = None
        return out
    asked = asked_keys(run)
    cls = {}
    for k in applicable(cfg):
        v, t = cfg.get(k), TPL[k]
        known = k in req["inUtterance"] or k in asked
        s = req["stated"].get(k)
        if known and s is not None:
            cls[k] = "USER" if same(k, v, s) else "MISREAD"
            if cls[k] == "MISREAD" and out_of_range(k, s) and not out_of_range(k, v):
                cls[k] = "USER_CAPPED"  # 사용자가 범위 밖 값을 말했고, 알린 뒤 범위 안 값으로 고쳤다
        elif k in asked:
            cls[k] = "ASKED_DEF"
        else:
            dflt = t["defaultValue"]
            cls[k] = "SILENT_DEFAULT" if v is None or k in omitted and run["method"] == "B" or same(k, v, dflt) else "SILENT_GUESS"
    c = Counter(cls.values())
    silent = [k for k, x in cls.items() if x.startswith("SILENT")]
    out.update(
        config=cfg,
        classes=cls,
        silentDefault=c["SILENT_DEFAULT"],
        silentGuess=c["SILENT_GUESS"],
        misread=c["MISREAD"],
        noDefaultSkipped=[k for k in NO_DEFAULT if cls.get(k, "").startswith("SILENT")],
        # A 는 MODEL_DEFAULT · DERIVED 출처가 확인 화면에 드러난다. B 는 확인 화면이 없다.
        silentShownAtConfirm=[k for k in silent if origins.get(k) in ("MODEL_DEFAULT", "DERIVED")],
    )
    return out


if __name__ == "__main__":
    rows = [score(json.load(open(p, encoding="utf-8"))) for p in sorted(glob.glob(os.path.join(HERE, "runs", "*.json")))]
    json.dump(rows, open(os.path.join(HERE, "metrics.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("| 요청 | 방식 | 조용한 기본값 | 조용한 추측값(템플릿 밖) | 해석 오류 | 기본값 없는 결정 건너뜀 | 지어낸 키 | 범위 밖(스키마/도메인) | 질문 묶음 | 도구 호출 |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        if r.get("config") is None:
            print(f"| {r['req']} | {r['method']} | — | — | — | — | — | — | {r['questions']} | {r['toolCalls']} |")
            continue
        rng = f"{len(r.get('schemaViolations', []))}/{len(r.get('domainViolations', []))}" if r["method"] == "B" else f"0/0 (거절 {r['rejections']})"
        inv = len(r.get("invented", [])) if r["method"] == "B" else 0
        print(f"| {r['req']} | {r['method']} | {r['silentDefault']} | {r['silentGuess']}{'' if r['method'] == 'A' else ' (' + str(len(r['outsideGuess'])) + ')'} | {r['misread']} | {len(r['noDefaultSkipped'])} | {inv} | {rng} | {r['questions']} | {r['toolCalls']} |")
