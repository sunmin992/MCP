# -*- coding: utf-8 -*-
"""불변 스냅샷. 원본 바이트를 복사해 두고, 이후 모든 단계는 **사본만** 읽는다.

해시만 저장하면 변경 전 원문을 복원할 수 없다. 해시를 계산한 뒤 복사하기 전에 파일이
바뀌면 그 사이가 비어 버린다. 그래서 순서를 뒤집는다 — 먼저 읽어 바이트를 들고,
그 바이트에서 해시를 계산하고, 같은 바이트를 사본으로 쓴다.

행 대응 규칙(인용 대조가 여기에 걸려 있다):
  · 행 번호는 1부터, 양 끝 포함이다.
  · 행 분리는 CRLF·CR 을 LF 로 바꾼 뒤 "\\n" 으로 자른다.
  · 그 밖의 공백(탭·전각 공백·문자열 안의 연속 공백)은 **바꾸지 않는다.**
"""
from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath

SNAPSHOT_SCHEMA = "ses-extraction/snapshot/1.0"


def safe_path(root, relative):
    """Only portable relative paths contained in root, including symlink resolution."""
    if not isinstance(relative, str) or not relative or "\\" in relative or ":" in relative:
        raise ValueError(f"unsafe relative path: {relative!r}")
    p = PurePosixPath(relative)
    if p.is_absolute() or any(x in ("", ".", "..") for x in relative.split("/")):
        raise ValueError(f"unsafe relative path: {relative!r}")
    base = Path(root).resolve()
    result = (base / relative).resolve()
    if not result.is_relative_to(base):
        raise ValueError(f"path escapes root: {relative!r}")
    return str(result)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def normalize_newlines(text):
    return text.replace("\r\n", "\n").replace("\r", "\n")


def split_lines(text):
    return normalize_newlines(text).split("\n")


def materialize(repo_root, decision_report, out_dir, snapshot_id, git=None):
    """승인된 파일의 바이트를 out_dir/files 아래로 복사하고 manifest를 쓴다."""
    if not decision_report.get("approved") or decision_report.get("blocks"):
        raise ValueError("input policy is not approved")
    if not decision_report.get("included"):
        raise ValueError("empty source snapshot")
    # An incomplete earlier copy is evidence too. Never overwrite it.
    os.makedirs(out_dir, exist_ok=False)
    files_dir = os.path.join(out_dir, "files")
    os.makedirs(files_dir)
    records = []
    roles = {r["path"]: r.get("role") for r in decision_report.get("decisions", [])}
    for rel in decision_report["included"]:
        src = safe_path(repo_root, rel)
        with io.open(src, "rb") as f:
            raw = f.read()
        sha = hashlib.sha256(raw).hexdigest()
        dst = safe_path(files_dir, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with io.open(dst, "xb") as f:
            f.write(raw)
        text = raw.decode("utf-8")
        records.append({
            "path": rel, "sha256": sha, "bytes": len(raw),
            "role": roles.get(rel, "unclassified"), "encoding": "utf-8",
            "line_count": len(split_lines(text)),
            "copy_path": os.path.relpath(dst, out_dir).replace("\\", "/"),
        })
    manifest = {
        "schema": SNAPSHOT_SCHEMA,
        "snapshot_id": snapshot_id,
        "status": "materialized",
        "git": git or {},
        "policy_id": decision_report.get("policy_id"),
        "policy_sha256": decision_report.get("policy_sha256"),
        "decision_report_sha256": digest(decision_report),
        "line_rule": "1-based inclusive; CRLF·CR -> LF 후 분리; 그 밖의 공백 보존",
        "files": records,
        "totals": {"files": len(records), "bytes": sum(r["bytes"] for r in records),
                   "lines": sum(r["line_count"] for r in records)},
        "excluded": decision_report.get("excluded", []),
    }
    manifest["manifest_sha256"] = digest(manifest)
    path = os.path.join(out_dir, "manifest.json")
    with io.open(path, "x", encoding="utf-8") as f:
        f.write(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    return manifest


def load(out_dir):
    with io.open(os.path.join(out_dir, "manifest.json"), encoding="utf-8") as f:
        return json.load(f)


class Snapshot:
    """사본만 읽는 접근자. 저장소 작업트리는 다시 보지 않는다."""

    def __init__(self, out_dir, manifest=None):
        self.dir = out_dir
        self.manifest = manifest or load(out_dir)
        expected = self.manifest.get("manifest_sha256")
        if expected and expected != digest({k: v for k, v in self.manifest.items()
                                           if k != "manifest_sha256"}):
            raise ValueError("snapshot manifest hash mismatch")
        self.by_path = {r["path"]: r for r in self.manifest["files"]}
        if len(self.by_path) != len(self.manifest["files"]):
            raise ValueError("duplicate snapshot paths")
        for rec in self.by_path.values():
            safe_path(self.dir, rec["copy_path"])
        self._cache = {}

    @property
    def snapshot_id(self):
        return self.manifest["snapshot_id"]

    def has(self, path):
        return path in self.by_path

    def sha256(self, path):
        return self.by_path[path]["sha256"]

    def text(self, path):
        rec = self.by_path[path]
        with io.open(safe_path(self.dir, rec["copy_path"]), "rb") as f:
            raw = f.read()
        if hashlib.sha256(raw).hexdigest() != rec["sha256"]:
            raise ValueError(f"스냅샷 사본이 manifest 해시와 다르다: {path}")
        return raw.decode("utf-8")

    def lines(self, path):
        return split_lines(self.text(path))

    def slice(self, path, start_line, end_line):
        """1부터, 양 끝 포함. 범위를 벗어나면 None."""
        ls = self.lines(path)
        if not (type(start_line) is int and type(end_line) is int):
            return None
        if start_line < 1 or end_line < start_line or end_line > len(ls):
            return None
        return "\n".join(ls[start_line - 1:end_line])

    def bundle(self, header="===== FILE: {path} =====", line_numbers=False):
        """모델에 보낼 본문. 스냅샷 사본에서만 만든다.

        `line_numbers=True` 면 각 줄 앞에 행 번호를 붙인다. 근거의 행 번호를 모델이 세지
        않아도 되게 하는 것이고, **조건이 달라지므로** 요청 기록에 남긴다.
        인용문에는 번호가 들어가지 않아야 하므로 그 사실을 프롬프트가 함께 말해야 한다.
        """
        out = []
        for rec in self.manifest["files"]:
            body = self.text(rec["path"])
            if line_numbers:
                body = "\n".join(f"{n:5d}| {ln}" for n, ln in enumerate(split_lines(body), 1))
            out.append(header.format(path=rec["path"]) + "\n" + body)
        return "\n\n".join(out)
