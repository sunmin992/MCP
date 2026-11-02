# 첫 조회 기준선 (규칙 변경 전)

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
