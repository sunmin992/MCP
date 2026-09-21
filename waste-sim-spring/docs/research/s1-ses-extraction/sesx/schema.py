"""Machine-readable Draft 2020-12 artifact schema; semantic checks live elsewhere."""
from __future__ import annotations

import json
from pathlib import Path

from . import contract


def obj(properties, required=()):
    return {"type": "object", "properties": properties, "required": list(required)}


STRING = {"type": "string", "minLength": 1}
NULL_STRING = {"type": ["string", "null"]}
IDS = {"type": "array", "items": STRING, "uniqueItems": True}
BASE = {"id": STRING, "status": {"enum": ["proposed", "unresolved", "accepted", "rejected", "superseded"]},
        "evidence_ids": IDS, "uncertainty": {"type": ["object", "null"]}}
BOX = obj({"status": {"enum": ["known", "unknown", "explicit_null"]},
           "value": {}, "evidence_ids": IDS}, ("status", "value", "evidence_ids"))
ENDPOINT = obj({"entity_id": STRING, "attribute_id": NULL_STRING, "symbol": NULL_STRING,
                "evidence_ids": IDS}, ("entity_id",))
DEFS = {
    "entities": obj({**BASE, "name": STRING,
                     "kind": {"enum": list(contract.ENTITY_KIND)},
                     "scope": {"enum": list(contract.ENTITY_SCOPE)}},
                    ("id", "status", "evidence_ids", "name", "kind", "scope")),
    "attributes": obj({**BASE, "name": STRING, "entity_id": STRING,
                       "unit": BOX, "default": BOX, "range": BOX},
                      ("id", "status", "evidence_ids", "name", "entity_id", "unit", "default", "range")),
    "decompositions": obj({**BASE, "kind": {"enum": ["ASPECT", "SPEC", "MULTI"]},
                           "parent_entity_id": STRING, "activation_id": STRING,
                           "members": {"type": "array", "minItems": 1,
                                       "items": obj({"entity_id": STRING, "evidence_ids": IDS},
                                                    ("entity_id", "evidence_ids"))},
                           "selection": {"type": ["object", "null"]},
                           "multiplicity": obj({"evidence_ids": IDS})},
                          ("id", "status", "evidence_ids", "kind", "parent_entity_id",
                           "members", "activation_id")),
    "couplings": obj({**BASE, "source": ENDPOINT, "target": ENDPOINT,
                      "payload": obj({"kind": {"enum": ["value", "event", "shared_state", "unknown"]},
                                      "code_expression": NULL_STRING, "evidence_ids": IDS}, ("kind",)),
                      "activation_id": STRING},
                     ("id", "status", "evidence_ids", "source", "target", "payload", "activation_id")),
    "activation": obj({**BASE, "state": {"enum": ["known", "unconditional", "unknown"]},
                       "coverage": {"enum": ["complete", "incomplete"]},
                       "combination": {"enum": ["AND", "OR", None]},
                       "clauses": {"type": "array", "items": obj(
                           {"expression": STRING, "symbol": NULL_STRING, "evidence_ids": IDS},
                           ("expression", "evidence_ids"))}},
                      ("id", "status", "evidence_ids", "state", "coverage", "combination", "clauses")),
    "observations": obj({**BASE, "name": STRING, "about": {"type": "object"}},
                        ("id", "status", "evidence_ids", "name")),
    "evidence": obj({"id": STRING, "snapshot_id": STRING, "file_path": STRING,
                     "file_sha256": {"type": ["string", "null"], "pattern": "^[a-f0-9]{64}$"},
                     "start_line": {"type": "integer", "minimum": 1},
                     "end_line": {"type": "integer", "minimum": 1}, "quote": STRING,
                     "symbol": NULL_STRING,
                     "supports": {"type": "array", "minItems": 1,
                                  "items": obj({"item_id": STRING, "field": STRING}, ("item_id", "field"))}},
                    ("id", "snapshot_id", "file_path", "start_line", "end_line", "quote", "supports")),
    "unresolved": obj({"id": STRING, "item_ids": IDS, "origin_raw": {},
                       "reason_code": STRING, "resolution_needed": STRING,
                       # 검토가 닫은 기록. 보류 자체는 지우지 않는다.
                       "resolution": obj({"outcome": {"enum": list(contract.RESOLUTIONS)},
                                          "reason": STRING, "by": NULL_STRING,
                                          "reviewer": NULL_STRING, "at": {}},
                                         ("outcome", "reason"))},
                      ("id", "item_ids", "reason_code")),
    "provenance": obj({"id": STRING, "raw_id": NULL_STRING, "origin_raw": {},
                       "decision": STRING, "input_item_ids": IDS, "output_item_ids": IDS},
                      ("id", "decision", "input_item_ids", "output_item_ids")),
}
SCHEMA = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "$id": "urn:sesx:artifact:evidence-v1",
    **obj({"schema_version": {"const": "ses-extraction/1.1"}, "artifact_id": STRING,
           "parent_artifact_id": NULL_STRING,
           "source_snapshot": obj({"snapshot_id": NULL_STRING, "files": {"type": "array"},
                                   "selection_rule": {"type": "object"}}),
           "extraction_run": {"type": "object"}, "structure": {"type": "object"},
           "validation_results": {"type": "object"}, "review_status": {"type": "object"},
           **{k: {"type": "array", "items": {"$ref": f"#/$defs/{k}"}} for k in DEFS}},
          ("schema_version", "artifact_id", "parent_artifact_id", "source_snapshot",
           "extraction_run", "structure", "validation_results", "review_status", *DEFS)),
    "$defs": DEFS,
}


def errors(doc):
    from jsonschema import Draft202012Validator
    return [{"path": "/".join(str(x) for x in e.absolute_path), "message": e.message}
            for e in Draft202012Validator(SCHEMA).iter_errors(doc)]


if __name__ == "__main__":
    Path(__file__).with_name("artifact.schema.json").write_text(
        json.dumps(SCHEMA, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
