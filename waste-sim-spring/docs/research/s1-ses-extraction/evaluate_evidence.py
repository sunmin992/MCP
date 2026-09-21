"""Offline evaluation only. Never imported by the extractor or sent to the model.

Reference must be a separately reviewed evidence-v1 artifact for the same source.
Name alignment is supplied explicitly by the evaluator, not learned from predictions.
Exact condition-expression matching is syntactic agreement, not semantic equivalence.
"""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path

from sesx.snapshot import digest


def projection(doc, aliases=None):
    aliases = aliases or {}
    def name(s):
        return aliases.get(s, s)
    alive = lambda x: x.get("status") not in ("rejected", "superseded")
    ents = {e["id"]: name(e["name"]) for e in doc.get("entities", []) if alive(e)}
    attrs = {a["id"]: (ents.get(a.get("entity_id")), name(a["name"]))
             for a in doc.get("attributes", []) if alive(a)}
    result = {k: set() for k in ("stateful_entities", "structural_nodes", "attributes",
                               "decompositions", "couplings", "activation")}
    for e in doc.get("entities", []):
        if alive(e):
            key = "stateful_entities" if e.get("kind") == "stateful" else "structural_nodes"
            result[key].add((ents[e["id"]], e.get("kind"), e.get("scope")))
    result["attributes"] = set(attrs.values())
    targets = {}
    for d in doc.get("decompositions", []):
        if not alive(d):
            continue
        key = (ents.get(d.get("parent_entity_id")), d.get("kind"),
               tuple(sorted(ents.get(m.get("entity_id"), "<unresolved>") for m in d.get("members", []))),
               d.get("label"))
        result["decompositions"].add(key)
        targets[d.get("activation_id")] = ("decomposition", key)
    def endpoint(box):
        # IDs are local; compare named entity/attribute identities, preserving direction.
        attr = attrs.get(box.get("attribute_id"))
        return (ents.get(box.get("entity_id")), attr[1] if attr else None, box.get("symbol"))
    for c in doc.get("couplings", []):
        if not alive(c):
            continue
        p = c.get("payload") or {}
        key = (endpoint(c.get("source") or {}), endpoint(c.get("target") or {}),
               p.get("kind"), p.get("code_expression"))
        result["couplings"].add(key)
        targets[c.get("activation_id")] = ("coupling", key)
    for a in doc.get("activation", []):
        if not alive(a) or a.get("state") == "unknown" or a["id"] not in targets:
            continue
        # Complete vs incomplete and AND vs OR cannot collapse to one score.
        result["activation"].add((targets[a["id"]], a.get("state"), a.get("coverage"),
                                  a.get("combination"), tuple(sorted(
                                      c["expression"] for c in a.get("clauses", [])))))
    return result


def prf(pred, gold):
    hit = len(pred & gold)
    p = hit / len(pred) if pred else None
    r = hit / len(gold) if gold else None
    f = 2 * hit / (len(pred) + len(gold)) if pred or gold else None
    return {"matched": hit, "predicted": len(pred), "reference": len(gold),
            "precision": p, "recall": r, "f1": f,
            "missing": sorted(map(repr, gold - pred)), "extra": sorted(map(repr, pred - gold))}


def compare(doc, reference, aliases=None):
    if reference.get("review_status", {}).get("status") != "reviewed":
        raise ValueError("reference needs an independent expert review")
    a, b = doc.get("source_snapshot", {}), reference.get("source_snapshot", {})
    source_key = lambda s: sorted((f["path"], f["sha256"]) for f in s.get("files", []))
    if not source_key(a) or source_key(a) != source_key(b):
        raise ValueError("source files/hashes differ; scores are not comparable")
    pred, gold = projection(doc, aliases), projection(reference, aliases)
    return {"evaluation_schema": "sesx-evaluation/1.0", "artifact_id": doc["artifact_id"],
            "reference_sha256": digest(reference), "alignment_sha256": digest(aliases or {}),
            "metrics": {k: prf(pred[k], gold[k]) for k in gold},
            "unknown_activation_count": sum(a.get("state") == "unknown" and
                a.get("status") not in ("rejected", "superseded") for a in doc.get("activation", [])),
            "unresolved_count": len(doc.get("unresolved", [])),
            "field_evidence_failures": next((c.get("item_ids") for c in doc.get("validation_results", {}).get(
                "checks", []) if c.get("check_id") == "FIELD_EVIDENCE"), None),
            "limitations": ["exact structural agreement, not semantic equivalence",
                            "location-valid evidence is not semantic precision",
                            "small repeated runs estimate variation, not independent codebase generalization"]}


def stability(docs, aliases=None):
    projected = [projection(d, aliases) for d in docs]
    rows = []
    for i, j in itertools.combinations(range(len(docs)), 2):
        row = {"pair": [docs[i]["artifact_id"], docs[j]["artifact_id"]], "jaccard": {}}
        for k in projected[i]:
            a, b = projected[i][k], projected[j][k]
            row["jaccard"][k] = len(a & b) / len(a | b) if a | b else None
        rows.append(row)
    return rows


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--reference", required=True)
    ap.add_argument("--artifacts", nargs="+", required=True)
    ap.add_argument("--alignment")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    read = lambda p: json.loads(Path(p).read_text(encoding="utf-8"))
    ref, docs = read(a.reference), [read(p) for p in a.artifacts]
    aliases = read(a.alignment) if a.alignment else {}
    result = {"runs": [compare(d, ref, aliases) for d in docs], "stability": stability(docs, aliases)}
    with Path(a.out).open("x", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)
        f.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
