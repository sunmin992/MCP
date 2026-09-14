# 자산 계약과 매개변수 결정 원장 — 설계

2026-09-13 · 코드 `feature/ses-conversion @ 1760509` · 선행 스펙 `2026-09-11-ses-conversion-design.md`

## 왜 하는가

SES 변환 1단계는 **질문이 어디서 오는가**를 풀었다. 34문항이 손으로 적은 목록이 아니라 SES 가지치기
지점에서 유도된다는 것을 `SesSubtaskDerivation`이 보인다.

풀리지 않은 것은 **값이 어디서 왔고 왜 그 값이어도 되는가**다. 지금 구조는 답변이 들어오면 검증하고
조립하지만, 조립된 `SimulationConfig`를 다시 열어 "이 숫자의 출처가 무엇이냐"고 물을 자리가 없다.
`SubtaskAnswerSource`는 답변 시점의 출처를 한 번 기록할 뿐 이력이 아니고, 구조를 바꾸는 답변이
들어와도 이미 받은 값들의 유효성을 다시 따지지 않는다.

이 설계의 산출물은 그 자리다 — **매개변수 하나마다 값·상태·출처·변환 이력을 쌓는 원장**과,
**구조가 바뀌면 다시 계산하는 규칙**, 그리고 **조립 결과를 원장과 대조하는 역검증**이다.

## 무엇을 하지 않는가

이 설계는 자산 레지스트리·검색·다자산 의존성 폐포를 **만들지 않는다.**

실행 가능한 자산은 장량동 시뮬레이터 하나다. `docs/specifications/`의 라즈베리파이 열전달 SRS는
문서만 있고 코드가 없다. 자산이 하나인 상태에서 검색기와 의존성 폐포를 만들면 입력이 언제나 한
건이므로 **동작하는 것처럼 보이지만 아무것도 검증하지 못한다.** 그래서 이 계층들은 계약의 자리와
등록 검증기까지만 만들고, 검색·폐포·커플링 호환성은 본 문서에 계약으로 명시만 한다.

마찬가지로 **새 컴파일러를 만들지 않는다.** 기존 `PesFlattener`/`JangnyangScenarioBuilder`가
컴파일 타깃이고, 새로 붙는 것은 그 결과를 검사하는 역검증기뿐이다. 별도 컴파일 경로를 만들면
`DerivedSetVsV4ReportTest`가 고정해 둔 대조 기준이 셋으로 늘어난다.

## 계층 배치

| 계층 | 위치 | 신규 여부 |
|---|---|---|
| 등록 — 계약·digest·승인 상태 | `com.wastesim.registry` | 신규, 검증기까지만 |
| 구조 — SES·실행 바인딩 | `com.wastesim.ses` | 기존, 수정 없음 |
| 값 해결 — 원장·활성 규칙 | `com.wastesim.ledger` | 신규 |
| 조달 — MCP 브로커 | `com.wastesim.mcp` | 기존 확장 |
| 컴파일 | `PesFlattener` · `JangnyangScenarioBuilder` | 기존, 수정 없음 |
| 역검증·시험 실행 | `com.wastesim.ledger.verify` | 신규 |
| 평가 | `llm_benchmark.py` | 별도 스펙 |

`SimulationEngine`·`SimulationConfig`·python 어댑터는 1단계 계획의 제약을 승계해 수정하지 않는다.

## 결정 1 — 기존 타입은 선언, 원장은 이력

이미 있는 것을 다시 만들지 않는 것이 이 설계의 첫 규칙이다.

| 요구된 것 | 이미 있는 것 |
|---|---|
| 매개변수별 허용 출처 | `BasisKind` — `REGULATION`·`MEASURED`·`MODEL_DEFAULT`·`NONE`·`EXPERIMENT_INTENT` |
| LLM 추론 금지 | `BasisKind.NONE.canFillWithoutAsking() == false` |
| 값의 출처 | `SubtaskAnswerSource` — `USER_DIRECT`·`LLM_NORMALIZED`·`SERVER_DEFAULT` |
| 충돌 시 차단 | `InvalidValuePolicy.failClosed()` |
| 실행 게이트 | `SubtaskState.canBuild()` · `canRun()` |
| 세트 버전 고정 | `JangnyangSubtaskDefinition`의 정규 형식 해시 (D-45) |

**`BasisKind`는 필드가 무엇에 근거할 수 있는가의 선언이고, `SubtaskAnswerSource`는 이번 답변이
어디서 왔는가의 기록이다. 둘 다 한 시점의 사실이다.** 원장은 이 둘을 입력으로 받아 매개변수
하나의 결정 이력을 쌓는다. 선언을 원장으로 승격하지 않는 이유는, 선언이 세트 해시가 덮는
불변 자산이기 때문이다 — 이력이 섞이면 해시가 실행마다 달라진다.

### 원장 레코드

```
ParameterDecision
  parameterId          asset-id::input-field
  state                unresolved | confirmed | derived | defaulted
                     | conflicted | invalid | stale
  rawValue, rawUnit
  normalizedValue, normalizedUnit
  source               type · reference · version · acquiredAt
  transformation       ruleRef · inputEventRefs      (derived면 필수)
  evidenceRefs
  blockingReason       unresolved·conflicted·invalid·stale면 필수
  supersededBy         stale면 필수 — 이 결정을 낡게 만든 트리거나 대체 결정의 참조
```

append-only다. 값을 고치면 덮어쓰지 않고 새 레코드를 쌓는다 — **현재 값은 저장하지 않고
이력의 마지막으로 계산한다.** 두 사실을 하나로 줄이면 이력과 현재가 어긋날 자리가 없어진다.
`stale`은 구조나 상위 값이 바뀌어 기존 결정을 더는 믿을 수 없을 때 쌓는 레코드이며,
`supersededBy`가 무엇 때문인지를 가리킨다.
덮어쓰면 "왜 이 값으로 바뀌었는가"가 사라지고, 그 질문은 결과가 이상할 때만 나오므로 그때는
이미 늦다.

### 상태 대응

| 들어온 것 | 상태 |
|---|---|
| `USER_DIRECT` | `confirmed` |
| `LLM_NORMALIZED` | `derived` — `transformation.ruleRef` 없으면 등록 거부 |
| `SERVER_DEFAULT` + `BasisKind.MODEL_DEFAULT` | `defaulted` — 결과에 모델 기본값 표시 |
| `BasisKind.NONE` | `unresolved` |
| `BasisKind.EXPERIMENT_INTENT` 미답 | `unresolved` |
| MCP 후보값, 검사 미통과 | `invalid` |
| 두 출처가 다른 값 | `conflicted` |
| 상위 값·구조 변경으로 낡음 | `stale` |

실행이 허용되는 상태는 `confirmed`·`derived`·`defaulted` 셋뿐이다. 나머지는 실행 패키지 발행을
차단한다. **이는 `BasisKind`가 이미 하는 판정을 실행 직전에 한 번 더 하는 것이다.** 중복으로
보이지만 그렇지 않다 — 답변 수집 시점의 판정과 조립 후 실행 시점의 판정 사이에 구조 변경이
끼어들 수 있고, 그 사이를 메우는 것이 이 설계의 목적이다.

## 결정 2 — 활성성은 계산하고, 질문은 유지한다

"비활성 가지를 질문하지 않는다"는 요구와 `JangnyangSubtask`의 현재 규약은 정면으로 충돌한다.
고정 세트는 관련 없는 항목도 생략하지 않고 묻고, `allowsNotApplicable`로 "해당 없음"을 정식
답변으로 받는다. 50항목을 생략 없이 유지한다는 규약이 세트 해시와 `DerivedSetVsV4ReportTest`
대조의 전제다.

**세트는 그대로 두고, 원장이 활성성을 계산한다.** 비활성으로 판정된 항목은 원장이
`defaulted`(사유: `not_applicable_by_rule`)로 자동 확정하고 근거를 남긴다. 사용자에게 나가는
질문 턴은 줄고, 세트의 항목 수와 해시는 깨지지 않는다.

요구의 실질은 "사용자가 답할 필요 없는 것을 답하게 하지 말라"이지 "세트에서 지워라"가 아니다.
이 처리로 그 요구는 달성된다.

### 활성 조건은 규칙 ID로만

자연어 조건식을 실행하지 않는다. `Coupling.activeWhen`이 지금 들고 있는 문자열 조건을 규칙
ID로 승격하고, `RuleRegistry`가 결정론적으로 평가한다. **미등록 규칙 ID는 등록 검증에서 차단한다.**

평가 결과는 셋이다 — `active` · `inactive` · `unknown`.

**`unknown`을 `inactive`로 접지 않는다.** 조건이 의존하는 값이 아직 `unresolved`면 그 가지의
필수값도 `unresolved`로 남는다. 미확정을 비활성으로 처리하면, 아무 답도 하지 않은 실행이 조용히
"그 가지는 필요 없다"는 가정을 쓴다 — `FieldBasis.unknown()`이 선언 누락을 `MODEL_DEFAULT`가
아니라 `NONE`으로 보는 것과 같은 이유다.

## 결정 3 — 무효화는 트리거로, 전이는 기존 상태 기계로

| 트리거 | 작동 |
|---|---|
| 구조 답변 변경 (spec 축·multi 수·커플링 활성) | 활성 필수값 재계산 → 종속 결정 `stale` → 이전 검증 결과 무효화 |
| 상위 값 변경 | 파생 결정 `stale` |
| 출처 만료 | 해당 결정 `stale` |
| 세트 버전 변경 | 전체 재계산 |

`SubtaskState`는 손대지 않는다. `READY → COLLECTING`과 `BUILT → COLLECTING` 전이가 이미
허용돼 있다("답을 고치면 다시 수집으로"). 원장은 그 전이를 **일으키는** 쪽만 추가한다. 상태
기계에 상태를 더하면 기존 전이표의 불변식을 다시 증명해야 하는데, 그럴 이유가 없다.

## 결정 4 — 등록 계약은 자기 자신을 증명하지 못한다

계약이 입력을 누락하면 그 계약으로 만든 검증기도 같이 누락한다. `SesFieldMapping`은 의미 대응에
사람의 지식이 필요하다고 스스로 적고 있다. 그러므로 "필수값 누락 0"이 등록 문서 기준으로만
성립할 위험이 구조적으로 존재한다.

그래서 등록 검증은 계약을 정본으로 삼지 않고 **반대 방향으로 검사한다.**

| 규칙 | 하는 일 |
|---|---|
| `rejectUnresolvedPlaceholders` | `<...>` 미치환 값이 있으면 등록 거부 |
| `rejectUnknownRuleRefs` | 미등록 규칙 ID 차단 |
| `rejectUnknownAdapterRefs` | 미등록 어댑터 차단 |
| `checkInputBindingCoverage` | **실제 `SimulationConfig` 입력 필드를 열거해 계약이 덮지 않는 것을 보고한다** |
| `requireEvidenceBeforeVerified` | 증거 없는 자산은 `proposed`에 머문다 |

`checkInputBindingCoverage`가 이 설계에서 가장 중요한 검사다. 나머지는 계약 안의 일관성을 보지만
이것만 계약 **밖**을 본다. 덮지 못한 필드는 오류가 아니라 보고 항목이다 — 계약을 늘릴지 그
필드가 실험 대상이 아닌지는 사람이 판단한다.

자산 상태는 `proposed → verified` 둘뿐이다. 자동 추출 결과는 언제나 `proposed`에서 시작한다.
추출에 성공했다는 것은 문장을 찾았다는 뜻이지 그 값이 맞다는 뜻이 아니다 — `SpanVerifier`가
인용 문자열의 포함 여부만 검사한다는 사실이 이 구분의 근거다. 인용문이 실재해도 그 문장에서
올바른 숫자·단위·대상을 뽑았는지는 별개 문제다.

## 결정 5 — 역검증은 컴파일 결과를 원장과 대조한다

기존 빌더가 만든 `SimulationConfig`를 다시 열어 원장과 한 필드씩 맞춘다.

차단 조건:
- 원장의 확정값과 설정의 값이 다르다
- 설정에 값이 있는데 원장에 결정 레코드가 없다
- 결정은 있는데 `source`가 없다

**검증된 계획이 잘못된 실행 설정으로 바뀌는 오류는 계획 검증으로는 잡히지 않는다.** 계획 쪽은
전부 통과하기 때문이다. 역검증은 변환 자체를 의심하는 유일한 자리다.

### 시험 실행의 보증 범위

`trial.supported`의 기본값은 `false`다. 지원하더라도 통과가 보증하는 것은 **초기화 가능**까지다.

0-step 또는 짧은 실행으로 전체 기간·다중 자산 실행을 보증할 수 없다. 그래서 계약에 보증 범위를
산문이 아니라 필드로 적는다 — `trial.guarantees: [initialization]`. 보고서가 "시험 실행 통과"를
실행 가능성으로 옮겨 적는 것을 막는 것이 목적이다.

의미 불변조건(`semanticRuleRefs`)도 같다. 통과는 **등록된 규칙에 걸리지 않았다**는 뜻이며,
과학적 타당성 보증이 아니다. 이 문장을 계약 주석이 아니라 보고서 출력에 넣는다.

## 결정 6 — MCP 결과는 후보값으로 들어온다

MCP 도구의 출력 스키마는 선택 사항이고 멱등성 표시는 보증이 아니라 힌트다. 그러므로 단위·출처·
중복 실행 방지는 **브로커가 관리하는 응용 계약**이며 프로토콜이 주는 것이 아니다. 이 구분을
계약에 적는다.

흐름: 호출 시 `purposeField`로 이 값이 어느 매개변수에 쓰일지 먼저 고정한다 → 결과는 원장에
후보값으로 들어온다 → 의미 타입·단위·시공간 범위·최신성 검사를 통과해야 `confirmed`가 된다 →
통과하지 못하면 `invalid`.

타임아웃은 재요청하지 않고 `unresolved`로 되돌린다. 중복 실행 방지가 보증되지 않는 이상 재시도는
같은 작업을 두 번 시키는 것일 수 있다.

`purposeField`를 먼저 고정하는 이유는 결과가 온 뒤에 쓸 곳을 정하면 "평균 통행시간"과 "혼잡
시간대 통행시간"이 같은 필드에 들어가기 때문이다. 단위가 같아서 검사를 통과한다.

## 테스트

기존 `DerivedSetVsV4ReportTest`의 차등 대조 방식을 따른다. 오류 주입과 과차단을 **짝으로** 둔다 —
차단만 늘리면 성공률이 올라가는 착시가 생긴다.

| 시험 | 기대 |
|---|---|
| 미치환 placeholder 등록 | 등록 거부 |
| 미등록 규칙 ID 참조 | 등록 거부 |
| 필수값 미해결 상태로 실행 요청 | 실행 패키지 미발행, 질문 목록은 보존 |
| 단위 불일치 MCP 응답 | `invalid`, 확정 안 됨 |
| 시간 범위가 다른 동일 단위 응답 | `invalid` 또는 `conflicted` |
| MCP 타임아웃 | `unresolved` 복귀, 재요청 없음 |
| 구조 답변 변경 | 새 활성 필수값 발견 + 종속 결정 `stale` |
| 활성 조건이 `unknown` | 해당 가지 필수값 `unresolved` 유지 |
| 컴파일 결과 1개 필드 변조 | 역검증 차단 |
| 정상 골드 구성 | **차단되지 않음** |
| 계약이 덮지 않는 입력 필드 존재 | 보고됨, 등록은 통과 |

## 구현 순서

1. `ParameterDecision` 레코드와 append-only 원장 저장소
2. `BasisKind`·`SubtaskAnswerSource` → 원장 상태 대응
3. `RuleRegistry`와 3값 활성 평가 (`active`/`inactive`/`unknown`)
4. 무효화 트리거와 재계산
5. 등록 검증기 다섯 규칙, `checkInputBindingCoverage` 우선
6. 역검증기
7. MCP 브로커의 `purposeField`와 후보값 승격 검사
8. 오류 주입·과차단 테스트

1~4가 이 설계의 본체다. 5~7은 계약 자리를 만드는 작업이며, 8이 앞 전부를 묶는다.

## 재설계 기준

- 골드 수동 구성이 역검증을 통과하지 못한다 → 원장이 아니라 계약의 대응표를 고친다
- `checkInputBindingCoverage`가 다수 필드를 보고한다 → 자동 추출을 늘리지 말고 등록 계약을 보완한다
- 구조 변경 후에도 누락이 남는다 → 질의 정책이 아니라 무효화 트리거를 고친다
- 과차단 테스트가 깨진다 → 상태 대응표를 다시 본다. 차단을 늘려 얻은 성공률은 성공이 아니다
