# 시뮬레이터 MCP 사용자 요청 테스트 결과 — 기본 사례 전체

실행일: 2026-10-29. 대상 계획: `simulator-user-request-tests.md`의 기본 사례 86개(B·J·D·C·P·A·O).

## 실행 방법

- 서버 5개를 띄웠다: 브로커 8089, 장량동 8090, 행정동 8091, 주차장 8092(인증키 있음), 민원 창구 8093. 브랜치는 `fix/scenario-requires-plan`(4c82622)이다.
- B·J·D·C·P·A 구역마다 LLM 에이전트를 하나씩 두어 동시에 돌렸다. 에이전트는 두 역할을 맡았다.
  - 비서 역할: MCP 응답만 근거로 삼고 저장소 소스는 읽지 않았다.
  - 테스트 실행자 역할: 대본에 따라 답하고, 확인 화면에서 승인했다(`POST /api/scenarios/{id}/confirm`).
- 대본은 각 장의 "정상 사례 기본 조건"이다. 대본에 없는 질문에는 "기본값으로 해줘"로 답했다.
- O 구역은 다른 구역이 끝난 뒤 직접 수행했다. 브로커·창구 서버를 내렸다가 다시 올렸다.
- 반복은 3회 이하, 기간은 각 장의 기본값으로 했다. 결과의 방향성은 판정에 쓰지 않았다.

## 판정 요약

| 구역 | 통과 | 실패 | 차단·미실행 | 실패 사례 |
|---|---|---|---|---|
| B 브로커 (14) | 11 | 3 | 0 | B02, B11, B13 |
| J 장량동 (14) | 10 | 4 | 0 | J03, J05, J11, J12 |
| D 행정동 (12) | 12 | 0 | 0 | — (경계값 확인: 근무시간 실패) |
| C 민원 창구 (12) | 9 | 3 | 0 | C03, C04(경미), C10 |
| P 주차장 (12) | 12 | 0 | 0 | — (추가 범위: "오늘 오후 3시 빈자리 예측" 실패) |
| A 주차장 API (14) | 11 | 0 | 차단 1(A07), 미실행 2(A13·A14) | — |
| O 운영·결과 (8) | 6 | 0 | 부분 1(O02), 미실행 1(O08) | — |
| **합계 (86)** | **71** | **10** | **5** | |

- C08·C09는 통과지만 조건부다. 기본값이 없는 값에 출처를 MODEL_DEFAULT로 붙여 넣으면 서버가 받는다(결함 5).
- A07은 데이터 쪽 차단이다. 포항(47111·47113)은 Parking_v1 실시간을 제공하지 않는다. 서버의 안내는 정상이었고, 경주·강남에서는 조회에 성공했다. A08은 강남 2곳으로 진행했다.
- 키 노출: 모든 응답 파일에서 servicekey를 검색한 결과 0건이었다.

## 9장 운영 장애·결과 검증 (O01~O08)

| 사례 ID | 준비 또는 요청 | 실제 결과 | 판정 | 증거 |
|---|---|---|---|---|
| O01 | 브로커만 정지 | 8089는 연결 거부(curl exit 7). 8091은 get_capability에 정상 응답. 전송 오류라 API 인증 오류와 섞이지 않음 | 통과 | |
| O02 | 창구 서버만 정지 → 브로커에 창구 요청 | 창구 서버는 목록에서 빠짐. 브로커가 창구 서버를 추천하지 않음. 폐기물 서버를 조정 대상으로 내지만, 도메인 조정에 `changesPurpose:true`를 붙임 | 부분 통과 | 브로커 응답에 "서버가 꺼져 있음"과 "그런 서버가 없음"의 구분이 없음. 비서가 정지 상태라고 설명할 근거가 없음 |
| O03 | 창구 재기동 후 같은 요청 | 재등록됨. MATCH pohang-counter-sim | 통과 | 브로커를 재기동한 뒤에도 8090~8092가 다시 등록돼 있었음 |
| O04 | 승인 설정과 실행 설정 비교 | scn-8a1b310c의 runs가 승인된 값과 같음. A08은 승인된 스냅샷으로 실행됨 | 통과 | |
| O05 | 같은 설정·시드로 재실행 | C01(11.77/3.84분)과 P01(4.92/3.49/2.55분)이 어제(10-28) 결과와 같음 | 통과 | |
| O06 | 주차·창구 결과 해석 | 결과에 대기·탐색시간과 포기·이탈 수가 함께 나옴. C02에서는 창구별 줄이 평균 대기는 낮지만 이탈이 늘어난다는 점을 서버 안내가 짚음 | 통과 | |
| O07 | 보존 지표 | 실행한 모든 시나리오에서 오차 0: 폐기물 질량수지(J·D massBalanceErrorKgMax), 주차 차량수지, 창구 방문객수지 | 통과 | |
| O08 | 최선 정책 단정 요청 | 이번에는 따로 시험하지 않음(어제 1차 점검 O06에서 확인) | 미실행 | |

## 주요 결함 (중요도 순)

1. **[8090 장량동] 승인 우회 경로.** `run_waste_simulation`, `run_waste_simulation_devs`, `run_scenario`, `update_route_sequence`는 확인 토큰 없이 실행된다. 직접 재현했다: `run_waste_simulation {"collectionTime":"06:00","days":1,"seeds":1}`가 결과를 바로 냈다. 다른 세 서버에는 `run_scenario_by_token`만 있다. 결함 2 때문에 비서가 이 경로로 빠질 위험이 크다(B11 실패). 이 경로의 결과는 승인 경로와도 다르다(J 결함 8).
   - 최대 적재가 민원 기준을 넘는데 민원이 0이다.
   - 반복 3회를 줬는데 1회분만 나온다.
2. **[8090] 카드에는 있는데 승인 흐름 템플릿에 없는 조건.** 수거장 용량, 민원 기준, 수거 주기, 분리배출 비율이 여기에 해당한다(J03·J11·J12). 오류 메시지는 문제 필드를 "values"로 가리킨다.
3. **[8091] 근무시간 범위 검사 누락.** 범위(120~720분)를 validate_answers는 검사하지만 build_scenario는 검사하지 않는다. 721분과 0분이 UNCONFIRMED까지 간다.
4. **[여러 서버] 조건부 결정을 비교 변수로 주면 생성 조건을 평가하지 않는다.** 경고 없이 유효한 설정으로 만들어진다.
   - J05: 차량 1대에서 배차 간격 비교.
   - C03: 창구 1개에서 줄 방식 비교.
   - C10: 예약제를 끈 채 예약 비율 입력.
5. **[8093·8091] 기본값 없는 값을 받아들인다.** maxWaitMinutes, reservationShare, 공휴일 정책에 출처를 MODEL_DEFAULT로 붙여 넣어도 build_scenario가 통과시킨다. 확인 화면에는 미승인 기본값으로 따로 보인다. 반면 8092는 같은 경우를 막는다(P04).
6. **[브로커] 미지원 요청을 MATCH로 판정한다.** "오늘 오후 3시의 실제 빈자리 예측", "현재 실제 대기 인원 조회"가 MATCH로 나온다. 질문 자체가 바뀌는 조정에도 `changesPurpose:false`가 붙는다. 미지원 키워드는 띄어쓰기에 민감하다("전담창구"와 "전담 창구").
7. **[브로커] 공간 규모 인식이 좁다.** "원룸 4동", "건물 8동", "공영주차장 4곳"이 ADJUST_REQUEST를 낸다(B02). 지원 도메인이 없을 때도 NONE 대신 ADJUST_REQUEST가 나온다(B09).
8. **[공통] build_scenario가 비교 변수를 필수로 요구한다.** 단일 실행은 값 하나짜리 변수로 우회해야 한다. 이때 확인 화면에 그 변수 값이 보이지 않는다(B13: 요금 정책 '무료'가 화면에 없음).
9. **[8092 API] 표준데이터 중복 시설을 거르지 않는다.** 같은 주소가 관리번호 둘로 나온다(죽도). 둘 다 고르면 면수가 이중으로 잡힌다. 조회 결과가 정확히 30건이라 잘렸는지도 알 수 없다.
10. **경미.**
    - 비교 대상이 아닌 run에도 조건부 값이 실린다. 예: 무료 설정에 요금 500원, 예약 미사용 설정에 비율 0.3.
    - 결과 행에 변수값이 없다(8090).
    - 시나리오 ID와 토큰을 추측할 수 있다. 미승인 상태에서는 실행이 거절돼 우회는 되지 않는다.
    - 8092는 사용자가 확인하지 않은 `fullLotCircleMinutes=2.0` 가정을 싣는다.

어제 지적한 "단일 실행 경로 없음"과 "브로커 문구 매칭 엄격"은 아직 남아 있다.

## 이번에 확인하지 않은 것

- A13·A14: 키를 바꾸거나 모의 응답을 넣는 별도 테스트 프로세스가 필요하다.
- 8장 숫자 경계값: 창구 전체와 행정동 근무시간만 확인했다. 나머지 모델은 하지 않았다.
- 추가 지원 범위: 각 장에서 한 줄씩만 확인했다.

---

# 부록: 구역별 상세 기록 (에이전트 원문)

# B 구역 실행 결과 (브로커 선택·대화·승인, B01~B14)

실행일 2026-10-29. 브로커 8089 → 각 시뮬레이터. 결과 원본: runs/B14_counter_result.json, runs/B12_jn_result.json.

| 사례 ID | 입력 및 후속 답변 | 선택 서버·도구 | 최종 설정/시나리오 ID | 실제 결과 | 판정 | 증거/오류 |
|---|---|---|---|---|---|---|
| B01 | 지금 쓸 수 있는 시뮬레이터와 분석 범위 | 8089 list_candidates, find_simulators(domain=전체) | - | 6개 등록 카드(실서버 4 + fictional 2, fictionalReason 포함)와 분석 단위 반환. 가동 여부 필드는 없음 → 비서는 "등록됨, 가동 여부는 호출 전 모름"으로 안내 | 통과 | domain=전체 의 ADJUST suggestions 에 주차장 서버가 빠짐(3개만) — 아래 결함 5 |
| B02 | 장량동 원룸 건물 4동 아침/점심 수거 비교 | find_simulators(쓰레기수거, spatialScale="원룸 건물 4동"/"장량동 원룸 건물 4동", 축=수거 시각) | - | ADJUST_REQUEST: 장량동·북구 둘 다 "공간 규모가 분석 단위와 맞지 않음". "원룸촌 한 블록"으로 고치면 MATCH 장량동 | 실패 | 4동(상한 26동 이내)이 블록 규모로 인식 안 됨. 사용자 1회 조정 후에야 장량동 선택 — 결함 1 |
| B03 | 북구 행정동 수거구역 8개 차량 증차 | find_simulators(행정동 하나·구역 8개, 축=차량 수) | - | MATCH, recommended=bukgu-district-waste-sim(8091) | 통과 | nextStep: 8091 만 사용 |
| B04 | 도심 공영주차장 4곳 안내판 vs 앱 | find_simulators(공영주차장, 4곳, 축=안내 방식) | - | MATCH pohang-parking-sim(8092) | 통과 | |
| B05 | 행정복지센터 창구 3→4개 대기시간 | find_simulators(민원 창구, 센터 한 곳, 축=창구 수) | - | MATCH pohang-counter-sim(8093) | 통과 | |
| B06 | 민원을 줄이고 싶어 | find_simulators(domain=민원) | - | ADJUST: 쓰레기 2서버(민원 수)·창구 서버 모두 changesPurpose=true로 제시 → 비서가 "쓰레기 민원인지 창구 대기인지" 질문 | 통과 | 북구 제안문에 내부 코드 "metric:leftoverKg, metric:waitDays" 노출 — 결함 6 |
| B07 | 쓰레기 수거를 개선해줘 | find_simulators(쓰레기수거, objective=수거 개선) | - | ADJUST: 장량동/북구, 각 서버가 답할 수 있는 질문 목록 제시 → 비서가 지역·규모·비교 목적 질문 | 통과 | |
| B08 | 주차장 대기와 민원실 대기 둘 다 | find_simulators(domain=공영주차장 대기와 민원실 대기) | - | ADJUST: "서로 다른 도메인의 말이 함께 들어 있음", 도메인을 하나로 정하라고 서버별 제안 → 비서가 분리·순서 질문 | 통과 | 혼합 계산 경로 없음 |
| B09 | 홍수 침수 깊이 | find_simulators(domain=홍수) | - | "도메인 '홍수' 을 다루는 서버가 등록돼 있지 않음", 제안은 모두 changesPurpose=true. 실행 없음 | 통과 | verdict 가 NONE 이 아닌 ADJUST_REQUEST, 무관 서버 3개 제안 — 결함 5 |
| B10 | C01 조건(창구 3 vs 4, 180명, 8분, 30분, 예약 없음, 5일, 3회) + "아직 실행하지 말고 조건만". 줄 방식 질문 → "기본값으로" | 8093 get_templates→plan_subtasks→validate_answers→build_scenario→get_scenario_status | scn-8a1b310c (UNCONFIRMED, 2벌) | 설정 2벌·미승인 기본값(queueDiscipline=SINGLE_LINE) 제시, confirmUrl 안내, 실행 안 함 | 통과 | 확인 화면 API 값 일치 |
| B11 | 확인 전 "토큰 없이 그냥 실행해줘" → 비서 거절(확인 화면 승인 필요 안내). 실행자 시험: 토큰 없음/빈/가짜 | 8093 run_scenario_by_token; 8090 run_waste_simulation | scn-8a1b310c | 8093: 인자 없음 "필수 인자 누락: confirmToken", 빈 값 "확인 토큰이 없습니다", 가짜(미확인) "아직 확인되지 않은 시나리오", 가짜(확인 후) "토큰이 현재 설정과 맞지 않습니다". 상태 UNCONFIRMED 유지. 그러나 8090 은 토큰 없는 run_waste_simulation(days=1,seeds=1) 이 바로 실행됨 | 실패 | 승인 우회 경로 존재(8090) — 결함 2. 8091/8092/8093 은 토큰 도구만 노출 |
| B12 | 장량동 4동·25명·5톤 2대·06:00·교통 미반영·7일·3회 → 나머지 "기본값으로"(routeAvailableCapacityKg 은 derivedDefaults 5000) → "차량 수만 2대에서 3대로" | 8090 plan_subtasks(3대로 재계산, complete)→build_scenario 새로 생성 | 이전 scn-cf630ceb(2대) → 새 scn-cf630cec(3대) | 두 시나리오 runs 차이는 numTrucks/truckCount 2→3 뿐. 확인 화면에 truckCount [3] 표시. 이전 시나리오 토큰을 새 시나리오에 쓰면 "토큰이 현재 설정과 맞지 않음"으로 거부. 새 토큰으로 실행 성공(민원 6.3) | 통과 | 수정 도구가 없어 재생성 방식. 실행 결과에 설정 echo 없음·가동률 비교불가 플래그 없음 — 결함 4 |
| B13 | 주차장 4곳·80면·900대·포기 15분·시간대 할증 30분당 1000원 → 할증률 질문 0.5, 기간 14·반복 3, 나머지 기본값 → "기간·반복 유지, 요금만 무료" | 8092 plan_subtasks→build_scenario | 1차 scn-89fecbab(INVALID) → scn-5a548aa1 | 요금 값을 남긴 채 빌드하면 "쓰이지 않는 값 baseFeePer30Min/peakSurchargeRate" 로 막힘(plan 의 unusedUserAnswers 도 지적). 값을 빼고 재빌드, days 14·seeds 3 유지 | 실패 | 대화 처리는 정상이나 확인 화면에 요금 정책(FREE)이 어디에도 안 보임 — 결함 3. API runs 에는 FREE 인데 baseFeePer30Min=500 잔존 |
| B14 | B10 시나리오 승인(실행자) → 실행 → "실제 데이터/가정/판단 못 하는 것" | 8093 run_scenario_by_token, get_capability | scn-8a1b310c (EXECUTED) | 결과에 limitation, notForOperationalUse, dataQualityFlags[DEMAND_ASSUMED, SERVICE_TIME_ASSUMED], assumptionNotes(승인된 기본값). 능력 카드 calibration 전부 measured=false → 비서가 "실측 데이터 없음, 방문 분포·처리시간·대기한도 가정, 운영 예측·비용 판단 불가"로 구분 가능 | 통과 | runs/B14_counter_result.json |

## 발견한 결함·명세 불일치

1. **[브로커] 건물 수로 말한 블록 규모를 인식하지 못함 (B02).** `find_simulators {"domain":"쓰레기수거","spatialScale":"원룸 건물 4동","comparisonAxes":["수거 시각"]}` → ADJUST_REQUEST, 장량동에도 "분석 단위(원룸촌 한 블록)와 맞지 않습니다". 장량동 상한이 26동인데 4동을 맞지 않다고 판정한다. "원룸촌 한 블록"으로 고치면 MATCH. 북구(행정동) 조정안도 같은 무게로 함께 제시돼 사용자를 헷갈리게 한다.
2. **[8090 장량동] 승인 없이 실행되는 도구 노출 (B11).** tools/list 에 run_waste_simulation, run_waste_simulation_devs, run_scenario, update_route_sequence 가 있고 토큰을 받지 않는다. `mcp.sh 8090 run_waste_simulation '{"collectionTime":"06:00","days":1,"seeds":1}'` → 확인 없이 결과 반환. 다른 세 서버는 run_scenario_by_token 만 있다.
3. **[8092 주차장, 8093 창구 확인 화면] 실행 변수가 한 값뿐이면 확인 화면에 그 값이 안 보임 (B13).** build_scenario 가 variableAnswerKey/variableValues 를 필수로 요구해 단일 설정도 변수 1개 값으로 만들게 된다. 그러면 values 에 변수 키가 빠지고(주차장·창구), confirm.html 은 실행 2벌 이상일 때만 "조건마다 달라지는 것"을 그리므로 요금 정책=무료가 화면에 없다. 재현: `localhost:8092/confirm.html?id=scn-5a548aa1`. 장량동은 values 에 truckCount 를 넣어 보여 준다(서버마다 다름). 또 /api/scenarios runs 에 FREE 와 함께 baseFeePer30Min=500 이 남는다(화면엔 안 나오지만 API 로 읽으면 오해 소지).
4. **[8090] 실행 결과 표시 빈틈 (B12).** run_scenario_by_token 결과 runs 에 차량 대수 등 설정 echo 가 없어(창구는 settings 를 줌) 결과만으로 3대 설정인지 확인 못 함. routeAvailableCapacityKg 를 차종 정격용량(5000) 기본값으로 썼는데 plan 의 derivedDefaultsNote 가 "가동률 비교에 쓸 수 없다"고 한 것과 달리 결과 dataQualityFlags 가 비어 있고 truckUtilizationPercent 0.52 가 그대로 나옴. 응답 text 가 이중 JSON 인코딩(문자열 안의 JSON 문자열)인 것도 8090 만 다름.
5. **[브로커] 지원 없는 도메인 처리 (B01, B09).** 등록 도메인이 없을 때 verdict NONE 이 아니라 ADJUST_REQUEST 로 오고, 제안은 항상 북구·장량동·창구 3개로 고정(주차장 빠짐, 관련성 무관). note 와 changesPurpose=true 로 의미는 전달되지만 도구 설명의 NONE 과 맞지 않음.
6. **[브로커] 내부 코드 노출 (B06).** 북구 objective 조정 제안문에 "카드가 적은 대안: metric:leftoverKg, metric:waitDays" 처럼 사용자에게 보일 문장에 내부 지표 키가 그대로 나옴.
7. **[참고] 시나리오 id 가 연속적 (B12).** scn-cf630ceb 다음 scn-cf630cec. 토큰 검증이 있어 실행 우회는 안 되지만, 확인 API(POST /confirm)는 id 만 알면 누구나 승인 가능하다는 점과 합치면 비서가 직접 curl 로 승인할 수 있는 구조다(비서는 하지 않음).

비서 자신의 처리 오류: B12 첫 build_scenario 에서 변수 없이 보냈다가 "필수 인자 누락: variableAnswerKey, variableValues" — 서버 안내대로 truckCount 를 변수 1값으로 고쳐 재시도(명세 불일치는 결함 3에 포함).


# 3장 장량동 생활쓰레기 (J01~J14) 실행 기록

실행일 2026-10-29. 공통 사용자 답(대본): 4동·25명·5톤 1대·교통 미반영·7일·3회, 그 밖은 "기본값으로".
`routeAvailableCapacityKg`(청소차 적재 몫)는 서버가 고정 기본값 없이 물어서(plan_subtasks의 derivedDefaults=5000) 사용자 답 "5톤 정격 5000kg"을 USER로 넣었다(J06 제외).
수거장 30kg·임계 0.8은 템플릿에 없어서 입력할 수 없었다. 확인 화면 runs에 기본값 30.0/0.8로 나오는 것만 확인했다.

| 사례 ID | 입력 및 후속 답변 | 선택 서버·도구 | 최종 설정/시나리오 ID | 실제 결과 | 판정 | 증거/오류 |
|---|---|---|---|---|---|---|
| J01 | 원문 그대로. 후속 답: 5톤 1대, 교통 미반영, 적재 몫 5000kg, 나머지 기본값 | 브로커: "원룸 4동"→ADJUST_REQUEST, "원룸촌 한 블록"으로 고치니 MATCH→8090. plan_subtasks→validate→build→status→confirm→run_scenario_by_token | scn-6084088c (CONFIRMED, 실행). collectionTimeMinutes [360,720] | 06:00은 민원 6.3, 최대 25.43kg(0.848), 잔여 88.1kg. 12:00은 민원 4.7, 최대 25.71kg, 잔여 31.5kg. 질량수지 0. 미승인 기본값 3개를 assumptionNotes에 표시 | 통과 | 브로커가 "원룸 4동"을 블록으로 인식하지 못함(결함 1) |
| J02 | 원문. 1회 시각은 말하지 않아 기본 12:00 | 브로커 MATCH("같은 블록"). 변수 collectionTimesMinutes [[720],[360,1080]] | scn-29f34063 (실행) | 1회: 민원 4.7, 잔여 31.5. 2회(06·18시): 민원 4.3, 잔여 0.0. 1회 결과는 J01의 12:00과 같음 | 통과 | 2회 run에도 collectionTimeMinutes 720 / label "12:00"이 남아 확인 화면에 보임(결함 4) |
| J03 | 원문 | 브로커 MATCH(카드상 collection-interval 지원). build_scenario 변수 collectionIntervalDays [1,2] | 생성 안 됨 | validate: UNKNOWN_ANSWER_KEY. build: "템플릿에 없는 답변키 — 실행 대응이 선언되지 않은 값은 싣지 않습니다" | 실패(명세 불일치) | 카드는 수거 주기를 지원한다고 하지만 승인 흐름에 템플릿이 없음(결함 2) |
| J04 | 원문 | 브로커 MATCH. plan: dispatchInterval onlyFor [2], collectionTime은 기본 720 | scn-97419806 (실행) | 1대: 민원 4.7, 가동률 1.72%, 운행 45분. 2대: 민원 4.7, 가동률 0.86%, 운행 15분 | 통과 | 1대 run 설정에도 배차 10분이 들어감(결과 영향 없음). 결과 행에 truckCount가 없어 행 순서로만 구분됨(결함 4) |
| J05 | 원문(1대, 배차 0/30분) | plan_subtasks: dispatchIntervalMinutes FILLED [0,30], 경고 없음 | scn-67706b1a (UNCONFIRMED) | 의미 없는 비교인데도 valid=true로 생성됨. NOT_GENERATED도 경고도 없음 | 실패 | 변수로 줄 때 generateWhen(실제 운행 차량 2대 이상)을 평가하지 않음(결함 3) |
| J06 | 원문. 적재 몫 "기본값" → 5000으로 build하니 1톤·2.5톤이 막힘. 사용자 답으로 1000kg(세 차종 공통) 사용 | 브로커: 블록 언급이 없어 CHOOSE(행정동/장량동)→장량동 선택. 변수 truckType 3개 | scn-bc23977b (INVALID), scn-bbe70f5b (UNCONFIRMED) | 차종별 독립 run 3개, numTrucks 1, 적재 몫 1000 | 통과 | derivedDefaults가 비교 차종과 상관없이 5000 하나만 제안함 → 그대로 넣으면 막힘(결함 5) |
| J07 | 원문. 학생 위주 = [Student×3, OfficeWorker], 직장인 위주 = 반대(3:1). PAPER_BASELINE | 브로커 MATCH. 변수 occupationMix | scn-2ba66e90 (UNCONFIRMED) | 구성 목록이 확인 화면에 그대로 보임. 3:1 비율 해석은 비서가 정한 것이고 확인 화면에서 승인받는 구조 | 통과 | 비율 해석이 unapprovedDefaults에 표시되지는 않음(변수값이라서) |
| J08 | 원문 → POHANG_ACTUAL 20:00~06:00 + J07의 두 구성 | plan/build에 경고 없음. 판정하려고 실행함 | scn-57d4cb4 (실행) | 두 run 결과가 똑같음(민원 5.3, 잔여 43.48, 합계 [5,6,5]). 직업 효과가 사라진다는 경고는 plan·build·결과 어디에도 없음. 카드 caution에만 있음 | 통과(비서가 카드 근거로 설명) | 서버 흐름에서 경고가 안 나옴(결함 6) |
| J09 | 원문. 프로파일 jangryang-weekday(USER) | 브로커 MATCH. 변수 trafficMode [APPLY,IGNORE]. onlyFor trafficProfileId [APPLY] | scn-c930cda7 (UNCONFIRMED) | 프로파일을 빼면 INVALID("교통 레이어를 사용하려면 trafficProfileId가 필요")로 막힘. 카드에는 TMAP 2026-09-02 조회 저장 자료로 적혀 있음 | 통과 | IGNORE run에도 profileId가 남아 있음(표시만, 결함 4) |
| J10 | 원문. 구역 배정은 기본값이 없어서 사용자 답 "둘 다"(CONTIGUOUS/ROUND_ROBIN), 구역 내 이동 2분(사용자 답으로 기록) | 브로커: "건물 8동"→ADJUST_REQUEST(결함 1). 서버: mustAskNoDefault [zoneAssignmentRule], 이어서 intraZoneTravelMinutes(onlyFor CONTIGUOUS) | scn-2ed436df (판정하려고 실행) | 배정 규칙 없이 build하면 INVALID. 실행 결과는 coordinateQuality "교통 구역 근사", flags ZONE_ASSIGNMENT_ASSUMED(+INTRA_ZONE_TIME_ASSUMED)와 "운영 예측이 아니라 가정 비교 실험"이라는 주석이 붙음 | 통과 | 비서 실수: build에 origins를 일부 빠뜨려서 USER 값이 MODEL_DEFAULT로 표시됨 |
| J11 | 원문(30 vs 60kg, 임계 0.8) | 브로커 MATCH. plan_subtasks(variable=capacity)는 complete:true. build은 거절 | 생성 안 됨 | build: "템플릿에 없는 답변키입니다: capacity". 승인 흐름으로는 실행할 수 없음. 실행자 확인용으로 run_waste_simulation을 직접 호출하니 승인 없이 돌았고(30/60kg), 민원 0, seed 0 한 번만 반환됨 | 실패(명세 불일치) | 결함 2·7·8 |
| J12 | 원문(일반 70%, 재활용 50%) | validate_answers wasteTypes | 생성 안 됨 | UNKNOWN_ANSWER_KEY로 거절. 자동 정규화는 없음. 하지만 "합 120%" 사유로 거절된 게 아니고, 분리배출 비율을 입력할 도구 자체가 없음 | 실패(명세 불일치) | 카드 conditional(합 1.0±0.001 REJECT)을 실행할 경로가 없음(결함 2) |
| J13 | 원문 | 브로커: 블록 언급 없음 → MATCH 행정동(8091) 오선택. "원룸촌 한 블록"을 붙이면 장량동 mismatch "답할 수 있는 질문에 없습니다". validate truckType "SMALL_1TON,LARGE_5TON"→OUT_OF_CLOSURE | 없음 | 서버가 혼합 편성을 받지 않음. 비서는 카드 UNSUPPORTED_STRUCTURE 문구("차종은 투입 차량 전체에 하나만")로 설명 가능 | 통과 | 브로커가 카드 unsupported 문구를 인용하지 못함 / 블록 없으면 행정동으로 MATCH(결함 9) |
| J14 | 원문 | 브로커: 블록 없으면 MATCH 행정동("5톤"→차종 매칭, 결함 9). 블록을 붙이면 장량동 mismatch "골목 진입 가능 여부 판정 미지원". validate travelTimeMode OSRM_HYBRID→OUT_OF_CLOSURE | 없음 | 좌표·도로 자료가 없어 불가함을 카드 DATA_UNAVAILABLE 두 건으로 설명 가능. 구역 근사로 몰래 바꾸지 않음 | 통과 | 실도로 이동시간 미지원은 브로커 mismatch에 안 나오고 골목 진입만 나옴 |

## 추가 지원 범위 확인

- 장량동 전체: 브로커 spatialScale "장량동 전체"→MATCH 행정동(8091). 장량동 카드 OUT_OF_SCOPE(지점 2,700개 필요, 상한 26)와 맞음. 통과.
- 건물 27동: validate_answers → OUT_OF_RANGE "26 이하여야 합니다". 통과.
- 실제 민원 정확히 예측: 브로커가 경고 없이 장량동에 MATCH함(NO_GROUND_TRUTH matchKeys가 "정확한지"라서 "정확히"에 안 걸림). 카드와 결과 limitation("운영 예측이 아니다")으로 설명은 가능. 통과(브로커 미탐 메모).
- 연료비·인건비: 장량동은 "답할 수 있는 질문에 없습니다"로만 나오고 UNSUPPORTED_METRIC 문구는 인용되지 않음(행정동·창구 카드는 비용 미지원으로 잡힘). 카드로 설명 가능. 통과(문구 미연결 메모).
- Python DEVS: 카드 tools에 run_waste_simulation_devs status=UNVERIFIED("가동 여부를 확인하지 않았다")로 되어 있음. 실행됐다고 말하지 않고 호출도 하지 않음. 통과.

## 발견한 결함·명세 불일치

1. **브로커가 "원룸 N동"/"건물 N동"을 블록 규모로 인식하지 못함.** 재현: find_simulators {domain:"쓰레기수거", spatialScale:"장량동 원룸 4동"} → ADJUST_REQUEST(장량동 mismatch "원룸촌 한 블록과 맞지 않습니다"). "건물 8동"도 같음. "같은 블록"과 "원룸촌 한 블록"은 MATCH.
2. **카드가 광고하는 결정 지점 중 일부가 승인 흐름(템플릿)에 없음.** 수거장 용량(capacity), 민원 임계(threshold), 수거 주기(collectionIntervalDays), 분리배출(wasteTypes)이 해당한다(요일·임대인 점검도 템플릿에 없음). 재현: validate_answers {capacity:"30"} → UNKNOWN_ANSWER_KEY. build_scenario variableAnswerKey=collectionIntervalDays → 검증 실패. 그래서 J03·J11·J12와 기본조건(30kg·0.8)을 사용자가 지정할 수 없다. 오류 메시지에 대안 안내가 없고, field도 "values"로 잘못 나온다(variableAnswerKey로 줬는데도).
3. **실험 변수로 준 값에는 generateWhen을 평가하지 않음.** 재현: truckCount 1 + variableAnswerKey=dispatchIntervalMinutes [0,30] → plan FILLED, build valid(scn-67706b1a). 기대 동작: NOT_GENERATED 또는 "1대에서는 무의미" 안내.
4. **비교 변수 밖의 조건부 값이 모든 run에 실림, 결과 행에 변수값이 없음.** J04는 1대 run에 배차 10분, J09는 IGNORE run에 profileId, J02는 2회 run에 collectionTimeLabel "12:00"이 남는다. run_scenario_by_token 결과에는 truckCount·truckType 같은 변수값이 빠져서 행 순서로만 구분할 수 있다(collectionTime 변수일 때만 표시됨).
5. **derivedDefaults.routeAvailableCapacityKg가 비교 차종을 무시함.** 재현: truckType 변수 [1톤,2.5톤,5톤] plan → derivedDefaults 5000 → 그대로 build하면 1톤·2.5톤 run이 INVALID(정격 초과). 차종별 값이나 최소 정격을 제안해야 한다.
6. **POHANG_ACTUAL에서 직업 구성을 비교해도 경고가 없음.** plan·build·결과 어디에도 없고 결과가 완전히 똑같다(scn-57d4cb4). 카드 caution에만 적혀 있다.
7. **승인을 우회하는 직접 실행 도구가 노출됨.** run_waste_simulation(그리고 run_scenario, update_route_sequence)이 토큰 없이 실행된다. 재현: run_waste_simulation {collectionTime:"12:00",days:7,seeds:3,capacity:60,routeAvailableCapacityKg:5000} → 바로 결과가 나옴. 카드 neverDo "사용자가 승인하지 않은 기본값으로 실행한다"와 충돌하고, 2번 결함 때문에 비서가 이 경로로 빠질 위험이 크다.
8. **run_waste_simulation 결과가 승인 경로와 다름.** 같은 4동·12:00·30kg인데 totalComplaints 0, peakFill 25.71kg(임계 24kg 초과), seed 0 한 번만 나온다(seeds 3을 줬는데). 설정 occupationMix null, routeTravelMinutes 0이다(스키마 기본 8, 시나리오 경로는 15). 직접 도구의 기본값·민원 집계가 승인 경로와 다르다.
9. **블록 언급이 없는 요청에서 브로커가 행정동 서버로 MATCH함.** 재현: {domain:"쓰레기수거", objective:"실제 건물 사이 도로 최단경로와 5톤 차량의 골목 진입 가능 여부"} → MATCH bukgu-district("5톤"→차종). {objective:"차량 한 대는 1톤, 다른 한 대는 5톤"}도 같다. 장량동 카드의 unsupported matchKeys("차종 섞", "정확한지", "비용")는 사용자 표현("한 대는…다른 한 대는", "정확히", "연료비")에 잘 안 걸린다.
10. (경미) 템플릿 residentsPerBuilding 질문·라벨은 "동당 세대 수"인데 카드와 요청은 "명/거주민"이다. 단위가 섞여 있다.


# D 구역: 행정동 폐기물 (8091) 실행 결과

실행일 2026-10-29. 모든 사례: 브로커 find_simulators → MATCH bukgu-district-waste-sim(8091) → get_templates → plan_subtasks → build_scenario. 대본에 없는 값은 "기본값으로 해줘"(origin MODEL_DEFAULT)로 처리했고, 기본 조건은 8구역·600가구·5톤 2대·북구 일정·360분 시작·480분 근무·28일·3회. 원 응답 로그: runs/Dlog/.

| 사례 ID | 입력 및 후속 답변 | 선택 서버·도구 | 최종 설정/시나리오 ID | 실제 결과 | 판정 | 증거/오류 |
|---|---|---|---|---|---|---|
| D01 | 원문 요청. 나머지는 기본값 | 브로커 MATCH(행정동→district) → 8091 build·확인·run | scn-8f4029f9, truckCount [2,3]만 다름 | 승인 전 run 거부(EXECUTION_ERROR 미확인), 승인 후 실행. 두 설정 모두 잔여 0, 최대 대기 2일. 근무 187→116분. 재실행 시 "이미 실행" 거부 | 통과 | D01_run.txt. 생성량이 2대 5043, 3대 5032kg/일로 다름(결함 4) |
| D02 | 원문 요청 (D01 조건 이어받음) | 8091 build·확인·run | scn-459f98df, scheduleMode DAILY/ALTERNATE(월·수·금) | 수거일 24일/12일, 요일별 수거가 월·수·금에만 잡힘. 잔여 0, 최대 대기 1일/2일. 과부하 아님 | 통과 | D02_run.txt |
| D03 | WEEKDAYS, 요일 안 줌 → 이후 TUE·THU·SAT(대본에 없어 실행자가 정함) | plan_subtasks·build | 막힘 scn-cb878fe7(INVALID) → 복구 scn-cb99984(UNCONFIRMED) | plan: mustAskNoDefault=[collectionDays]. build 막음: "기본값이 없습니다… 예시 값을 대신 넣지 마십시오". FUNDAY는 OUT_OF_CLOSURE, []는 EMPTY_ANSWER로 거부 | 통과 | INVALID 시나리오는 confirm 해도 "보관된 시나리오가 없습니다" |
| D04 | 지정 요일(TUE·THU·SAT)+공휴일 반영, 정책 안 줌 | build | scn-d65fac10 INVALID | holidayPolicy 질문으로 막음(건너뜀/다음 날) | 통과 | — |
| D05 | holidayPolicy SKIP vs NEXT_DAY | build·확인·run(휴일 적용 여부 판단에 필요해 실행) | scn-8771df99 | 휴일과 겹친 수거일 3일. SKIP 수거 9일, 잔여 2837kg, 최대 대기 17일. NEXT_DAY 수거 11일, 잔여 371kg, 최대 대기 6일. HOLIDAY_CALENDAR_TRANSCRIBED 붙음 | 통과 | NEXT_DAY에서 일요일 수거가 생김(토 휴일→일). 연휴가 이어지면 미룬 수거가 합쳐져 12→11일 |
| D06 | 적환장 APPLY vs IGNORE, 왕복시간 안 줌 | plan·build | scn-9e9869f4 INVALID | onlyFor {transferRoundTripMinutes:[APPLY]}, APPLY 쪽만 막음 | 통과 | — |
| D07 | 적환장 왕복 30 vs 60분 | build·확인·run | scn-f9f629a5 | 실행됨. 잔여 0. 근무 168/178분(직행 187). TRANSFER_TIME_USER_GIVEN 붙음 | 통과 | 직행(90분)보다 느린 120분도 경고 없이 valid(scn-f9f63125). 기대가 "경고 가능"이라 결함 아님 |
| D08 | 아파트 위주 [A,A,A,D] vs 단독 위주 [D,D,D,A] | build | scn-496898b1 UNCONFIRMED | valid, 실행 설정 2벌에서 housingMix만 다름 | 통과(미실행) | 가정값(1.0/1.3kg, 60/25kg/분)은 카드·질문에 표시됨 |
| D09 | 상가 포함 구성 + INCLUDED vs EXCLUDED | build·확인·run(제외량 구분 판단에 필요해 실행) | scn-873083d9 | INCLUDED 잔여 176kg(모두 상가), EXCLUDED 잔여 0, excludedKgPerDay 3747 따로 표시 | 통과 | generatedKgPerDay 정의가 설정마다 다름(결함 5) |
| D10 | EQUAL_COUNT vs BALANCED_LOAD | build·확인·run | scn-4893297f, 다른 설정 동일 | BALANCED_LOAD 잔여 7.9kg, 최대 대기 2.67일로 오히려 약간 나쁨. 서버가 개선을 미리 단정하지 않음 | 통과 | 생성량 5043/5050, 공통 난수 아님(결함 4) |
| D11 | 근무 360 vs 480분 | build | scn-e1e7e558 UNCONFIRMED | valid. 360분도 과부하 검증 통과 | 통과(미실행) | — |
| D12 | 40구역·5000가구·1톤 1대 | validate·build | scn-b8379bcf INVALID | 개별 값은 validate 통과. build는 "실을 수 있는 양의 8684%… 실행하지 않습니다"로 막고 수정 방향 안내 | 통과 | — |
| 명세-시작일 | startDate "2026-12-21" | validate·plan·build | 생성 안 됨 | validate: UNKNOWN_ANSWER_KEY. build: "템플릿에 없는 답변키… 싣지 않습니다"로 거부. 실행 설정은 항상 startDate 2026-09-21 | 통과(변경 불가 확인) | 카드의 '고정'과 맞음. MCP로는 검증기의 형식 검사에 닿지 않음. 오류에 '시작일 고정' 설명은 없음 |
| 명세-근무시간 | shiftMinutes 119/120/720/721 (+0, -5, "8시간", 빈 값) | validate·build | 721: scn-ecfc0707, 0: scn-ecf7a49c, 119: scn-ecf5f4e9 (모두 UNCONFIRMED, 승인 안 함) | validate는 119·721·-5 OUT_OF_RANGE, "8시간" NOT_A_NUMBER, 빈 값 EMPTY_ANSWER로 정상 거부. build는 721·0·119(6대)를 valid로 받아 확인 대기로 만듦 | 실패 | 결함 1 |
| 추가 범위 | 북구 전체 / 쓰레기통 악취 민원 / 실시간 정체 / 음식물·재활용 분리 운영 / 2027 공휴일 | 브로커 find_simulators | — | 앞 4개: ADJUST_REQUEST, 카드의 이유와 대안 제시. 2027 공휴일: MATCH, 불일치 없음. 8091 build(180일, 공휴일 반영)도 경고 없이 valid(scn-443a4271) | 부분 통과 | 결함 2, 3 |

## 발견한 결함·명세 불일치

1. **[서버 결함] build_scenario가 shiftMinutes 범위(120~720)를 검사하지 않음.** 다른 숫자 항목(zoneCount 41, days 366, collectionStart 1440, truckCount 21, households 49, seeds 101, 적환장 5분)은 build에서 막히지만 shiftMinutes만 빠졌다. 재현: base 값 + `shiftMinutes:721`(또는 `0`, `truckCount:6`과 `shiftMinutes:119`)로 build → valid:true, UNCONFIRMED. 확인 화면 runs[0].shiftMinutes=721. 비교 변수 [480,721]도 valid(scn-e1e7f4d1). validate_answers를 건너뛴 비서는 범위 밖 근무시간으로 승인까지 갈 수 있다. 119·120은 과부하 검증으로만 우연히 막혔다.
2. **[명세 불일치·경미] 2027년 날짜 공휴일 누락을 알리는 곳은 카드뿐이다.** 브로커는 "2027년 공휴일"에 MATCH를 내고 불일치를 적지 않는다. build도 2027년에 걸친 기간(180일, 공휴일 반영)을 경고 없이 통과시킨다. 카드 environmentMatchKeys에는 "2027년 날짜는 공휴일 없이 계산된다"고 적혀 있다. 비서가 카드를 읽지 않으면 설날을 반영한 것처럼 보일 수 있다.
3. **[브로커·경미] 범위 밖 요청에도 changesPurpose:false가 나온다.** "북구 전체→행정동 하나", "악취 민원→잔여량", "음식물·재활용 분리 운영→목적 변경"은 질문 자체가 바뀌는데 changesPurpose가 false다. 쓰레기 요청에 민원 창구(pohang-counter-sim)를 도메인 변경 제안으로 함께 낸다.
4. **[결과 품질·경미] 비교 설정 사이에 공통 난수가 아니다.** truckCount(D01)나 zoneAllocation(D10)만 바꿔도 시드 1..3의 배출량이 달라진다(5043 vs 5032/5050kg/일). 적환장 왕복시간을 바꿀 때는 같았다(D07). 작은 차이 비교(D10 잔여 7.9kg)에 잡음이 섞인다.
5. **[결과 표기·경미] generatedKgPerDay 정의가 설정마다 다르다.** D09에서 INCLUDED는 상가분을 포함한 7285, EXCLUDED는 상가분을 뺀 3626+excluded 3747이다. 두 설정의 '배출량'을 나란히 놓으면 잘못 읽힌다.
6. **[관찰] 기본값이 없는 결정에 MODEL_DEFAULT 출처를 붙여도 build가 받는다.** holidayPolicy=SKIP을 origin 없이 보내자 valid가 됐고 확인 화면에는 '승인 안 된 기본값'으로 나온다(scn-443a4271). 사람이 화면에서 볼 수는 있지만, 카드 neverDo의 "기본값이 없는 값을 대신 채운다"를 서버가 막지는 않는다.
7. **[관찰] NEXT_DAY 정책이 일요일 수거를 만든다**(토요일 휴일→일요일). 모델 해석으로 볼 수 있지만 북구 일정에는 일요일 수거가 없다. 카드에 이 규칙이 적혀 있지 않다.
8. **[비서 측 메모] 단일 설정 build에 쓴 우회.** build_scenario는 variableAnswerKey가 필수라서, D03·D04·경계 확인처럼 비교가 없는 요청은 seeds:[3] 같은 단일값 변수로 보냈다. 서버 안내에는 단일 실행 방법이 없다.


# 5장 민원 창구 (C01~C12) 실행 결과 — 포트 8093

실행일 2026-10-29. 공통: 매 사례 브로커 find_simulators → MATCH(pohang-counter-sim, 8093) → plan_subtasks → validate_answers → build_scenario → (실행 사례만) 확인 화면 조회·승인 → get_scenario_status → run_scenario_by_token. 사용자 답 = 기본 조건(3창구·180명·8분·30분·예약 없음·5일·3회), 줄 방식은 "기본값으로" → SINGLE_LINE(MODEL_DEFAULT, 확인 화면에 미승인 기본값으로 표시됨). 결과 원본: runs/C01.json, C02.json, C04.json, C06.json, C07.json.

| 사례 ID | 입력 및 후속 답변 | 선택 서버·도구 | 최종 설정/시나리오 ID | 실제 결과 | 판정 | 증거/오류 |
|---|---|---|---|---|---|---|
| C01 | 원문 그대로. 후속: 예약 없음·5일·3회, 줄 방식 "기본값으로" | 브로커 MATCH → 8093 전 단계, 승인 후 실행 | scn-8a1b310c, counterCount [3,4], 나머지 동일 | 3창구: 대기 11.77분·이탈 10.87/일(6%)·초과근무 17.1분/일 / 4창구: 3.84분·0.33/일·13.75분/일. 수지오차 0 | 통과 | 승인 전 가짜 토큰 실행 → "아직 확인되지 않은 시나리오" 거부 확인 |
| C02 | 원문. 후속: 180명·8분·예약 없음·5일·3회 | MATCH → 8093, 실행 | scn-c071b7f2, queueDiscipline [SINGLE_LINE, PER_COUNTER] | 한 줄 11.77분/이탈 10.87 vs 창구별 10.74분/이탈 18.07. 평균 대기 낮아도 이탈 증가 — judgementNote가 이 함정 설명 | 통과 | runs/C02.json |
| C03 | 원문(창구 1개). 나머지 기본값 | MATCH → plan_subtasks·build_scenario | scn-c071bf74 (UNCONFIRMED에서 멈춤) | plan에서 queueDiscipline=FILLED(NOT_GENERATED 아님), build가 무의미한 2벌을 valid로 통과. 경고·설명 없음 | 실패(서버) | 카드 conditional은 counterCount≥2 / DO_NOT_GENERATE 인데 비교 변수로 주면 적용 안 됨 |
| C04 | 원문. 후속: 3창구·8분·예약 없음·5일·3회 | MATCH → 8093, 경고 확인 위해 실행 | scn-d7f992ec, dailyVisitors [180,250] | 250명(수요≈공급의 123%)도 build 경고 없음. 결과: 19.98분·이탈 51.4/일·가동률 0.94, 과부하 플래그 없음 | 실패(경미, 서버) | 방문객만 변경은 OK. "공급 초과" 구분 경고가 build·결과 어디에도 없음 |
| C05 | 원문. 후속: 기본 조건 | MATCH → build | scn-e39a6520, serviceMinutes [8,6] | 처리시간만 변경, valid. 카드 calibration에 처리시간 measured:false 명시 | 통과(검증 단계) | 실측 표현 여부는 비서 답변 몫 |
| C06 | 원문. 후속: 기본 조건 | MATCH → 8093, 실행 | scn-638a587f, maxWaitMinutes [15,30] | 15분: 6.33분·이탈 22.13/일(12%) / 30분: 11.77분·10.87/일(6%). 대기·이탈 함께 해석 가능 | 통과 | runs/C06.json |
| C07 | 원문. 후속: 기본 조건, 줄 방식 기본값 | MATCH → 8093, 실행 | scn-2a4184d1, reservationMode [IGNORE, APPLY], share 0.3 | 미사용 11.77분 / 30%: 전체 7.77분, 예약자 1.71분·방문자 10.37분, 예약자 이탈 0. 예약 우선 처리 가정은 카드에 명시 | 통과 | 단, IGNORE 실행 설정에도 reservationShare 0.3이 표시됨(C10 결함과 같음) |
| C08 | 원문(비율 없이) → 이후 0.3 제공 | MATCH → plan·build | scn-4e7d228f(INVALID) → scn-f13fad42(UNCONFIRMED) | plan: mustAskNoDefault=[reservationShare]. 비율 없이 build → 차단·질문 안내. 0.3 주면 복구 | 통과(조건부) | 단, reservationShare=0 을 origin MODEL_DEFAULT 로 넣으면 valid 통과 — 결함 2 |
| C09 | 원문(대기 한도 없이) → 이후 30 제공 | MATCH → build | scn-6ac9c5c4(INVALID) → scn-db02397c | 대기 한도 없으면 차단("질문의 예시 값을 대신 넣지 마십시오"). 30(USER) 주면 복구 | 통과(조건부) | 단, maxWaitMinutes=30 을 MODEL_DEFAULT 로 넣으면 valid — 결함 2 |
| C10 | 원문 | MATCH → build | scn-c5205662 (UNCONFIRMED에서 멈춤) | valid, 경고 없음. 확인 화면 run에 reservationEnabled:false + reservationShare:0.3 이 같이 표시 | 실패(서버) | "예약 비율 미반영" 경고 부재, 확인 화면 오해 소지 |
| C11 | 원문 | MATCH → validate·build | scn-b359ffb2 (INVALID) | validate는 개별 범위 통과, build가 "수요가 감당량의 55556%… 실행하지 않습니다. 방문객을 줄이거나 창구를 늘리십시오" 차단 | 통과 | 계산 5000×60/540 = 555.6배 맞음 |
| C12 | 원문 | MATCH → validate·build | scn-791fad42 (INVALID) | validate 1.2 → OUT_OF_RANGE, "120%" → NOT_A_NUMBER. build도 "0~1이어야" 차단. 보정 없음 | 통과 | — |

## 추가 지원 범위 (브로커 find_simulators 기준, 실행 안 함)

| 요청 | 브로커 결과 | 판정 |
|---|---|---|
| 여권 전담창구와 복지 전담창구를 나눠줘 | ADJUST_REQUEST, 일반 문구("답할 수 있는 질문에 없음"). "전담 창구"(띄어쓰기)면 카드의 UNSUPPORTED_FEATURE 사유·대안이 나옴 | 통과(기능 있다고 안 함), 결함 4 |
| 직원마다 숙련도를 다르게 해줘 | ADJUST_REQUEST, 일반 문구 | 통과 |
| 북구 센터들에 민원인을 분산해줘 | ADJUST_REQUEST, 공간 규모·목적 불일치, 센터 한 곳 제안 | 통과 |
| 현재 실제 대기 인원을 조회해줘 | **MATCH** ("대기"→민원 대기 시간). 카드 UNSUPPORTED "실시간 운영 예측" 키("지금 대기","오늘 몇 명")와 안 맞음 | 실패(브로커), 결함 3 |
| 직원 증원 비용을 계산해줘 | ADJUST_REQUEST, 일반 문구("비용" 이 인건비 키에 안 걸림) | 통과(경미) |
| 정부24 이용 증가 효과 | ADJUST_REQUEST, 대안 "하루 방문객 수를 줄여 표현" | 통과 |

## 8장 경계값 — 창구 (validate_answers + build_scenario 검증 단계만, 승인·실행 안 함)

| 항목 | 최솟값-1 | 최솟값 | 최댓값 | 최댓값+1 | 판정 |
|---|---|---|---|---|---|
| 창구 1~20 | 0 거부 | 1 통과 | 20 통과(build valid) | 21 거부(validate·build 모두) | 통과 |
| 하루 방문객 1~5000 | 0 거부 | 1 통과 | 5000 통과, build는 3창구 조합 과부하(2469%)로 차단 | 5001 거부 | 통과 |
| 평균 처리 1~60분 | 0 거부, 0.5 거부 | 1 통과 | 60 통과, build는 조합 과부하(667%) 차단 | 61 거부 | 통과 |
| 최대 대기 1~240분 | 0 거부 | 1 통과 | 240 통과(build valid) | 241 거부 | 통과 |
| 예약 비율 0~1 | -0.1 거부 | 0 통과 | 1 통과 | 1.1 거부; "30%" NOT_A_NUMBER; 30 OUT_OF_RANGE(0.3으로 보정 안 함) | 통과 |
| 기간 1~60일 | 0 거부 | 1 통과 | 60 통과(build valid) | 61 거부 | 통과 |
| 반복 1~100회 | 0 거부 | 1 통과 | 100 통과 | 101 거부 | 통과 |
| 형식 | queueDiscipline FOO → OUT_OF_CLOSURE(허용값 제시), reservationMode YES 거부, -3·빈 값·abc·3.5 거부. single_line → SINGLE_LINE 대소문자 정규화 | | | | 통과(경미 결함 5·6) |

## 발견한 결함·명세 불일치

1. **[C03] 창구 1개에서 줄 방식 비교가 그대로 통과** — 재현: plan_subtasks `{"answers":{"counterCount":1},"variableAnswerKey":"queueDiscipline","variableValues":["SINGLE_LINE","PER_COUNTER"]}` → queueDiscipline FILLED. build_scenario 같은 조건 → valid, runCount 2, 경고 없음(scn-c071bf74). 카드 conditional(`counterCount >= 2`, DO_NOT_GENERATE, "줄 서는 방식이 결과를 바꾸지 않습니다")이 비교 변수 경로에서 적용되지 않음.
2. **[C08·C09] 기본값 없는 값(maxWaitMinutes·reservationShare)을 origin MODEL_DEFAULT로 넣으면 build가 통과** — 재현: build_scenario values에 `"maxWaitMinutes":30` + origins `"maxWaitMinutes":"MODEL_DEFAULT"` → valid, UNCONFIRMED(scn-db02397c). reservationShare=0/MODEL_DEFAULT 도 같음. 확인 화면에 미승인 기본값으로 뜨긴 하지만, 카드 neverDo "기본값이 없는 값을 대신 채워 실행한다" 와 "임의 0% 금지"를 서버가 막지 않고 사람 확인에만 맡김. origin 을 생략해도(=MODEL_DEFAULT) 같음(scn-c071bf74).
3. **[추가 범위] "현재 실제 대기 인원을 조회해줘" 가 브로커에서 MATCH** — 재현: find_simulators `{"domain":"민원 창구","objective":"현재 실제 대기 인원 조회"}` → MATCH, 사유 "→ 민원 대기 시간". 카드 unsupported(실시간 운영 예측) matchKeys 가 "지금 대기"·"오늘 몇 명" 뿐이라 "현재"·"실제"를 못 잡음.
4. **[추가 범위] unsupported matchKey 띄어쓰기 민감** — "전담창구" 는 일반 목적 불일치, "전담 창구" 만 UNSUPPORTED_FEATURE 사유·대안 출력. 같은 이유로 "증원 비용" 은 인건비 항목에 안 걸림. 또 목적 자체를 바꾸라는 제안인데 changesPurpose:false 로 나옴.
5. **[C10·C07] 예약제 미사용 + 예약 비율 입력 시 경고 없음** — 재현: build_scenario reservationMode IGNORE + reservationShare 0.3 → valid, 경고 없음, 확인 화면 run 에 reservationEnabled:false 와 reservationShare:0.3 이 함께 표시(scn-c5205662). 결과에서는 비율이 반영되지 않음(C07 IGNORE 실행에 RESERVED 유형 없음) — 계산은 맞지만 안내 부재.
6. **[C04] 공급 초과(수요≈123%) 구간에 경고 없음** — 과부하 차단(수백 %)만 있고, 차단 문턱 아래 공급 초과 구간은 build·결과 어느 쪽에도 플래그가 없음(scn-d7f992ec).
7. **[경계값, 경미] build_scenario에 variableValues ["abc"] (counterCount) → "설정에 세터가 없습니다: counterCount" 내부 오류 문구** — validate_answers 는 NOT_A_NUMBER 로 바르게 안내함. 또 maxWaitMinutes 30.5 의 코드가 NOT_A_NUMBER(정수 아님이 맞음).
8. **[참고] 같은 값으로 다시 build 하면 같은 scenarioId 를 재사용하고 CONFIRMED → UNCONFIRMED 로 되돌림**(옛 토큰 무효) — 안전 측면은 정상. 다만 여러 대화가 같은 값으로 만들면 서로의 승인을 지울 수 있음.

비서 역할 자체의 처리 실수는 없음(서버 안내대로 진행). 인증키·URL 노출 없음(이 서버는 키 사용 안 함).


# 6장 공영주차장 기본 실험 (P01~P12) 실행 결과

실행일 2026-10-29. 브로커 8089 → 주차장 8092. 모든 사례가 가정 시설(외부 API 도구는 부르지 않음). 공통 사용자 답: 4곳·80면·900대·Commuter/Shopper/Visitor·14일·3회(USER), 나머지는 "기본값으로 해줘"(MODEL_DEFAULT: FLAT·500원·NONE·갱신 5분·혼잡 IGNORE·간격 3분). 모든 사례에서 브로커 find_simulators 는 MATCH → pohang-parking-sim.

| 사례 ID | 입력 및 후속 답변 | 선택 서버·도구 | 최종 설정/시나리오 ID | 실제 결과 | 판정 | 증거/오류 |
|---|---|---|---|---|---|---|
| P01 | 원문 요청 + 공통 답 | find_simulators → plan_subtasks(변수 guidanceMode 3값) → validate_answers → build_scenario → 승인 전 run(가짜 토큰) → confirm → run_scenario_by_token | scn-e46f079, 3벌, guidanceMode만 다름 | 탐색(전체) 4.92/3.49/2.55분, 포기 89.2/69.1/68.5대/일; 미승인 기본값 5개를 확인 화면·assumptionNotes에 표시; 승인 전 실행 거절 | 통과 | vehicleBalanceErrorMax 0, flags DEMAND_ASSUMED·LOT_LAYOUT_ASSUMED, notForOperationalUse=true |
| P02 | 1곳, NONE vs REALTIME_APP, 포기 15 + 공통 답 | plan_subtasks → build_scenario | scn-5c95d618 (UNCONFIRMED에서 멈춤) | plan_subtasks `unusedVariableWarning`, build `warnings`: "lotCount=1에서는 안내가 돌려보낼 다른 주차장이 없음… 사용자에게 알리고 다른 비교를 고를지 물으라" | 통과 | 차단은 아니고 경고만 함(카드 conditional은 DO_NOT_GENERATE). 갱신주기·간격은 NOT_GENERATED |
| P03 | 무료 vs 30분당 500원(USER), 포기 15 + 공통 답 | plan_subtasks → build(변수 pricingPolicy [FREE,FLAT]) → confirm → run | scn-293f205d, 2벌, pricingPolicy만 다름 | FREE: 탐색 6.47분·포기 207.6/일·수입 0; FLAT: 4.92분·89.2/일·요금 회피 180/일·수입 3,198,488원/일(reference 칸) | 통과 | 수입은 reference로 분리, 카드 note "판정 근거로 쓰지 않는다". FREE 실행 설정에도 baseFeePer30Min 500 표시(아래 4) |
| P04 | TIME_OF_DAY(USER), 할증률 미제공 → "기본값으로" → 0.5 제공 | plan_subtasks → build ×3 | scn-5cf63fd2 INVALID → scn-990f5ff7 INVALID → scn-c1af5ff7 UNCONFIRMED | 누락 시 mustAskNoDefault=[peakSurchargeRate], build 차단; MODEL_DEFAULT 0 넣어도 "지어낸 값" 차단; 0.5(USER)로 복구 | 통과 | 0.5는 테스트 실행자가 준 값(대본에 없음). validate_answers "50%"는 NOT_A_NUMBER(아래 5) |
| P05 | userMix에 Resident 추가, 비율 미제공 → 0 제공 | plan_subtasks → build ×2 | scn-a86732 INVALID → scn-3f5199d UNCONFIRMED | 비율 누락 차단(mustAskNoDefault), 0(USER)은 유효로 받아들임 | 통과 | 브로커: "거주자도 이용" → Resident |
| P06 | 4곳만, 포기시간 미제공 → 15 제공 | plan_subtasks → build ×2 | scn-def7fed1 INVALID → scn-789636ed UNCONFIRMED | mustAskNoDefault=[maxSearchMinutes]; build 차단 "기본값이 없는 값"; 15 제공 후 복구 | 통과 | — |
| P07 | REALTIME_APP(DERIVED), 갱신 20, 포기 10 | plan_subtasks → validate_answers → build | scn-4f5cc1e5 INVALID | 개별 값은 유효, build에서 "갱신 주기(20분)가 포기 시간(10분)보다 깁니다" 차단 | 통과 | plan_subtasks는 complete=true(조합 검증은 build에서) |
| P08 | 면수 80 vs 100, 포기 15 + 공통 답 | plan_subtasks → build(변수 spacesPerLot) → confirm → run → 같은 토큰 재실행 | scn-d30de62b, 두 벌 차이 = spacesPerLot만 | 80면: 4.92분·89.2/일·만차 266분/곳; 100면: 2.69분·14.3/일·160분/곳; 재실행 "이미 실행한 시나리오" 거절 | 통과 | balance 0. P01 NONE 실행과 같은 시드·같은 설정이어서 수치가 같음(재현됨) |
| P09 | 간격 3 vs 6(USER), 포기 15 + 공통 답 | build(변수 lotSpacingMinutes) | scn-676c3ab2 (UNCONFIRMED) | 두 벌 차이는 lotSpacingMinutes(3.0/6.0)만. 질문문·카드에 "한 줄 배치 가정" 표시 | 통과 | 실행 설정에 템플릿에 없는 fullLotCircleMinutes=2.0이 고정으로 있음(아래 6) |
| P10 | 혼잡 APPLY + 프로파일 unknown | validate_answers → plan_subtasks → build ×2 | scn-df40caae / scn-4a6e7037 INVALID | validate OUT_OF_CLOSURE(허용값 제시), build "알 수 없는 혼잡 프로파일"; 조용히 IGNORE로 바꾸지 않음 | 통과 | — |
| P11 | 1곳·10면·Commuter만·20000대·포기 15 | build | scn-83c655d6 INVALID | "수요가 감당량의 75000%… 도착 차량을 줄이거나 면수를 늘리십시오" 차단 | 통과 | 20000×9h ÷ (10면×24h) = 750배, 계산 맞음 |
| P12 | userMix [Shopper], 거주자 구획 0.3 | find_simulators → plan_subtasks → build | scn-e0679b4f INVALID | plan_subtasks `unusedUserAnswers`, build "거주자가 없는데 우선 구획을 두었습니다" 차단 + 어느 쪽인지 물으라고 안내 | 통과 | 서버는 통과. 브로커는 "거주자 없음"을 Resident로 매칭함(아래 1) |

## 추가 지원 범위 (브로커 find_simulators)

| 요청 | verdict | 결과 | 판정 |
|---|---|---|---|
| 포항 전체 실제 지도 길안내 | ADJUST_REQUEST | 공간 규모·목적 둘 다 mismatch, 대안 "도심 한 구역 1~12곳" | 통과(changesPurpose는 아래 2) |
| 불법주차 단속 효과 | ADJUST_REQUEST | "노상·불법 주차 미지원", 대안 "면수·요금 변경" | 통과(아래 2) |
| 전기차 충전 대기 | ADJUST_REQUEST | "충전면·충전 시간 항이 없다", 대안 없음 | 통과(아래 2) |
| 주차장 건설비와 비용편익 | ADJUST_REQUEST | "비용 항 없음", 대안 metric:giveUps/searchTime/revenue | 통과(아래 2) |
| 오늘 오후 3시의 실제 빈자리 예측 | **MATCH** | 목적을 "주차장 혼잡도·점유율"로 매칭, mismatch 없음 | **실패**(아래 3) |

미지원 기능을 계산해 낸 경우는 없음(MATCH를 받은 마지막 건도 시뮬레이터까지는 가지 않음).

## 발견한 결함·명세 불일치

1. **[브로커] 부정 표현을 이용자 유형으로 매칭함** (경미). find_simulators `{"domain":"공영주차장","population":["쇼핑객만 이용","거주자 없음"]}` → reasons에 "거주자 없음 → Resident". verdict는 MATCH로 같지만, 비서가 reasons를 믿으면 Resident를 이용자 구성에 넣을 수 있음.
2. **[브로커] 목적을 바꾸는 조정인데 changesPurpose=false** (명세 불일치). 불법주차·전기차·비용편익·길안내 4건 모두 objective 축 requestAdjustments가 `changesPurpose:false`. "불법주차 단속 효과" → "면수·요금 변경"은 질문 자체가 달라지는 것임. 도구 설명은 changesPurpose가 참이면 다른 질문이 된다고 함. 재현: 위 4개 요청으로 find_simulators.
3. **[브로커] 실시간 운영 예측 요청이 MATCH됨** (실패). `{"domain":"공영주차장","objective":"오늘 오후 3시의 실제 빈자리 예측"}` → MATCH, mismatch 없음. 카드 unsupported의 matchKeys가 "실시간 예측"·"오늘 몇 시"뿐이라 "오늘 오후 3시"·"빈자리 예측"이 빠짐("빈자리"는 occupancy 목적에 걸림). "오늘 오후 3시 빈자리 실시간 예측"이라고 쓰면 ADJUST_REQUEST가 나옴.
4. **[서버] FREE 실행 설정에도 baseFeePer30Min 500이 실림** (경미, 표시 문제). scn-293f205d의 runs[0]은 pricingPolicy FREE인데 baseFeePer30Min 500. 결과 수입은 0이라 계산에는 영향이 없지만, 확인 화면에서 "무료인데 500원"으로 보일 수 있음. 같은 이유로 NONE/STATIC_SIGN 실행에도 guidanceRefreshMinutes 5가 실림(P01).
5. **[서버] 할증률 "50%" 입력 시 형식 안내가 없음** (경미). validate_answers `{"peakSurchargeRate":"50%"}` → NOT_A_NUMBER "수여야 합니다". 보정하지 않는 것은 맞지만, 메시지에 0.5 형식(0~3) 힌트가 없음. 질문문에는 "0.5 = 50% 할증"이라고 있음.
6. **[서버] 템플릿에 없는 고정 가정값 fullLotCircleMinutes=2.0** (명세 불일치, 경미). 모든 실행 설정에 있지만 템플릿·질문·unapprovedDefaults·assumptionNotes 어디에도 없음. 만차 주차장을 한 바퀴 도는 시간이라는 가정이 사용자 확인 없이 들어감.
7. **[명세] 단일 실행에도 variableAnswerKey·variableValues가 필수**. P04~P07, P10~P12처럼 비교가 아닌 요청은 비서가 임의 변수(예: days=[14])를 골라야 build_scenario를 부를 수 있음. 동작에는 문제가 없지만 확인 화면에 "비교 변수"로 보일 수 있음.
8. 참고: giveUpRate 분모는 요금 회피 차량을 뺀 도착 대수임(FLAT 89.24/(900-180)=0.124, FREE 207.6/900=0.23). 요금 정책 비교에서 비율만 보면 분모가 서로 달라 헷갈릴 수 있음. 판정은 giveUpsPerDay로 보는 것이 맞음.

비서 역할(나)의 처리 오류는 없음. P04의 0.5는 테스트 실행자가 준 값이고 대본 밖의 값임.


# 7장 주차장 외부 API (A01~A14) 실행 결과

실행일 2026-10-29 (KST 19시 전후). 주차장 서버 8092, 실제 인증키 사용. 모든 응답 파일에서 `servicekey` 문자열 검색 결과 0건(키·인증 URL 노출 없음). 원본 응답: `runs/A01.json`, `A02.json`, `A05.json`, `A07*.json`, `A08s.json`, `A08_screen.json`, `A08_run.json`, `A12.json`.

사례 시작 시 브로커 호출: `find_simulators {domain:"주차", objective:"포항시시설관리공단 공영·노외 주차장 조회"}` → ADJUST_REQUEST(목적 "조회"가 답할 수 있는 질문에 없음). `{domain:"주차", environmentConditions:["전국주차장 표준데이터"]}` → MATCH pohang-parking-sim(8092). 이하 사례는 이 MATCH 후 8092 사용.

| 사례 ID | 입력 및 후속 답변 | 선택 서버·도구 | 최종 설정/시나리오 ID | 실제 결과 | 판정 | 증거/오류 |
|---|---|---|---|---|---|---|
| A01 | 포항시시설관리공단 공영·노외 조회 | 브로커(1차 ADJUST_REQUEST→MATCH) → 8092 fetch_standard_parking | - | 30곳, 전부 institutionNm=포항시시설관리공단·공영·노외. 시설명·totalSpaces·referenceDate(04-16/05-15)·source·fetchedAt·realtimeAvailable=false·availableSpaces=null(NOT_PROVIDED) | 통과 (결함 2·3 참고) | runs/A01.json |
| A02 | 경상북도 포항시청 관리 주차장 | fetch_standard_parking | - | 17곳, 전부 institutionNm=경상북도 포항시청(insttNm=경상북도 포항시). 기관 필터 정확 | 통과 | runs/A02.json. 관리번호가 기관 간 겹침(350-2-000016 등) — 기관 범위 안에서만 선택되므로 문제 없음 |
| A03 | A01 결과 중 이동4공원(88면)·동빈부두(49면) 선택 | fetch_standard_parking lotIds만 | - | 거절: lotIds·initialAvailableSpaces·initialStateTime 는 기본값 없는 사용자 가정, 물어서 USER로 넣으라 안내 | 통과 | 오류 코드가 일반 EXECUTION_ERROR(결함 6) |
| A04 | 표준데이터로 지금 빈자리 | (A01 응답 근거) | - | realtimeAvailable=false, availableSpaces=null, note "실시간 잔여면수 없음" → 비서가 불가 설명·fetch_parking_snapshot(실시간)과 구분 | 통과 | 도구 설명·A01 note |
| A05 | 절반 가정, 60분 출차, 포기 15분. 시각 "기본값으로"→거절 후 09:00 제공(대본 외, 합리값). 49의 절반 24.5 전송→거절 후 24(내림)로 답 | fetch_standard_parking → plan_subtasks → build_scenario | 스냅샷 standard-9360ab52…, scn-942b7e6e-a563e52909bea67d (UNCONFIRMED, 미승인) | 24.5 "정수여야 합니다" 거절(보정 없음). 시각 MODEL_DEFAULT 거절. 스냅샷 lots 88/44·49/24, occupancySource=USER_ASSUMED, assumedStartMinute=540. 확인 화면에 기본값 5개 unapprovedDefaults로 분리 | 통과 (결함 4) | 범위 밖(100, -1) 빈자리도 "총면수 범위를 벗어남" 거절, 없는 관리번호 거절 |
| A06 | 요금·운영시간 자동 반영 요청 | (A01/A05 응답 근거) | scn-942b7e6e… | note "요금·운영시간·좌표는 참고용이며 엔진에 자동 적용하지 않습니다". 확인 화면 baseFeePer30Min=500은 MODEL_DEFAULT(실제 시설 500원/20분과 다름), 실행 플래그 PRICING_AND_HOURS_NOT_IMPORTED | 통과 | 반영했다고 주장하지 않음 |
| A07 | Parking_v1 잔여면수 조회. 지역 질문에 "포항 북구"→47113, 남구 47111 | fetch_parking_snapshot | - | 포항 두 구 모두 realtimeAvailable=false, "이 지역은 실시간 미제공" + 대안(표준데이터/가정값/다른 지역) 안내. 비교로 경주 47130: 공영 실시간 1곳(신경주역 564/211). 강남 11680: 1차 "목록이 길어 15100건까지 받음, 다시 조회하면 이어서" 오류 → 1회 재조회로 18곳 | 차단(포항 데이터 미제공) / 서버 동작은 통과 | runs/A07*.json. fetchedAt 기록, note "관측 시각은 API에 없음", capacityMismatch 필드 제공 |
| A08 | 강남구(포항 미제공 대체) 역삼문화공원 59/23·언주초교 191/49, 잔여 60분·포기 15분, 나머지 "기본값으로"(도착 703=snapshotDefaults), 14일·3회 | fetch_parking_snapshot(lotIds) → build_scenario → get_scenario_status → (가짜 토큰 실행 거절 확인) → 승인 → run_scenario_by_token | 스냅샷 parking-ee08fb88…, scn-942fa108-5eef184e1459a5c4 EXECUTED | 0.5초 실행. occupancySource=REALTIME_API, simulationStartMinuteKst=1147(조회 시각 19:07), sourceObservedAt=null. 플래그 API_CAPACITY_AND_INITIAL_OCCUPANCY·SOURCE_TIMESTAMP_UNAVAILABLE·INITIAL_DEPARTURE_ASSUMED. 메모 "이후 API 재조회 없이 모의 차량만 변함". 수지 오차 0 | 통과 (결함 4·5) | runs/A08_screen.json, A08_run.json. 탐색 2.48분, 포기 59.0대/일 |
| A09 | 출차 시각 "알아서" | plan_subtasks → build_scenario(값 없음 / MODEL_DEFAULT 120) | - | mustAskNoDefault=[initialVehicleRemainingMinutes]. 두 build 모두 거절, 사용자에게 받으라 안내 | 통과 | 임의 값 생성 안 함 |
| A10 | 스냅샷+거주자 구획 30% | build_scenario | scn-673b48a7…(INVALID) / 0 → scn-225b48a7…(UNCONFIRMED) | 0.3: blocks "API는 기존 점유의 거주자 구획을 구분하지 않음, 0으로 정하라" INVALID. 0은 통과 | 통과 | INVALID 시나리오 승인 시도 → "보관된 시나리오 없음" 거절 |
| A11 | 매분 API 호출로 덮어쓰기 | (능력 카드·실행 메모 근거) | - | 지속 동기화 도구 없음. 카드 unsupported "실시간 운영 예측", 실행 메모 "API 재조회 없이 모의 차량만 변함" → 비서가 초기 스냅샷만 가능 설명 | 통과 | 서버 측 명시적 거절 문구는 없음(요청을 받을 도구 자체가 없음) |
| A12 | 없는 기관명 | fetch_standard_parking("존재하지않는관리기관테스트") | - | isError=false, candidates=[], emptyNote "등록된 공영·노외 없음, 기관명 정확히 일치해야 함" | 통과 | runs/A12.json. 인증 실패·가짜 시설 없음 |
| A13 | 키 없음/잘못된 키 | - | - | MCP 수준에서 키를 바꿀 방법 없음, 운영 키 변경·재시작 금지 | 미실행 | 별도 테스트 프로세스 필요 |
| A14 | 시간 초과·호출 한도·잘못된 응답 | - | - | 모의 응답 주입 수단이 MCP에 없음 | 미실행 | 별도 통합 테스트 필요 |

## API 응답 검증 추가 항목 (MCP로 관찰 가능한 범위)

- header/body·items.item 구조: 두 API 모두 정상 파싱(표준 30·17건, 실시간 18건). 비정상 구조는 미실행(A14와 같음).
- 중복 시설·기준일: **처리 안 됨** (결함 1).
- 총면수 0·잔여 음수·총면수 초과: 사용자 가정값(표준데이터)에서는 음수·초과 모두 거절 확인. API가 그런 값을 줄 때의 차단은 모의 응답 필요(미실행). 실시간 응답에 capacityMismatch(시설면수≠총면수, 예: 수서역 649/561) 표시는 확인.
- 총면수와 선택 주차장 수 일치: lotCount가 스냅샷 곳수(2)로 DERIVED 설정됨. 다만 실행 설정에 spacesPerLot=80이 함께 남음 (결함 4).
- 출처·초기 점유 출처 보존: source·fetchedAt·snapshotId·occupancySource(USER_ASSUMED/REALTIME_API)가 확인 화면과 실행 결과까지 유지됨.
- 대량 지역 이어받기: 강남 1차 조회가 오류로 끝나고 재조회해야 이어짐(결함 7).

## 발견한 결함·명세 불일치

1. **[결함] 표준데이터 중복 시설을 걸러내거나 경고하지 않는다.** 같은 주소·같은 면수 시설이 관리번호와 기준일만 달리 두 번 나옴(예: 죽도 공영주차장 351-2-000032[04-16, 제공 경상북도 포항시] / 351-2-000006[05-15, 제공 포항시시설관리공단], 동빈부두 351-2-000031/351-2-000004, 대이동(3공원) 350-2-000007/350-2-000019, 상도 3건). 이동4공원 350-2-000020(88면)과 대이동(4공원) 350-2-000008(86면)은 같은 주소에 면수가 다름. 재현: `fetch_standard_parking {institutionName:"포항시시설관리공단", lotIds:["351-2-000032","351-2-000006"], ...USER}` → 경고 없이 스냅샷 생성(같은 시설 21면이 두 번, 총면수 이중 계산). 기준일 선택(최신 우선) 규칙도 없음.
2. [확인 필요] 포항시시설관리공단 결과가 정확히 30건이고 totalCount·잘림 표시가 없음. 페이지 한도에서 잘렸는지 MCP 응답만으로 판단 불가.
3. [명세 불일치·경미] 브로커가 "주차장 조회" 목적을 ADJUST_REQUEST로 돌리고, MATCH nextStep 도구 순서에 fetch_standard_parking/fetch_parking_snapshot이 없다. 능력 카드에는 조회 도구가 있는데 브로커 안내만 따르면 조회 경로가 안 보인다. 재현: `find_simulators {domain:"주차", objective:"포항시시설관리공단 공영·노외 주차장 조회"}`.
4. [표시 결함] API 스냅샷 시나리오의 확인 화면 runs[0]에 `spacesPerLot: 80`(label "주차장당 면수")이 실제 면수(88/49, 59/191)와 함께 그대로 나온다. 엔진이 무시하는지 MCP 응답만으로 확인할 수 없고, 승인하는 사람에게 혼동을 준다. 재현: A05/A08 시나리오 `GET /api/scenarios/{id}`.
5. [결함·경미] A08 실행 결과 assumptionNotes가 실험 변수 guidanceMode(=NONE, 비서가 variableValues로 준 값)를 "PES에 없어 초기값으로 실행된 결정 — 확인 화면에 보이지 않았던 값"으로 적는다. 생성되지 않는 guidanceRefreshMinutes도 같이 적힘. runs[].settings는 {}로 비어 있다. 재현: scn-942fa108… 실행 결과.
6. [경미] 검증 실패 코드가 모두 EXECUTION_ERROR라서 사용자 가정 누락·정수 아님·범위 초과·시설 없음을 코드로 구분할 수 없다(메시지는 명확함).
7. [경미] 시설이 많은 지역(강남 11680)은 첫 조회가 isError=true("15100건까지 받음, 다시 조회하면 이어서")로 끝난다. 비서가 같은 요청을 다시 보내야 하며, 몇 번 더 해야 하는지 알려 주지 않는다. 1회 재조회로 성공.
8. [참고] 확인 토큰이 시나리오 ID 끝부분과 같다(scn-942fa108-**5eef184e1459a5c4** → cft-**5eef184e1459a5c4**). 미승인 상태에서는 실행이 거절돼 우회는 안 되지만, 토큰은 추측 가능한 값이다.
9. [데이터 제공] 포항 남구(47111)·북구(47113)는 Parking_v1 실시간 미제공 → A07·A08의 포항 대상 실행은 차단. A08은 강남구로 대체 실행.

비서 역할 자체 처리 메모: 시군구 코드(47113/47111/47130/11680)는 MCP가 알려 주지 않아 비서가 일반 지식으로 넣었다. 7장에 기본 조건이 없어 14일·3회는 6장 기준을 썼고, 도착 차량은 "기본값으로"에 서버 snapshotDefaults(385/703)를 썼다.


