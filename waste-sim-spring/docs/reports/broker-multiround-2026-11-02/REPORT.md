# 다단계 매칭 첫 조회 정확도 보고서

> **라벨은 초안이며 연구자 검토가 필요하다.** 정답 모음 54줄은 전부 `confirmed:false` 이다. 아래 숫자는 검토 전 라벨 기준이라 확정 수치가 아니다.

정답 모음: `broker/src/test/resources/eval/first-round-gold.json` — V2 실험 A 조건 54회의 첫 조회.
**라벨은 초안이며 연구자 검토 전이다**(전부 `confirmed:false`). 기대 서버는 요청 id 첫 글자(J/D/P)가 가리키는 서버를 그대로 옮긴 것이다.

레지스트리: 장량동 · 행정동 · 주차 실제 카드 + `mcp/candidates/*.json`(가상 후보).
측정: `FirstRoundAccuracyTest.measure`. Task 2 전이라 분류가 없어 조정이 있으면 모두 불일치로 세고, 되묻기는 0.

## 숫자

`Score[total=54, falsePositives=3, misses=20, asked=0]`

## 잘못 걸림 (3)

- V2-J2-A-r1 → bukgu-district-waste-sim
- V2-J2-A-r2 → bukgu-district-waste-sim
- V2-J2-A-r3 → bukgu-district-waste-sim

J2 의 기대 서버는 jangnyang-waste-sim 인데, 행정동 서버가 요청을 그대로 받는다고 걸렸다.

## Task 2 뒤 (항목을 일치 · 불일치 · 애매로 나눈 뒤)

측정: 같은 `FirstRoundAccuracyTest.measure`. 놓침 = 기대 서버가 없거나 불일치 조정으로 막힘, 되묻기 = 애매함만으로 막힘.

`Score[total=54, falsePositives=3, misses=20, asked=0]`

- 잘못 걸림 (3): V2-J2-A-r1 · r2 · r3 → bukgu-district-waste-sim (기준선과 같음)
- 놓침 (20): V2-D2-A-r2, V2-D3-A-r1~r3, V2-D5-A-r1~r3 (bukgu-district-waste-sim) · V2-J3-A-r1~r3, V2-J4-A-r1~r3, V2-J5-A-r1~r3, V2-J6-A-r1 (jangnyang-waste-sim) · V2-P3-A-r1~r3 (pohang-parking-sim)

숫자가 기준선과 같다. 실제 카드 셋(장량동 · 행정동 · 주차)은 목적 · 비교 축 표를 이미 적었고, 행정동이 거주민을 적지 않은 자리는 이 정답 모음의 요청에 거주민이 없어 닿지 않는다. 분류 변경의 효과는 표가 빈 카드(외부 등록 카드)에서 나타나며 단위 시험으로 확인한다.

## Task 3 뒤 (환경 조건이 여러 어휘에 걸리면 애매)

`Score[total=54, falsePositives=3, misses=20, asked=3]`

- 잘못 걸림 (3) · 놓침 (20): Task 2 뒤와 같은 케이스.
- 되묻기 0 → 3: 한 구절이 서로 포섭되지 않는 환경 조건 둘 이상에 걸려 애매로 막힌 경우가 새로 잡혔다.

## 최종 (Task 7: 두 층 측정)

측정은 `FirstRoundAccuracyTest` 의 세 시험이다. 주 지표는 잘못 걸림(목표 0), 놓침은 보조 지표다. 되묻기는 애매함만으로 막힌 줄이며 놓침이 아니다.

### 대조 층 — 대화에서 만든 프로필(`profileSent`)로 조회

`Score[total=54, falsePositives=3, misses=20, asked=3]`

- 잘못 걸림 (3): V2-J2-A-r1 · r2 · r3 → bukgu-district-waste-sim
- 되묻기 (3): V2-P4-A-r1 · r2 · r3 → pohang-parking-sim (환경 조건 "주말 교통 혼잡" 이 서로 포섭되지 않는 두 어휘에 걸림)
- 놓침 (20): Task 2 뒤와 같다.

| 시점 | 잘못 걸림 | 놓침 | 되묻기 |
|---|---|---|---|
| 기준선 | 3 | 20 | 0 |
| Task 2 뒤 | 3 | 20 | 0 |
| Task 3 뒤 | 3 | 20 | 3 |
| 최종 | 3 | 20 | 3 |

### 대조 층 — 요청 문장대로 쓴 정답 프로필(`goldProfile`)로 조회

`Score[total=54, falsePositives=3, misses=36, asked=0]`

- 잘못 걸림은 같은 J2 세 줄이다.
- 놓침이 20 → 36 으로 늘었다. 늘어난 줄: V2-D1~D6 전부(18줄 중 기존 7을 뺀 11), V2-J6-A-r2 · r3, V2-P4-A-r1~r3 등. 문장 그대로 쓰면 카드의 어휘와 어긋나는 곳이 많다는 뜻이다.
  - 도메인: 문장에 "수거" 만 있는 줄은 도메인에 "수거" 를 쓰는데 카드 `domainAliases` 에는 "쓰레기수거" · "쓰레기" 등만 있어 걸리지 않는 것으로 **추정**한다(줄별로 확인하지는 않았다). J5 는 문장에 "쓰레기" · "수거" 가 둘 다 없어 도메인이 비고, 프로필을 만들 수 없어 조회가 거절된다. 측정은 이를 놓침으로 센다.
  - D1 은 문장이 "장량동 전체" 라 장량동 카드가 `unsupported` 로 막는 쪽이 맞고, 기대 서버를 행정동으로 둔 실험 설계와 어긋난다.
- 되묻기는 0 이다. P4 는 환경 조건을 두 구절("주말에 길이 막힐 때" · "하루 3만 대가 몰리면")로 적어 애매 대신 놓침으로 바뀌었다(원인은 줄별로 확인하지 않았다).

### 해석 층 — `profileSent` 대 `goldProfile` 칸별 일치

일치 = 둘 다 비었거나, 공백을 뺀 글이 같거나 한쪽이 다른 쪽을 품음.

| 칸 | 일치 / 54 |
|---|---|
| domain | 49 |
| spatialScale | 46 |
| environmentConditions | 42 |
| objective | 6 |
| comparisonAxes | 21 |
| population | 46 |

objective 가 6 인 것은 라벨을 문장 구절 그대로 쓴 반면 모델은 의역해서 보내기 때문이다(예: "쓰레기가 얼마나 쌓일까" 대 "쓰레기가 얼마나 쌓이는지"). 의미는 같아도 글이 달라 낮게 나온다. 칸 값이 맞는지는 이 지표가 아니라 연구자가 라벨 검토 때 본다.

## 잘못 걸림 3건의 원인 (연구자 확인용 발견)

V2-J2-A: 요청 "우리 동네 쓰레기 수거 시각을 바꾸면 어떻게 될까?" 의 기대 서버는 장량동이다. 행정동 카드가 그대로 맞는다고 걸린 이유는 다음과 같다.

- 행정동 카드 `applicability.matchKeys` 에 "한 동네" · "동네" 가 있어 spatialScale "우리 동네" 가 일치한다. 장량동 카드도 같은 키를 가져 두 카드가 같이 걸린다.
- 행정동 카드의 목적 표에 "수거 시각"(collection-time) 이 있어 비교 축 · 목적도 일치한다.
- 요청에 환경 조건 · 거주민이 없어 걸러 낼 축이 없다.

"동네" 는 두 카드에 모두 적혀 있어 어느 쪽 규모인지 가리지 못한다. 요청이 일부러 짧아서(규모를 말하지 않음) 생긴 일이며, 브로커 규칙 문제라기보다 카드 `matchKeys` 가 겹친 것이다. 이번 작업에서는 매처와 카드를 바꾸지 않았다. 고친다면 연구자가 판단할 일이다(예: 행정동 카드의 "동네" 를 빼거나, 겹치는 키를 애매로 보는 규칙).

## 남은 놓침 (카드 `matchKeys` 보강 후보, 스펙 8절)

- V2-D2-A-r2, V2-D3-A-r1~r3, V2-D5-A-r1~r3 (행정동)
- V2-J3-A-r1~r3, V2-J4-A-r1~r3, V2-J5-A-r1~r3, V2-J6-A-r1 (장량동)
- V2-P3-A-r1~r3 (주차, 거주자 우선 구획 표)

보강 후보: 행정동 · 장량동 `domainAliases` 에 "수거" 단독, 장량동 `objectiveMatchKeys` 에 "수거차 대수 · 차종 · 감당" 류, 행정동 `environmentMatchKeys`/`spatialScale` 에 "구역 50개" 류. 어느 것을 넣을지는 연구자가 정한다. 라벨에 맞춰 카드를 고치면 측정이 라벨에 끌려가므로, 라벨 확정 뒤에 한다.

## 라벨 초안의 정책

`goldProfile` 은 요청 문장이 실제로 말한 구절로 칸마다 다시 썼다. 카드 어휘로 바꿔 쓰지 않았고, 문장에 없는 칸은 비웠다. 같은 요청의 세 줄은 같은 `goldProfile` 이다. 줄마다 `labelNote` 에 칸별 근거를 적었다. 판단이 갈리는 곳은 D5 "상가가 많은"(환경 조건으로 둠), J6 "포항시"(공간 규모로 보지 않음), J5 도메인(비움) 이다.
