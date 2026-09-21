# -*- coding: utf-8 -*-
"""입력 승인 정책. 모델을 부르기 **전에** 입력 경계를 정하고 막는다.

교차검토 판정에 따라 두 가지를 하지 않는다.

  · 참조 SES의 노드 이름으로 파일을 걸러내지 않는다 — 정답이 입력 구성에 개입한다.
  · 지원 코드를 통째로 지우지 않는다 — 대상 개체를 만들거나 구성하는 근거가 거기 있을 수 있다.
    지원 코드는 `role`을 붙여 포함하고, 그것이 Entity가 아니라는 판단은 추출·검토에서 한다.

막는 것은 **평가 자료와 SES 선언**이다. 경로·역할로 막으므로 도메인 지식이 필요 없다.
"""
from __future__ import annotations

import io
import json
import os
import re
from .snapshot import digest, safe_path

BLOCKED_ROLES = ("ses_declaration", "reference", "alias", "evaluation_asset")

# Denylist independent of a caller-supplied role. It does not inspect reference contents.
FRESH_BLOCKS = ("**/reference-ses*.json", "**/aliases.json",
                "docs/research/s1-ses-extraction/**", "**/ses/**",
                "**/subtask/**", "**/ledger/**")

_RX_CACHE = {}


def load_policy(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def _rx(pattern):
    """경로 글로브. `*` 는 `/` 를 넘지 않고, `**` 만 디렉터리를 넘는다.

    fnmatch 를 쓰면 `*` 가 `/` 를 넘어가 `app/*.java` 가 `app/ses/X.java` 까지 잡는다 —
    차단해야 할 것을 포함시키거나, 포함해야 할 것을 차단한다.
    """
    if pattern not in _RX_CACHE:
        out, i = [], 0
        while i < len(pattern):
            if pattern.startswith("**/", i):
                out.append("(?:[^/]+/)*")
                i += 3
            elif pattern.startswith("**", i):
                out.append(".*")
                i += 2
            elif pattern[i] == "*":
                out.append("[^/]*")
                i += 1
            elif pattern[i] == "?":
                out.append("[^/]")
                i += 1
            else:
                out.append(re.escape(pattern[i]))
                i += 1
        _RX_CACHE[pattern] = re.compile("^" + "".join(out) + "$")
    return _RX_CACHE[pattern]


def match(path, pattern):
    return bool(_rx(pattern).match(path))


def _match_any(path, globs):
    return next((g for g in globs or [] if match(path, g)), None)


def _role_for(path, policy):
    for entry in policy.get("file_roles") or []:
        if match(path, entry.get("glob", "")):
            return entry.get("role", "unclassified"), entry.get("reason", ""), entry.get("glob")
    return policy.get("default_role_for_included", "unclassified"), "정책 기본값", None


def _walk(repo_root, include_globs):
    out = set()
    for dirpath, dirnames, filenames in os.walk(repo_root):
        dirnames[:] = [d for d in dirnames if d not in (".git", "target", "__pycache__")]
        for fn in filenames:
            rel = os.path.relpath(os.path.join(dirpath, fn), repo_root).replace("\\", "/")
            if _match_any(rel, include_globs):
                out.add(rel)
    return sorted(out)


def decide(repo_root, policy):
    """정책을 적용해 파일별 판단과 차단 사유를 낸다. 여기서 모델을 부르지 않는다.

    반환: {"policy_id", "approved", "included": [...], "excluded": [...],
           "blocks": [{"code","path","detail"}], "decisions": [파일별 기록]}
    """
    decisions, included, excluded, blocks = [], [], [], []

    if policy.get("approval_status") != "approved":
        blocks.append({"code": "POLICY_NOT_APPROVED", "path": None,
                       "detail": f"approval_status={policy.get('approval_status')!r}"})

    inventory = policy.get("inventory_globs") or policy.get("include_globs")
    for rel in _walk(repo_root, inventory):
        if not _match_any(rel, policy.get("include_globs")):
            excluded.append(rel)
            decisions.append({"path": rel, "role": "outside_boundary", "decision": "excluded",
                              "reason": "outside predeclared source boundary"})
            continue
        role, reason, role_glob = _role_for(rel, policy)
        ex = _match_any(rel, policy.get("exclude_globs"))
        asset = _match_any(rel, policy.get("evaluation_asset_globs"))
        rec = {"path": rel, "role": role, "role_reason": reason, "role_glob": role_glob,
               "matched_exclude": ex, "matched_evaluation_asset": asset}

        try:
            safe_path(repo_root, rel)
        except ValueError as err:
            blocks.append({"code": "SOURCE_PATH_ESCAPE", "path": rel, "detail": str(err)})
            rec.update(decision="blocked", reason=str(err))
            decisions.append(rec)
            continue
        if role not in BLOCKED_ROLES and policy.get("experiment_kind", "fresh_extraction") == "fresh_extraction":
            asset = asset or _match_any(rel, FRESH_BLOCKS)

        if asset:
            rec["decision"] = "blocked"
            rec["reason"] = "평가 자료다. 추출 입력에 넣을 수 없다"
            blocks.append({"code": "EVALUATION_ASSET_IN_INPUT", "path": rel, "detail": asset})
        elif role in BLOCKED_ROLES:
            rec["decision"] = "blocked"
            rec["reason"] = f"역할 {role} 은 추출 입력에 넣을 수 없다"
            blocks.append({"code": "BLOCKED_ROLE_IN_INPUT", "path": rel, "detail": role})
        elif ex:
            rec["decision"] = "excluded"
            rec["reason"] = (policy.get("exclude_reasons") or {}).get(ex, "정책 제외 규칙")
            excluded.append(rel)
        else:
            rec["decision"] = "included"
            rec["reason"] = reason
            included.append(rel)
        decisions.append(rec)

    inc = set(included)
    if policy.get("check_local_imports"):
        for owner in included:
            if not owner.endswith(".java"):
                continue
            with io.open(safe_path(repo_root, owner), encoding="utf-8") as f:
                source = f.read()
            for symbol in re.findall(r"(?m)^import\s+(com\.wastesim\.[\w.]+);", source):
                dep = "src/main/java/" + symbol.replace(".", "/") + ".java"
                if dep not in inc and dep not in (policy.get("dependency_waivers") or {}):
                    blocks.append({"code": "LOCAL_IMPORT_MISSING", "path": owner, "detail": dep})
    for owner, deps in (policy.get("required_dependencies") or {}).items():
        if owner not in inc:
            continue
        for dep in deps:
            if dep not in inc and dep not in (policy.get("dependency_waivers") or {}):
                blocks.append({"code": "REQUIRED_DEPENDENCY_MISSING", "path": owner,
                               "detail": f"{dep} 가 포함되지 않았고 면제 사유도 없다"})

    for rec in decisions:
        if rec["decision"] == "included" and rec["role"] == "unclassified" \
                and policy.get("require_classified_roles", True):
            blocks.append({"code": "FILE_ROLE_UNCLASSIFIED", "path": rec["path"],
                           "detail": "포함 파일의 역할이 기록되지 않았다"})

    if not included:
        blocks.append({"code": "EMPTY_INPUT", "path": None, "detail": "no source selected"})
    return {"policy_id": policy.get("policy_id"), "policy_sha256": digest(policy),
            "policy": policy, "approved": not blocks,
            "included": included, "excluded": excluded,
            "blocks": blocks, "decisions": decisions}


def write_report(report, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8") as f:
        f.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return path
