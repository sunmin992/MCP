#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
score_ses.py — S1 추출 결과 채점

사용:
  python3 score_ses.py --ref reference-ses.json --runs runs/*.json [--aliases aliases.json]
  python3 score_ses.py --selftest

내는 것:
  1) 참조 대비 노드/관계/속성 P·R·F1
  2) 관계 종류 오류 (부모-자식은 맞고 kind만 다른 것) — 따로 센다
  3) 임계점 CP-1~5 통과 여부
  4) 근거(evidence) 없는 노드 수
  5) 반복 간 구조 일치율 (실행끼리 쌍별 Jaccard 평균) — 정답지와 무관한 재현성 지표
  6) 미매칭 이름 목록 (별칭 사전에 추가할 후보)
"""
from __future__ import annotations
import argparse, glob, json, re, sys, itertools, unicodedata
from pathlib import Path

# ---------------------------------------------------------------- 이름 정규화
_STRIP = re.compile(r"[\s·\.\-_/()\[\]{}]+")

def norm(name: str, aliases: dict[str, str]) -> str:
    s = unicodedata.normalize("NFKC", str(name)).strip()
    s = _STRIP.sub("", s).lower()
    return aliases.get(s, s)

def build_aliases(raw: dict) -> dict[str, str]:
    """{'쓰레기통': '수거지점'} -> {정규화된키: 정규화된값}"""
    out = {}
    for k, v in (raw or {}).items():
        # _ 로 시작하는 키는 메타(설명·기록)다. 별칭으로 취급하면 안 된다 —
        # 원래도 _comment 가 조용히 별칭 항목이 되고 있었고, 값이 문자열이 아니면 죽는다.
        if str(k).startswith("_"):
            continue
        nk = _STRIP.sub("", unicodedata.normalize("NFKC", k)).lower()
        nv = _STRIP.sub("", unicodedata.normalize("NFKC", v)).lower()
        out[nk] = nv
    return out

# ---------------------------------------------------------------- 추출
def nodes_of(ses: dict, al: dict) -> set[str]:
    return {norm(n, al) for n in ses.get("entities", {})}

def relations_of(ses: dict, al: dict) -> set[tuple[str, str, str]]:
    """(부모, kind, 자식)"""
    out = set()
    for parent, body in ses.get("entities", {}).items():
        for d in body.get("decompositions", []) or []:
            kind = str(d.get("kind", "")).lower()
            for c in d.get("children", []) or []:
                out.add((norm(parent, al), kind, norm(c, al)))
    return out

def edges_of(ses: dict, al: dict) -> set[tuple[str, str]]:
    """(부모, 자식) — kind 무시"""
    return {(p, c) for p, _, c in relations_of(ses, al)}

def attrs_of(ses: dict, al: dict) -> set[tuple[str, str]]:
    out = set()
    for e, body in ses.get("entities", {}).items():
        for a in body.get("attrs", []) or []:
            out.add((norm(e, al), norm(a, al)))
    return out

def kind_map(ses: dict, al: dict) -> dict[tuple[str, str], str]:
    return {(p, c): k for p, k, c in relations_of(ses, al)}

# ---------------------------------------------------------------- 지표
def prf(pred: set, gold: set) -> dict:
    tp = len(pred & gold)
    p = tp / len(pred) if pred else 0.0
    r = tp / len(gold) if gold else 0.0
    f = 2 * p * r / (p + r) if (p + r) else 0.0
    return {"tp": tp, "pred": len(pred), "gold": len(gold),
            "P": round(p, 3), "R": round(r, 3), "F1": round(f, 3)}

def jaccard(a: set, b: set) -> float:
    return len(a & b) / len(a | b) if (a or b) else 1.0

# ---------------------------------------------------------------- 임계점
def check_critical(ses: dict, ref: dict, al: dict) -> list[dict]:
    """참조의 critical_points를 추출 결과에 대해 확인한다."""
    km = kind_map(ses, al)
    ents = ses.get("entities", {})
    nents = {norm(k, al): v for k, v in ents.items()}
    res = []

    def kinds_under(parent: str) -> set[str]:
        p = norm(parent, al)
        return {k for (pp, _c), k in km.items() if pp == p}

    def spec_axis_count(parent: str) -> int:
        p = norm(parent, al)
        for name, body in ents.items():
            if norm(name, al) == p:
                return sum(1 for d in (body.get("decompositions") or [])
                           if str(d.get("kind", "")).lower() == "spec")
        return 0

    for cp in ref.get("critical_points", []):
        cid, where = cp["id"], cp["where"]
        ok, detail = None, ""

        if cid == "CP-1":          # 민원 판정이 aspect인가
            ks = kinds_under(where)
            ok = ("aspect" in ks) and ("spec" not in ks)
            detail = f"kinds={sorted(ks) or '없음'}"
        elif cid == "CP-2":        # 민원임계값이 수거지점의 속성인가
            ent, attr = where.split(".", 1)
            ok = (norm(ent, al), norm(attr, al)) in attrs_of(ses, al)
            detail = "속성으로 존재" if ok else "해당 엔티티의 속성이 아님"
        elif cid == "CP-3":        # 폐기물 유형이 독립 multi 개체인가
            ok = "multi" in kinds_under(where)
            detail = f"kinds={sorted(kinds_under(where)) or '없음'}"
        elif cid == "CP-4":        # 이동시간 방식이 수거 경로 아래 spec인가
            ok = "spec" in kinds_under("수거 경로")
            detail = f"수거 경로 아래 kinds={sorted(kinds_under('수거 경로')) or '없음'}"
        elif cid == "CP-5":        # 거주민의 spec 축이 2개인가
            n = spec_axis_count("거주민")
            ok = n >= 2
            detail = f"spec 축 {n}개"

        res.append({"id": cid, "where": where, "pass": bool(ok), "detail": detail})
    return res

# ---------------------------------------------------------------- 채점 본체
def score_one(run: dict, ref: dict, al: dict) -> dict:
    rn, gn = nodes_of(run, al), nodes_of(ref, al)
    rr, gr = relations_of(run, al), relations_of(ref, al)
    ra, ga = attrs_of(run, al), attrs_of(ref, al)

    # 관계 종류 오류: 엣지는 맞는데 kind만 다른 것
    rk, gk = kind_map(run, al), kind_map(ref, al)
    shared = set(rk) & set(gk)
    kind_err = [{"edge": list(e), "got": rk[e], "expected": gk[e]}
                for e in sorted(shared) if rk[e] != gk[e]]

    ev = run.get("evidence", {}) or {}
    ev_keys = {norm(k.split(".")[0], al) for k in ev}
    no_ev = sorted(n for n in rn if n not in ev_keys)

    return {
        "nodes": prf(rn, gn),
        "relations": prf(rr, gr),
        "edges_kind_ignored": prf(edges_of(run, al), edges_of(ref, al)),
        "attrs": prf(ra, ga),
        "kind_errors": kind_err,
        "critical": check_critical(run, ref, al),
        "nodes_without_evidence": len(no_ev),
        "_unmatched_nodes": sorted(rn - gn),
        "_missed_nodes": sorted(gn - rn),
    }

def agreement(runs: list[dict], al: dict) -> dict:
    if len(runs) < 2:
        return {"pairs": 0}
    nj, rj = [], []
    for a, b in itertools.combinations(runs, 2):
        nj.append(jaccard(nodes_of(a, al), nodes_of(b, al)))
        rj.append(jaccard(relations_of(a, al), relations_of(b, al)))
    return {"pairs": len(nj),
            "nodes_jaccard_mean": round(sum(nj) / len(nj), 3),
            "relations_jaccard_mean": round(sum(rj) / len(rj), 3)}

# ---------------------------------------------------------------- 출력
def report(ref_path, run_paths, alias_path=None):
    ref = json.loads(Path(ref_path).read_text(encoding="utf-8"))
    al = build_aliases(json.loads(Path(alias_path).read_text(encoding="utf-8"))
                       if alias_path and Path(alias_path).exists() else {})
    runs = [json.loads(Path(p).read_text(encoding="utf-8")) for p in run_paths]

    print("=" * 74)
    print(f" S1 채점  |  참조 {Path(ref_path).name}  |  실행 {len(runs)}건")
    print("=" * 74)

    unmatched_all, missed_all = set(), set()
    for p, run in zip(run_paths, runs):
        s = score_one(run, ref, al)
        print(f"\n── {Path(p).name}")
        for key in ("nodes", "relations", "edges_kind_ignored", "attrs"):
            m = s[key]
            print(f"   {key:<20} P {m['P']:.3f}  R {m['R']:.3f}  F1 {m['F1']:.3f}"
                  f"   ({m['tp']}/{m['pred']} 예측, 정답 {m['gold']})")
        print(f"   관계 종류 오류        {len(s['kind_errors'])}건"
              + ("".join(f"\n        {e['edge'][0]} → {e['edge'][1]} : {e['got']} (정답 {e['expected']})"
                         for e in s["kind_errors"][:5]) if s["kind_errors"] else ""))
        print(f"   근거 없는 노드        {s['nodes_without_evidence']}개")
        cps = s["critical"]
        line = "  ".join(("O" if c["pass"] else "X") + c["id"] for c in cps)
        print(f"   임계점                {line}")
        for c in cps:
            if not c["pass"]:
                print(f"        X {c['id']} {c['where']} — {c['detail']}")
        unmatched_all |= set(s["_unmatched_nodes"])
        missed_all |= set(s["_missed_nodes"])

    ag = agreement(runs, al)
    print("\n" + "-" * 74)
    print(" 반복 간 구조 일치율 (정답지 무관)")
    if ag["pairs"]:
        print(f"   쌍 {ag['pairs']}개   노드 Jaccard {ag['nodes_jaccard_mean']:.3f}"
              f"   관계 Jaccard {ag['relations_jaccard_mean']:.3f}")
    else:
        print("   실행이 1건이라 계산하지 않음")

    if unmatched_all or missed_all:
        print("\n" + "-" * 74)
        print(" 이름 미매칭 — 별칭 사전 후보")
        if unmatched_all:
            print("   추출에만 있음(과잉 또는 이름 다름):")
            for n in sorted(unmatched_all): print(f"      + {n}")
        if missed_all:
            print("   참조에만 있음(누락 또는 이름 다름):")
            for n in sorted(missed_all): print(f"      - {n}")
        print("\n   같은 것을 다르게 부른 쌍은 aliases.json에 등록하고 다시 돌리세요.")

# ---------------------------------------------------------------- 자체 검증
def selftest():
    ref = json.loads((Path(__file__).parent / "reference-ses.json").read_text(encoding="utf-8"))
    al = build_aliases({})

    # (1) 자기 자신 채점 -> 전부 1.0, 임계점 전부 통과
    s = score_one(ref, ref, al)
    assert s["nodes"]["F1"] == 1.0, s["nodes"]
    assert s["relations"]["F1"] == 1.0, s["relations"]
    assert s["attrs"]["F1"] == 1.0, s["attrs"]
    assert not s["kind_errors"]
    assert all(c["pass"] for c in s["critical"]), s["critical"]
    print("  [1] 자기 채점 F1=1.0, 임계점 5/5 통과 ......... OK")

    # (2) 민원 판정을 aspect -> spec 으로 바꾸면 CP-1 실패 + 자식 수만큼 종류 오류
    #     개수를 상수로 박지 않는다 — 참조를 코드와 대조해 고치면(교통혼잡 판정 제거로
    #     자식이 3→2가 됐다) 상수가 틀려 스크립트 자체가 죽는다.
    bad = json.loads(json.dumps(ref))
    bad["entities"]["민원 판정"]["decompositions"][0]["kind"] = "spec"
    n_children = len(ref["entities"]["민원 판정"]["decompositions"][0]["children"])
    s2 = score_one(bad, ref, al)
    cp1 = [c for c in s2["critical"] if c["id"] == "CP-1"][0]
    assert cp1["pass"] is False, cp1
    assert len(s2["kind_errors"]) == n_children, (s2["kind_errors"], n_children)
    assert s2["edges_kind_ignored"]["F1"] == 1.0   # 엣지는 그대로
    print(f"  [2] Aspect→Spec 오기 시 CP-1 실패·종류오류 {n_children}건 .. OK")

    # (3) 거주민의 두 spec 축을 하나로 합치면 CP-5 실패
    bad2 = json.loads(json.dumps(ref))
    d = bad2["entities"]["거주민"]["decompositions"]
    merged = {"kind": "spec", "name": "합친 축", "children": d[0]["children"] + d[1]["children"]}
    bad2["entities"]["거주민"]["decompositions"] = [merged]
    s3 = score_one(bad2, ref, al)
    cp5 = [c for c in s3["critical"] if c["id"] == "CP-5"][0]
    assert cp5["pass"] is False, cp5
    print("  [3] 거주민 축 병합 시 CP-5 실패 ............... OK")

    # (4) 노드 하나 빼면 재현율만 떨어진다
    bad3 = json.loads(json.dumps(ref))
    del bad3["entities"]["폐기물 유형"]
    s4 = score_one(bad3, ref, al)
    assert s4["nodes"]["P"] == 1.0 and s4["nodes"]["R"] < 1.0, s4["nodes"]
    print("  [4] 노드 누락 시 P=1.0, R<1.0 ................ OK")

    # (5) 별칭이 이름 차이를 흡수한다
    ren = json.loads(json.dumps(ref))
    ren["entities"]["쓰레기통"] = ren["entities"].pop("수거지점")
    for body in ren["entities"].values():
        for dd in body.get("decompositions", []):
            dd["children"] = ["쓰레기통" if c == "수거지점" else c for c in dd["children"]]
    al2 = build_aliases({"쓰레기통": "수거지점"})
    s5 = score_one(ren, ref, al2)
    assert s5["nodes"]["F1"] == 1.0, s5["nodes"]
    s5n = score_one(ren, ref, al)
    assert s5n["nodes"]["F1"] < 1.0
    print("  [5] 별칭 사전이 이름 차이를 흡수 .............. OK")

    # (6) 반복 간 일치율: 같은 것 둘이면 1.0
    assert agreement([ref, ref], al)["nodes_jaccard_mean"] == 1.0
    assert agreement([ref, bad3], al)["nodes_jaccard_mean"] < 1.0
    print("  [6] 반복 간 일치율 계산 ...................... OK")

    print("\n  자체 검증 6/6 통과")

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--ref", default="reference-ses.json")
    ap.add_argument("--runs", nargs="*", default=[])
    ap.add_argument("--aliases", default="aliases.json")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        selftest(); sys.exit(0)
    paths = [p for pat in a.runs for p in sorted(glob.glob(pat))]
    if not paths:
        print("실행 결과 JSON이 없습니다. --runs 'runs/*.json'"); sys.exit(1)
    report(a.ref, paths, a.aliases)
