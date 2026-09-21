# -*- coding: utf-8 -*-
"""정답지(ref-v8)를 계약 모양 SES 로 옮기는 **시험용** 어댑터.

여기 두는 이유가 있다. `sesx/` 안의 어떤 모듈도 정답지를 읽어서는 안 된다 —
`input_policy` 가 `reference-ses.json` 을 평가 자료로 차단하는 것과 같은 이유다.
이 어댑터는 시험에서만 쓰인다. 추출 경로는 이 파일을 import 하지 않는다.

정답지는 이름을 열쇠로 쓰는 사전이고 계약은 ID 를 쓴다. 옮기는 동안 구조를 고치지
않는다 — 이름만 ID 로 바꾼다. 그래야 "PES 검사기가 실제 SES 하나를 통과시키는가"를
정답지로 잴 수 있다.
"""
from __future__ import annotations

import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from sesx import contract  # noqa: E402

KIT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF_PATH = os.path.join(KIT, "reference-ses.json")

KIND = {"aspect": "ASPECT", "spec": "SPEC", "multi": "MULTI"}

# 정답지에는 활성 조건 항목이 없다. 지어내지 않는다 — 전부 하나의 `unknown` 을 가리킨다.
UNKNOWN_ACTIVATION = {
    "id": "AC0", "status": "proposed", "evidence_ids": [],
    "state": "unknown", "coverage": "incomplete", "combination": None, "clauses": [],
}


def _box():
    return {"status": "unknown", "value": None, "evidence_ids": []}


def load(path=REF_PATH, artifact_id="ref-v8"):
    with io.open(path, encoding="utf-8") as f:
        raw = json.load(f)
    names = sorted(raw["entities"])
    eid = {n: f"E{i + 1}" for i, n in enumerate(names)}

    doc = contract.new_artifact(artifact_id)
    doc["activation"] = [dict(UNKNOWN_ACTIVATION)]

    aid, attr_of = {}, {}
    for n in names:
        body = raw["entities"][n]
        doc["entities"].append({
            "id": eid[n], "status": "proposed", "evidence_ids": [], "name": n,
            "kind": "whole" if n == raw["root"] else "unknown",
            "scope": "simulation_target",
        })
        for a in body.get("attrs") or []:
            k = f"A{len(aid) + 1}"
            aid[(n, a)] = k
            attr_of.setdefault(n, {})[a] = k
            doc["attributes"].append({
                "id": k, "status": "proposed", "evidence_ids": [], "name": a,
                "entity_id": eid[n], "unit": _box(), "default": _box(), "range": _box(),
            })

    for n in names:
        for d in raw["entities"][n].get("decompositions") or []:
            did = f"D{len(doc['decompositions']) + 1}"
            rec = {
                "id": did, "status": "proposed", "evidence_ids": [],
                "kind": KIND[d["kind"]], "parent_entity_id": eid[n],
                "activation_id": "AC0",
                "members": [{"entity_id": eid[c], "evidence_ids": []} for c in d["children"]],
                "selection": None,
            }
            if rec["kind"] == "SPEC":
                rec["selection"] = {"axis": d.get("name"), "evidence_ids": []}
            if rec["kind"] == "MULTI":
                # 정답지는 반복 횟수를 적지 않는다. 채워 넣지 않는다 — PES 요청이 정한다.
                rec["multiplicity"] = {"status": "unknown", "value": None, "evidence_ids": []}
            doc["decompositions"].append(rec)

    def endpoint(ref):
        name, _, attr = str(ref).partition(".")
        if name not in eid:
            return None
        return {"entity_id": eid[name],
                "attribute_id": attr_of.get(name, {}).get(attr),
                "evidence_ids": []}

    for i, c in enumerate(raw.get("couplings") or [], start=1):
        s, t = endpoint(c["from"]), endpoint(c["to"])
        if not s or not t:
            continue
        doc["couplings"].append({
            "id": f"C{i}", "status": "proposed", "evidence_ids": [],
            "source": s, "target": t,
            "payload": {"kind": "unknown", "code_expression": c.get("mechanism"),
                        "evidence_ids": []},
            "activation_id": "AC0",
        })

    parents = {m["entity_id"] for d in doc["decompositions"] for m in d["members"]}
    doc["structure"] = {
        "root_candidate_ids": sorted(e["id"] for e in doc["entities"]
                                     if e["id"] not in parents),
        "selected_root_id": None,      # 루트는 PES 가 정한다
        "status": "not_checked",
    }
    return doc, eid, raw
