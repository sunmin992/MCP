# docs/specifications — 기준 명세서

이 프로젝트의 **단일 기준 명세서**는 SRS·SDD·TDD 통합 문서다. `docs/guides/`(운영·연동)와
`docs/reference/`(결정·카탈로그·결함)는 전부 이 문서에서 갈라져 나온 파생 문서이므로,
서로 어긋나면 **이 문서가 이긴다**.

| 항목 | 내용 |
|---|---|
| 문서명 | waste-sim-spring SRS / SDD / TDD 통합 명세서 |
| 현행 버전 | **v1.10** (2026-08-17) |
| 원본 형식 | Google Docs → Markdown 내보내기 |
| 파일명 | [`docs_waste-sim-spring_SRS_SDD_TDD_v1_10.md`](docs_waste-sim-spring_SRS_SDD_TDD_v1_10.md) (이전: [v1.9](docs_waste-sim-spring_SRS_SDD_TDD_v1_9.md)) |

## v1.9부터 Markdown을 커밋한다

v1.8까지는 `.docx`를 기준으로 삼았는데, 바이너리라 병합이 불가능해 세 대의 개발
머신에서 충돌이 났고 "어느 문단이 언제 왜 바뀌었는지"를 git으로 되짚을 수 없었다.
v1.9는 같은 문서를 Markdown으로 내보내 커밋하므로 diff·PR 리뷰·blame이 그대로 된다.
v1.8 `.docx`는 이력 보존용으로 남겨 둔다.

## 코드와의 정합 상태 (2026-08-17 재대조)

`mvn -B test` **425건 통과·2건 스킵**(Python 참조 엔진 미설치 환경) 기준으로 v1.9 명세를 소스와 대조한 결과다.
스킵은 실패로 보지 않는다(TDD 3.14). 425건에는 PTM 제어기 신규 테스트 17건이 포함된다.

### 코드를 고친 것

| 항목 | 내용 |
|---|---|
| FR-78 | `EdgeToolSelector`의 검사 순서가 캘리브레이션 → 스윕이라 FR-78이 정한 **스윕 → 캘리브레이션** → 배치 → 발열과 반대였다. 두 어휘가 함께 나온 문장("실측 프로파일 기준으로 최적 팬 rpm 찾아줘")이 캘리브레이션으로 가면 답이 아예 안 나온다 — 캘리브레이션은 채팅에서 실행하지 않기 때문이다(FR-83). 순서를 FR-78에 맞추고 회귀 테스트로 고정했다. |
| E-07 (부록 B.2) | `ThermalParams`가 불변식 없는 public record라 도구 계층을 우회하면 비물리적 값이 들어갔다. compact constructor에 `rJa>0`, `cTh>0`, `0 < min ≤ softFloor ≤ max`, `softLimit < hardLimit`, 유한성을 넣었다. 프리셋·파생 메서드는 그대로 통과한다. |
| A-02 (부록 B.2) | `/api/simulation/compare`의 빈 `times` 배열이 조용히 기본값 3종으로 대체돼 D-26(조용한 보정 금지)과 정면으로 충돌했다. 빈 배열·null은 400 VALIDATION으로 거부하고, **미지정**은 종전대로 기본값으로 실행한다(하위호환). |
| 도메인 게이트의 v1.9 팬 어휘 누락 | `DomainIntentDetector`의 EDGE 어휘에 `rpm`·`pwm`·`회전수`·`운전점`이 없어, "팬 rpm 몇이 가성비가 제일 좋아?"·"최적 회전수 찾아줘"가 양쪽 점수 0으로 **UNKNOWN**이 됐다. `sweep_fan_rpm`(FR-97~103)을 추가할 때 `EdgeToolSelector`에는 이 어휘를 넣었지만 **그보다 먼저 도는 도메인 게이트에는 빠져** 있어서, 도구 선택기가 어휘를 다 알고 있는데도 호출조차 되지 않았다. 네 단어만 추가했다 — `스윕`·`가성비`는 장량동에도 수거시각 sweep 시나리오가 있고 "가성비"가 트럭 선택에도 쓰이는 중립 어휘라 일부러 제외했다(장량동→엣지 누수 0건 유지). 225개 문장 회귀 확인 결과 판정이 바뀐 것은 팬 관련 4문장뿐이다. |

### 코드에 추가한 것 (명세 갱신 필요)

| 항목 | 내용 |
|---|---|
| **예측 냉각(PTM) 제어기** (2026-08-17) | 부록 A.3이 "제어기만 남았다"고 적어 둔 마지막 조각이다. `PtmController` + MCP 도구 `simulate_ptm_control`을 신설하고 **명세도 함께 갱신했다**(FR-108~112, SDD 2.15.11, TDD 3.6.13, 도구 10종·엣지 5종). 팬 회전수가 시간에 따라 바뀌므로 `ThermalSimulator`가 매 스텝 열저항·팬 전력을 다시 계산한다 — 제어기를 넣지 않으면 종전 경로 그대로다(UT-217로 고정). |
| 시나리오 `truck-route` | **차종 × 방문 순서 격자 탐색**(민원 최소 조합)을 신설했다. FR-07의 시나리오가 **11종 → 12종**이 되므로 SRS 1.5(FR-07)·1.8.1·SDD 2.7.3(run_scenario 설명)·2.16(waste.js 버튼 수)·TDD를 함께 갱신해야 한다. 두 축을 따로 훑지 않고 격자로 도는 이유는 **상호작용** 때문이다 — 1톤은 골목을 빨리 돌지만 용량이 작아, 어느 차종이 유리한지가 방문 순서에 따라 뒤집힌다. |

이 시나리오는 명세의 두 원칙을 그대로 따른다.

- **후보를 지어내지 않는다**(FR-100·101과 같은 원칙) — 순서 후보는 n! ≤ 24일 때만 전수로 돌고,
  그보다 크면 대표 후보(정방향·역방향)만 돌면서 *전수가 아니라는 사실*을 결과에 남긴다.
  무작위 후보는 섞지 않는다(NFR-02 재현성).
- **없는 우열을 만들지 않는다**(D-25와 같은 원칙) — 전 조합이 동률이면 축 순위를 매기지 않고,
  왜 평평한지와 무엇을 올려야 축이 살아나는지(이동시간·거주민 수)를 알린다. 실제로 건물 3개·
  이동시간 15분 기본 조건에서는 18개 조합이 전부 9.3건으로 같게 나온다.

### 문서를 고쳐야 하는 것 (코드가 맞다) — 2026-08-17 **전건 반영 완료**

아래 11건은 v1.9 Markdown과 `docs/reference/DEBUGGING_ISSUES.md`에 이미 반영했다.
표는 "무엇이 왜 틀렸었는지"를 남기기 위해 보존한다 — 다음 개정에서 같은 자리가 다시 어긋나는지 보는 기준선이다.

| 위치 | 문서 기술 | 실제 코드 |
|---|---|---|
| SRS 1.5(FR-07)·1.8.1·FR-54·SDD 2.7.3·2.14.1·2.16 | 시나리오 **11종** | **12종** — `truck-route`가 코드(`SimulationTool`·`ScenarioController`·`McpToolCatalog`·`ScenarioIntentDetector`)에 전부 등록돼 있다 |
| SDD 2.7.1 | 현재 구현체 = 엣지 도구 **3종** | **4종** — v1.9에서 `sweep_fan_rpm`이 추가됐다(2.7.3 표와 자기모순). TDD IT-49·3.10 주석의 "엣지 3종"·"허브 8개"도 같이 어긋난다 |
| SDD 2.10 | TruckType 용량 60 / 30 / 12 kg | **5000 / 2500 / 1000 kg** — FR-48이 정한 정격용량. 2.10의 숫자는 v1.7 잔재다 |
| SDD 2.15.9 | 검사 순서 = 캘리브레이션 → 배치 → 발열 | **스윕 → 캘리브레이션 → 배치 → 발열** — `EdgeToolSelector.select()`. FR-78과 자기모순 |
| SDD 2.15.5 4단계 | 목적함수 기본값 = `MIN_TOTAL_ENERGY` | **운용 모드별로 다르다** — `FanSweepResult.Objective.defaultFor()`는 MAX_THROUGHPUT일 때 `MIN_ENERGY_PER_FRAME`을 쓴다(총에너지로 고르면 *일을 덜 한* 저RPM 지점이 이기므로). FR-99에도 이 조건이 빠져 있다 |
| SDD 2.16 | index.html 262 · waste.js 507 · edge.js 109 · app.css 480줄 | **265 · 580 · 473 · 590줄** (chat.js 181·domain.js 198은 일치) |
| TDD IT-49 | 엣지 **3종**만 노출 | **4종** |
| TDD 3.10 주석 | 운영 컨텍스트 허브 기준 **8개** | **9개** (2.7.3 "9종"과 자기모순) |
| 부록 A.3 | main **78개** 클래스 · test **38개** 클래스 | **84 · 50** |
| 부록 B.1 (E-04) | 반영 위치 = SDD **2.15.6** | 2.15.6은 HeatsinkThermalModel이다. 결측 3상태(UNKNOWN)는 `ThermalCalibrator` = **2.15.7** |
| 부록 B.2 (E-07) | 미해결 | **해소됨** — `ThermalParams` compact constructor + `ThermalParamsInvariantTest`. B.1로 옮길 것 |
| 부록 B.2 (A-02) | 미해결 | **해소됨** — `SimulationController`가 빈 `times`를 400 VALIDATION으로 거부. B.1로 옮길 것 |

### 파생 문서에서 고칠 것 — **반영 완료**

| 위치 | 내용 |
|---|---|
| `docs/reference/DEBUGGING_ISSUES.md` §6 3단계 | 1(E-04)·3(E-07)이 미완료로 남아 있다 — 둘 다 코드·테스트로 해소됐다(`ThrottleMissingDataTest`·`ThermalParamsInvariantTest`) |
| `docs/reference/DEBUGGING_ISSUES.md` §7 | 체크박스 3개가 비어 있다 — 중간 null throttle(`ThrottleMissingDataTest`), 1노드/2노드 정상상태 일치(`TwoNodeThermalModelTest.steadyStateMatchesOneNodeModel`), dt 수렴(`ThermalSimulatorAccuracyTest`) 전부 존재한다 |

### 문서가 맞고 저장소가 따라가야 할 것

TDD 3.15 환경 메모가 지적한 **Maven Wrapper(`mvnw`/`mvnw.cmd`)가 아직 없다.** 개발자와 CI가 같은
Maven 버전으로 회귀를 재현하려면 추가해야 한다 — 문서 오류가 아니라 남은 작업이다.

### 남겨 둔 것

부록 B.2의 나머지 두 건은 결정이 필요해 손대지 않았다.

- **E-06** — `trtServiceSec`를 `trtServiceCapacitySec`로 바꾸는 것은 공개된 MCP 응답 계약이
  바뀌는 일이라 클라이언트 하위호환 결정이 먼저다.
- **E-08** — 접촉률 1% 이하에서 `rMisalign`(하한 1%)과 `rTim`·`rSpread`(실제 면적)가 같은
  "접촉 면적"에 다른 값을 쓴다. 계산용/보고용 coverage 분리와 무효 배치 거부 중 어느 쪽이
  물리적으로 옳은지가 모델링 결정이다.

### 검증 스크립트

```bash
mvn -B test
```

## v1.10 반영 결과 (2026-08-17, 이 페이지 작성 직후)

위 "코드에 추가한 것" 절의 truck-route 항목("해야 한다")을 v1.10에서 마무리했다.

- **truck-route 시나리오 전건 문서화** — SDD 2.14.4 신설(후보를 지어내지 않는다 D-31·없는
  우열을 만들지 않는다 D-32), FR-113 신설, TDD 3.3.1(UT-224~231)·3.9(IT-76) 추가, 부록 A.1/A.2/A.3 갱신.
- **엣지 도메인 게이트 팬 어휘 결함(D-33)** — DomainIntentDetector의 EDGE 어휘에 rpm·pwm·회전수·
  운전점이 빠져 있던 것을 SDD 2.3.3에 결함 수정 기록으로 남기고 UT-233으로 고정했다. "스윕"·"가성비"는
  장량동과 겹치는 어휘라 의도적으로 제외한 이유도 함께 적었다.
- **EdgeToolSelector 순서 회귀(FR-78)** — 코드는 이미 이 페이지 작성 이전에 수정됐고 SDD 2.15.9도
  이미 정확했으므로, TDD에 회귀 테스트(UT-232)만 추가했다.
- **부록 B.3 완성** — "다음 두 항목은 아직 테스트로 고정되지 않았다"에서 실제로는 한 항목(E-06)만
  적혀 있고 나머지(E-08)가 비어 있던 것을 채웠다.

E-06·E-08은 여전히 결정이 먼저 필요해 미해결로 남아 있다(부록 B.2). Maven Wrapper 부재도 그대로다.
