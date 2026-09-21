# -*- coding: utf-8 -*-
"""모델 호출. 같은 설정이라도 같은 응답이 보장되지 않는다 — 그렇게 적지도 않는다.

재현되는 것은 **저장된 응답으로 조립·검증을 다시 도는 것**이고, 새 호출의 재현은 별개다.
지원되지 않는 설정(seed 등)은 보내지 않고 '제공되지 않음'으로 기록한다.
"""
from __future__ import annotations

import json
import math
import time
import urllib.error
import urllib.request

URL = "https://api.openai.com/v1/chat/completions"


# 429 라고 다 같은 429가 아니다. 분당 한도는 기다리면 풀리고, 잔액 소진은 기다려도 그대로다.
# 구분하지 않으면 못 풀릴 오류를 재시도하며 시간만 쓴다 (jn-T2d-1 에서 두 번 그랬다).
NON_RETRYABLE_429 = ("insufficient_quota", "credit_balance_exhausted",
                     "billing_hard_limit_reached", "account_deactivated")


def retryable_429(body):
    return not any(k in (body or "") for k in NON_RETRYABLE_429)


class LlmError(RuntimeError):
    def __init__(self, message, kind="failed"):
        super().__init__(message)
        self.kind = kind          # failed | unknown (요청 상태를 모른다)


class FakeClient:
    """시험용. 단계 이름으로 고정 응답을 돌려준다."""

    def __init__(self, responses, fail_on=None, error_kind="failed"):
        self.responses = responses
        self.fail_on = set(fail_on or ())
        self.error_kind = error_kind
        self.calls = []

    @property
    def describe(self):
        return {"name": "fake", "revision": None, "seed": "제공되지 않음"}

    def complete(self, stage, system, user):
        self.calls.append(stage)
        if stage in self.fail_on:
            raise LlmError(f"{stage} 주입된 실패", self.error_kind)
        r = self.responses[stage]
        text = r(user) if callable(r) else r
        return text, {"prompt_tokens": len(user) // 4, "completion_tokens": len(text) // 4}


class OllamaClient:
    """로컬 ollama. API 키도 크레딧도 필요 없다.

    OpenAI 호환 경로(/v1) 대신 네이티브 /api/chat 을 쓴다 — 창 크기(`num_ctx`)를 여기서만
    지정할 수 있기 때문이다. ollama 의 기본 창은 작아서 그대로 두면 코드가 잘린 채 들어간다.
    **잘린 입력은 조용한 실패**이므로 창 크기를 요청 기록에 남긴다.
    """

    def __init__(self, model, host="http://127.0.0.1:11434", num_ctx=32768,
                 temperature=None, timeout=1800, max_tokens=16000, tokenizer_path=None):
        self.model = model
        self.host = host.rstrip("/")
        self.num_ctx = num_ctx
        self._ratios = []   # 이 실행에서 관측한 글자/토큰 비
        self.temperature = temperature
        self.timeout = timeout
        self.max_tokens = max_tokens
        self.tokenizer_path = tokenizer_path
        self.tokenizer = None
        if tokenizer_path:
            from tokenizers import Tokenizer
            self.tokenizer = Tokenizer.from_file(tokenizer_path)

    @property
    def describe(self):
        return {"name": self.model, "provider": "ollama", "host": self.host,
                "revision": None, "seed": "제공되지 않음",
                "num_ctx": self.num_ctx, "temperature": self.temperature,
                "response_format": "json", "max_tokens": self.max_tokens,
                "timeout": self.timeout, "tokenizer_path": self.tokenizer_path}

    def complete(self, stage, system, user):
        input_tokens = None
        if self.tokenizer is not None:
            input_tokens = (len(self.tokenizer.encode(system).ids)
                            + len(self.tokenizer.encode(user).ids))
        # 출력 상한을 **남은 예산에서** 정한다. 고정값을 쓰면 두 방향으로 다 틀린다 —
        # 입력이 작을 때는 답할 자리를 안 주고, 입력이 클 때는 끝낼 수 없는 요청을 보낸다.
        sent_chars = len(system) + len(user)
        ratio = (min(self._ratios) * RATIO_MARGIN if self._ratios
                 else FIRST_CALL_CHARS_PER_TOKEN)
        estimated = (input_tokens if input_tokens is not None
                     else int(math.ceil(sent_chars / ratio)))
        budget = self.num_ctx - estimated - TEMPLATE_RESERVE
        # 최소값과 견주는 것은 **예산**이지 상한이 아니다. 호출자가 상한을 작게 준 것은
        # 그쪽 선택이므로 막지 않는다. 막을 것은 창에 자리가 없는 경우뿐이다.
        if budget < MIN_OUTPUT_TOKENS:
            raise LlmError(
                f"답할 자리가 없다: 입력 추정 {estimated:,} + 여유 {TEMPLATE_RESERVE:,} 로 "
                f"창 {self.num_ctx:,} 에서 남는 출력 {budget:,} 토큰이 최소 "
                f"{MIN_OUTPUT_TOKENS:,} 에 못 미친다. 창을 키우거나 입력을 줄인다",
                "unknown")
        predict = min(self.max_tokens, budget)
        opts = {"num_ctx": self.num_ctx, "num_predict": predict}
        if self.temperature is not None:
            opts["temperature"] = self.temperature
        body = json.dumps({"model": self.model, "stream": False, "format": "json",
                           "options": opts,
                           "messages": [{"role": "system", "content": system},
                                        {"role": "user", "content": user}]},
                          ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.host + "/api/chat", data=body,
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                d = json.load(r)
        except urllib.error.HTTPError as e:
            raise LlmError(f"HTTP {e.code} · {e.read().decode('utf-8', 'replace')[:300]}",
                           "failed")
        except (urllib.error.URLError, TimeoutError) as e:
            raise LlmError(f"ollama 응답을 받지 못했다: {e}", "unknown")
        text = (d.get("message") or {}).get("content", "")
        usage = {"prompt_tokens": d.get("prompt_eval_count", 0),
                 "completion_tokens": d.get("eval_count", 0),
                 "total_tokens": d.get("prompt_eval_count", 0) + d.get("eval_count", 0),
                 "num_ctx": self.num_ctx, "preflight_content_tokens": input_tokens,
                 "estimated_prompt_tokens": estimated, "output_budget": budget,
                 "estimate_ratio": round(ratio, 3),
                 "estimate_source": "observed" if self._ratios else "first_call_floor",
                 "num_predict": predict, "max_tokens": self.max_tokens,
                 "template_reserve": TEMPLATE_RESERVE,
                 "done_reason": d.get("done_reason"),
                 "total_duration_ns": d.get("total_duration"),
                 "load_duration_ns": d.get("load_duration"),
                 "prompt_eval_duration_ns": d.get("prompt_eval_duration"),
                 "eval_duration_ns": d.get("eval_duration")}
        # 창보다 긴 입력은 ollama 가 말없이 앞을 버린다. 셈이 창에 닿으면 경고를 남긴다.
        if usage["prompt_tokens"] >= self.num_ctx - 8:
            usage["warning"] = (f"입력 토큰 {usage['prompt_tokens']} 이 창 {self.num_ctx} 에 "
                                f"닿았다 — 코드가 잘렸을 수 있다")
        usage["sent_chars"] = sent_chars
        usage["chars_per_token"] = truncation_ratio(usage["sent_chars"],
                                                    usage["prompt_tokens"])
        # 이 실행에서 실제로 본 비를 다음 단계가 쓴다. 코드·모델마다 다르므로 상수로
        # 못 박지 않는다 — 배우는 편이 낫다.
        if usage["prompt_tokens"]:
            self._ratios.append(usage["sent_chars"] / usage["prompt_tokens"])
        if truncated(usage["sent_chars"], usage["prompt_tokens"]):
            # 보낸 글자에 비해 센 토큰이 너무 적다. 모델은 **다른 질문**을 받은 것이다.
            raise LlmError(
                f"프롬프트가 잘린 것으로 보인다: {usage['sent_chars']:,}자를 보냈는데 "
                f"{usage['prompt_tokens']:,}토큰만 셌다 "
                f"(글자/토큰 {usage['chars_per_token']}, 한계 {MAX_CHARS_PER_TOKEN}, "
                f"창 {self.num_ctx}). 창을 키우거나 입력을 줄인다", "unknown")
        return text, usage


#: 잘림 판정 한계. 관측값 — 정상 2.6~3.2 (q3-whole-1·q3-whole-3 의 a 단계),
#: 잘림 5.0 (q3-whole-3 의 b2·w). ollama 는 창을 넘으면 문맥을 절반 버리고 **적게 센 값**을
#: 돌려주므로, "창에 닿았는가"만 보는 검사로는 잡히지 않는다 — 실제로 q3-whole-2·3 에서
#: 놓쳤다. 보낸 글자 대비 센 토큰의 비로 잡는다.
MAX_CHARS_PER_TOKEN = 4.5

#: **첫 호출에만** 쓰는 글자/토큰 비. 아직 아무것도 관측하지 못했을 때의 바닥값이다.
#:
#: 이 값을 모든 단계에 쓰면 안 된다. 실측해 보니 이 코드·이 모델의 실제 비는 2.45~2.67
#: 인데 2.0 으로 잡으면 토큰을 22~32% 많게 세고, 그만큼 출력 예산이 깎인다. 반복 실행
#: 5회가 전부 그래서 죽었다 — d 단계가 예산 4,350 을 받아 잘리거나, 답할 수 있는 요청을
#: "자리가 없다"며 거절당했다.
FIRST_CALL_CHARS_PER_TOKEN = 2.0

#: 관측한 비에 곱하는 여유. 뒤 단계는 맥락 블록이 붙어 비가 조금 올라가므로(2.45 → 2.67)
#: 앞 단계에서 본 **가장 낮은** 비를 쓰면 이미 보수적이다. 여기에 한 번 더 깎는다.
RATIO_MARGIN = 0.95

#: 채팅 템플릿이 먹는 몫. 정확히 알 수 없으므로 넉넉히 뺀다.
TEMPLATE_RESERVE = 1024

#: 이보다 적게 남으면 **부르지 않는다.** 끝낼 수 없는 요청을 보내는 것보다 낫다 —
#: q3-whole-4 의 d 단계가 입력 118,142 에 상한 24,000 을 받아 잘렸다. 창이 131,072 였으니
#: 애초에 답할 자리가 12,930 뿐이었다.
MIN_OUTPUT_TOKENS = 4096

#: 이보다 짧은 프롬프트는 비로 판정하지 않는다. 짧은 글에서는 비가 요동친다.
MIN_CHARS_FOR_RATIO = 20000


def truncation_ratio(sent_chars, prompt_tokens):
    if not prompt_tokens:
        return None
    return round(sent_chars / prompt_tokens, 2)


def truncated(sent_chars, prompt_tokens):
    """보낸 글자에 비해 센 토큰이 너무 적으면 앞이 잘린 것이다."""
    if sent_chars < MIN_CHARS_FOR_RATIO or not prompt_tokens:
        return False
    return sent_chars / prompt_tokens > MAX_CHARS_PER_TOKEN


class OpenAiChatClient:
    def __init__(self, model, api_key, max_tokens=16000, temperature=None, timeout=900,
                 retries=6):
        self.model = model
        self.api_key = api_key
        self.max_tokens = max_tokens
        self.temperature = temperature
        self.timeout = timeout
        self.retries = retries

    @property
    def describe(self):
        return {"name": self.model, "revision": None, "seed": "제공되지 않음",
                "max_tokens": self.max_tokens, "temperature": self.temperature,
                "response_format": "json_object"}

    def complete(self, stage, system, user):
        body = {"model": self.model, "max_tokens": self.max_tokens,
                "response_format": {"type": "json_object"},
                "messages": [{"role": "system", "content": system},
                             {"role": "user", "content": user}]}
        if self.temperature is not None:
            body["temperature"] = self.temperature
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        for i in range(self.retries):
            req = urllib.request.Request(URL, data=data, headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self.api_key})
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    d = json.load(r)
                usage = dict(d.get("usage", {}))
                usage.update(finish_reason=d["choices"][0].get("finish_reason"),
                             response_model=d.get("model"), response_id=d.get("id"),
                             system_fingerprint=d.get("system_fingerprint"))
                return d["choices"][0]["message"]["content"], usage
            except urllib.error.HTTPError as e:
                # 본문을 버리면 한도 종류(분당·일일·잔액)를 알 수 없다. 남겨서 되판다.
                try:
                    body = e.read().decode("utf-8", "replace")[:400]
                except Exception:
                    body = ""
                hdr = {k.lower(): v for k, v in dict(e.headers or {}).items()
                       if k.lower().startswith(("retry-after", "x-ratelimit"))}
                if e.code == 429 and not retryable_429(body):
                    raise LlmError(f"기다려도 풀리지 않는 429 · {body}", "failed")
                if e.code == 429 and i < self.retries - 1:
                    wait = int(e.headers.get("retry-after") or 0) or min(90, 20 * (i + 1))
                    time.sleep(wait)
                    continue
                raise LlmError(f"HTTP {e.code} · {body} · {hdr}", "failed")
            except (urllib.error.URLError, TimeoutError) as e:
                # 요청이 닿았는지 모른다. 실패로 단정하지 않는다.
                raise LlmError(f"응답을 받지 못했다: {e}", "unknown")
        raise LlmError("재시도를 다 썼다", "failed")
