# -*- coding: utf-8 -*-
"""값 흐름에서 결합을 만든다. **모델이 참여하지 않는 단계다.**

b2 는 이미 상태 값마다 쓰는 자리(state_evidence)와 읽는 자리(consumption_evidence)를
따로 묻는다. e1 은 그 목록에서 빠진 자리를 채운다. 여기서 둘을 합치고(merge_sites),
`쓰는 자리 × 읽는 자리` 를 짝지어 방향 있는 결합을 만든다(derive).

짝짓기와 방향을 코드가 하므로 출발과 도착이 같은 결합이 나올 수 없다. 같은 주체끼리의
쌍은 결합이 아니라 상태 갱신이며, 버리지 않고 판정 기록으로 옮긴다.
"""
from __future__ import annotations

EFFECTS = ("increase", "decrease", "assign", "unknown")


def _site(raw, site_id, origin):
    """근거 하나를 자리 하나로. 형식이 어긋나면 None."""
    if not isinstance(raw, dict):
        return None
    path, start = raw.get("file_path"), raw.get("start_line")
    if not isinstance(path, str) or not path.strip() or not isinstance(start, int):
        return None
    effect = raw.get("effect")
    return {"site_id": site_id,
            "file_path": path,
            "start_line": start,
            "end_line": raw.get("end_line", start),
            "quote": raw.get("quote"),
            "effect": effect if effect in EFFECTS else "unknown",
            "from": origin}


def _key(site):
    return (site["file_path"], site["start_line"], site["end_line"])


def _base_name(expr):
    """`fill[b][t]` · `fill.get(k)` 에서 값 이름 `fill` 만 남긴다."""
    if not isinstance(expr, str):
        return ""
    out = expr.strip()
    for sep in ("[", "(", "."):
        i = out.find(sep)
        if i > 0:
            out = out[:i]
    return out.strip()


def _index_by_value(b2_payload, e1_payload):
    """e1 의 값마다 대응하는 b2 항목 순번. 못 맞추면 None.

    이름이 그대로 맞으면 그것을 쓰고, 아니면 아래 첨자를 떼고 **하나에만** 걸릴
    때에 한해 맞춘다. 둘 이상에 걸리면 맞추지 않는다 — 조용히 엉뚱한 값에 붙이는
    것이 못 맞추는 것보다 나쁘다.
    """
    subjects = _entity_states(b2_payload)
    exact = {}
    for i, sub in enumerate(subjects):
        st = sub.get("state")
        if isinstance(st, str):
            exact.setdefault(st, i)
    by_base = {}
    for i, sub in enumerate(subjects):
        by_base.setdefault(_base_name(sub.get("state")), []).append(i)

    out = {}
    for vs in (e1_payload or {}).get("value_sites") or []:
        if not isinstance(vs, dict):
            continue
        value = vs.get("value") if isinstance(vs.get("value"), dict) else {}
        expr = value.get("code_expression")
        if not isinstance(expr, str):
            continue
        if expr in exact:
            out[expr] = exact[expr]
            continue
        hits = by_base.get(_base_name(expr)) or []
        out[expr] = hits[0] if len(hits) == 1 else None
    return out


def _entity_states(b2_payload):
    return [s for s in (b2_payload or {}).get("subjects") or []
            if isinstance(s, dict) and s.get("classification") == "entity_state"]


def unmatched_value_sites(b2_payload, e1_payload):
    """b2 의 어느 값과도 맞추지 못한 e1 항목. **버리지 않고 보류로 넘긴다.**

    자리 인용은 살아 있으므로 사람이 값을 이어 주면 살아난다.
    """
    matched = _index_by_value(b2_payload, e1_payload)
    known = [s.get("state") for s in _entity_states(b2_payload)]
    out = []
    for vs in (e1_payload or {}).get("value_sites") or []:
        if not isinstance(vs, dict):
            continue
        value = vs.get("value") if isinstance(vs.get("value"), dict) else {}
        expr = value.get("code_expression")
        if not isinstance(expr, str) or matched.get(expr) is not None:
            continue
        sites = list(vs.get("writes") or []) + list(vs.get("reads") or [])
        out.append({"value": expr,
                    "why": f"{expr!r} 을 앞 단계의 상태 값 {known} 중 어느 것과도 "
                           f"맞추지 못했다",
                    "sites": sites})
    return out


def merge_sites(b2_payload, e1_payload):
    """b2 씨앗과 e1 보충을 합친 자리 목록.

    같은 행을 둘 다 냈으면 하나로 합치고 **씨앗 쪽을 남긴다**(먼저 온 것이 출처다).
    e1 의 corrections 가 가리킨 자리는 목록에서 뺀다.
    """
    subjects = _entity_states(b2_payload)
    matched = _index_by_value(b2_payload, e1_payload)
    by_index = {}
    for vs in (e1_payload or {}).get("value_sites") or []:
        if not isinstance(vs, dict):
            continue
        value = vs.get("value") if isinstance(vs.get("value"), dict) else {}
        expr = value.get("code_expression")
        idx = matched.get(expr) if isinstance(expr, str) else None
        if idx is not None:
            by_index[idx] = vs

    out = []
    for vi, subject in enumerate(subjects):
        expr = subject.get("state")
        if not isinstance(expr, str) or not expr.strip():
            continue
        extra = by_index.get(vi) or {}
        value = extra.get("value") if isinstance(extra.get("value"), dict) else {}
        declaration = value.get("declaration")
        if declaration is None:
            ident = subject.get("identity_evidence") or []
            declaration = ident[0] if ident else None

        entry = {"value": expr, "declaration": declaration, "writes": [], "reads": []}
        for field, seeds, added in (
                ("writes", subject.get("state_evidence"), extra.get("writes")),
                ("reads", subject.get("consumption_evidence"), extra.get("reads"))):
            letter = "w" if field == "writes" else "r"
            seen, bucket = set(), []
            for raws, origin in ((seeds, "b2"), (added, "e1")):
                for raw in raws or []:
                    s = _site(raw, f"e1:v{vi}:{letter}{len(bucket)}", origin)
                    if s is None or _key(s) in seen:
                        continue
                    seen.add(_key(s))
                    bucket.append(s)
            entry[field] = bucket

        dropped = {c.get("site_id") for c in extra.get("corrections") or []
                   if isinstance(c, dict)}
        if dropped:
            entry["writes"] = [s for s in entry["writes"] if s["site_id"] not in dropped]
            entry["reads"] = [s for s in entry["reads"] if s["site_id"] not in dropped]
        out.append(entry)
    return out


def _actors(e2_payload):
    out = {}
    for a in (e2_payload or {}).get("site_actors") or []:
        if not isinstance(a, dict) or not isinstance(a.get("site_id"), str):
            continue
        name = a.get("entity")
        if not isinstance(name, str) or not name.strip():
            continue        # unresolved. 주체를 못 정한 자리다
        out[a["site_id"]] = {"entity": name, "attribute": a.get("attribute")}
    return out


def _evidence(site):
    return {"file_path": site["file_path"], "start_line": site["start_line"],
            "end_line": site["end_line"], "quote": site["quote"]}


def derive(sites, e2_payload):
    """자리 목록과 주체 판정에서 방향 있는 결합을 만든다.

    값마다 `쓰는 자리 × 읽는 자리` 를 전부 짝짓는다. 주체가 다르면 결합, 같으면
    상태 갱신, 하나라도 미해결이면 보류다. 같은 (출발·속성, 도착·속성, 값) 은
    결합 하나로 합치고 기여한 자리를 전부 단다.
    """
    actors = _actors(e2_payload)
    merged, order = {}, []
    internal, pending = [], []

    for entry in sites or []:
        value = entry.get("value")
        declaration = entry.get("declaration")
        for w in entry.get("writes") or []:
            for r in entry.get("reads") or []:
                aw, ar = actors.get(w["site_id"]), actors.get(r["site_id"])
                if aw is None or ar is None:
                    missing = [s["site_id"] for s, a in ((w, aw), (r, ar)) if a is None]
                    pending.append({"value": value,
                                    "write_site_id": w["site_id"],
                                    "read_site_id": r["site_id"],
                                    "why": f"주체 미해결: {missing}"})
                    continue
                if aw["entity"] == ar["entity"]:
                    internal.append({"entity": aw["entity"], "value": value,
                                     "write_site_id": w["site_id"],
                                     "read_site_id": r["site_id"]})
                    continue
                key = (aw["entity"], aw["attribute"], ar["entity"], ar["attribute"], value)
                if key not in merged:
                    merged[key] = {
                        "source": {"entity": aw["entity"], "attribute": aw["attribute"],
                                   "symbol": None},
                        "target": {"entity": ar["entity"], "attribute": ar["attribute"],
                                   "symbol": None},
                        "payload": {"kind": "shared_state", "code_expression": value,
                                    "meaning": None},
                        "mechanism": None,
                        "derived_from": [],
                        "evidence": [declaration] if declaration else [],
                        "_lines": set(),
                    }
                    order.append(key)
                item = merged[key]
                for s in (w, r):
                    if s["site_id"] not in item["derived_from"]:
                        item["derived_from"].append(s["site_id"])
                    line = (s["file_path"], s["start_line"])
                    if line not in item["_lines"]:
                        item["_lines"].add(line)
                        item["evidence"].append(_evidence(s))

    couplings = []
    for key in order:
        item = merged[key]
        lines = sorted(str(ln) for _, ln in item.pop("_lines"))
        item["mechanism"] = (f"공유 상태 {key[4]} 를 통해 전달된다. "
                             f"기여한 행: {', '.join(lines)}")
        couplings.append(item)
    return {"couplings": couplings, "internal_updates": internal,
            "unresolved_pairs": pending}
