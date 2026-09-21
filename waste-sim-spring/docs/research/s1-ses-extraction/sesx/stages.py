# -*- coding: utf-8 -*-
"""추출 경로. 단계별(T2)과 단일(S2)이 **같은 후보 어휘·같은 계약**을 쓴다.

두 경로의 차이는 질문을 나누느냐뿐이다. 조립·검증·검토는 공통이다. 그래야 방식 차이가
다른 것과 섞이지 않는다.

앞 단계 결과는 후보로 넘긴다. 잘라내야 할 만큼 길면 **자른 사실을 기록한다** —
조용히 [:80] 으로 자르면 뒤 단계가 무엇을 못 봤는지 알 수 없다.
"""
from __future__ import annotations

import io
import json
import os

from . import axis, collection, composition, contract, flow, llm as llm_mod
from .run_store import resume_key, implementation_revision, sha256_text

PROMPTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "prompts")

PLANS = {
    # r 은 루트 단계다. d(구성 관계) 뒤, f(활성 조건) 앞에 둔다 — r 이 만든 구성 관계도
    # 활성 조건을 받아야 하기 때문이다.
    "T3-candidates": ("states", "components"),
    "T2": ("a", "b", "c", "d", "r", "e", "f"),
    # r2 는 상위 개체를 **만들 수 없는** 루트 단계다. 후보 사이의 포함 관계만 묻는다.
    "T2b": ("a", "b", "c", "d", "r2", "e", "f"),
    # b2 는 상태값마다 소유자·식별·대응 근거를 잇게 하는 단계다. 연결을 못 대면 보류된다.
    "T2c": ("a", "b2", "c", "d", "r2", "e", "f"),
    # b3 는 2차 확인이다. e 가 끝점으로 지목했으나 확정되지 않은 이름만 다시 묻는다.
    "T2d": ("a", "b2", "c", "d", "r2", "e", "f", "b3"),
    "T2-tree": ("a", "b2", "g", "c", "d", "r2", "e", "f", "b3"),
    # 색인 축이 아니라 값 흐름을 읽는 경로. 루트는 사람이 검토에서 넣는다.
    "T2-flow": ("a", "b2", "g", "c", "d", "e1", "e2", "f", "b3"),
    "T2-noroot": ("a", "b", "c", "d", "e", "f"),
    "S2": ("single",),
    "T2-evidence": ("a", "b2", "c", "d", "e", "f"),
    # w 는 전체 후보 단계다. 부분(a·b2) 뒤, 관계(c·d) 앞에 둔다 — c 가 전체에 속성을
    # 매달 수 있어야 하기 때문이다. **루트를 고르는 단계가 아니다.** 루트는 PES 가 정한다.
    "T2-whole": ("a", "b2", "w", "c", "d", "e", "f"),
    # T2-whole 의 대조군. **w 하나만 빠진다.** T2-evidence 는 대조군이 될 수 없다 —
    # 단계 목록은 같아도 프롬프트 계열이 다르다(EVIDENCE_PROMPT_PLANS 참조). 실제로
    # q3-base-1·q3-base-2 가 a 단계에서 똑같이 출력 상한까지 늘어놓고 죽었다.
    "T2-whole-control": ("a", "b2", "c", "d", "e", "f"),
    # n 은 **선언**에서 개체를 찾는 단계다. a·b2 가 상태 중심이라 색인 축만 개체가 되는
    # 문제를 겨냥한다(q3-control-2 에서 개체 5개가 전부 배열 축이었다). 유형·집합·특수화는
    # 갱신되는 줄이 아니라 선언된 줄에 있다. b2 뒤에 두어 이미 상태로 확정된 이름을 피한다.
    "T2-decl": ("a", "b2", "n", "c", "d", "e", "f"),
}
PROMPT_FILE = {
    "a": "T2/a.md", "b": "T2/b.md", "c": "T2/c.md", "d": "T2/d.md", "e": "T2/e.md",
    "f": "T2/f.md", "r": "T2/r.md", "r2": "T2/r2.md", "b2": "T2/b2.md", "b3": "T2/b3.md",
    "g": "T2/g.md", "e1": "T2/e1.md", "e2": "T2/e2.md", "w": "T2/w.md", "n": "T2/n.md",
    "states": "semantic/states.md", "components": "semantic/components.md",
    "single": "S2/single.md",
}
CONTEXT_FROM = {"b": ("a",), "b2": ("a",),
                "c": ("b", "b2", "w"), "d": ("a", "b", "b2", "w"),
                "r": ("b", "b2", "d"), "r2": ("b", "b2", "d"),
                "e": ("a", "b", "b2"), "f": ("b", "b2", "d", "r", "r2", "e", "e1", "e2", "flow"),
                "b3": ("b2", "e")}

CONTEXT_FROM["w"] = ("a", "b2")
CONTEXT_FROM["n"] = ("b2",)      # 상태 목록만 필요하다. 관찰 전체를 주면 다시 상태를 센다
CONTEXT_FROM["g"] = ("a", "b2")
CONTEXT_FROM["e1"] = ("a", "b2")
CONTEXT_FROM["e2"] = ("b2", "e1", "d")

ROOT_STAGES = ("r", "r2")
# 프롬프트가 금지한 것을 코드도 막는다. 부탁만 해 두면 지켜졌는지 알 수 없다.
NO_NEW_ENTITY_STAGES = ("r2",)

# 이 계획들은 엄격 조립을 받는다 — 알 수 없는 출력 필드를 **버리지 않고 보류**한다.
# 여기 빠지면 모델이 엉뚱한 모양으로 답했을 때 항목 0개·보류 0개가 되어, 아무것도 내지
# 않은 것처럼 보인다. 실제로 q3-whole-2 에서 b2 가 package.json 모양을, w 가 파일 통째로를
# 냈는데 산출물에는 흔적이 없었다. 계획을 새로 만들 때 여기 넣는 것을 잊기 쉬우므로
# extract.py 의 하드코딩 대신 계획 옆에 둔다.
EVIDENCE_PLANS = ("S2", "T2-evidence", "T2-whole", "T2-whole-control", "T2-decl")

# 계획 이름에 걸린 두 번째 성질 — **프롬프트 계열**이다. 위의 EVIDENCE_PLANS(조립 엄격도)와
# 뜻이 다르므로 합치지 않는다. 다만 둘 다 이름으로 갈리므로 나란히 둔다. 여기 든 계획은
# prompts/evidence/*.md 를, 나머지는 prompts/common.md + prompts/T2/*.md 를 쓴다.
# evidence/a.md 는 311바이트로 출력 모양도 멈출 자리도 없다. 그래서 같은 입력에서
# T2/a.md 는 6,200 토큰을, evidence/a.md 는 24,000 토큰(상한 도달)을 냈다.
EVIDENCE_PROMPT_PLANS = ("S2", "T2-evidence")

# 이름을 그대로 쓰게 하지 않으면 뒤 단계가 자기 어휘로 부른다. 그러면 조립이 전부 미해결로
# 붙잡는다(jn-T2-2 에서 분해 5건이 그렇게 보류됐다). 목록을 명시하고 그 밖의 이름을 금한다.
# 구성 관계(부모-자식)를 내는 단계들. 이 단계가 속성 이름을 자식으로 적으면 개체와 속성의
# 층위가 섞인다 — q3-control-1 에서 `건물 → [fill, peak]` 가 그랬다. 조립기가 보류로 막지만
# (reason_code: attribute_as_member), 애초에 적지 않게 목록을 준다.
ATTRIBUTE_LIST_STAGES = ("n", "d", "r", "r2")

NAME_LIST_STAGES = ("c", "d", "r", "r2", "e", "e2", "f")   # c 누락으로 jn-T2c-1 에서 17건이 죽었다

# 행 번호를 붙여 줄 때만 앞에 놓는다. 조건이 달라지므로 요청 기록의 line_numbers 로 남는다.
LINE_NUMBER_NOTE = (
    "아래 코드의 각 줄 앞에는 `행번호| ` 가 붙어 있습니다. 이것은 원문이 아니라 표시입니다.\n"
    "근거의 start_line·end_line 에는 그 번호를 쓰고, quote 에는 `|` 뒤의 **원문만** 적으세요.\n"
    "행 번호와 막대(|)를 인용에 포함하지 마세요.\n\n=====\n\n")


def flow_payload(payloads):
    """b2·e1·e2 에서 파생된 결합 payload. 조립기에 "flow" 단계로 넣는다."""
    b2, e1 = payloads.get("b2") or {}, payloads.get("e1") or {}
    out = flow.derive(flow.merge_sites(b2, e1), payloads.get("e2") or {})
    # 어느 값에도 못 붙인 e1 항목은 버리지 않는다 — 자리 인용이 살아 있다
    out["unresolved_values"] = flow.unmatched_value_sites(b2, e1)
    return out


def _read(path):
    with io.open(path, encoding="utf-8") as f:
        return f.read()


def system_prompt(stage, prompts_dir=PROMPTS):
    if stage in ("states", "components"):
        return _read(os.path.join(prompts_dir, PROMPT_FILE[stage]))
    common = _read(os.path.join(prompts_dir, "common.md"))
    body = _read(os.path.join(prompts_dir, PROMPT_FILE[stage].replace("/", os.sep)))
    return common.rstrip() + "\n\n---\n\n" + body


def dependencies(stage, tree=False):
    src = CONTEXT_FROM.get(stage, ())
    return src + (("g",) if tree and stage in ("c", "d", "r2", "e", "e2", "f", "b3")
                  else ())


def _context_block(stage, payloads, limit):
    src = dependencies(stage, "g" in payloads)
    if not src:
        return "", {"included_stages": [], "truncated": False}
    ctx = {s: payloads[s] for s in src if s in payloads}
    text = json.dumps(ctx, ensure_ascii=False, indent=1)
    truncated = False
    if len(text) > limit:
        truncated = True
    head = ("아래는 앞 단계의 **후보**입니다. 정답이 아니며, 원문을 다시 보고 틀렸다고 판단하면 "
            "disputes 에 적으세요.\n")
    if stage in ROOT_STAGES:
        cands = root_candidates(payloads)
        head += ("\n코드가 센 **루트 후보**는 다음 " + str(len(cands)) + "개입니다 "
                 "(구성 관계에서 어느 것의 자식도 아닌 개체).\n"
                 + "".join(f"  - {n}\n" for n in cands))
    if stage == "e1":
        vals = [x.get("state") for x in (payloads.get("b2") or {}).get("subjects") or []
                if isinstance(x, dict) and x.get("classification") == "entity_state"]
        head += ("\n자리를 채울 **상태 값**은 다음 " + str(len(vals)) + "개입니다. "
                 "value.code_expression 에 이 표현식을 글자 그대로 적으세요.\n"
                 + "".join("  - " + str(v) + "\n" for v in vals if v))
    if stage == "e2":
        lines = []
        for entry in flow.merge_sites(payloads.get("b2") or {}, payloads.get("e1") or {}):
            lines.append("  값 " + str(entry["value"]))
            for role, field in (("쓰기", "writes"), ("읽기", "reads")):
                for site in entry[field]:
                    lines.append("    %s  %s  %s:%s  %s" % (
                        site["site_id"], role, site["file_path"], site["start_line"],
                        site["quote"]))
        head += ("\n주체를 정할 **자리**는 다음과 같습니다. "
                 "site_id 를 글자 그대로 쓰세요.\n"
                 + "".join(l + "\n" for l in lines))
    if stage == "b3":
        pend = pending_endpoints(payloads)
        head += ("\n다른 단계가 끝점으로 지목했으나 **확정되지 않은 이름** "
                 + str(len(pend)) + "개입니다.\n"
                 + "".join(f"  - {n}\n" for n in pend))
    if stage in ATTRIBUTE_LIST_STAGES:
        attrs = attribute_names(payloads)
        if attrs:
            head += ("\n이미 어떤 개체의 **상태**로 확정된 이름은 다음 " + str(len(attrs))
                     + "개입니다. 이것을 members 에 적지 마세요 — 상태는 개체의 부분이 "
                       "아닙니다. 적으면 보류됩니다.\n"
                     + "".join(f"  - {st} → {ow} 의 상태\n" for st, ow in attrs))
    if stage in NAME_LIST_STAGES:
        names = entity_names(payloads)
        if names:
            head += ("\n확정된 개체 이름은 다음 " + str(len(names)) + "개뿐입니다. parent·members·"
                     "from·to·target 에는 **이 목록의 이름을 글자 그대로** 적으세요. 목록에 없는 "
                     "이름을 쓰면 그 항목은 미해결로 보류되어 버려집니다. 새 개체가 필요하다고 "
                     "판단되면 항목을 만들지 말고 disputes 에 적으세요.\n"
                     + "".join(f"  - {n}\n" for n in names))
    if "g" in payloads:
        head = head.replace("확정된 개체", "형식 요건을 갖춘 개체 후보").replace("보류되어 버려집니다", "보류되어 기록됩니다")
    if truncated:
        head += f"\n[중단] 앞 단계 결과가 {limit}자 제한을 초과했습니다. 호출하지 않습니다.\n"
    return head + "\n" + text + "\n\n=====\n", {"included_stages": list(ctx),
                                                "truncated": truncated}


def entity_names(payloads):
    """확정된 개체 이름. b 는 entities 로, b2 는 소유자 후보로 낸다.

    b2 에서는 **세 근거가 갖춰진 entity_state 만** 개체가 된다 — 조립기의 규칙과 같다.
    여기서 느슨하게 세면 뒤 단계에 없는 이름을 주게 된다.
    """
    if "g" in payloads:
        from .assemble import build
        doc = build(payloads, "context", "context", {}, {},
                    no_new_entity_stages=NO_NEW_ENTITY_STAGES)
        return [e["name"] for e in doc["entities"]]
    out, seen = [], set()
    # w 의 전체 후보도 목록에 넣는다. 넣지 않으면 c 단계가 "전체 시뮬레이션" 같은
    # 자기 어휘를 쓰고 그 속성이 통째로 보류된다 (q3-flow-2 에서 24건이 그랬다).
    wholes = [e for e in ((payloads.get("w") or {}).get("entities") or [])
              if isinstance(e, dict) and not composition.whole_error(e)]
    # 코드가 만든 축·갈래 개체. 이름 목록에 없으면 d·e 가 그것을 가리킬 수 없다.
    for key in ("axis", "collection"):
        wholes = wholes + [e for e in ((payloads.get(key) or {}).get("entities") or [])
                           if isinstance(e, dict)]
    for e in wholes + ((payloads.get("b") or {}).get("entities") or []) + ((payloads.get("b2") or {}).get("entities") or []):
        if isinstance(e, dict) and isinstance(e.get("name"), str) and e["name"].strip():
            if e["name"] not in seen:
                seen.add(e["name"])
                out.append(e["name"])
    for s in (payloads.get("b2") or {}).get("subjects") or []:
        if not isinstance(s, dict) or s.get("classification") != "entity_state":
            continue
        name = s.get("owner_candidate")
        if not isinstance(name, str) or not name.strip():
            continue
        if not (s.get("state_evidence") and s.get("identity_evidence")
                and s.get("linkage_evidence") and s.get("consumption_evidence")):
            continue
        if name not in seen:
            seen.add(name)
            out.append(name)
    return out


def attribute_names(payloads):
    """이미 개체의 **상태**로 확정된 이름과 그 소유자. [(상태, 소유자), ...]

    조립기가 속성으로 받을 것과 같은 규칙으로 센다 — 네 근거가 갖춰진 `entity_state` 만.
    여기서 느슨하게 세면 뒤 단계에 "쓰지 말라"고 한 이름이 실제로는 속성이 아닐 수 있다.
    """
    out, seen = [], set()
    for x in (payloads.get("b2") or {}).get("subjects") or []:
        if not isinstance(x, dict) or x.get("classification") != "entity_state":
            continue
        state, owner = x.get("state"), x.get("owner_candidate")
        if not isinstance(state, str) or not state.strip():
            continue
        if not isinstance(owner, str) or not owner.strip():
            continue
        if not (x.get("state_evidence") and x.get("identity_evidence")
                and x.get("linkage_evidence") and x.get("consumption_evidence")):
            continue
        if state not in seen:
            seen.add(state)
            out.append((state, owner))
    return out


def pending_endpoints(payloads):
    """e 가 끝점으로 적었으나 확정 개체가 아닌 이름. **코드가 고른다.**

    조립기가 `unknown_reference` 로 보류할 이름과 같은 규칙으로 뽑는다. 여기서 느슨하면
    2차 질문의 대상이 실제 손실과 어긋난다.
    """
    from .assemble import norm
    known = {norm(n) for n in entity_names(payloads)}
    out, seen = [], set()
    for c in (payloads.get("e") or {}).get("couplings") or []:
        if not isinstance(c, dict):
            continue
        for side in ("source", "target"):
            box = c.get(side)
            name = box.get("entity") if isinstance(box, dict) else None
            if not isinstance(name, str) or not name.strip():
                continue
            if norm(name) in known or norm(name) in seen:
                continue
            seen.add(norm(name))
            out.append(name)
    return out


def root_candidates(payloads):
    """구성 관계에서 진입 차수가 0인 개체 이름. **코드가 센다.**

    루트를 고르는 것이 아니라 후보를 세는 것이다. 고르는 것은 근거를 본 사람(또는 r 단계가
    근거를 댄 상위 개체)의 몫이다. 분해가 하나도 없으면 모든 개체가 후보다.
    """
    from .assemble import norm
    if "g" in payloads:
        from .assemble import build
        from .structure import analyze
        doc = build(payloads, "context", "context", {}, {},
                    no_new_entity_stages=NO_NEW_ENTITY_STAGES)
        roots = set(analyze(doc)["root_candidate_ids"])
        return [e["name"] for e in doc["entities"] if e["id"] in roots]
    names = entity_names(payloads)
    known = {norm(n) for n in names}
    children = set()
    for d in (payloads.get("d") or {}).get("decompositions") or []:
        if not isinstance(d, dict):
            continue
        # 부모가 개체가 아니면 그 분해는 조립에서 보류된다. 보류될 분해의 자식을 세면
        # 후보가 줄어들어 **잘못된 질문**을 하게 된다 — jn-T2b-1 에서 후보가 7개인데
        # 2개라고 물었다. 개체로 확정된 이름만 부모·자식으로 센다.
        # 조립기의 거부 규칙을 **그대로** 따라야 한다. 조립기는 자식이 하나라도 개체가
        # 아니거나 자기 자신이면 그 분해를 통째로 보류한다. 여기서 그 일부만 살려 세면
        # 후보 목록이 실제와 어긋난다 — jn-T2b-1 에서 `collection site` 가 빠졌고,
        # 모델이 "그건 후보가 아니라서 뺐다"고 답했다.
        members = [m for m in (d.get("members") or []) if isinstance(m, str)]
        if norm(d.get("parent")) not in known or not members:
            continue
        if any(norm(m) not in known or norm(m) == norm(d.get("parent")) for m in members):
            continue
        children.update(norm(m) for m in members)
    return [n for n in names if norm(n) not in children]


def run_pipeline(store, snapshot, client, plan="T2", stages=None, settings=None,
                 prompts_dir=PROMPTS, context_limit=120000, line_numbers=False):
    """한 경로를 끝까지 돈다. 실패해도 앞 단계 산출물과 원문은 남는다.

    반환: (payloads, errors) — payloads 는 {단계: 후보}, errors 는 단계별 실패 기록.
    """
    requested = tuple(stages or PLANS[plan])
    if not requested or any(s not in PLANS[plan] for s in requested):
        return {}, [{"kind": "stage_selection", "error": "stage is not in the selected plan"}]
    # Walk the full prefix to recompute every resume key. Unrequested prerequisites
    # may only be reused after verification, never recalled by stage name alone.
    stage_list = PLANS[plan][:max(PLANS[plan].index(s) for s in requested) + 1]
    settings = dict(settings or {})
    settings["client"] = getattr(client, "describe", {})
    settings["plan"] = plan
    settings["extractor_sha256"] = implementation_revision()
    settings["snapshot_manifest_sha256"] = snapshot.manifest.get("manifest_sha256")
    payloads, errors = {}, []
    # 컬렉션 필드에서 집합·원소를 찾는 것은 모델이 필요 없다. 스냅샷만 있으면 된다.
    # 뒤 단계가 이 이름들을 가리킬 수 있도록 첫 호출 전에 만들어 둔다.
    if "n" in stage_list:
        payloads["collection"] = collection.derive(snapshot)
    code = snapshot.bundle(line_numbers=line_numbers)
    if line_numbers:
        code = (LINE_NUMBER_NOTE + code)

    for stage in stage_list:
        if stage == "f" and "e1" in payloads and "e2" in payloads:
            payloads["flow"] = flow_payload(payloads)
        if plan in EVIDENCE_PROMPT_PLANS:
            system = _read(os.path.join(prompts_dir, "evidence/common.md")) + "\n\n" + _read(
                os.path.join(prompts_dir, "evidence", stage + ".md"))
        else:
            system = system_prompt(stage, prompts_dir)
        if plan == "T2-tree":
            system += "\n\n" + _read(os.path.join(prompts_dir, "T2/tree_rules.md"))
        ctx, ctx_meta = _context_block(stage, payloads, context_limit)
        user = ctx + code
        upstream = {s: sha256_text(json.dumps(p, sort_keys=True, ensure_ascii=False))
                    for s, p in payloads.items()}
        key = resume_key(stage, system, user, settings, contract.SCHEMA_VERSION,
                         snapshot.snapshot_id, upstream)

        try:
            cached = store.reusable(stage, key)
        except ValueError as err:
            errors.append({"stage": stage, "kind": "integrity", "error": str(err)})
            break
        if cached is not None:
            payloads[stage] = cached
            if stage == "n":
                payloads["axis"] = axis.derive(cached, snapshot)
            continue

        if stage not in requested:
            errors.append({"stage": stage, "kind": "stale_dependency",
                           "error": "prerequisite resume key changed; resume the full plan"})
            break

        request = {"stage": stage, "plan": plan, "snapshot_id": snapshot.snapshot_id,
                   "system": system, "context_block": ctx, "context_meta": ctx_meta,
                   "user_body": user,
                   "user_sha256": resume_key("user", "", user, {}, "", "", {}),
                   "settings": settings, "model": getattr(client, "describe", {}),
                   "line_numbers": line_numbers, "resume_key": key}
        adir = store.begin_attempt(stage, request)
        if ctx_meta["truncated"]:
            store.finish_attempt(adir, "failed", error="context exceeds configured limit")
            errors.append({"stage": stage, "kind": "context_overflow", "attempt_dir": adir})
            break
        try:
            text, usage = client.complete(stage, system, user)
        except llm_mod.LlmError as e:
            store.finish_attempt(adir, e.kind, error=str(e))
            errors.append({"stage": stage, "kind": e.kind, "error": str(e),
                           "attempt_dir": adir})
            break
        try:
            if usage.get("done_reason") == "length" or usage.get("finish_reason") == "length":
                raise ValueError("model output reached the token limit")
            payload = json.loads(text)
            if not isinstance(payload, dict):
                raise ValueError("최상위가 객체가 아니다")
            for name, val in payload.items():
                if name in ("entities", "attributes", "decompositions", "couplings",
                            "activation", "observations", "disputes", "subjects"):
                    if not isinstance(val, list):
                        raise ValueError(f"{name} must be an array")
        except Exception as e:                      # 원문은 이미 저장한다
            store.finish_attempt(adir, "failed", response_text=text, usage=usage,
                                 error=f"파싱 실패: {e}")
            errors.append({"stage": stage, "kind": "parse_error", "error": str(e),
                           "attempt_dir": adir})
            break
        store.finish_attempt(adir, "completed", response_text=text, usage=usage)
        store.record_completion(stage, key, payload, adir,
                                raw_ref=os.path.join(adir, "response.raw"))
        payloads[stage] = payload
        # 열거형 축에서 SPEC 을 만드는 것은 코드다. 모델은 "이것이 축이다"까지만 답했다.
        if stage == "n":
            payloads["axis"] = axis.derive(payload, snapshot)

    return payloads, errors
