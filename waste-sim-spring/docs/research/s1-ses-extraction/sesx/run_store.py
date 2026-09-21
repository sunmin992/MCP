# -*- coding: utf-8 -*-
"""실행 기록. 시도(attempt)는 불변이고, 완료는 추가만 되는 로그다.

두 가지를 특히 지킨다.

  · **요청을 보내기 전에** request.json 을 쓴다. 시간 초과처럼 응답을 못 받은 경우에도
    무엇을 보냈는지 남는다. 그런 시도는 status="unknown" 이며 실패로 단정하지 않는다.
  · 완료 단계의 재사용은 **재개 키**가 같을 때만 한다. 키에는 프롬프트·설정·스키마·
    스냅샷·상위 단계 산출물이 모두 들어간다. 하나라도 바뀌면 캐시가 무효가 된다.
"""
from __future__ import annotations

import hashlib
import io
import json
import os
import time
from pathlib import Path

ATTEMPT_STATUS = ("completed", "failed", "unknown")


def sha256_text(s):
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


def implementation_revision():
    """Tracks uncommitted extractor changes too; no source/answer assets are read."""
    root = Path(__file__).parent
    records = [(p.name, hashlib.sha256(p.read_bytes()).hexdigest())
               for p in sorted(root.glob("*.py"))]
    records.append(("extract.py", hashlib.sha256((root.parent / "extract.py").read_bytes()).hexdigest()))
    return sha256_text(json.dumps(records, sort_keys=True))


def _read_json(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def _write_atomic(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".part"
    with io.open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


def resume_key(stage, prompt, user_input, settings, schema_version, snapshot_id, upstream):
    """이 단계를 다시 부르지 않아도 되는가를 정하는 지문."""
    payload = {
        "stage": stage,
        "prompt_sha256": sha256_text(prompt),
        "user_sha256": sha256_text(user_input),
        "settings": settings,
        "schema_version": schema_version,
        "snapshot_id": snapshot_id,
        "upstream": dict(sorted((upstream or {}).items())),
    }
    return sha256_text(json.dumps(payload, ensure_ascii=False, sort_keys=True))


class RunStore:
    def __init__(self, run_dir):
        self.dir = run_dir
        self.stages_dir = os.path.join(run_dir, "stages")
        self.completions_path = os.path.join(run_dir, "completions.jsonl")
        os.makedirs(self.stages_dir, exist_ok=True)

    # ------------------------------------------------------------ 시도
    def begin_attempt(self, stage, request):
        d = os.path.join(self.stages_dir, stage)
        os.makedirs(d, exist_ok=True)
        n = 1
        while os.path.exists(os.path.join(d, f"attempt-{n}")):
            n += 1
        adir = os.path.join(d, f"attempt-{n}")
        os.makedirs(adir)
        _write_atomic(os.path.join(adir, "request.json"),
                      json.dumps(request, ensure_ascii=False, indent=2) + "\n")
        _write_atomic(os.path.join(adir, "meta.json"), json.dumps(
            {"stage": stage, "attempt": n, "status": "unknown",
             "started_at": time.time(), "finished_at": None,
             "note": "요청은 보냈다. 응답을 받지 못한 채 여기서 끝나면 상태는 unknown 이다."},
            ensure_ascii=False, indent=2) + "\n")
        return adir

    def finish_attempt(self, adir, status, response_text=None, usage=None, error=None):
        assert status in ATTEMPT_STATUS, status
        if response_text is not None:
            _write_atomic(os.path.join(adir, "response.raw"), response_text)
        meta = _read_json(os.path.join(adir, "meta.json"))
        if meta.get("finished_at") is not None:
            raise ValueError("attempt already finalized")
        meta.update({"status": status, "finished_at": time.time(),
                     "usage": usage or {}, "error": error})
        _write_atomic(os.path.join(adir, "meta.json"),
                      json.dumps(meta, ensure_ascii=False, indent=2) + "\n")
        return meta

    def attempts(self, stage=None):
        out = []
        for s in sorted(os.listdir(self.stages_dir)) if os.path.isdir(self.stages_dir) else []:
            if stage and s != stage:
                continue
            d = os.path.join(self.stages_dir, s)
            if not os.path.isdir(d):
                continue
            for a in sorted(os.listdir(d)):
                p = os.path.join(d, a, "meta.json")
                if os.path.exists(p):
                    out.append(_read_json(p))
        return out

    # ------------------------------------------------------------ 완료
    def record_completion(self, stage, key, payload, attempt_dir, raw_ref=None):
        """추가만 한다. 이전 완료를 지우지 않는다."""
        payload_path = os.path.join(attempt_dir, "payload.json") if attempt_dir else os.path.join(
            self.stages_dir, stage, f"payload-{key}.json")
        text = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
        os.makedirs(os.path.dirname(payload_path), exist_ok=True)
        with io.open(payload_path, "x", encoding="utf-8", newline="\n") as f:
            f.write(text)
        rec = {"stage": stage, "resume_key": key, "at": time.time(),
               "payload_sha256": sha256_text(text),
               "payload_path": os.path.relpath(payload_path, self.dir).replace("\\", "/"),
               "attempt_dir": os.path.relpath(attempt_dir, self.dir).replace("\\", "/")
                              if attempt_dir else None,
               "raw_ref": raw_ref}
        os.makedirs(self.dir, exist_ok=True)
        with io.open(self.completions_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        return rec

    def completions(self):
        if not os.path.exists(self.completions_path):
            return []
        out = []
        with io.open(self.completions_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    out.append(json.loads(line))
        return out

    def last_completion(self, stage):
        hits = [c for c in self.completions() if c["stage"] == stage]
        return hits[-1] if hits else None

    def reusable(self, stage, key):
        """재개 키가 같은 완료가 있으면 그 산출물을 준다. 아니면 None."""
        rec = self.last_completion(stage)
        if not rec or rec["resume_key"] != key:
            return None
        p = os.path.join(self.dir, rec["payload_path"].replace("/", os.sep))
        if not os.path.exists(p):
            return None
        with io.open(p, encoding="utf-8", newline="") as f:
            text = f.read()
        if not rec.get("payload_sha256") or sha256_text(text) != rec["payload_sha256"]:
            raise ValueError(f"payload integrity not established: {stage}")
        return json.loads(text)

    def payload_hash(self, stage):
        """상위 단계 산출물의 지문. 하위 단계의 재개 키에 들어간다."""
        rec = self.last_completion(stage)
        return rec.get("payload_sha256") if rec else None
