# 공통 요구의 사용자 표현과 제공자 대응 (두 층 해석)

작성일: 2026-11-04. 지적 "LLM 이 공통 명세로 해석한 것을 어떻게 믿나, 시뮬레이터마다 달라서 어떻게 일반화하나"에 대한 설계다.

## 문제

이전 계약에서는 LLM 이 요구를 만들 때 **시뮬레이터마다 다른 이름**까지 맞혀야 했다.
결과 지표는 `meanComplaints`, 수치 조건은 `numTrucks` 처럼 특정 제공자의 지표·설정 이름이었다.
그래서 해석 정확도를 재려 해도 정답이 시뮬레이터마다 달랐고, 새 시뮬레이터가 붙으면 LLM 평가를 다시 해야 했다.

## 설계: 두 층으로 나눈다

```
사용자 발화
   │ ① LLM — 공통 명세로 구조화. 시뮬레이터 이름을 모른다
   ▼
공통 요구  regionId=덕명동 · simulationDuration=2 day · requiredOutputs=["평균 민원 수"]
           constraints=[{"name":"배차 간격","value":1,"unit":"시간"}]
   │ ② 규칙(RequirementTerms) — 각 제공자 카드의 표현 목록으로 대응. LLM 아님
   ▼
제공자별 요구  장량동: meanComplaints · dispatchIntervalMinutes=60
              구 단위: "민원"은 미지원 표현 → UNSATISFIED
```

| 층 | 하는 주체 | 시뮬레이터에 따라 달라지나 | 신뢰 확인 |
|---|---|---|---|
| ① 공통 해석 | 클라이언트 LLM | 아니오 | 정답 데이터로 **한 번** 측정(R1) |
| ② 제공자 대응 | `mcp-common`의 `RequirementTerms` | 예(카드마다) | 제공자 예문으로 **등록 시 자동 검사** |

새 시뮬레이터에서의 해석 신뢰 = ① 공통 해석 신뢰(이미 측정) × ② 그 제공자의 예문 통과(등록 시 확인). LLM 평가를 시뮬레이터마다 다시 하지 않는다.

## 공통 요구 형식

| 항목 | 형식 | 예 |
|---|---|---|
| requiredOutputs | 사용자 말 그대로의 결과 구절 목록 | `["평균 민원 수", "차량 가동률"]` |
| constraints / preferences | `[{name, value, unit}]` — name·unit 은 사용자 말 그대로 | `[{"name":"배차 간격","value":1,"unit":"시간"}]` |
| regionId · simulationDuration · timeResolution | 이전과 같음(원래 공통) | |
| evidence | 항목별 근거 구절. 키는 항목 이름 또는 `constraints.이름` | `{"constraints.배차 간격":"배차 간격은 1시간"}` |

옛 형식(`requiredOutputs`에 제공자 지표 이름, `constraints`가 `{필드: 값}`)도 그대로 받는다.

## 카드의 `capabilities.terms`

```json
"terms": {
  "outputs":     {"meanComplaints": ["평균 민원 수", "민원 수", "민원 건수", "민원"]},
  "constraints": {"dispatchIntervalMinutes": {"terms": ["배차 간격", "배차"], "unit": "minute"},
                  "numBuildings": {"terms": ["건물 수", "건물"], "unitWords": ["동", "채", "개"]}},
  "examples":    [{"output": "평균 민원 수", "expect": "meanComplaints"},
                  {"constraint": {"name": "배차 간격", "value": 1, "unit": "시간"}, "expect": {"dispatchIntervalMinutes": 60}},
                  {"output": "예산", "expect": null}]
}
```

- 대응: 구절 안에 들어 있는 표현 가운데 **가장 긴 것**의 이름. 길이가 같은 다른 이름이 있으면 모호(TERM_AMBIGUOUS)로 본다.
- 단위: 카드가 선언한 단위(minute · day · ratio · kg · won)로 바꾼다. 1시간 → 60분, 30% → 0.3, 1천원 → 1000원.
  사용자 단위말이 그 조건의 단위말이 아니면(건물 수에 "대") UNIT_MISMATCH.
- 대응되지 않은 표현은 버리지 않는다. 카드의 미지원 표현(`unsupported[].matchKeys`)에 맞으면 UNSATISFIED(TERM_UNSUPPORTED),
  아무 데도 맞지 않으면 UNKNOWN(TERM_UNMATCHED). 둘 다 브로커가 해당 후보를 그대로 고르지 않게 한다.
- `examples`: 카드를 불러올 때와 브로커에 등록할 때(`RequirementContract.validateCard`) 모두 돌린다. 하나라도 어긋나면 서버가 뜨지 않거나 등록이 거부된다.

### SES 가 있는 제공자: 조건 칸은 만든다

장량동 제공자는 브로커가 판정에 쓰는 조건 범위(`capabilities.constraints`)와 조건 표현(`terms.constraints`)을 손으로 적지 않는다.
SES 결정에 `"constraint": {"terms": [...]}` 를 달면 이름은 설정 필드, 범위는 결정의 min/max, 단위 · 단위말은 결정의 unit/unitWords 에서
`SesDerivation.derivedCard` 가 만든다. SES 에서 범위를 고치면(예: 배차 간격 최대 1439 → 120분) 시뮬레이터의 범위 거절과 브로커의 match 판정이
함께 바뀐다. 카드 파일이 SES 와 어긋나면 `SesDerivationTest` 가 실패한다. SES 가 없는 제공자(주차 · 민원 창구 · 구 단위)는 지금처럼 카드에 적는다.

## 현재 카드

| 제공자 | 결과 표현 | 조건 표현 | 예문 |
|---|---:|---:|---:|
| 장량동 블록 폐기물 | 11 지표 | 6 조건 | 9 |
| 주차 | 15 | 11 | 6 |
| 민원 창구 | 12 | 7 | 5 |
| 구 단위 폐기물 | 14 | 8 | 5 |

## 해석 신뢰의 측정(R1)

- 정답 데이터: [extraction-gold.jsonl](../reports/model-evaluation-2026-11-03/extraction-gold.jsonl) 28건. 제공자 이름이 없는 공통 명세다.
  연결된 시뮬레이터가 없는 버스 요청과 어느 카드에도 없는 "탄소 배출량"도 넣었다. 이전 판은 `extraction-gold-v1.jsonl`.
- ②층 검사: `GoldCandidateTest`가 정답 28건을 네 제공자의 실제 카드로 돌려 기대 후보 집합과 28/28 일치함을 확인한다.
- ①층 측정: `scripts/run_requirement_model_evaluation.py`(프롬프트 `requirements-v2-common`, 제공자 이름 목록을 주지 않는다)
  → `scripts/score_requirement_model_evaluation.py`.
  - 사용자 표현은 한쪽이 다른 쪽을 품으면 같은 항목으로 본다("평균 민원 수" ≈ "민원 수").
  - 예측마다 사용자 발화를 함께 브로커에 보내, 틀린 해석의 결과를 나눈다:
    근거 검사로 막힘 / 후보 없음 / 같은 후보 / **다른 후보까지 감**.
  - 신뢰도 = 1 − (틀린 해석 가운데 다른 후보까지 간 비율). API 크레딧이 복구되면 측정한다.

## 한계

- 표현 목록은 제공자가 적는다. 적지 않은 말로 요청하면 TERM_UNMATCHED 가 되어 질문으로 넘어간다 — 틀린 대응보다는 낫지만 묻는 일이 늘 수 있다.
- 대응은 글자 포함 관계라 동의어 · 오탈자 · 다른 언어를 넓게 다루지 못한다.
- ①층 정확도는 아직 측정 전이다. 정답 데이터는 작성자가 만든 28건이며 독립 라벨링이 필요하다.
