"""T2-tree의 구성 후보 형식 계약. 의미 지지는 인용 검증과 사람 검토로 판단한다."""


def evidence_list(value):
    return (isinstance(value, list) and bool(value)
            and all(isinstance(e, dict) and isinstance(e.get("file_path"), str)
                    and bool(e["file_path"].strip())
                    and type(e.get("start_line")) is int and e["start_line"] > 0
                    and type(e.get("end_line", e["start_line"])) is int
                    and e.get("end_line", e["start_line"]) >= e["start_line"]
                    and isinstance(e.get("quote"), str) and bool(e["quote"].strip())
                    for e in value))


def entity_error(e):
    if e.get("kind") not in ("boundary", "set", "type"):
        return "구성 개체는 boundary/set/type 역할이어야 한다"
    if e.get("scope") != "simulation_target":
        return "대상 시스템의 구성 개체만 허용한다"
    for key in ("identity_evidence", "composition_evidence"):
        if not evidence_list(e.get(key)):
            return key + "가 없거나 형식이 잘못되었다"
    return None


def decomposition_error(d):
    members = d.get("members")
    if (not isinstance(members, list) or not members
            or any(not isinstance(m, str) or not m.strip() for m in members)):
        return "비어 있거나 잘못된 자식 목록"
    if not evidence_list(d.get("evidence")):
        return "구성 관계 근거 없음"
    ev = d.get("member_evidence")
    if not isinstance(ev, dict) or any(not evidence_list(ev.get(m)) for m in members):
        return "각 부모-자식 연결의 역할 근거가 필요하다"
    if str(d.get("kind", "")).upper() == "MULTI":
        mult = d.get("multiplicity")
        if not isinstance(mult, dict) or not evidence_list(mult.get("evidence")):
            return "MULTI 반복 범위·개수 근거 없음"
    if str(d.get("kind", "")).upper() == "SPEC" and not d.get("selection"):
        return "SPEC 선택 범위·규칙 없음"
    return None



# --- 전체(whole) 후보 -------------------------------------------------------
#
# 전체는 "부품들이 한자리에서 조립되는 것"이다. 루트라는 뜻이 아니다 — 루트는 PES 가
# 정한다(sesx/pes.py). 여기서는 형식만 본다. 의미는 인용 검증과 사람 검토가 판단한다.
#
# 실행 지원 소프트웨어를 전체로 세우면 시뮬레이션 대상의 SES 가 오염된다. 실제로
# `이벤트 우선순위 큐` · `event queue` 가 개체로 나온 적이 있다(jn-T2-4 · jn-T2b-1 ·
# smoke-v8-02). 그래서 범위를 형식 요건으로 막는다.

def whole_error(e):
    if e.get("kind") != "whole":
        return "전체 후보는 kind 가 whole 이어야 한다"
    if e.get("scope") != "simulation_target":
        return "실행 지원 소프트웨어는 전체가 될 수 없다"
    if not evidence_list(e.get("assembly_evidence")):
        return "조립 자리 근거가 없거나 형식이 잘못되었다"
    if not evidence_list(e.get("evidence")):
        return "존재 근거가 없거나 형식이 잘못되었다"
    return None


# --- 선언 기반 개체 후보 ------------------------------------------------------
#
# 왜 필요한가. 지금 단계 구성은 전부 상태 중심이다 — a 는 "무엇이 달라지는가", b2 는
# "그 상태는 누구의 것인가". 그래서 나오는 개체가 배열의 **색인 축**뿐이다(건물·직업·
# 요일·월). 정답지의 개체 62개 중 유형·집합·특수화는 선언과 컬렉션에서 나오는데,
# 그것을 읽는 단계가 없었다.
#
# 여기서 막는 것. "클래스 목록을 그대로 개체 목록으로 바꾸지 마라"가 과제의 제약이다.
# 프롬프트로는 막히지 않는 것이 두 번 확인됐으므로(NAME_LIST_STAGES·ATTRIBUTE_LIST_STAGES
# 둘 다 무시당했다) **역할마다 선언의 모양을 코드가 요구한다.** 모양이 맞지 않으면 보류다.

from . import shapes  # noqa: E402  (모듈 하단 배치 — 순환 없음)

DECLARATION_ROLES = ("set", "type", "object")

#: 역할마다 인정하는 선언의 모양. 아무 줄이나 인용해서 개체를 세울 수 없다.
DECLARATION_SHAPES = {
    "set": ("array_alloc", "field_declaration"),          # 배열·컬렉션 선언
    "type": ("enum_member", "type_declaration"),          # 열거 상수·하위 유형
    "object": ("type_declaration",),                      # 클래스·레코드 선언
}


def declaration_error(e):
    role = e.get("role")
    if role not in DECLARATION_ROLES:
        return f"역할은 {DECLARATION_ROLES} 중 하나여야 한다"
    if e.get("scope") != "simulation_target":
        return "실행 지원 소프트웨어는 선언 개체가 될 수 없다"
    decl = e.get("declaration_evidence")
    if not evidence_list(decl):
        return "선언 근거가 없거나 형식이 잘못되었다"
    seen = [shapes.identity_shape(d.get("quote")) for d in decl]
    allowed = DECLARATION_SHAPES[role]
    if not any(sh in allowed for sh in seen):
        return (f"{role} 의 선언 근거가 {allowed} 모양이 아니다 (인용한 모양: {seen}). "
                "선언하는 줄을 인용해야 한다")
    if role == "set":
        if not isinstance(e.get("element_type"), str) or not e["element_type"].strip():
            return "집합은 원소 유형을 적어야 한다"
    if role == "type":
        sib = e.get("siblings")
        if not isinstance(sib, list) or not [x for x in sib if isinstance(x, str) and x.strip()]:
            return "유형은 형제(같은 축의 다른 값)를 하나 이상 적어야 한다"
    if role == "object":
        # 클래스 선언만으로는 대상 세계의 개체가 되지 않는다. 시뮬레이션 안에서 **만들어
        # 지거나 등록되는** 자리가 있어야 한다. 이것이 클래스 목록 복사를 막는 자리다.
        inst = e.get("instantiation_evidence")
        if not evidence_list(inst):
            return "개체는 생성·등록 근거가 있어야 한다 — 클래스 선언만으로는 부족하다"
        kinds = [shapes.identity_shape(d.get("quote")) for d in inst]
        if not any(k in ("construction", "registration") for k in kinds):
            return f"생성·등록 모양이 아니다 (인용한 모양: {kinds})"
    return None
