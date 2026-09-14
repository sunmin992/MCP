"""채점 회귀 검사. 네트워크/모델 호출 없이 실행: python -m unittest test_llm_benchmark"""
import copy
import io
import json
import unittest
import urllib.error
from unittest.mock import patch
import llm_benchmark as b


def empty():
    return dict(values=[], reaskFields=[], scenario={}, targetRegion="",
                targetDomain="", requestedConclusion="")


class ScoringTest(unittest.TestCase):
    def setUp(self):
        self.template, self.fields = b.load_template()
        self.case = {"id": "test", "request": "30일", "expected": {"days": 30}}
        self.valid = empty()
        self.valid["values"] = [dict(field="days", value=30, span="30일", valid=True)]
        self.valid["scenario"] = {"days": 30}

    def test_valid_and_explicit_empty(self):
        self.assertTrue(b.score(self.case, self.valid, self.fields)["pass"])
        c = dict(expected={}, forbidden_all=True, request="설정해줘")
        self.assertTrue(b.score(c, empty(), self.fields)["pass"])
        for out in ({}, [], {"truckCount": 1}):
            with self.subTest(out=out):
                s = b.score(c, out, self.fields)
                for key in ("pass", "reask", "scenario", "noInvention", "contract"):
                    self.assertFalse(s[key])

    def test_missing_wrong_type_duplicate_and_extra_keys(self):
        variants = []
        for key in empty():
            v = copy.deepcopy(self.valid); del v[key]; variants.append(v)
        for key, val in [("reaskFields", None), ("values", {}), ("scenario", []), ("targetRegion", 3), ("truckCount", 1)]:
            v = copy.deepcopy(self.valid); v[key] = val; variants.append(v)
        for key, val in [("valid", "false"), ("span", None), ("extra", 1)]:
            v = copy.deepcopy(self.valid); v["values"][0][key] = val; variants.append(v)
        v = copy.deepcopy(self.valid); v["values"] *= 2; variants.append(v)
        for out in variants:
            with self.subTest(out=out):
                self.assertFalse(b.score(self.case, out, self.fields)["contract"])

    def test_unknown_fields_cannot_pass_no_invention(self):
        out = empty(); out["values"] = [dict(field="madeUp", value=1, span="설정", valid=True)]
        result = b.score(dict(expected={}, forbidden_all=True, request="설정해줘"), out, self.fields)
        self.assertFalse(result["pass"])
        self.assertFalse(result["noInvention"])

    def test_invalid_value_must_be_preserved_and_excluded(self):
        c = dict(request="건물 27개", expected={}, invalid={"numBuildings":27}, reask=["numBuildings"])
        out = empty(); out["values"] = [dict(field="numBuildings", value=27, span="27개", valid=False)]
        out["reaskFields"] = ["numBuildings"]
        self.assertTrue(b.score(c, out, self.fields)["pass"])
        out["scenario"] = {"numBuildings":4}
        self.assertFalse(b.score(c, out, self.fields)["scenario"])
        out["values"][0].update(value=4, valid=True)
        self.assertEqual(b.score(c, out, self.fields)["constraints"], 0)

    def test_scenario_requires_extraction_and_real_span(self):
        out = copy.deepcopy(self.valid); out["values"] = []
        self.assertFalse(b.score(self.case, out, self.fields)["scenario"])
        out = copy.deepcopy(self.valid); out["values"][0]["span"] = "없는 원문"
        self.assertFalse(b.score(self.case, out, self.fields)["scenario"])

    def test_nonstandard_numbers_duplicates_and_bool(self):
        for raw in ('{"a":1,"a":2}', '{"a":NaN}', '{"a":Infinity}'):
            with self.assertRaises(ValueError): b.parse(raw)
        self.assertFalse(b.same(True, 1))
        self.assertFalse(b.same("30", 30))
        self.assertFalse(b.template_valid(self.fields["routeSequence"], [{}]))

    def test_call_error_and_bad_json_denominators(self):
        error = urllib.error.HTTPError("https://example.invalid",400,"Bad Request",{},None)
        with patch.object(b,"CASES",[self.case]), patch.object(b,"RUNS",3), patch.object(b,"call",side_effect=[error, ("broken",1), (json.dumps(self.valid),2)]):
            r = b.run({"name":"test"},"",self.fields,[])
        self.assertEqual((r["runs"],r["responses"],r["callErrors"]),(3,2,1))
        self.assertEqual((r["stated"],r["values"],r["contract"]),(2,1,1))
        with patch.object(b,"CASES",[self.case]), patch.object(b,"RUNS",1), patch.object(b,"call",side_effect=error):
            r = b.run({"name":"offline"},"",self.fields,[])
        line = next(x for x in b.report(self.template,{"offline":r},{}).splitlines() if x.startswith("| offline"))
        self.assertIn("0/1 | 1 | -",line)
        self.assertNotIn("0.0%",line)
        self.assertNotIn("0.00s",line)


class RequestContractTest(unittest.TestCase):
    """요청이 API에 거절당하지 않는지 — 응답을 채점하기 전에 성립해야 하는 조건."""

    def test_prompt_contains_the_word_json(self):
        """OpenAI는 json_object 모드에서 messages 안에 'json'이라는 낱말을 요구한다.

        없으면 HTTP 400으로 요청 자체가 거절된다 — 실측으로 확인했다:
        "'messages' must contain the word 'json' in some form".
        Ollama는 이 제약이 없어서 로컬 모델만 통과하고 gpt-4o-mini는 36/36 전부
        실패했다. 모델 능력 차이로 읽히지만 실은 요청이 서버에 닿지도 못한 것이다.
        """
        template, fields = b.load_template()
        prompt = b.prompt_from(template, fields)
        self.assertRegex(prompt, r"(?i)json",
                         "json_object 모드를 쓰면서 프롬프트에 'json'이 없으면 400이다")

    def test_http_error_body_is_kept(self):
        """HTTPError의 본문을 버리면 실패 사유를 알 수 없다.

        서버는 400에 이유를 JSON으로 돌려주는데, str(e)는 "HTTP Error 400: Bad
        Request"뿐이다. 36번 실패해도 왜인지 모르는 상태가 그렇게 만들어졌다.
        """
        body = b'{"error":{"message":"messages must contain the word json"}}'
        err = urllib.error.HTTPError("http://x", 400, "Bad Request", {}, io.BytesIO(body))
        text = b.call_error_text(err)
        err.close()
        self.assertIn("HTTPError", text)
        self.assertIn("400", text)
        self.assertIn("must contain the word", text,
                      "서버가 준 사유가 없으면 로그를 봐도 원인을 알 수 없다")

    def test_contract_comes_after_the_template(self):
        """출력 계약은 템플릿 <b>뒤</b>에 있어야 한다.

        모델은 읽은 순서대로 다음 글자를 예측한다. 계약이 템플릿 앞에 있으면 생성 직전에
        읽은 것이 `- numBuildings | type=... | rule=...` 꼴의 행 32개(9,700자)이고, 모델은
        그 모양을 베껴 평평한 설정 객체를 낸다. 실측: 계약을 뒤로 옮기기만 해서
        gemma2:9b가 {} → 계약 통과, qwen2.5:7b가 값 0/4 → 3/4이 됐다.
        """
        template, fields = b.load_template()
        prompt = b.prompt_from(template, fields)
        # '"values"'로 찾으면 안 된다 — ENUM 필드의 allowedRange 안에도 "values"가 있어
        # 템플릿 행에 먼저 걸린다. 계약 문장 자체를 앵커로 쓴다.
        contract = prompt.find("출력은 JSON 객체 하나만 낸다")
        rows = prompt.rfind("| retry=")
        self.assertNotEqual(-1, contract)
        self.assertNotEqual(-1, rows)
        self.assertGreater(contract, rows,
                           "계약이 템플릿 앞에 있으면 모델이 템플릿 모양을 베낀다")

    def test_field_names_are_listed_compactly(self):
        """필드명은 압축 목록으로 따로 나열해야 한다.

        템플릿 행은 평균 304자여서 필드명이 그 안의 한 토큰으로 묻힌다. 그러면 모델이
        "쓸 수 있는 이름의 목록"으로 읽지 못하고 buildingCount·peoplePerBuilding·seedCount
        처럼 그럴듯한 이름을 만들어 낸다. 이름만 따로 나열하면 그 창작이 사라진다(실측).
        """
        template, fields = b.load_template()
        prompt = b.prompt_from(template, fields)
        names = list(fields)
        joined = ", ".join(names)
        self.assertIn(joined, prompt,
                      "필드명이 한 줄 목록으로 없으면 모델이 이름을 지어낸다")

class NoFabricationMetricTest(unittest.TestCase):
    """`지어낸 값 없음`은 <b>값을 지어냈는지만</b> 본다.

    예전 이름은 `무창작`이었고 채점이 `not reask`까지 요구했다. 그래서 값을 하나도
    지어내지 않은 gpt-4o-mini가 0%로, 32개 필드를 통째로 지어내고 scenario에 29개를
    밀어 넣은 gemma2:9b도 0%로 적혔다 — 가장 안전한 모델과 가장 위험한 모델이 같은
    숫자였다. 되묻기 판단은 이미 `재질문` 열이 따로 재므로 여기서 겹쳐 재지 않는다.
    """

    def setUp(self):
        self.template, self.fields = b.load_template()
        self.case = {"id": "no-invention", "expected": {}, "forbidden_all": True,
                     "request": "장량동 쓰레기 수거 시뮬레이터를 내 조건에 맞게 만들어줘."}

    def test_asking_for_everything_is_not_fabricating(self):
        """값을 안 내고 되묻기만 한 응답은 '지어냄'이 아니다 — gpt-4o-mini가 낸 실제 응답."""
        out = empty()
        out["reaskFields"] = list(self.fields)
        self.assertTrue(b.score(self.case, out, self.fields)["noInvention"],
                        "values가 비었는데 지어냈다고 세면 안전한 모델을 위험하게 적는다")

    def test_filling_every_field_is_fabricating(self):
        """요청에 값이 없는데 전 필드를 채운 응답은 지어낸 것이다 — gemma2:9b가 낸 실제 응답."""
        out = empty()
        out["values"] = [dict(field=f, value=1, span="내 조건", valid=True)
                         for f in list(self.fields)[:3]]
        self.assertFalse(b.score(self.case, out, self.fields)["noInvention"])

    def test_report_uses_the_new_column_name(self):
        r = b.run  # 이름만 확인하므로 실행하지 않는다
        text = b.report(self.template, {}, {})
        self.assertIn("지어낸 값 없음", text)
        self.assertNotIn("무창작", text, "옛 이름이 남으면 반대 뜻으로 읽힌다")

    def test_template_rows_are_not_disclaimed(self):
        """템플릿 행을 "베끼지 마라"고 부인하면 안 된다.

        작은 모델이 행 모양을 베끼는 것을 막으려고 "아래 행의 모양을 출력에 베끼지
        않는다"를 넣었더니, gpt-4o-mini가 그 행에서 <b>읽어야 할</b> 형식 정보까지
        불신했다 — dischargeWindow의 allowedRange에는 설명(HH:MM~HH:MM)과
        min:0/max:1439가 함께 있는데, 설명을 버리고 분 단위를 값으로 냈다("1200~360").
        절제 실험에서 이 문장만 빼자 그 케이스가 통과했다.

        행 모양을 베끼는 문제는 이 부인문이 아니라 <b>계약을 맨 뒤에 두는 배치</b>로
        해결한다(test_contract_comes_after_the_template).
        """
        template, fields = b.load_template()
        prompt = b.prompt_from(template, fields)
        self.assertNotIn("베끼지", prompt,
                         "템플릿 행을 부인하면 그 행의 형식 정보까지 버려진다")

    def test_field_list_guides_rather_than_forbids(self):
        """이름 목록은 안내여야 한다. 금지문은 정당한 추론까지 막았다.

        "목록에 없는 이름을 만들면 안 된다"를 넣었더니 gpt-4o-mini가 필드 선택을 어휘
        매칭으로 처리해, "빈 설정에는 기본값을 모두 적용해도 돼" → defaultApproval=ALL
        추론을 포기했다. 목록만 남기고 금지문을 빼도 로컬 모델의 창작 필드명은 0개로
        유지된다(qwen2.5:7b·gemma2:9b 실측).
        """
        template, fields = b.load_template()
        prompt = b.prompt_from(template, fields)
        self.assertIn(", ".join(fields), prompt, "목록 자체는 있어야 한다")
        self.assertNotIn("만들면 안 된다", prompt,
                         "금지문은 열거값을 문장에서 옮기는 정당한 추론까지 막는다")


if __name__ == "__main__":
    unittest.main()
