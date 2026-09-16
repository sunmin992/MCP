# -*- coding: utf-8 -*-
"""검토 보고서. **실패 상태에서도** 만들어진다.

그림에 담기지 않는 것이 있다는 사실이 그것을 숨기는 이유가 될 수 없다. 트리로 그릴 수 없는
후보(루트 미확정·고립·보류·거부·파싱 실패)는 목록으로 낸다. 원시 후보 하나마다 어디로 갔는지
적는다.
"""
from __future__ import annotations

import io
import json
import os


def _tree_lines(doc, root, depth=0, seen=None):
    seen = seen if seen is not None else set()
    ents = {e["id"]: e for e in doc.get("entities") or []}
    if root in seen:
        return [f"{'  ' * depth}- {ents.get(root, {}).get('name', root)} (순환)"]
    seen.add(root)
    e = ents.get(root, {})
    attrs = [a["name"] for a in doc.get("attributes") or [] if a.get("entity_id") == root]
    line = f"{'  ' * depth}- {e.get('name', root)} [{e.get('status')}]"
    if attrs:
        line += "  · " + ", ".join(attrs)
    out = [line]
    for d in doc.get("decompositions") or []:
        if d.get("parent_entity_id") != root:
            continue
        act = next((a for a in doc.get("activation") or []
                    if a["id"] == d.get("activation_id")), {})
        out.append(f"{'  ' * (depth + 1)}[{d['kind']}] {d['id']} "
                   f"(활성 {act.get('state', '?')}/{act.get('coverage', '?')})")
        for m in d.get("members") or []:
            out += _tree_lines(doc, m.get("entity_id"), depth + 2, seen)
    return out


def _human_rows(doc):
    """사람이 넣은 항목. 모델이 뽑은 것과 섞이지 않게 따로 센다.

    루트는 코드에 근거가 없어 검토에서 들어온다(README 규칙 2). 그 사실이 보고서에
    남지 않으면 "파이프라인이 루트를 찾았다"는 주장이 생긴다.
    """
    rows = []
    for arr, 이름 in (("entities", "개체"), ("decompositions", "분해")):
        items = doc.get(arr) or []
        human = [it for it in items if it.get("origin") == "human"]
        if not human:
            continue
        rows.append(f"- {이름}: 추출 {len(items) - len(human)} · 사람 {len(human)}")
        for it in human:
            rows.append(f"  - {it.get('name') or it.get('label') or it['id']} ({it['id']})")
    return rows


def render(doc, run_errors=None, store_attempts=None):
    a = (doc.get("structure") or {}).get("analysis") or {}
    vr = doc.get("validation_results") or {}
    L = []
    w = L.append

    w(f"# SES 추출 검토 보고서 — {doc.get('artifact_id')}")
    w("")
    run = doc.get("extraction_run") or {}
    snap = doc.get("source_snapshot") or {}
    w(f"- 실행 `{run.get('run_id')}` · 방식 `{run.get('strategy')}` · 상태 `{run.get('status')}`")
    w(f"- 모델 {json.dumps(run.get('model'), ensure_ascii=False)}")
    w(f"- 스냅샷 `{snap.get('snapshot_id')}` · 파일 {len(snap.get('files') or [])}개 "
      f"· 상태 `{snap.get('status')}`")
    w(f"- 승인 가능 여부: **{'가능' if vr.get('approval_eligible') else '불가'}**"
      f"  (차단 실패 {vr.get('blocking_failure_ids') or '없음'})")
    w("")
    w("> 이 보고서는 검증에 실패해도 생성된다. 차단되는 것은 승인 산출물이지 검토가 아니다.")
    w("")

    if run_errors:
        w("## 실행 실패")
        w("")
        for e in run_errors:
            w(f"- `{e.get('stage')}` — {e.get('kind')}: {e.get('error')} "
              f"(원문: `{e.get('attempt_dir')}`)")
        w("")

    if store_attempts:
        w("## 시도 기록")
        w("")
        w("| 단계 | 시도 | 상태 | 토큰 |")
        w("|---|---|---|---|")
        for m in store_attempts:
            u = m.get("usage") or {}
            w(f"| {m.get('stage')} | {m.get('attempt')} | {m.get('status')} | "
              f"{u.get('prompt_tokens', '?')}/{u.get('completion_tokens', '?')} |")
        w("")

    w("## 검증 결과")
    w("")
    w("| 검사 | 층 | 결과 | 차단 | 내용 |")
    w("|---|---|---|---|---|")
    for c in vr.get("checks") or []:
        mark = {"pass": "통과", "fail": "실패", "not_checked": "미검사"}[c["result"]]
        w(f"| {c['check_id']} | {c['layer']} | {mark} | {'예' if c['blocking'] else '아니오'} "
          f"| {c['details']} |")
    w("")

    w("## 구조")
    w("")
    roots = a.get("root_candidate_ids") or []
    w(f"- 루트 후보 {len(roots)}개: {roots}")
    w(f"- 선택된 루트: {a.get('selected_root_id') or '**미확정 — 코드가 고르지 않는다**'}")
    w(f"- 분해 순환: {['->'.join(c) for c in a.get('decomposition_cycles') or []] or '없음'}")
    w(f"- 부모 둘 이상: {a.get('multi_parent_entity_ids') or '없음'}")
    w(f"- 고립: {a.get('isolated_entity_ids') or '없음'} · "
      f"도달 불가: {a.get('unreachable_entity_ids') or '없음'}")
    w(f"- 결합 되먹임: {len(a.get('coupling_cycles') or [])}건 (오류가 아니다)")
    w("")

    w("## 트리")
    w("")
    w("```")
    if a.get("selected_root_id"):
        L.extend(_tree_lines(doc, a["selected_root_id"]))
    else:
        for r in roots:
            L.append(f"[루트 후보] {r}")
            L.extend(_tree_lines(doc, r))
    if not roots:
        w("(개체가 없다)")
    w("```")
    w("")

    drawn = set()
    if a.get("selected_root_id"):
        for line in _tree_lines(doc, a["selected_root_id"]):
            drawn.add(line.strip().lstrip("- ").split(" [")[0])
    w("## 그림에 담기지 않은 것")
    w("")
    w("| 종류 | ID | 상태 | 내용 |")
    w("|---|---|---|---|")
    for arr in ("entities", "attributes", "decompositions", "couplings", "activation"):
        for it in doc.get(arr) or []:
            if it.get("status") in ("proposed", "accepted") and arr == "entities" \
                    and it.get("name") in drawn:
                continue
            if it.get("status") in ("unresolved", "rejected", "superseded") or arr != "entities":
                if arr == "entities" or it.get("status") != "proposed":
                    w(f"| {arr} | {it['id']} | {it.get('status')} | "
                      f"{it.get('name') or it.get('kind') or ''} |")
    w("")

    w("## 보류")
    w("")
    w("| ID | 사유 | 대상 | 설명 |")
    w("|---|---|---|---|")
    for u in doc.get("unresolved") or []:
        w(f"| {u['id']} | {u.get('reason_code')} | {u.get('item_ids') or u.get('raw_id')} "
          f"| {str(u.get('explanation') or '').replace('|', '/')} |")
    w("")

    w("## 원시 후보 판정")
    w("")
    counts = {}
    for p in doc.get("provenance") or []:
        counts[p.get("decision")] = counts.get(p.get("decision"), 0) + 1
    w(f"판정 {len(doc.get('provenance') or [])}건 — {counts}")
    w("")
    w("| 원시 후보 | 단계 | 판정 | 결과 항목 | 사유 |")
    w("|---|---|---|---|---|")
    for p in doc.get("provenance") or []:
        w(f"| {p.get('raw_id') or '-'} | {p.get('origin_stage')} | {p.get('decision')} "
          f"| {p.get('output_item_ids') or '-'} | {str(p.get('reason') or '')[:60]} |")
    w("")

    human = _human_rows(doc)
    if human:
        w("## 사람이 넣은 것")
        w("")
        w("코드에 근거가 없어 검토에서 넣은 항목이다. 파이프라인이 뽑은 것이 아니다.")
        w("")
        for row in human:
            w(row)
        w("")

    rs = doc.get("review_status") or {}
    w("## 검토")
    w("")
    w(f"- 상태 `{rs.get('status')}` · 승인 범위 {len(rs.get('accepted_item_ids') or [])}개")
    if doc.get("approval"):
        w(f"- 승인: {'예' if doc['approval']['approved'] else '아니오'} "
          f"{doc['approval']['reasons']}")
    w("")
    return "\n".join(L) + "\n"


def write(doc, path, run_errors=None, store_attempts=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(render(doc, run_errors, store_attempts))
    return path
