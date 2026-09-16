#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_ref_v8.py — 참조 SES(ref-v8)의 구조 불변식 검사

사용:
  python check_ref_v8.py skeleton    # 최상위 골격·개명·보존량
  python check_ref_v8.py systems     # DEVS 모델 구성 5종과 그 아래
  python check_ref_v8.py settings    # 설정 구성 5종과 sets 대응
  python check_ref_v8.py all         # 위 셋 + 전역 불변식

채점기(score_ses.py)와 역할이 다르다. 채점기는 추출 결과를 정답지와 견주고,
이것은 정답지 자체가 스스로 모순되지 않는지 본다.
"""
from __future__ import annotations
import io, json, os, sys

KIT = os.path.dirname(os.path.abspath(__file__))
REF = os.path.join(KIT, "reference-ses.json")

# ---------------------------------------------------------------- 기대값
ROOT = "장량동 생활쓰레기 수거 시뮬레이터"

TOP = ["시뮬레이션 설정", "생활쓰레기 수거 모델", "시나리오 실험", "시뮬레이션 결과"]

SYSTEMS = {
    "거주민 배출 시스템": ["거주민 집합"],
    "수거장 적재 시스템": ["수거지점 집합", "폐기물 유형 집합"],
    "차량 수거 시스템": ["수거차량 집합"],
    "경로 교통 시스템": ["수거 경로", "교통구역 집합"],
    "민원 감시 시스템": ["민원 판정"],
}

SETTINGS = ["거주민·배출 설정", "수거지점·폐기물 설정", "차량·수거 설정",
            "교통·경로 설정", "실험 설정"]

RENAMED = {"대상 시스템": "생활쓰레기 수거 모델",
           "실험": "시나리오 실험",
           "관측": "시뮬레이션 결과"}

BANNED = ["엔진 실행", "DEVS 엔진", "이벤트 실행 시스템", "이벤트 집합"]

N_ENTITIES = 62
N_COUPLINGS = 6
N_CRITICAL = 5
N_EVIDENCE = 92          # 87(v7) + 시스템 5. 설정 6은 Task 5에서 98이 된다

# 잎 값 — ref-v7을 따른다
LEAF_COUNTS = {
    ("거주민", "직업 축"): 5,
    ("거주민", "배출시각 모델 축"): 2,
    ("수거차량", "차종 축"): 3,
    ("수거 경로", "이동시간 방식 축"): 3,
    ("민원 판정", "판정 구성"): 3,
    ("시나리오 실험", "실험 유형 축"): 13,
    ("시뮬레이션 결과", "결과 구성"): 6,
}

# 구성결정-SES-대응표의 29개 결정. 문항 16·17이 한 속성으로 합쳐져 항목은 28개다.
# (설정 엔티티, 속성) -> target
SETS_EXPECTED = {
    "거주민·배출 설정": {
        "거주민수": "거주민 집합.개수",
        "직업구성": "거주민 집합.직업구성",
        "1인배출량": "생활쓰레기 수거 모델.1인배출량",
        "배출량변동": "생활쓰레기 수거 모델.배출량변동",
        "외출시각변동": "거주민.외출시각변동",
        "배출시각모델": "거주민.배출시각 모델 축",
        "배출허용창": "포항시 배출시간대 기반.배출허용창",
    },
    "수거지점·폐기물 설정": {
        "수거지점수": "수거지점 집합.개수",
        "용량": "폐기물 유형.용량",
        "임계값": "폐기물 유형.임계값",
    },
    "차량·수거 설정": {
        "수거시각": "수거차량.수거시각",
        "수거요일": "수거차량.수거요일",
        "차종": "수거차량.차종 축",
        "차량수": "수거차량 집합.개수",
        "적재용량": "수거차량.적재용량",
        "초기적재량": "수거차량.초기적재량",
        "배차간격": "수거차량.배차간격",
        "지점당수거시간": "수거차량.지점당수거시간",
    },
    "교통·경로 설정": {
        "교통모드": "결합: 교통 구역.혼잡계수 -> 수거 경로.이동시간 · 교통혼잡 판정.판정 의 active_when",
        "시간대프로파일": "교통 구역.시간대프로파일",
        "이동시간방식": "수거 경로.이동시간 방식 축",
        "구간이동시간": "구간 상수.구간이동시간",
        "구역내이동시간": "교통구역 근사.구역내이동시간",
        "구역배정가정": "교통구역 근사.구역배정가정",
        "방문순서": "수거 경로.방문순서",
    },
    "실험 설정": {
        "실험유형": "시나리오 실험.실험 유형 축",
        "기간": "시나리오 실험.기간",
        "반복횟수": "시나리오 실험.반복횟수",
    },
}

# ---------------------------------------------------------------- 도우미
def load():
    return json.load(io.open(REF, encoding="utf-8"))

def dec_of(ents, name, dec_name):
    """엔티티의 특정 이름 분해를 돌려준다. 없으면 None."""
    for d in ents.get(name, {}).get("decompositions", []) or []:
        if d.get("name") == dec_name:
            return d
    return None

def children_of(ents, name):
    out = []
    for d in ents.get(name, {}).get("decompositions", []) or []:
        out += list(d.get("children") or [])
    return out

def all_edges(ents):
    for p, body in ents.items():
        for d in body.get("decompositions", []) or []:
            for c in d.get("children") or []:
                yield p, c

# ---------------------------------------------------------------- 검사
def check_skeleton(ref, bad):
    ents = ref["entities"]

    if ref.get("root") != ROOT:
        bad.append(f"root가 {ref.get('root')!r} 이다. {ROOT!r} 이어야 한다")

    d = dec_of(ents, ROOT, "시스템 구성")
    if d is None:
        bad.append("루트에 aspect「시스템 구성」 분해가 없다")
    else:
        if d.get("kind") != "aspect":
            bad.append(f"「시스템 구성」의 kind가 {d.get('kind')!r} 이다. aspect 이어야 한다")
        if list(d.get("children") or []) != TOP:
            bad.append(f"「시스템 구성」의 자식이 {d.get('children')} 이다. {TOP} 이어야 한다")

    if dec_of(ents, ROOT, "최상위") is not None:
        bad.append("루트에 옛 분해「최상위」가 남아 있다")

    for old, new in RENAMED.items():
        if old in ents:
            bad.append(f"옛 이름 {old!r} 이 엔티티로 남아 있다 ({new!r} 으로 개명해야 한다)")
        if new not in ents:
            bad.append(f"새 이름 {new!r} 엔티티가 없다")

    for b in BANNED:
        if b in ents:
            bad.append(f"넣지 않기로 한 {b!r} 이 엔티티로 있다 (사양서 3절)")

    for (owner, axis), n in LEAF_COUNTS.items():
        dd = dec_of(ents, owner, axis)
        if dd is None:
            bad.append(f"{owner}에 분해「{axis}」가 없다")
        elif len(dd.get("children") or []) != n:
            bad.append(f"{owner}「{axis}」의 자식이 {len(dd.get('children') or [])}개다. {n}개여야 한다")

    for label, got, want in (("엔티티", len(ents), N_ENTITIES),
                             ("결합", len(ref.get("couplings") or []), N_COUPLINGS),
                             ("임계점", len(ref.get("critical_points") or []), N_CRITICAL),
                             ("근거", len(ref.get("evidence") or {}), N_EVIDENCE)):
        if got != want:
            bad.append(f"{label} 수가 {got}이다. {want}이어야 한다")

    for old in RENAMED:
        for k in ref.get("evidence") or {}:
            if k == old or k.startswith(old + "."):
                bad.append(f"근거 키 {k!r} 가 옛 이름을 쓴다")
        for c in ref.get("couplings") or []:
            for side in ("from", "to"):
                if str(c.get(side, "")).split(".")[0] == old:
                    bad.append(f"결합의 {side} {c.get(side)!r} 가 옛 이름을 쓴다")
        for cp in ref.get("critical_points") or []:
            if str(cp.get("where", "")).split(".")[0] == old:
                bad.append(f"임계점 {cp.get('id')} 의 where 가 옛 이름을 쓴다")

def check_systems(ref, bad):
    ents = ref["entities"]

    d = dec_of(ents, "생활쓰레기 수거 모델", "DEVS 모델 구성")
    if d is None:
        bad.append("「생활쓰레기 수거 모델」에 aspect「DEVS 모델 구성」이 없다")
    else:
        if d.get("kind") != "aspect":
            bad.append(f"「DEVS 모델 구성」의 kind가 {d.get('kind')!r} 이다. aspect 이어야 한다")
        want = list(SYSTEMS)
        if list(d.get("children") or []) != want:
            bad.append(f"「DEVS 모델 구성」의 자식이 {d.get('children')} 이다. {want} 이어야 한다")

    if dec_of(ents, "생활쓰레기 수거 모델", "구성") is not None:
        bad.append("「생활쓰레기 수거 모델」에 옛 분해「구성」이 남아 있다")

    for sysname, kids in SYSTEMS.items():
        if sysname not in ents:
            bad.append(f"시스템 {sysname!r} 엔티티가 없다")
            continue
        got = children_of(ents, sysname)
        if got != kids:
            bad.append(f"{sysname}의 자식이 {got} 이다. {kids} 이어야 한다")
        if sysname not in (ref.get("evidence") or {}):
            bad.append(f"{sysname}에 근거가 없다")

def check_settings(ref, bad):
    ents = ref["entities"]

    d = dec_of(ents, "시뮬레이션 설정", "설정 구성")
    if d is None:
        bad.append("「시뮬레이션 설정」에 aspect「설정 구성」이 없다")
    else:
        if d.get("kind") != "aspect":
            bad.append(f"「설정 구성」의 kind가 {d.get('kind')!r} 이다. aspect 이어야 한다")
        if list(d.get("children") or []) != SETTINGS:
            bad.append(f"「설정 구성」의 자식이 {d.get('children')} 이다. {SETTINGS} 이어야 한다")

    if "시뮬레이션 설정" in ents and "시뮬레이션 설정" not in (ref.get("evidence") or {}):
        bad.append("「시뮬레이션 설정」에 근거가 없다")

    if "sets" not in (ref.get("_meta", {}).get("schema_extensions") or {}):
        bad.append("_meta.schema_extensions 에 sets 설명이 없다")

    n_sets = 0
    for owner, expected in SETS_EXPECTED.items():
        if owner not in ents:
            bad.append(f"설정 {owner!r} 엔티티가 없다")
            continue
        if owner not in (ref.get("evidence") or {}):
            bad.append(f"{owner}에 근거가 없다")
        if children_of(ents, owner):
            bad.append(f"{owner}는 자식을 갖지 않아야 한다 (모델 엔티티를 다시 소유하지 않는다)")
        sets = ents[owner].get("sets") or {}
        attrs = ents[owner].get("attrs") or []
        for attr, target in expected.items():
            n_sets += 1
            if attr not in attrs:
                bad.append(f"{owner}.attrs 에 {attr!r} 가 없다")
            if attr not in sets:
                bad.append(f"{owner}.sets 에 {attr!r} 가 없다")
                continue
            got = sets[attr].get("target")
            if got != target:
                bad.append(f"{owner}.sets[{attr!r}].target 가 {got!r} 이다. {target!r} 이어야 한다")
            if not str(sets[attr].get("evidence") or "").strip():
                bad.append(f"{owner}.sets[{attr!r}] 에 evidence 가 비어 있다")
        for attr in sets:
            if attr not in expected:
                bad.append(f"{owner}.sets 에 대응표에 없는 {attr!r} 가 있다")

    if n_sets != 28:
        bad.append(f"sets 항목이 {n_sets}개다. 28개여야 한다 "
                   f"(대응표의 결정 29개 중 문항 16·17이 수거시각 하나로 합쳐진다)")

    # target 이 실재하는 자리를 가리키는가
    for owner, sets in ((o, ents.get(o, {}).get("sets") or {}) for o in SETS_EXPECTED):
        for attr, body in sets.items():
            t = str(body.get("target") or "")
            if t.startswith("결합:"):
                continue                      # 문항 24 한 건만 이 형식이다
            ent, _, tail = t.partition(".")
            if ent not in ents:
                bad.append(f"{owner}.sets[{attr!r}] 가 없는 엔티티 {ent!r} 를 가리킨다")
                continue
            is_attr = tail in (ents[ent].get("attrs") or [])
            is_axis = dec_of(ents, ent, tail) is not None
            if not (is_attr or is_axis):
                bad.append(f"{owner}.sets[{attr!r}] 의 {tail!r} 가 {ent!r} 의 속성도 분해도 아니다")

def check_global(ref, bad):
    ents = ref["entities"]

    parents = {}
    for p, c in all_edges(ents):
        if c not in ents:
            bad.append(f"{p} 의 자식 {c!r} 가 엔티티에 없다")
            continue
        parents.setdefault(c, []).append(p)

    for c, ps in parents.items():
        if len(ps) > 1:
            bad.append(f"{c!r} 의 부모가 {len(ps)}개다: {ps}")

    reach, stack = {ROOT}, [ROOT]
    while stack:
        n = stack.pop()
        for c in children_of(ents, n):
            if c in ents and c not in reach:
                reach.add(c); stack.append(c)
    for n in ents:
        if n not in reach:
            bad.append(f"{n!r} 이 루트에서 닿지 않는다 (고아)")

    for n in ents:
        if n in parents and n == ROOT:
            bad.append("루트에 부모가 있다 (순환)")

    for n in ents:
        if n not in (ref.get("evidence") or {}):
            has_attr_ev = any(k.startswith(n + ".") for k in (ref.get("evidence") or {}))
            if not has_attr_ev:
                bad.append(f"{n!r} 에 근거가 없다")

GROUPS = {"skeleton": [check_skeleton],
          "systems": [check_systems],
          "settings": [check_settings],
          "all": [check_skeleton, check_systems, check_settings, check_global]}

if __name__ == "__main__":
    group = sys.argv[1] if len(sys.argv) > 1 else "all"
    if group not in GROUPS:
        print(f"묶음은 {list(GROUPS)} 중 하나입니다"); sys.exit(2)
    ref = load()
    bad = []
    for fn in GROUPS[group]:
        fn(ref, bad)
    if bad:
        print(f"[{group}] 불변식 위반 {len(bad)}건")
        for b in bad:
            print(f"   X {b}")
        sys.exit(1)
    print(f"[{group}] 통과")
