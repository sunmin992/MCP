# SES ↔ 실행설정 ↔ 템플릿 연결 설계 — 2026-09-21

추출의 목표를 "개체와 관계를 얼마나 찾았는가"에서 "찾은 구조로 서브태스크 템플릿의 어느
항목을 채울 수 있는가"로 옮긴다. 추출 범위를 늘리지 않는다. **작은 사례 하나를 끝까지
잇는다.**

시범 대상은 셋이다 — `truckType`(차종) · `truckCount`(차량 대수) · `dispatchIntervalMinutes`
(배차간격).

## 1. 지금 어디가 비어 있는가

대조해 보니 브리핑이 요구한 것의 절반은 이미 서버에 있다. 없는 것은 **그 사이를 잇는
근거**다.

| 브리핑 | 상태 | 자리 |
|---|---|---|
| 3절 템플릿 후보 생성기 | **있다** | `ses/DecisionPointExtractor` — SPEC→SpecChoice, MULTI→MultiCount, Attribute→AttributeValue, active_when→CouplingActivation. ASPECT 는 의도적으로 아무것도 내지 않는다 |
| 3절 여섯 구분 | **있다** | `subtask/BasisKind` + `GapResolver` — 채울지 물을지를 기계적으로 가른다 |
| 3절 답변 형식·허용값 | **있다** | `ses/SesSubtaskDerivation` — spec 축의 허용값은 트리에서 온다 |
| 1절 실행설정 대응표 | **없다** | `ses/SesFieldMapping` 은 **손으로 쓴 표**다. 클래스 주석이 스스로 "이것만은 유도할 수 없다"고 적는다. pointId ↔ answerField 두 문자열뿐이고 코드 근거가 없다 |
| 2절 사용자 결정 사항 수집 | **없다** | `sesx/` 에 실행설정을 보는 경로가 아예 없다(`sesx/README.md` "아직 하지 않은 것" 마지막 줄) |
| 4절 제공자 빈칸 목록 | **없다** | 보류는 `unjudged`·`unresolved` 로만 남는다. "템플릿의 어느 칸이 왜 비었는가"로 읽히지 않는다 |

그러므로 이 작업이 만드는 것은 **근거 있는 대응표**와 **그 표에서 나온 템플릿 초안**,
그리고 **빈칸 목록**이다.

## 2. 결정된 것

### 2.1 코드는 `sesx/` 에 둔다

Python 쪽이 낳고 Java 는 읽는다. 근거 수집·불변 보존·리비전·`origin` 구분·전문가 검토가
이미 `sesx/` 에 있고(규칙 3·4·11), 4절과 5절이 그 위에 그대로 올라간다. Java 에 같은
기반을 새로 만들면 검토 장치가 둘로 갈라진다.

이번 증분에서 Java 는 **건드리지 않는다.** `SesFieldMapping` 교체는 대조가 끝난 뒤의 일이다.

### 2.2 설정 필드에서 출발한다

SES 지점에서 출발하면 지점 이름이 한글이라 코드에 손잡이가 없고, 결국 이름 유사도로
돌아간다 — 브리핑 1절이 금지한 바로 그것이다. 설정 필드는 유한하고 각각 앵커를 갖는다.

**언어 장벽을 건너는 다리는 이름이 아니라 앵커다.** `sesx` 의 개체 후보는 이미
`{file_path, owner_type, symbol}` 앵커를 갖는다(`candidates.py:candidate_id`). 설정 필드도
앵커를 갖게 한다. 두 앵커가 같은 기호·같은 소유 유형을 가리키면 그것이 코드 근거 있는
연결 후보다.

`수거차량 ↔ truckType` 이 이어지는 이유는 이름이 비슷해서가 아니라, `truckType` 의 선언
유형 `TruckType` 이 개체 후보의 앵커 기호와 같기 때문이다.

### 2.3 이름으로는 못 잇는다는 증거 — `truckCount`

시범 셋 중 하나가 그 자체로 이 설계의 근거다.

```java
// JangnyangScenarioBuilder:212
c.setNumTrucks(f.intOr("truckCount", c.getNumTrucks()));
```

사용자가 입력하는 이름은 `truckCount`, 설정 필드 이름은 **`numTrucks`** 다. 이름이 다르다.
둘을 잇는 것은 이 한 줄, **변환 자리** 하나뿐이다. 이름 유사도로 짝지으면 이 대응은
나오지 않고, 나온다면 그것은 근거가 아니라 우연이다.

### 2.4 모델은 참여하지 않는다

`flow.py` 와 같은 규칙이다(규칙 9). 근거 수집과 짝짓기는 코드가 한다. 이번 증분에는 LLM
호출이 없다. 의미 판정이 필요해지면 그때 `j3` 와 같은 **짧은 판정** 경로를 따로 만든다 —
긴 산출물을 한 번에 받는 자리를 새로 만들지 않는다.

## 3. 입력 경계 — `P-binding`

대응표의 핵심 근거인 소비 지점 `JangnyangScenarioBuilder.toConfig()` 가 `subtask/` 안에
있는데, 같은 패키지에 정답(34문항 카탈로그)이 함께 있다. 지금은 `FRESH_BLOCKS` 가 패키지를
통째로 막는다.

`input_policy.py` 를 고치지 않는다. 이미 갈래가 있다 —

```python
if role not in BLOCKED_ROLES and policy.get("experiment_kind", "fresh_extraction") == "fresh_extraction":
    asset = asset or _match_any(rel, FRESH_BLOCKS)
```

`sesx/rules/P-binding.json` 이 `experiment_kind: "config_binding"` 을 선언하면 `FRESH_BLOCKS`
를 타지 않고, 막을 것은 `evaluation_asset_globs` 에 **파일 단위로** 적는다.
`ALWAYS_EVALUATION_ASSETS`(참조 SES · aliases · eval-roles)는 정책과 무관하게 계속 막힌다.

| | 파일 |
|---|---|
| **연다** | `model/SimulationConfig.java` · `model/TruckType.java` 등 모델 enum · `tool/SimulationConfigValidator.java` · `tool/ConfigArgs.java` · `registry/SimulationConfigFields.java` · `subtask/JangnyangScenarioBuilder.java` · `subtask/JangnyangScenarioSpec.java` |
| **막는다** | `ses/**` 전부(손으로 쓴 대응표·SES 선언) · `subtask/JangnyangSubtaskCatalog.java` · `subtask/JangnyangSubtaskDefinition.java` · `subtask/JangnyangSubtask.java` · `resources/**/jangnyang-simulator-v4*.json` |

**기존 정책과 과거 실행 조건은 손대지 않는다.** 개체 추출 실행은 지금 그대로 돈다.

산출물은 `exp/<run-id>/binding/` 아래에 제 스냅샷과 함께 둔다 — 같은 실행의 출처를 한자리에
모으되 개체 추출 스냅샷과 섞지 않는다.

## 4. `sesx/binding.py` — 실행설정 연결 후보

설정 필드마다 근거 묶음 일곱을 모은다. `signals.py` 와 같이 각각 `scanned`(조사 범위)를
함께 적어 **"못 찾음"과 "없음"을 가른다.** 못 본 것을 없다고 적으면 그 순간 근거가 아니라
주장이 된다.

| 근거 | 찾는 것 | 브리핑 1절 |
|---|---|---|
| `declaration` | 설정 클래스의 필드 선언 | 항목을 구현한 코드 |
| `conversion` | 사용자 입력 → 설정값 변환 (`f.intOr("truckCount", …)`) | 사용자가 입력할 값 |
| `write_site` | 설정에 반영하는 자리 (`c.setNumTrucks(…)`) | 값을 반영할 위치 |
| `validation` | 허용값·범위 검사 (`ValidationError(..., "truckCount", …)`) | 값을 검사하는 방법 |
| `enum_values` | 열거 상수 | 선택할 수 있는 범위 |
| `read_site` | 값을 읽는 자리 (`c.getNumTrucks()`) | — |
| `dependents` | 그 값을 받아 쓰는 계산 (`truckType.capacityKg`) | 결정에 의존하는 항목 |

### 산출 모양

```json
{
  "binding_id": "BD-1a2b3c4d5e",
  "config_field": "numTrucks",
  "answer_field": "truckCount",
  "owner_type": "SimulationConfig",
  "declared_type": "int",
  "anchor": {"file_path": "...SimulationConfig.java", "owner_type": "SimulationConfig",
             "symbol": "numTrucks"},
  "evidence": {
    "declaration": {"found": true, "sites": [...], "scanned": "...", "detail": "..."},
    "conversion":  {"found": true, "sites": [...], "answer_field": "truckCount", ...},
    "write_site":  {...}, "validation": {...}, "enum_values": {"found": false, ...},
    "read_site":   {...}, "dependents": {...}
  },
  "unit": null,
  "range": {"min": null, "max": null, "source": null},
  "default": {"value": "1", "site": {...}},
  "ses_link": {"state": "proposed", "point_id": null,
               "entity_candidate": "CD-…", "why": "앵커 기호 일치", "origin": "derived"}
}
```

`answer_field` 는 **변환 자리에서만** 나온다. 변환 자리를 못 찾으면 `null` 이고, 그것이
"사용자가 이 값을 입력하는지 확인하지 못했다"는 뜻이다. 설정 필드 이름을 답변 필드 이름으로
베껴 쓰지 않는다 — `numTrucks` 가 그렇게 하면 틀린다.

단위·범위를 코드에서 확인하지 못하면 `null` 이다. 지어내지 않는다.

### 연결 규칙

`ses_link.state` 는 셋이다.

- `unlinked` — 앵커가 어느 개체 후보와도 맞지 않는다
- `proposed` — 앵커가 맞았다. **이름은 보지 않았다**
- `approved` — 사람이 승인했다 (`review.py` 를 탄다)

앵커 일치의 근거는 셋 중 하나이고, 어느 것이었는지 `why` 에 적는다.

1. 선언 유형이 개체 후보의 기호와 같다 (`truckType` → `TruckType`)
2. 값을 읽는 자리가 개체 후보의 소유 유형 안에 있다
3. `dependents` 가 개체 후보의 기호를 가리킨다

## 5. `sesx/template.py` — 템플릿 후보 생성기

SES 요소 → 작업 후보. **요소 하나당 무조건 작업 하나가 아니다.** 각 후보가 처분을 갖고,
처분은 근거에서 나온다.

| SES 요소 | 작업 후보 |
|---|---|
| SPEC | `choose_alternative` — 대안 중 사용할 유형을 선택 |
| MULTI | `decide_count` — 개수와 필요한 구성 자료 |
| Attribute | `provide_value` — 값을 입력·조회·계산하고 검증 |
| ASPECT | `check_parts_ready` — 필요한 부분들의 준비 여부 |
| Coupling | `confirm_link` — 연결 대상과 전달 정보의 대응 |
| 활성 조건 | `check_needed` — 현재 선택에서 이 작업이 필요한가 |

### 처분 — 근거에서 나온다

```
user_decides     변환 자리가 있다 (사용자 입력에서 온다)
server_looks_up  리소스·레지스트리에서 읽는 자리가 있고 변환 자리가 없다
server_computes  읽는 자리만 있고 쓰는 자리가 없다 (다른 값에서 나온다)
default_applies  기본값 적용 코드가 있다
not_needed_now   active_when 이 주어진 선택에서 거짓이다
unknown          가리지 못했다  ← 기본값
```

**기본값이 `unknown` 이다.** `signals.py` 와 같은 규칙이다. 가리지 못한 것을 "사용자가
결정할 값"으로 밀어 넣으면 RQ2 의 사용자 부담이 근거 없이 는다.

### 작업 후보의 슬롯 — 빈칸이 곧 제공자의 일

| 슬롯 | 브리핑 2절 | 어디서 오나 |
|---|---|---|
| `decides` | 작업이 결정할 대상 | SES 지점 |
| `options` | 선택할 수 있는 범위 | `enum_values`, 또는 SPEC 축의 자식 |
| `answer_shape` | 입력 형식 | `declaration` 의 선언 유형 |
| `check` | 검증 기준 | `validation` |
| `applies_when` | 작업의 적용 조건 | `active_when`, 분기 |
| `after` | 먼저 결정할 항목 | 다른 설정을 먼저 읽는 관계 |
| `default_rule` | 입력이 없을 때의 처리 | `default` |
| `delivers_to` | 결과를 전달할 위치 | `write_site` |

**코드에서 확인하지 못한 슬롯은 `null` 이고, 그것이 그대로 제공자의 빈칸이 된다.**

## 6. `sesx/gaps.py` — 제공자 검토 목록

빈 슬롯마다 한 줄.

```
{point_id, slot, state, evidence_ids, provider_action, source_digest}
```

| `state` | 뜻 | `provider_action` |
|---|---|---|
| `extracted` | 코드에서 나왔다 | 의미 확인 |
| `proposed` | 연결 후보가 있다 | 대응 승인 |
| `missing` | 근거가 없다 | 보충 |
| `unlinked` | 연결을 못 찾았다 | 기존 기능 지정 |
| `stale` | 사람이 채웠는데 근거 코드가 바뀌었다 | 재검토 |

사람이 채운 것은 기존 `review.py` 를 타고 들어가 `origin: "human"` 으로 갈라 저장된다
(규칙 11 이 이미 그렇게 센다).

**재검토 표시**는 슬롯마다 `source_digest` — 그 슬롯의 근거가 인용한 파일들의 sha256 —
를 남기는 것으로 한다. 다음 실행에서 해시가 달라지면 `state: "stale"` 로 뜬다. 사람의
보충을 지우지 않는다. 다시 보라고 표시만 한다.

## 7. 지표 — `score_binding.py`

`구성결정-SES-대응표.md` 를 읽지 않는다. 시범 세 필드에 대해 낸 결과를 손으로 쓴
`SesFieldMapping` 과 대조한다.

| 내는 수 | 뜻 |
|---|---|
| `filled_with_evidence` | 시스템이 근거와 함께 채운 슬롯 수 |
| `blank` | 제공자가 보충해야 할 슬롯 수 |
| `mismatch` | 손표와 어긋난 대응 수 |
| `unlinked` | 연결을 못 찾은 필드 수 |

**하나의 "정확도"로 합치지 않는다.** `score_roles.py` 가 이미 그 규칙을 쓴다 — 오통과와
누락을 따로 낸다.

## 8. 명령

```bash
python extract.py bind     --run-id r1 --rule P-binding
python extract.py template --run-id r1
python extract.py gaps     --run-id r1
python extract.py gaps     --run-id r1 --fill filled.json --reviewer 이름
python score_binding.py exp/r1
```

`bind` 는 자기 정책으로 자기 스냅샷을 만든다. 개체 추출 스냅샷을 다시 쓰지 않는다 —
경계가 다르다.

## 9. 이번에 하지 않는 것

- **브리핑 5절의 `b2·c·d·e·f` 후보별 분할.** 별도 증분이다. 같은 절의 나머지는 이미
  있다 — 파일·소유 유형·기호 기반 ID 는 `candidates.py:candidate_id`, 인용 일치와 의미
  판정의 분리는 `evidence.py` 와 `score_roles.py`
- **Java `SesFieldMapping` 교체.** 이번엔 대조까지다
- **LLM 서브태스크 생성과 사용자 부담 지표.** 앞 단계가 흔들리면 뒤 측정이 무의미하다
- **모든 ASPECT 추출을 기다리는 것.** 확인 가능한 구조로 작은 사례를 끝까지 잇고, 어떤
  누락이 실제 작업 생성을 막는지 측정한다

## 10. 구현하며 설계가 바뀐 곳

설계대로 되지 않은 자리 셋. 코드에 대 보고 고친 것이므로 여기 적는다.

### 연결 규칙이 셋에서 둘로 줄었다

§4의 "값을 읽는 자리가 개체 후보의 소유 유형 안에 있다"는 넣지 않았다. 실제 코드에 대 보니
근거가 되는 자리가 없었다 — 검증기는 `TruckType` 을 **읽지만 선언하지 않는다**. 규칙대로
구현하면 파일이 같다는 것만으로 잇게 된다. 규칙을 하나 줄이는 쪽을 택했다.

### 붙이는 다리가 하나 늘었다 — 기호만으로는 하나도 안 붙는다

§5는 SES 요소와 설정 필드를 근거의 **기호**로 붙이기로 했다. 실제 산출물을 열어 보니
`q3-obs1` 의 근거 **116건이 전부 `symbol` 이 없었다.** 기호만 다리로 두면 실제 추출물에서는
아무것도 붙지 않는다.

그래서 근거가 실제로 가진 것 — **파일 경로와 행 범위** — 을 둘째 다리로 더했다. 설정 필드의
선언·변환·쓰기 자리가 요소 근거의 행 범위 안에 들어오면 붙인다. 클래스 전체를 인용한 넓은
근거(60행 이상)는 어느 필드의 것도 아니므로 버린다.

### "0건"을 그대로 두지 않고 진단을 붙였다

다리를 둘로 늘려도 실제 산출물 셋에서 붙은 작업이 0이었다. 수만 내면 대조기가 틀린 것인지
재료가 없는 것인지 알 수 없어서 `bridge_report` 를 더했다.

## 11. 실측 — 어느 계획이 결정 표면에 닿는가 (2026-09-22 고침)

**이 절의 첫 판은 틀렸다.** 표본을 "개체 수가 많은 순"으로 셋만 골라 붙은 작업이 0인 것을
보고 "추출이 구성 표면에 닿지 않는다"고 적었다. 그 셋이 전부 `b2` 계열이었다. 산출물
27건 전부를 재니 다른 그림이 나온다.

설정 쪽은 계획과 무관하게 일정하다 — 필드 42개, 이름이 다른 것 2개를 변환 자리로 잡는다.
갈리는 것은 SES 쪽이다.

| 실행 | 단계 | 설정 근거 | 붙은 작업 |
|---|---|---:|---:|
| `jn-S2-2` | `single` | 45/50 (90%) | **39/44** |
| `q3-tree-1` | `a+b2+b3+c+d+e+f+g+r2` | 68/249 (27%) | 17/86 |
| `jn-T2-3` | `a+b+c+d+e+f` | 9/60 (15%) | 0/29 |
| `q3-obs1` | `a+b2+b3+c+d+e+f+r2` | 6/116 (5%) | 0/64 |
| `b2` 를 쓰는 나머지 10건 | — | 0 | 0 |

`jn-S2-2` 에서 사슬이 끝까지 돈다. 처분도 갈린다 — `user_decides` 27 ·
`default_applies` 12 · `unknown` 5.

**막는 것은 추출 자체가 아니라 단계 나눔이다.** `b2` 를 쓰는 13건 중 11건이 설정 근거
0이다. `stages.py` 가 이미 적어 둔 성질과 맞물린다 — "a·b2 가 상태 중심이라 색인 축만
개체가 되는 문제". 상태 중심으로 나누면 결정이 사는 선언 표면에 닿지 못하고, 그러면
템플릿이 나오지 않는다.

그러므로 다음에 잴 것은 "추출을 고치자"가 아니라 **"어느 계획이 결정 표면에 닿는가"** 이고,
그것은 지금 있는 도구로 바로 잰다.

### 왜 이 표를 한 번 틀렸는가

설계를 **스키마와 표본 하나**로 확정했기 때문이다. 이 문서 §10의 이탈 셋도 같은 원인이다.
`artifact.schema.json` 은 어떤 필드가 있을 수 있는지만 말하고 실제로 채워지는지는 말하지
않는다. LLM 이 만드는 산출물이라 실행마다·계획마다 다르다 — 근거의 `symbol` 보유율은
0%에서 100%까지 갈린다.

**빠져 있던 단계는 하나다 — 입력 분포를 먼저 재는 것.** `exp/*/ses.json` 을 한 번 훑는 데
1분이면 된다. 다음 설계는 거기서 시작한다.

## 12. 검증

TDD 로 만든다. 이 저장소의 인수 시험 203건이 그렇게 쌓였다.

| 시험 | 지키는 것 |
|---|---|
| `test_binding_policy` | `P-binding` 이 소비 지점을 열고 정답 파일을 막는다. `ALWAYS_EVALUATION_ASSETS` 가 여전히 막힌다 |
| `test_binding_evidence` | 근거 일곱이 `scanned` 를 갖는다. 못 찾은 근거가 `found: false` 이고 빈 배열이 아니다 |
| `test_binding_answer_field` | `numTrucks` 의 `answer_field` 가 `truckCount` 다. 변환 자리가 없으면 `null` 이다 |
| `test_binding_link` | 연결이 앵커로만 생긴다. 이름만 같고 앵커가 다른 쌍은 `unlinked` 다 |
| `test_template_disposition` | 근거가 없으면 `unknown` 이다. 처분이 근거에서만 나온다 |
| `test_template_slots` | 확인 못 한 슬롯이 `null` 이다. 설정 필드 이름을 답변 필드로 베끼지 않는다 |
| `test_gaps_stale` | 근거 파일이 바뀌면 사람이 채운 슬롯이 `stale` 로 뜨고, 채운 값은 남는다 |
| `test_score_binding` | 오통과와 누락을 따로 낸다. 합산 지표가 없다 |
