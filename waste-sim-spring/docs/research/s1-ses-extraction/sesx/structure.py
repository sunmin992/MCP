# -*- coding: utf-8 -*-
"""구조 검사. 분해 그래프와 결합 그래프를 **따로** 본다.

결합의 되먹임(A -> B -> A)은 정상이다. 분해의 순환은 오류다. 둘을 한 그래프로 합치면
정상 되먹임을 순환으로 오판한다.

루트 후보는 '부모로만 등장하는 노드'가 아니라 **분해 간선의 진입 차수가 0인 노드**다.
분해가 하나도 없는 단일 노드도 후보다.
"""
from __future__ import annotations


def _decomp_edges(doc):
    edges = []
    for d in doc.get("decompositions") or []:
        if d.get("status") in ("rejected", "superseded"):
            continue
        p = d.get("parent_entity_id")
        for m in d.get("members") or []:
            c = (m or {}).get("entity_id")
            if p and c:
                edges.append((p, c, d.get("id")))
    return edges


def _coupling_edges(doc):
    out = []
    for c in doc.get("couplings") or []:
        if c.get("status") in ("rejected", "superseded"):
            continue
        s = (c.get("source") or {}).get("entity_id")
        t = (c.get("target") or {}).get("entity_id")
        if s and t:
            out.append((s, t, c.get("id")))
    return out


def _cycles(nodes, edges):
    adj = {}
    for a, b, _ in edges:
        adj.setdefault(a, []).append(b)
    WHITE, GREY, BLACK = 0, 1, 2
    color = {n: WHITE for n in nodes}
    found, stack = [], []

    def dfs(n):
        color[n] = GREY
        stack.append(n)
        for m in adj.get(n, []):
            if m not in color:
                continue
            if color[m] == GREY:
                found.append(stack[stack.index(m):] + [m])
            elif color[m] == WHITE:
                dfs(m)
        stack.pop()
        color[n] = BLACK

    for n in sorted(nodes):
        if color[n] == WHITE:
            dfs(n)
    return found


def analyze(doc):
    ents = [e.get("id") for e in doc.get("entities") or [] if e.get("id")
            and e.get("status") not in ("rejected", "superseded")]
    nodes = set(ents)
    dec = [(a, b, d) for a, b, d in _decomp_edges(doc) if a in nodes and b in nodes]

    indeg = {n: 0 for n in nodes}
    parents = {}
    for a, b, d in dec:
        indeg[b] = indeg.get(b, 0) + 1
        parents.setdefault(b, set()).add(a)

    roots = sorted(n for n in nodes if indeg.get(n, 0) == 0)
    multi_parent = sorted(n for n, ps in parents.items() if len(ps) > 1)
    cycles = _cycles(nodes, dec)
    self_refs = sorted({d for a, b, d in dec if a == b})

    selected = roots[0] if len(roots) == 1 else None

    reachable = set()
    if selected:
        adj = {}
        for a, b, _ in dec:
            adj.setdefault(a, []).append(b)
        stack = [selected]
        while stack:
            n = stack.pop()
            if n in reachable:
                continue
            reachable.add(n)
            stack.extend(adj.get(n, []))
    unreachable = sorted(nodes - reachable) if selected else sorted(nodes)

    isolated = sorted(n for n in nodes
                      if indeg.get(n, 0) == 0 and not any(a == n for a, _, _ in dec))

    coup = [(a, b, c) for a, b, c in _coupling_edges(doc) if a in nodes and b in nodes]
    coupling_cycles = _cycles(nodes, coup)

    return {
        "root_candidate_ids": roots,
        "selected_root_id": selected,
        "multi_parent_entity_ids": multi_parent,
        "decomposition_cycles": cycles,
        "self_reference_decomposition_ids": self_refs,
        "unreachable_entity_ids": [] if selected is None else unreachable,
        "isolated_entity_ids": isolated,
        "coupling_cycles": coupling_cycles,
        "coupling_cycle_note": "결합의 되먹임은 오류가 아니다. 참고로만 적는다.",
        "status": "checked",
    }


def apply(doc):
    """검사 결과를 structure 에 넣는다.

    검토자가 근거와 함께 고른 루트(`reviewer_root`)가 있으면 그것을 존중한다.
    코드가 스스로 후보 중 하나를 고르는 일은 없다.
    """
    prev = doc.get("structure") or {}
    a = analyze(doc)
    selected = a["selected_root_id"]
    reviewer = prev.get("reviewer_root")
    if reviewer and reviewer.get("entity_id") in a["root_candidate_ids"]:
        selected = reviewer["entity_id"]
        a["selected_root_id"] = selected
        a = {**a, **_reachability(doc, selected)}
    doc["structure"] = {
        "root_candidate_ids": a["root_candidate_ids"],
        "selected_root_id": selected,
        "reviewer_root": reviewer,
        "status": "checked",
        "analysis": a,
    }
    return doc


def _reachability(doc, root):
    ents = {e.get("id") for e in doc.get("entities") or [] if e.get("id")
            and e.get("status") not in ("rejected", "superseded")}
    adj = {}
    for a, b, _ in _decomp_edges(doc):
        adj.setdefault(a, []).append(b)
    seen, stack = set(), [root]
    while stack:
        n = stack.pop()
        if n in seen:
            continue
        seen.add(n)
        stack.extend(adj.get(n, []))
    return {"unreachable_entity_ids": sorted(ents - seen)}
