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

from . import contract, flow, llm as llm_mod
from .run_store import resume_key

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
}
PROMPT_FILE = {
    "a": "T2/a.md", "b": "T2/b.md", "c": "T2/c.md", "d": "T2/d.md", "e": "T2/e.md",
    "f": "T2/f.md", "r": "T2/r.md", "r2": "T2/r2.md", "b2": "T2/b2.md", "b3": "T2/b3.md",
    "g": "T2/g.md", "e1": "T2/e1.md", "e2": "T2/e2.md",
    "states": "semantic/states.md", "components": "semantic/components.md",
    "single": "S2/single.md",
}
CONTEXT_FROM = {"b": ("a",), "b2": ("a",),
                "c": ("b", "b2"), "d": ("a", "b", "b2"),
                "r": ("b", "b2", "d"), "r2": ("b", "b2", "d"),
                "e": ("a", "b", "b2"), "f": ("b", "b2", "d", "r", "r2", "e"),
                "b3": ("b2", "e")}

CONTEXT_FROM["g"] = ("a", "b2")
CONTEXT_FROM["e1"] = ("a", "b2")
CONTEXT_FROM["e2"] = ("b2", "e1", "d")

ROOT_STAGES = ("r", "r2")
# 프롬프트가 금지한 것을 코드도 막는다. 부탁만 해 두면 지켜졌는지 알 수 없다.
NO_NEW_ENTITY_STAGES = ("r2",)

# 이름을 그대로 쓰게 하지 않으면 뒤 단계가 자기 어휘로 부른다. 그러면 조립이 전부 미해결로
# 붙잡는다(jn-T2-2 에서 분해 5건이 그렇게 보류됐다). 목록을 명시하고 그 밖의 이름을 금한다.
NAME_LIST_STAGES = ("c", "d", "r", "r2", "e", "e2", "f")   # c 누락으로 jn-T2c-1 에서 17건이 죽었다

# 행 번호를 붙여 줄 때만 앞에 놓는다. 조건이 달라지므로 요청 기록의 line_numbers 로 남는다.
LINE_NUMBER_NOTE = (
    "아래 코드의 각 줄 앞에는 `행번호| ` 가 붙어 있습니다. 이것은 원문이 아니라 표시입니다.\n"
    "근거의 start_line·end_line 에는 그 번호를 쓰고, quote 에는 `|` 뒤의 **원문만** 적으세요.\n"
    "행 번호와 막대(|)를 인용에 포함하지 마세요.\n\n=====\n\n")


def flow_payload(payloads):
    """b2·e1·e2 에서 파생된 결합 payload. 조립기에 "flow" 단계로 넣는다."""
    sites = flow.merge_sites(payloads.get("b2") or {}, payloads.get("e1") or {})
    return flow.derive(sites, payloads.get("e2") or {})


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
        text = text[:limit]
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
        head += (f"\n[주의] 앞 단계 결과가 {limit}자에서 잘렸습니다. 잘린 부분은 이 단계가 보지 "
                 f"못했습니다.\n")
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
    for e in (payloads.get("b") or {}).get("entities") or []:
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
    stage_list = tuple(stages or PLANS[plan])
    settings = dict(settings or {})
    settings["client"] = getattr(client, "describe", {})
    payloads, errors = {}, []
    # 한 단계만 돌릴 때도 그 단계의 문맥은 있어야 한다. 기록된 완료 결과를 불러 쓴다 —
    # 새로 부르지 않고, 무엇을 불러왔는지 남긴다.
    reused = []
    for st in stage_list:
        for dep in dependencies(st, plan == "T2-tree"):
            if dep in stage_list or dep in payloads:
                continue
            rec = store.last_completion(dep)
            if rec:
                import json as _json
                path = os.path.join(store.dir, rec["payload_path"].replace("/", os.sep))
                if os.path.exists(path):
                    payloads[dep] = _json.loads(io.open(path, encoding="utf-8").read())
                    reused.append(dep)
    code = snapshot.bundle(line_numbers=line_numbers)
    if line_numbers:
        code = (LINE_NUMBER_NOTE + code)

    for stage in stage_list:
        system = system_prompt(stage, prompts_dir)
        if plan == "T2-tree":
            system += "\n\n" + _read(os.path.join(prompts_dir, "T2/tree_rules.md"))
        ctx, ctx_meta = _context_block(stage, payloads, context_limit)
        user = ctx + code
        upstream = {s: store.payload_hash(s) for s in dependencies(stage, plan == "T2-tree")}
        key = resume_key(stage, system, user, settings, contract.SCHEMA_VERSION,
                         snapshot.snapshot_id, upstream)

        cached = store.reusable(stage, key)
        if cached is not None:
            payloads[stage] = cached
            continue

        request = {"stage": stage, "plan": plan, "snapshot_id": snapshot.snapshot_id,
                   "system": system, "context_block": ctx, "context_meta": ctx_meta,
                   "user_body": user,
                   "user_sha256": resume_key("user", "", user, {}, "", "", {}),
                   "settings": settings, "model": getattr(client, "describe", {}),
                   "line_numbers": line_numbers, "resume_key": key}
        adir = store.begin_attempt(stage, request)
        try:
            text, usage = client.complete(stage, system, user)
        except llm_mod.LlmError as e:
            store.finish_attempt(adir, e.kind, error=str(e))
            errors.append({"stage": stage, "kind": e.kind, "error": str(e),
                           "attempt_dir": adir})
            break
        try:
            if usage.get("done_reason") == "length":
                raise ValueError("model output reached the token limit")
            payload = json.loads(text)
            if not isinstance(payload, dict):
                raise ValueError("최상위가 객체가 아니다")
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

    return payloads, errors
