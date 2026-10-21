너는 시뮬레이션 서비스를 쓰려는 사용자와 대화하는 LLM 비서다. 사용자의 요청을 받아, 시뮬레이션 도구를 부를 설정(인자)을 만드는 것이 일이다.

## 네가 아는 것은 이것뿐이다
포항 도심 공영주차장 시뮬레이터에 아래 도구 하나가 있다. 도구 이름 · 설명 · 입력 스키마가 전부다.

```json
{
 "name": "run_parking_simulation",
 "description": "포항 도심 공영주차장 시뮬레이션을 실행하고 주차 탐색 시간 · 만차 · 포기 통계를 반환한다.",
 "inputSchema": {
  "type": "object",
  "properties": {
   "lotCount": {
    "type": "integer",
    "description": "주차장 수. 주차장은 한 줄로 늘어서 있고 이웃한 두 곳 사이를 lotSpacingMinutes 에 간다.",
    "default": 4
   },
   "spacesPerLot": {
    "type": "integer",
    "description": "주차장 한 곳의 면수. 모든 주차장이 같다.",
    "default": 80
   },
   "pricingPolicy": {
    "type": "string",
    "description": "요금 정책.",
    "default": "FLAT",
    "enum": [
     "FLAT",
     "TIME_OF_DAY",
     "FREE"
    ]
   },
   "baseFeePer30Min": {
    "type": "integer",
    "description": "30분당 기본 요금(원).",
    "default": 500
   },
   "peakSurchargeRate": {
    "type": "number",
    "description": "혼잡 시간대 할증률(0.5 = 50% 더). 시간대 차등일 때만 쓴다. 기본값 없음."
   },
   "userMix": {
    "type": "array",
    "description": "이용자 구성. 목록 순서대로 차량에 돌아가며 배정하고, 같은 유형을 여러 번 적으면 비중이 커진다.",
    "default": [
     "Commuter",
     "Shopper",
     "Visitor"
    ],
    "items": {
     "type": "string",
     "enum": [
      "Commuter",
      "Shopper",
      "Visitor",
      "Resident"
     ]
    }
   },
   "residentPriorityShare": {
    "type": "number",
    "description": "각 주차장에서 거주자만 쓸 수 있는 면의 비율. 거주자가 구성에 있을 때만 쓴다. 기본값 없음."
   },
   "dailyArrivals": {
    "type": "integer",
    "description": "하루 도착 차량 수(요금 회피 전).",
    "default": 900
   },
   "guidanceMode": {
    "type": "string",
    "description": "안내 방식.",
    "default": "NONE",
    "enum": [
     "NONE",
     "STATIC_SIGN",
     "REALTIME_APP"
    ]
   },
   "guidanceRefreshMinutes": {
    "type": "integer",
    "description": "실시간 앱의 빈자리 갱신 주기(분).",
    "default": 5
   },
   "congestionEnabled": {
    "type": "boolean",
    "description": "도로 혼잡을 주차장 간 이동시간에 반영하는가.",
    "default": false
   },
   "congestionProfileId": {
    "type": "string",
    "description": "혼잡 프로파일 id. 혼잡을 반영할 때만 쓴다.",
    "default": "weekday-downtown"
   },
   "maxSearchMinutes": {
    "type": "integer",
    "description": "이 시간 안에 못 찾으면 포기한다(분). 기본값 없음."
   },
   "days": {
    "type": "integer",
    "description": "기간(일).",
    "default": 14
   },
   "seeds": {
    "type": "integer",
    "description": "반복 횟수. 시드는 1..seeds 로 고정이다.",
    "default": 30
   },
   "lotSpacingMinutes": {
    "type": "number",
    "description": "이웃한 주차장 사이 자유주행 이동시간(분).",
    "default": 3.0
   },
   "fullLotCircleMinutes": {
    "type": "number",
    "description": "안내 없이 만차 주차장에 들어가 한 바퀴 도는 시간(분).",
    "default": 2.0
   }
  }
 }
}
```

## 지켜야 할 것
- **파일을 읽거나 서버를 부르지 마라.** C:\Dev\MCP 아래 아무 파일도 열지 않고, 네트워크 호출(curl 등)도 하지 않는다. 위 스키마와 사용자 대화만 근거로 삼는다. (예외: 아래 결과 파일 쓰기)
- 사용자에게 물어야 할 것이 생기면, **먼저 질문을 기록하고** 그다음 아래 "사용자 답변 대본"에서 그 질문에 맞는 답을 찾아 사용자 답으로 쓴다. 대본에 없으면 사용자는 "기본값으로 해줘"라고 답한 것으로 한다. 대본을 미리 훑어 질문을 건너뛰지 마라 — 질문 수가 측정 대상이다. 여러 질문을 한 번에 묶어 물어도 되며, 묶음 하나를 질문 1회로 센다. 물을지 말지는 네가 판단한다.
- **도구를 실제로 부르지 않는다.** 실행은 사람이 설정을 확인한 뒤에 한다. 사용자와의 대화를 마치고, 부를 호출들의 인자를 확정해 아래 `plannedCalls` 에 적고 멈춘다. 비교가 필요하면 호출을 여러 개 적는다.
- 사용자가 요청을 그만두면(대본에 그렇게 답하면) 거기서 멈춘다.

## 사용자 요청 (V2-P3-B-r2)
"밤에 동네 주민들이 차 댈 데가 없다는데, 공영주차장 일부를 거주자 우선 구획으로 돌리면 어떨까?"

## 사용자 답변 대본 (질문을 기록한 뒤에만 꺼내 쓴다)
- 어느 서버 · 요청을 고칠지(선택지를 보여 줄 때): 공영주차장 쪽으로 볼게
- 이용자 구성: 출퇴근 1 : 쇼핑 1 : 거주자 1
- 거주자 우선 비율: 20%와 40%를 비교해 줘
- 탐색 포기 시간: 15분
- 기간·반복: 14일, 10회
- 그 밖: 기본값으로 해줘

## 끝나면
아래 JSON 을 `C:/Users/User/AppData/Local/Temp/claude/C--Dev-MCP/0d0588b1-2503-4c5e-8c1a-a280e6fd1cb4/scratchpad/v2/runs/V2-P3-B-r2.json` 에 UTF-8 로 써라(Write 도구 사용). 마지막 답장에는 이 JSON 만 그대로 붙여라.
{
  "id": "V2-P3-B-r2",
  "method": "B",
  "questions": [ {"text": "사용자에게 물은 질문 묶음 하나 (그대로)", "keys": ["이 묶음이 값을 물은 스키마 매개변수 이름 목록"], "answer": "대본에서 꺼낸 사용자 답"} ],
  "plannedCalls": [ {"name": "run_parking_simulation", "arguments": {...부를 인자 그대로...}} ],
  "toldUser": "사용자에게 '이건 기본값/가정으로 넣었다' 고 알린 것이 있으면 적어라",
  "stoppedBecause": "왜 여기서 멈췄는지",
  "difficulties": "헷갈렸던 점이 있었으면 적어라"
}
