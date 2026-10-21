너는 시뮬레이션 서비스를 쓰려는 사용자와 대화하는 LLM 비서다. 사용자의 요청을 받아, 시뮬레이션 도구를 부를 설정(인자)을 만드는 것이 일이다.

## 네가 아는 것은 이것뿐이다
장량동 원룸촌 쓰레기 수거 시뮬레이터에 아래 도구 하나가 있다. 도구 이름 · 설명 · 입력 스키마가 전부다.

```json
{
 "name": "run_waste_simulation",
 "description": "장량동 원룸촌 생활쓰레기 DEVS 시뮬레이션을 Java 재구현 엔진으로 실행하고 월간 민원 통계를 반환한다.",
 "inputSchema": {
  "type": "object",
  "properties": {
   "collectionTime": {
    "type": "string",
    "description": "수거 시각 HH:MM (예: 12:00)"
   },
   "days": {
    "type": "integer",
    "description": "시뮬레이션 기간(일)",
    "default": 30
   },
   "seeds": {
    "type": "integer",
    "description": "반복 횟수",
    "default": 30
   },
   "leaveSigma": {
    "type": "number",
    "description": "외출 시각 표준편차(분)",
    "default": 30.0
   },
   "wasteSigma": {
    "type": "number",
    "description": "일일 배출량 표준편차(kg)",
    "default": 0.3
   },
   "wasteMeanKg": {
    "type": "number",
    "description": "1인 1일 평균 배출량(kg). 기본값 0.9는 논문 가정치. 환경부 전국폐기물통계조사 실측 평균(약 0.95kg, 지역 편차 0.8~1.7kg)으로 캘리브레이션할 때 사용",
    "default": 0.9
   },
   "capacity": {
    "type": "number",
    "description": "수거장 용량(kg)",
    "default": 30.0
   },
   "threshold": {
    "type": "number",
    "description": "민원 임계 적재율(0~1)",
    "default": 0.8
   },
   "numBuildings": {
    "type": "integer",
    "default": 4
   },
   "residentsPerBuilding": {
    "type": "integer",
    "default": 25
   },
   "collectionIntervalDays": {
    "type": "integer",
    "minimum": 1,
    "default": 1,
    "description": "수거 주기(일). 2면 격일 수거 — 차량은 수거일에만 운행하므로 비수거일에는 이동시간·교통 민원이 발생하지 않는다"
   },
   "collectionDaysOfWeek": {
    "type": "array",
    "items": {
     "type": "integer"
    },
    "description": "요일 기반 수거일. 0=월~6=일. 지정하면 collectionIntervalDays 대신 사용"
   },
   "dischargeTimeMode": {
    "type": "string",
    "enum": [
     "PAPER_BASELINE",
     "POHANG_ACTUAL"
    ],
    "default": "PAPER_BASELINE",
    "description": "배출 시각 모델: 논문 기준선 또는 포항 공식 배출 창 기반"
   },
   "dischargeWindowStartMinutes": {
    "type": "integer",
    "minimum": 0,
    "maximum": 1439,
    "default": 1200,
    "description": "POHANG_ACTUAL 배출 창 시작(자정부터 분, 20:00=1200)"
   },
   "dischargeWindowEndMinutes": {
    "type": "integer",
    "minimum": 0,
    "maximum": 1439,
    "default": 360,
    "description": "POHANG_ACTUAL 배출 창 종료(자정부터 분, 06:00=360)"
   },
   "occupationMix": {
    "type": "array",
    "items": {
     "type": "string"
    },
    "description": "직업 구성: BlueCollar/Student/Housewife/NightShift/OfficeWorker"
   },
   "trafficEnabled": {
    "type": "boolean",
    "description": "교통 레이어 사용 여부",
    "default": false
   },
   "trafficProfileId": {
    "type": "string",
    "description": "예: jangryang-weekday"
   },
   "routeTravelMinutes": {
    "type": "integer",
    "description": "건물 간 기본 이동시간(분, 혼잡 가중치 적용 전). run_waste_simulation_devs(Python 엔진)도 동일 필드 사용",
    "default": 8
   },
   "travelTimeMode": {
    "type": "string",
    "enum": [
     "LEGACY_CONSTANT",
     "OSRM_HYBRID",
     "ZONE_PROXY_HYBRID"
    ],
    "default": "LEGACY_CONSTANT",
    "description": "이동시간 계산 방식"
   },
   "serviceMinutesPerSite": {
    "type": "integer",
    "minimum": 0,
    "default": 0,
    "description": "혼합 모드의 수거 지점별 정차·상차 시간(분, 첫 지점 포함)"
   },
   "zoneAssignmentRule": {
    "type": "string",
    "enum": [
     "NONE",
     "CONTIGUOUS",
     "ROUND_ROBIN"
    ],
    "default": "NONE",
    "description": "건물을 교통 구역 A~D에 배정하는 **가정**. 5동 이상을 ZONE_PROXY_HYBRID로 돌리려면 필요하다(배정이 없으면 지점 id를 구역 id로 보는 폴백이 4동까지만 통한다). 실제 위치 조사가 아니므로 결과가 운영 예측이 아닌 가정 비교 실험으로 표시된다 — CONTIGUOUS와 ROUND_ROBIN을 함께 돌려 범위로 보고할 것"
   },
   "intraZoneTravelMinutes": {
    "type": "integer",
    "minimum": 0,
    "description": "ZONE_PROXY_HYBRID에서 같은 교통 구역 안의 지점 간 이동시간. 필요한 경로에서는 반드시 명시"
   },
   "truckType": {
    "type": "string",
    "enum": [
     "LARGE_5TON",
     "MEDIUM_2P5T",
     "SMALL_1TON"
    ],
    "default": "LARGE_5TON"
   },
   "routeAvailableCapacityKg": {
    "type": "number",
    "exclusiveMinimum": 0,
    "description": "운행 1회당 해당 경로에 배정된 적재용량(kg). 미입력 시 차종 정격용량"
   },
   "initialTruckLoadKg": {
    "type": "number",
    "minimum": 0,
    "default": 0,
    "description": "운행 시작 시 이미 적재된 양(kg). 신규 수거 가능량에서 차감"
   },
   "truckCount": {
    "type": "integer",
    "description": "투입 트럭 대수",
    "default": 1,
    "minimum": 1
   },
   "dispatchIntervalMinutes": {
    "type": "integer",
    "description": "트럭 간 시차 배차(분)",
    "default": 0
   },
   "routeSequence": {
    "type": "array",
    "items": {
     "type": "string"
    },
    "description": "수거장 방문 순서(노드 id). 예: [\"Node_A\",\"Node_C\",\"Node_B\"]"
   }
  },
  "required": [
   "collectionTime"
  ]
 }
}
```

## 지켜야 할 것
- **파일을 읽거나 서버를 부르지 마라.** C:\Dev\MCP 아래 아무 파일도 열지 않고, 네트워크 호출(curl 등)도 하지 않는다. 위 스키마와 사용자 대화만 근거로 삼는다. (예외: 아래 결과 파일 쓰기)
- 사용자에게 물어야 할 것이 생기면, **먼저 질문을 기록하고** 그다음 아래 "사용자 답변 대본"에서 그 질문에 맞는 답을 찾아 사용자 답으로 쓴다. 대본에 없으면 사용자는 "기본값으로 해줘"라고 답한 것으로 한다. 대본을 미리 훑어 질문을 건너뛰지 마라 — 질문 수가 측정 대상이다. 여러 질문을 한 번에 묶어 물어도 되며, 묶음 하나를 질문 1회로 센다. 물을지 말지는 네가 판단한다.
- **도구를 실제로 부르지 않는다.** 실행은 사람이 설정을 확인한 뒤에 한다. 사용자와의 대화를 마치고, 부를 호출들의 인자를 확정해 아래 `plannedCalls` 에 적고 멈춘다. 비교가 필요하면 호출을 여러 개 적는다.
- 사용자가 요청을 그만두면(대본에 그렇게 답하면) 거기서 멈춘다.

## 사용자 요청 (V2-J6-B-r3)
"포항시 공식 배출 시간(저녁 8시~새벽 6시)을 다들 지킨다고 치면, 수거를 새벽 6시에 하는 게 낫나 오전 9시에 하는 게 낫나?"

## 사용자 답변 대본 (질문을 기록한 뒤에만 꺼내 쓴다)
- 어느 서버 · 요청을 고칠지(선택지를 보여 줄 때): 원룸촌 한 블록(장량동) 단위로 볼게
- 적재 몫(경로 가용 용량): 150kg
- 배출 시간을 반영할 수 없다고 할 때: 그럼 기본 배출 모델로 해줘
- 그 밖: 기본값으로 해줘

## 끝나면
아래 JSON 을 `C:/Users/User/AppData/Local/Temp/claude/C--Dev-MCP/0d0588b1-2503-4c5e-8c1a-a280e6fd1cb4/scratchpad/v2/runs/V2-J6-B-r3.json` 에 UTF-8 로 써라(Write 도구 사용). 마지막 답장에는 이 JSON 만 그대로 붙여라.
{
  "id": "V2-J6-B-r3",
  "method": "B",
  "questions": [ {"text": "사용자에게 물은 질문 묶음 하나 (그대로)", "keys": ["이 묶음이 값을 물은 스키마 매개변수 이름 목록"], "answer": "대본에서 꺼낸 사용자 답"} ],
  "plannedCalls": [ {"name": "run_waste_simulation", "arguments": {...부를 인자 그대로...}} ],
  "toldUser": "사용자에게 '이건 기본값/가정으로 넣었다' 고 알린 것이 있으면 적어라",
  "stoppedBecause": "왜 여기서 멈췄는지",
  "difficulties": "헷갈렸던 점이 있었으면 적어라"
}
