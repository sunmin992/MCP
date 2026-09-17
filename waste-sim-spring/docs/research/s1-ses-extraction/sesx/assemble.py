# -*- coding: utf-8 -*-
"""결정적 조립. LLM이 낸 후보를 계약 산출물로 옮긴다.

무엇도 조용히 사라지지 않는다. 판정 기록(provenance)이 원시 후보 하나마다 하나씩 생긴다.
개수 등식이 아니라 **역추적 가능성**이 불변식이다 — 병합·분할이 있으면 개수는 맞지 않지만
원시 후보에서 최종 항목으로 가는 길은 늘 있어야 한다.

  kept      그대로 항목이 되었다
  merged    같은 것을 두 이름으로 부른 것을 하나로 합쳤다 (input 여럿 -> output 하나)
  split     하나를 둘로 나눴다 (input 하나 -> output 여럿)
  held      확정하지 못했다. unresolved 로 갔고 원문은 origin_raw 에 있다
  rejected  검토자가 버렸다 (조립 단계에서는 쓰지 않는다)

조립은 루트를 고르지 않는다. 자기 참조를 몰래 빼지 않는다. 활성 조건을 지어내지 않는다.
"""
from __future__ import annotations

import re
import unicodedata

from . import contract, composition, shape

_STRIP = re.compile(r"[\s·\.\-_/()\[\]{}]+")


def norm(name):
    return _STRIP.sub("", unicodedata.normalize("NFKC", str(name or ""))).lower()


class _Ids:
    def __init__(self):
        self.n = {}

    def next(self, prefix):
        self.n[prefix] = self.n.get(prefix, 0) + 1
        return f"{prefix}{self.n[prefix]}"


def _obj(x):
    """객체가 아닌 후보 원소는 그대로 돌려주지 않는다. None 이면 부르는 쪽이 보류한다.

    `x or {}` 로는 모자란다 — 비어 있지 않은 문자열은 그대로 통과해 `.get` 에서 죽는다.
    실제로 그렇게 죽은 적이 있다(jn-T2-4, c단계가 속성 하나를 문자열로 냈다).
    """
    return x if isinstance(x, dict) else None


class Assembler:
    def __init__(self, snapshot_id, no_new_entity_stages=()):
        self.snapshot_id = snapshot_id
        # 이 단계들은 새 개체를 만들 수 없다. 어기면 버리는 것이 아니라 보류로 남긴다 —
        # 무엇을 만들려 했는지가 검토 자료다.
        self.no_new_entity_stages = tuple(no_new_entity_stages)
        self.ids = _Ids()
        self.doc = None
        self.entity_by_norm = {}
        self.attr_by_key = {}

    # ------------------------------------------------------------ 기록
    def _prov(self, stage, raw_id, origin_raw, decision, reason,
              inputs=None, outputs=None):
        self.doc["provenance"].append({
            "id": self.ids.next("P"),
            "origin_stage": stage,
            "raw_id": raw_id,
            "origin_raw": origin_raw,
            "decision": decision,
            "reason": reason,
            "input_item_ids": list(inputs or []),
            "output_item_ids": list(outputs or []),
        })

    def _near_names(self, wanted):
        """확정된 개체 중 이름이 겹치는 것. **자동으로 잇지 않는다** — 검토 힌트일 뿐이다.

        jn-T2c-1 에서 c 단계가 `수거 지점(CollectionSite)` 이라 적어 17건이 미해결로 죽었다.
        정규화로 조용히 붙이면 괄호로만 다른 두 개체를 같다고 볼 위험이 있으므로 적어만 둔다.
        """
        w = norm(wanted)
        if not w:
            return []
        out = []
        for key, eid in self.entity_by_norm.items():
            if key and (key in w or w in key):
                ent = next(x for x in self.doc["entities"] if x["id"] == eid)
                out.append(f"{ent['name']}({eid})")
        return out

    def _hold(self, stage, raw_id, origin_raw, reason_code, explanation,
              resolution="승인 스냅샷에서 다시 확인한다.", item_ids=None, near=None):
        u = {"id": self.ids.next("U"),
             "item_ids": list(item_ids or []),
             "origin_stage": stage,
             "raw_id": raw_id,
             "origin_raw": origin_raw,
             "reason_code": reason_code,
             "explanation": explanation,
             "resolution_needed": resolution}
        hints = self._near_names(near) if near else []
        if hints:
            u["near_confirmed_names"] = hints
            u["resolution_needed"] = (resolution + f" 이름이 겹치는 확정 개체: {hints}")
        self.doc["unresolved"].append(u)
        return u["id"]

    def _evidence(self, raws, item_id, field):
        out = []
        for r in raws or []:
            if not isinstance(r, dict) or not r.get("file_path"):
                continue
            eid = self.ids.next("EV")
            self.doc["evidence"].append({
                "id": eid,
                "snapshot_id": self.snapshot_id,
                "file_path": r.get("file_path"),
                "file_sha256": r.get("file_sha256"),
                "start_line": r.get("start_line"),
                "end_line": r.get("end_line", r.get("start_line")),
                "symbol": r.get("symbol"),
                "quote": r.get("quote"),
                "supports": [{"item_id": item_id, "field": field}],
                "verification": {"file": "not_checked", "range": "not_checked",
                                 "quote": "not_checked", "symbol": "not_checked",
                                 "verdict": "not_checked", "details": {}},
            })
            out.append(eid)
        return out

    def _value_box(self, raw, item_id, field):
        if not isinstance(raw, dict) or raw.get("value") is None:
            return {"status": "unknown", "value": None, "evidence_ids": []}
        ev = self._evidence(raw.get("evidence"), item_id, field)
        if not ev:
            # 근거 없는 값은 확정하지 않는다. 코드에 리터럴이 있다는 주장만으로는 부족하다.
            return {"status": "unknown", "value": None, "evidence_ids": [],
                    "claimed_value": raw.get("value")}
        return {"status": raw.get("status", "known"), "value": raw.get("value"),
                "evidence_ids": ev}

    # ------------------------------------------------------------ 조립
    def build(self, stages, artifact_id, extraction_run, source_snapshot):
        """stages: {"a": payload, "b": payload, ...} 또는 {"single": payload}"""
        self.tree_mode = "g" in stages or extraction_run.get("strategy") == "T2-tree"
        self.doc = contract.new_artifact(artifact_id, extraction_run, source_snapshot)
        for stage, payload in stages.items():
            self._observations(stage, payload)
            self._disputes(stage, payload)
            self._entities(stage, payload)
        for stage, payload in stages.items():
            self._subjects(stage, payload)
        for stage, payload in stages.items():
            self._attributes(stage, payload)
        for stage, payload in stages.items():
            self._decompositions(stage, payload)
            self._couplings(stage, payload)
            self._flow_records(stage, payload)
        for stage, payload in stages.items():
            self._activation_updates(stage, payload)
        self._require_activation()
        self._mark_no_evidence()
        return self.doc

    # 관찰은 항목이 아니다. 기록으로만 남겨 전파를 추적한다.
    def _observations(self, stage, payload):
        for i, o in enumerate(payload.get("observations") or []):
            self._prov(stage, f"{stage}:observation:{i}", o, "held",
                       "관찰이다. 개체 확정 단계에서 다시 판단한다.")

    def _disputes(self, stage, payload):
        """뒤 단계가 앞 단계를 반박한 기록. 자동으로 반영하지 않고 검토로 넘긴다."""
        for i, d in enumerate(payload.get("disputes") or []):
            raw_id = f"{stage}:dispute:{i}"
            self._hold(stage, raw_id, d, "kind_conflict",
                       f"뒤 단계의 이의 제기: {(d or {}).get('claim')!r}",
                       "검토자가 앞 단계 항목과 대조해 판정한다.")
            self._prov(stage, raw_id, d, "held", "이의 제기")

    def _entities(self, stage, payload):
        for i, e in enumerate(payload.get("entities") or []):
            raw_id = f"{stage}:entity:{i}"
            if _obj(e) is None:
                self._hold(stage, raw_id, e, "schema_violation", "개체 후보가 객체가 아니다")
                self._prov(stage, raw_id, e, "held", "객체가 아님")
                continue
            original_entity = e
            name = e.get("name")
            if not isinstance(name, str) or not name.strip():
                self._hold(stage, raw_id, e, "schema_violation", "이름이 없는 개체 후보")
                self._prov(stage, raw_id, e, "held", "이름 없음")
                continue
            if self.tree_mode:
                error = (composition.entity_error(e) if stage == "g"
                         else "T2-tree의 직접 개체 후보는 g 단계에서만 허용한다")
                if error:
                    self._hold(stage, raw_id, e, "no_evidence", error)
                    self._prov(stage, raw_id, e, "held", error)
                    continue
                e = dict(e)
                e["evidence"] = e["identity_evidence"] + e["composition_evidence"]
            key = norm(name)
            if key not in self.entity_by_norm and stage in self.no_new_entity_stages:
                self._hold(stage, raw_id, e, "schema_violation",
                           f"{stage} 단계는 새 개체를 만들 수 없다: {name!r}",
                           "상위 개체가 정말 필요한지 검토자가 판정한다.")
                self._prov(stage, raw_id, e, "held", "새 개체 금지 단계")
                continue
            if key in self.entity_by_norm:
                eid = self.entity_by_norm[key]
                ent = next(x for x in self.doc["entities"] if x["id"] == eid)
                ent.setdefault("alternate_names", [])
                if name not in ent["alternate_names"] and name != ent["name"]:
                    ent["alternate_names"].append(name)
                ent["evidence_ids"] += self._evidence((e or {}).get("evidence"), eid, "existence")
                self._prov(stage, raw_id, original_entity, "merged",
                           f"정규화 이름이 같다: {ent['name']}", [eid], [eid])
                continue
            eid = self.ids.next("E")
            ent = {
                "id": eid, "name": name,
                "kind": (e or {}).get("kind") if (e or {}).get("kind") in contract.ENTITY_KIND
                        else "unknown",
                "scope": (e or {}).get("scope") if (e or {}).get("scope") in contract.ENTITY_SCOPE
                         else "unknown",
                "why_entity": (e or {}).get("why_entity"),
                "alternate_names": [],
                "evidence_ids": [], "status": "proposed",
            }
            self.doc["entities"].append(ent)
            self.entity_by_norm[key] = eid
            ent["evidence_ids"] = self._evidence((e or {}).get("evidence"), eid, "existence")
            self._prov(stage, raw_id, original_entity, "kept", "개체로 받았다", [], [eid])

    def _subjects(self, stage, payload):
        """상태값 하나마다 '무엇의 상태인가'를 잇는다. **잇지 못하면 개체를 만들지 않는다.**

        코드가 확인하는 것은 연결의 **형식**이다 — 세 근거가 서로 다른 자리를 가리키며 모두
        있는가. 그 근거가 정말 소유를 보이는지는 근거 검증과 사람이 본다. 모델이
        `entity_state` 라고 주장해도 형식이 갖춰지지 않으면 보류한다.
        """
        CLASSES = ("entity_state", "value", "activity", "execution_machinery", "unresolved")
        for i, s in enumerate(payload.get("subjects") or []):
            raw_id = f"{stage}:subject:{i}"
            if _obj(s) is None:
                self._hold(stage, raw_id, s, "schema_violation", "상태 후보가 객체가 아니다")
                self._prov(stage, raw_id, s, "held", "객체가 아님")
                continue
            cls = s.get("classification")
            state = s.get("state")
            owner = s.get("owner_candidate")
            if cls not in CLASSES:
                self._hold(stage, raw_id, s, "schema_violation", f"분류값 {cls!r}")
                self._prov(stage, raw_id, s, "held", "분류값 불명")
                continue
            if cls == "value":
                # 관측값은 **개체 소유자를 요구하지 않는다.** 결과 저장 구조라는 이유로
                # 개체를 만들지 않기 위해서다. 대상·범위·산출 근거를 갖는 별도 기록으로 둔다.
                oid = self.ids.next("O")
                about_raw = _obj(s.get("about")) or {}
                kind = about_raw.get("kind")
                ref = about_raw.get("ref")
                if kind == "entity":
                    eid = self._entity_id(ref)
                    about = ({"kind": "entity", "ref": eid} if eid
                             else {"kind": "name", "ref": ref})
                elif kind in ("axis", "whole", "name"):
                    about = {"kind": kind, "ref": ref}
                else:
                    about = {"kind": "unknown", "ref": ref}
                item = {"id": oid,
                        "name": state if isinstance(state, str) else "",
                        "scope": (s.get("scope") if s.get("scope") in contract.OBSERVATION_SCOPE
                                  else "unknown"),
                        "about": about,
                        "why": s.get("why"),
                        "evidence_ids": [], "status": "proposed"}
                self.doc["observations"].append(item)
                item["evidence_ids"] = (
                    self._evidence(s.get("produced_at_evidence") or s.get("state_evidence"),
                                   oid, "produced_at")
                    + self._evidence(s.get("about_evidence"), oid, "about"))
                if not item["evidence_ids"]:
                    item["status"] = "unresolved"
                    self._hold(stage, raw_id, s, "no_evidence",
                               f"관측 {state!r} 에 산출 근거가 없다", item_ids=[oid])
                if item["scope"] == "unknown" or about["kind"] == "unknown":
                    self._hold(stage, raw_id, s, "unproven_ownership",
                               f"관측 {state!r} 의 대상·범위가 확정되지 않았다",
                               "무엇에 대한 관측인지, 전체인지 분류별인지 확인한다.",
                               item_ids=[oid])
                self._prov(stage, raw_id, s, "kept", "관측값으로 받았다", [], [oid])
                continue
            if cls in ("activity", "execution_machinery"):
                self._hold(stage, raw_id, s, "not_an_entity",
                           f"{state!r} 은 {cls} 로 분류됐다: {s.get('why')!r}",
                           "개체가 아니라면 무엇의 활동·장치인지는 따로 판정한다.")
                self._prov(stage, raw_id, s, "held", f"{cls} 로 분류")
                continue
            if cls == "unresolved":
                self._hold(stage, raw_id, s, "unproven_ownership",
                           f"{state!r} 의 소유자를 정하지 못했다: {s.get('hold_reason')!r}")
                self._prov(stage, raw_id, s, "held", "소유자 미확정")
                continue

            # 소비 근거를 더 요구한다. 색인이 있다는 사실만으로 상태가 되지 않는다 —
            # 그 값이 **다시 읽혀 다음 상태·판정에 영향을 주는 자리**가 있어야 상태다.
            # 결과로만 나가는 값은 집계이며 관측 기록으로 간다(q3-fixa1 에서 peak·byOcc·
            # residualByTruck 가 상태로 잘못 분류됐다).
            missing = [k for k in ("state_evidence", "identity_evidence", "linkage_evidence",
                                   "consumption_evidence")
                       if not s.get(k)]
            if not isinstance(owner, str) or not owner.strip():
                missing.append("owner_candidate")
            if missing:
                self._hold(stage, raw_id, s, "unproven_ownership",
                           f"{state!r} 을 개체의 상태라고 했으나 {missing} 가 없다",
                           "세 근거가 서로 다른 자리를 가리켜야 소유가 성립한다.")
                self._prov(stage, raw_id, s, "held", f"연결 미완성: {missing}")
                continue

            key = norm(owner)
            if key in self.entity_by_norm:
                eid = self.entity_by_norm[key]
                ent = next(x for x in self.doc["entities"] if x["id"] == eid)
                ent["evidence_ids"] += self._evidence(s.get("identity_evidence"), eid, "identity")
                merged = True
            else:
                eid = self.ids.next("E")
                ent = {"id": eid, "name": owner, "kind": "stateful",
                       "scope": "simulation_target", "why_entity": s.get("why"),
                       "alternate_names": [], "evidence_ids": [], "status": "proposed"}
                self.doc["entities"].append(ent)
                self.entity_by_norm[key] = eid
                ent["evidence_ids"] = self._evidence(s.get("identity_evidence"), eid, "identity")
                merged = False

            akey = (eid, norm(state))
            if akey in self.attr_by_key:
                aid = self.attr_by_key[akey]
                self._prov(stage, raw_id, s, "merged", "같은 개체의 같은 상태", [aid], [aid])
                continue
            aid = self.ids.next("A")
            item = {"id": aid, "entity_id": eid, "name": state if isinstance(state, str) else "",
                    "value_type": None,
                    "unit": {"status": "unknown", "value": None, "evidence_ids": []},
                    "default": {"status": "unknown", "value": None, "evidence_ids": []},
                    "range": {"status": "unknown", "value": None, "evidence_ids": []},
                    "evidence_ids": [], "status": "proposed"}
            self.doc["attributes"].append(item)
            self.attr_by_key[akey] = aid
            item["evidence_ids"] = (self._evidence(s.get("state_evidence"), aid, "state_change")
                                    + self._evidence(s.get("linkage_evidence"), aid, "ownership")
                                    + self._evidence(s.get("consumption_evidence"), aid,
                                                     "consumption"))
            self._prov(stage, raw_id, s, "merged" if merged else "kept",
                       f"{state!r} 을 {owner!r} 의 상태로 이었다", [eid], [eid, aid])

    def _entity_id(self, name):
        return self.entity_by_norm.get(norm(name))

    def _kind_of(self, entity_id):
        """확정 개체의 역할. 못 찾으면 unknown — 모양 검사를 미룬다."""
        for e in self.doc["entities"]:
            if e["id"] == entity_id:
                return e.get("kind") or "unknown"
        return "unknown"

    def _attributes(self, stage, payload):
        for i, a in enumerate(payload.get("attributes") or []):
            raw_id = f"{stage}:attribute:{i}"
            if _obj(a) is None:
                self._hold(stage, raw_id, a, "schema_violation", "속성 후보가 객체가 아니다")
                self._prov(stage, raw_id, a, "held", "객체가 아님")
                continue
            eid = self._entity_id(a.get("entity"))
            if not eid:
                self._hold(stage, raw_id, a, "unknown_reference",
                           f"소유 개체 {a.get('entity')!r} 를 찾지 못했다",
                           near=a.get("entity"))
                self._prov(stage, raw_id, a, "held", "소유 개체 미해결")
                continue
            name = a.get("name")
            if not isinstance(name, str) or not name.strip():
                self._hold(stage, raw_id, a, "schema_violation", "이름 없는 속성 후보")
                self._prov(stage, raw_id, a, "held", "이름 없음")
                continue
            key = (eid, norm(name))
            if key in self.attr_by_key:
                aid = self.attr_by_key[key]
                self._prov(stage, raw_id, a, "merged", "같은 개체의 같은 속성", [aid], [aid])
                continue
            aid = self.ids.next("A")
            item = {"id": aid, "entity_id": eid, "name": name,
                    "value_type": a.get("value_type"),
                    "unit": None, "default": None, "range": None,
                    "evidence_ids": [], "status": "proposed"}
            self.doc["attributes"].append(item)
            self.attr_by_key[key] = aid
            item["evidence_ids"] = self._evidence(a.get("evidence"), aid, "existence")
            item["unit"] = self._value_box(a.get("unit"), aid, "unit")
            item["default"] = self._value_box(a.get("default"), aid, "default")
            item["range"] = self._value_box(a.get("range"), aid, "range")
            self._prov(stage, raw_id, a, "kept", "속성으로 받았다", [eid], [aid])

    def _attr_id(self, entity_id, name):
        return self.attr_by_key.get((entity_id, norm(name))) if entity_id and name else None

    def _decompositions(self, stage, payload):
        for i, d in enumerate(payload.get("decompositions") or []):
            raw_id = f"{stage}:decomposition:{i}"
            if _obj(d) is None:
                self._hold(stage, raw_id, d, "schema_violation", "분해 후보가 객체가 아니다")
                self._prov(stage, raw_id, d, "held", "객체가 아님")
                continue
            if self.tree_mode:
                error = composition.decomposition_error(d)
                if error:
                    self._hold(stage, raw_id, d, "no_evidence", error)
                    self._prov(stage, raw_id, d, "held", error)
                    continue
            parent = self._entity_id(d.get("parent"))
            kind = str(d.get("kind", "")).upper()
            members = [m for m in (d.get("members") or []) if isinstance(m, str)]
            if not parent:
                self._hold(stage, raw_id, d, "unknown_reference",
                           f"부모 {d.get('parent')!r} 를 찾지 못했다", near=d.get("parent"))
                self._prov(stage, raw_id, d, "held", "부모 미해결")
                continue
            if kind not in contract.DECOMP_KIND:
                self._hold(stage, raw_id, d, "kind_conflict", f"분해 종류 {d.get('kind')!r}")
                self._prov(stage, raw_id, d, "held", "종류 불명")
                continue
            # 자식이 아직 개체가 아니면 **역할별 근거가 있을 때만** 새로 만든다.
            # 관계를 완성하려고 빈 노드를 지어내는 것은 허용하지 않는다.
            #   SPEC  — 그 자식이 부모의 **하위 유형**임을 보이는 근거
            #   MULTI — 그 자식이 **동일 유형의 반복 인스턴스**임과 범위·개수 근거
            if any(self._entity_id(m) == parent for m in members):
                self._hold(stage, raw_id, d, "self_reference", "부모가 자기 자식으로 들어 있다")
                self._prov(stage, raw_id, d, "held", "자기 참조")
                continue
            if not members:
                self._hold(stage, raw_id, d, "schema_violation", "자식이 없는 분해")
                self._prov(stage, raw_id, d, "held", "자식 없음")
                continue
            member_ev = _obj(d.get("member_evidence")) or {}
            created, missing = [], []
            for m in members:
                raws = member_ev.get(m) if isinstance(member_ev.get(m), list) else None
                if not raws:
                    # 자식이 이미 확정 개체여도 역할 근거를 요구한다. tree_rules.md 가
                    # 그렇게 적어 두었는데 집행되지 않아 자식 7개의 근거가 0건이었다.
                    missing.append(m)
                    continue
                if self._entity_id(m):
                    continue
                if stage in self.no_new_entity_stages or (self.tree_mode and kind == "ASPECT"):
                    missing.append(m)
                    continue
                created.append((m, raws))
            if missing:
                self._hold(stage, raw_id, d, "unknown_reference",
                           f"자식 역할 근거 없음: {missing}",
                           "각 부모-자식 연결의 역할 근거가 필요하다. SPEC 은 하위 유형 근거, "
                           "MULTI 는 반복 인스턴스·범위 근거다.")
                self._prov(stage, raw_id, d, "held", "자식 역할 근거 없음")
                continue
            for m, raws in created:
                cid_e = self.ids.next("E")
                child = {"id": cid_e, "name": m,
                         "kind": "type" if kind == "SPEC" else "stateful",
                         "scope": "simulation_target",
                         "why_entity": f"{kind} 자식으로 확정 — 역할 근거 제시됨",
                         "alternate_names": [], "evidence_ids": [], "status": "proposed",
                         "introduced_by": {"stage": stage, "role": kind}}
                self.doc["entities"].append(child)
                self.entity_by_norm[norm(m)] = cid_e
                child["evidence_ids"] = self._evidence(
                    raws, cid_e, "subtype" if kind == "SPEC" else "instance")
                self._prov(stage, raw_id, {"child": m, "role": kind}, "kept",
                           f"{kind} 자식을 역할 근거로 새로 만들었다", [], [cid_e])
            pair = shape.shared_declaration(member_ev, members, kind)
            if pair:
                self._hold(stage, raw_id, d, "shape_violation",
                           f"{pair[0]} 와 {pair[1]} 의 역할 근거가 같은 선언 행이다",
                           "같은 선언의 서로 다른 차원은 형제다. 부모-자식으로 묶지 않는다.")
                self._prov(stage, raw_id, d, "held", "배열 차원을 부모-자식으로 접었다")
                continue
            err = shape.edge_error(self._kind_of(parent), kind,
                                   [self._kind_of(self._entity_id(m)) for m in members])
            if err:
                self._hold(stage, raw_id, d, "shape_violation", err,
                           "ref-v8 의 모양은 boundary ─ASPECT→ set ─MULTI→ 개체 ─SPEC→ 유형이다.")
                self._prov(stage, raw_id, d, "held", "트리 모양 계약 위반")
                continue
            member_ids = tuple(sorted(self._entity_id(m) for m in members))
            dup = next((x for x in self.doc["decompositions"]
                        if x["parent_entity_id"] == parent and x["kind"] == kind
                        and tuple(sorted(m["entity_id"] for m in x["members"])) == member_ids),
                       None)
            if dup:
                # 같은 분해를 두 단계가 냈다. 두 번 세면 자식이 부모를 둘 가진 것처럼 보인다
                # (jn-T2-5 에서 r 단계가 d 단계의 분해를 그대로 다시 냈다).
                self._prov(stage, raw_id, d, "merged",
                           f"같은 분해를 이미 받았다: {dup['id']}", [dup["id"]], [dup["id"]])
                continue
            did = self.ids.next("D")
            mult = _obj(d.get("multiplicity")) or {}
            item = {
                "id": did, "kind": kind, "label": d.get("label"),
                "parent_entity_id": parent,
                "members": [{"entity_id": self._entity_id(m), "evidence_ids": []}
                            for m in members],
                "selection": d.get("selection"),
                "multiplicity": {"count_expression": mult.get("count_expression"),
                                 "minimum": mult.get("minimum"),
                                 "maximum": mult.get("maximum"),
                                 "evidence_ids": []},
                "activation_id": None,
                "evidence_ids": [], "status": "proposed",
            }
            self.doc["decompositions"].append(item)
            item["evidence_ids"] = self._evidence(d.get("evidence"), did, "existence")
            if self.tree_mode:
                role = {"ASPECT": "composition", "SPEC": "subtype", "MULTI": "instance"}[kind]
                for member, name in zip(item["members"], members):
                    member["evidence_ids"] = self._evidence(member_ev[name], did, role)
                    item["evidence_ids"] += member["evidence_ids"]
            item["multiplicity"]["evidence_ids"] = self._evidence(
                mult.get("evidence"), did, "multiplicity")
            item["activation_id"] = self._activation_for(
                stage, d.get("activation"), did, "decomposition")
            self._prov(stage, raw_id, d, "kept", "분해로 받았다",
                       [parent] + [self._entity_id(m) for m in members], [did])

    def _couplings(self, stage, payload):
        for i, c in enumerate(payload.get("couplings") or []):
            raw_id = f"{stage}:coupling:{i}"
            if _obj(c) is None:
                self._hold(stage, raw_id, c, "schema_violation", "결합 후보가 객체가 아니다")
                self._prov(stage, raw_id, c, "held", "객체가 아님")
                continue
            src, tgt = _obj(c.get("source")) or {}, _obj(c.get("target")) or {}
            se, te = self._entity_id(src.get("entity")), self._entity_id(tgt.get("entity"))
            if not se or not te:
                self._hold(stage, raw_id, c, "unknown_reference",
                           f"끝점 미해결: {src.get('entity')!r} -> {tgt.get('entity')!r}",
                           near=src.get("entity") if not se else tgt.get("entity"))
                self._prov(stage, raw_id, c, "held", "끝점 미해결")
                continue
            cid = self.ids.next("C")
            payload_raw = _obj(c.get("payload")) or {}
            item = {
                "id": cid,
                "source": {"entity_id": se, "attribute_id": self._attr_id(se, src.get("attribute")),
                           "symbol": src.get("symbol")},
                "target": {"entity_id": te, "attribute_id": self._attr_id(te, tgt.get("attribute")),
                           "symbol": tgt.get("symbol")},
                "payload": {"kind": payload_raw.get("kind", "unknown"),
                            "code_expression": payload_raw.get("code_expression"),
                            "meaning": payload_raw.get("meaning")},
                "mechanism": c.get("mechanism"),
                "derived_from": list(c.get("derived_from") or []),
                "activation_id": None,
                "evidence_ids": [], "status": "proposed",
            }
            self.doc["couplings"].append(item)
            item["evidence_ids"] = self._evidence(c.get("evidence"), cid, "existence")
            item["activation_id"] = self._activation_for(stage, c.get("activation"), cid, "coupling")
            if item["payload"]["kind"] == "unknown":
                self._hold(stage, raw_id, c, "payload_unknown",
                           "무엇이 오가는지 확정하지 못했다", item_ids=[cid])
            self._prov(stage, raw_id, c, "kept", "결합으로 받았다", [se, te], [cid])

    def _flow_records(self, stage, payload):
        """파생이 결합으로 내지 않은 쌍들. **버리지 않고 기록으로 옮긴다.**

        같은 주체끼리의 쌍은 결합이 아니라 상태 갱신이다. 주체를 못 정한 쌍은 자리
        인용이 살아 있으므로 사람이 주체만 붙이면 살아난다.
        """
        for i, u in enumerate(payload.get("internal_updates") or []):
            self._prov(stage, f"{stage}:internal_update:{i}", u, "held",
                       f"{u.get('entity')} 가 쓰고 같은 개체가 읽는다 — "
                       f"결합이 아니라 상태 갱신이다")
        for i, v in enumerate(payload.get("unresolved_values") or []):
            raw_id = f"{stage}:unresolved_value:{i}"
            self._hold(stage, raw_id, v, "unknown_reference",
                       v.get("why") or "값 이름을 맞추지 못했다",
                       "자리 인용은 살아 있다. 값을 이어 주면 자리로 들어간다.")
            self._prov(stage, raw_id, v, "held", "값 이름 미해결")
        for i, p in enumerate(payload.get("unresolved_pairs") or []):
            raw_id = f"{stage}:unresolved_pair:{i}"
            self._hold(stage, raw_id, p, "unknown_reference",
                       p.get("why") or "주체 미해결",
                       "자리 인용은 살아 있다. 주체를 붙이면 결합이 된다.")
            self._prov(stage, raw_id, p, "held", "주체 미해결")

    # ------------------------------------------------------------ 활성 조건
    def _activation_for(self, stage, raw, target_id, target_kind):
        """활성 조건 항목을 만든다. 근거가 없으면 unknown 이다. 무조건 활성으로 바꾸지 않는다."""
        acid = self.ids.next("AC")
        raw = _obj(raw) or {}
        state = raw.get("state")
        clauses = []
        for cl in raw.get("clauses") or []:
            if not isinstance(cl, dict) or not cl.get("expression"):
                continue
            entry = {"expression": cl.get("expression"), "symbol": cl.get("symbol"),
                     "evidence_ids": []}
            clauses.append(entry)
        item = {"id": acid,
                "state": state if state in contract.ACTIVATION_STATE else "unknown",
                "clauses": clauses,
                "combination": raw.get("combination"),
                "coverage": raw.get("coverage") if raw.get("coverage") in contract.COVERAGE
                            else "incomplete",
                "applies_to": {"kind": target_kind, "target_id": target_id},
                "evidence_ids": [], "status": "proposed"}
        self.doc["activation"].append(item)
        item["evidence_ids"] = self._evidence(raw.get("evidence"), acid, "activation")
        for entry, cl in zip(clauses, [c for c in raw.get("clauses") or []
                                       if isinstance(c, dict) and c.get("expression")]):
            entry["evidence_ids"] = self._evidence(cl.get("evidence"), acid, "clause")

        # 규칙: 근거 없는 known·unconditional 은 성립하지 않는다.
        if item["state"] == "known" and not clauses:
            item["state"] = "unknown"
        if item["state"] == "unconditional" and item["coverage"] != "complete":
            item["state"] = "unknown"
        if item["state"] == "unknown":
            item["coverage"] = "incomplete"
            item["status"] = "unresolved"
            self._hold(stage, f"{stage}:activation:{target_id}", raw, "unknown_activation",
                       "활성 조건을 확정하지 못했다. 항상 활성으로 두지 않는다.",
                       item_ids=[acid, target_id])
        if len(clauses) > 1 and item["combination"] is None and item["state"] == "known":
            item["state"] = "unknown"
            item["coverage"] = "incomplete"
            item["status"] = "unresolved"
            self._hold(stage, f"{stage}:activation:{target_id}", raw, "unknown_activation",
                       "절이 여럿인데 AND/OR 를 확인하지 못했다", item_ids=[acid, target_id])
        self._prov(stage, f"{stage}:activation:{target_id}", raw,
                   "kept" if item["status"] == "proposed" else "held",
                   f"{target_kind} {target_id} 의 활성 조건", [target_id], [acid])
        return acid

    def _activation_updates(self, stage, payload):
        """별도 단계가 낸 활성 조건. 대상 키로 붙인다. 못 붙이면 보류한다."""
        for i, a in enumerate(payload.get("activation") or []):
            raw_id = f"{stage}:activation_update:{i}"
            if _obj(a) is None:
                self._hold(stage, raw_id, a, "schema_violation", "활성 조건 후보가 객체가 아니다")
                self._prov(stage, raw_id, a, "held", "객체가 아님")
                continue
            target = _obj(a.get("target")) or {}
            tid = self._resolve_activation_target(target)
            if not tid:
                self._hold(stage, raw_id, a, "unknown_reference",
                           f"활성 조건의 대상 {target!r} 을 찾지 못했다")
                self._prov(stage, raw_id, a, "held", "대상 미해결")
                continue
            owner = next((x for x in self.doc["couplings"] + self.doc["decompositions"]
                          if x["id"] == tid), None)
            old = owner.get("activation_id")
            new = self._activation_for(stage, a, tid, target.get("kind", "coupling"))
            owner["activation_id"] = new
            if old:
                prev = next(x for x in self.doc["activation"] if x["id"] == old)
                prev["status"] = "superseded"
                self._prov(stage, raw_id, a, "superseded",
                           "뒤 단계가 같은 대상의 활성 조건을 다시 냈다", [old], [new])

    def _resolve_activation_target(self, target):
        kind, ref = target.get("kind"), _obj(target.get("ref")) or {}
        if kind == "coupling":
            se, te = self._entity_id(ref.get("source_entity")), self._entity_id(ref.get("target_entity"))
            for c in self.doc["couplings"]:
                if c["source"]["entity_id"] == se and c["target"]["entity_id"] == te:
                    return c["id"]
        if kind == "decomposition":
            pe = self._entity_id(ref.get("parent"))
            k = str(ref.get("kind", "")).upper()
            for d in self.doc["decompositions"]:
                if d["parent_entity_id"] == pe and d["kind"] == k:
                    return d["id"]
        return None

    # ------------------------------------------------------------ 마무리
    def _require_activation(self):
        """활성 조건이 없는 결합·분해는 있을 수 없다. 없으면 unknown 을 만든다."""
        for arr, kind in (("couplings", "coupling"), ("decompositions", "decomposition")):
            for it in self.doc[arr]:
                if not it.get("activation_id"):
                    it["activation_id"] = self._activation_for("assemble", {}, it["id"], kind)

    def _mark_no_evidence(self):
        for arr in contract.ITEM_ARRAYS:
            for it in self.doc[arr]:
                if arr == "activation":
                    continue
                if not it.get("evidence_ids") and it.get("status") == "proposed":
                    it["status"] = "unresolved"
                    self._hold("assemble", None, None, "no_evidence",
                               f"{it['id']} 에 근거가 없다", item_ids=[it["id"]])


def build(stages, snapshot_id, artifact_id, extraction_run, source_snapshot,
          no_new_entity_stages=()):
    return Assembler(snapshot_id, no_new_entity_stages).build(
        stages, artifact_id, extraction_run, source_snapshot)


RAW_KINDS = (("observations", "observation"), ("disputes", "dispute"),
             ("subjects", "subject"),
             ("entities", "entity"), ("attributes", "attribute"),
             ("decompositions", "decomposition"), ("couplings", "coupling"),
             ("activation", "activation_update"))


def raw_ids_of(stages):
    """모델이 낸 원시 후보의 ID 목록. 역추적 검사의 기준이 된다."""
    out = []
    for stage, payload in stages.items():
        for key, label in RAW_KINDS:
            for i, _ in enumerate(payload.get(key) or []):
                out.append(f"{stage}:{label}:{i}")
    return out


def traceable(doc, raw_ids):
    """모든 원시 후보가 판정 기록을 갖는가. 개수 등식이 아니라 이것이 불변식이다."""
    seen = {p.get("raw_id") for p in doc.get("provenance") or []}
    return sorted(r for r in raw_ids if r not in seen)
