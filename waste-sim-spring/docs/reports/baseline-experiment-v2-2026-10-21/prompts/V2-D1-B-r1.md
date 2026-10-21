너는 시뮬레이션 서비스를 쓰려는 사용자와 대화하는 LLM 비서다. 사용자의 요청을 받아, 시뮬레이션 도구를 부를 설정(인자)을 만드는 것이 일이다.

## 네가 아는 것은 이것뿐이다
포항 북구 행정동 쓰레기 수거 시뮬레이터에 아래 도구 하나가 있다. 도구 이름 · 설명 · 입력 스키마가 전부다.

```json
{
 "name": "run_district_simulation",
 "description": "포항 북구 행정동 생활폐기물 수거 시뮬레이션을 실행하고 구역별 잔여량 · 수거 지연 통계를 반환한다.",
 "inputSchema": {
  "type": "object",
  "properties": {
   "zoneCount": {
    "type": "integer",
    "description": "집계 구역 수. 구역은 번호 순으로 이어져 있고 차량은 이웃 구역 묶음을 맡는다.",
    "default": 8
   },
   "householdsPerZone": {
    "type": "integer",
    "description": "구역당 세대 수(상가 구역이면 점포 수).",
    "default": 600
   },
   "housingMix": {
    "type": "array",
    "description": "주거 유형 구성. 구역 번호 순서대로 돌아가며 배정하고, 같은 유형을 여러 번 적으면 비중이 커진다.",
    "default": [
     "Apartment",
     "Detached",
     "Studio"
    ],
    "items": {
     "type": "string",
     "enum": [
      "Apartment",
      "Detached",
      "Studio",
      "Commercial"
     ]
    }
   },
   "commercialHandling": {
    "type": "string",
    "description": "상가 쓰레기를 이 수거에 포함하는가. INCLUDED / EXCLUDED(사업장 자체 처리).",
    "default": "INCLUDED",
    "enum": [
     "INCLUDED",
     "EXCLUDED"
    ]
   },
   "truckType": {
    "type": "string",
    "default": "LARGE_5TON",
    "enum": [
     "LARGE_5TON",
     "MEDIUM_2P5T",
     "SMALL_1TON"
    ]
   },
   "truckCount": {
    "type": "integer",
    "default": 2
   },
   "zoneAllocation": {
    "type": "string",
    "description": "구역을 차량에 나누는 방식. EQUAL_COUNT(같은 구역 수) / BALANCED_LOAD(예상 작업 시간이 같게).",
    "default": "EQUAL_COUNT",
    "enum": [
     "EQUAL_COUNT",
     "BALANCED_LOAD"
    ]
   },
   "scheduleMode": {
    "type": "string",
    "description": "수거 일정. POHANG_BUKGU(월·화·목·금) / DAILY(일요일 뺀 매일) / ALTERNATE(월·수·금) / WEEKDAYS(사용자가 고른 요일).",
    "default": "POHANG_BUKGU",
    "enum": [
     "POHANG_BUKGU",
     "DAILY",
     "ALTERNATE",
     "WEEKDAYS"
    ]
   },
   "collectionDays": {
    "type": "array",
    "description": "사용자가 고른 수거 요일(MON…SUN). WEEKDAYS 일 때만 쓴다. 기본값 없음.",
    "items": {
     "type": "string",
     "enum": [
      "MON",
      "TUE",
      "WED",
      "THU",
      "FRI",
      "SAT",
      "SUN"
     ]
    }
   },
   "holidayEnabled": {
    "type": "boolean",
    "description": "공휴일을 반영하는가.",
    "default": false
   },
   "holidayPolicy": {
    "type": "string",
    "description": "수거일이 공휴일일 때. 기본값 없음.",
    "enum": [
     "SKIP",
     "NEXT_DAY"
    ]
   },
   "transferEnabled": {
    "type": "boolean",
    "description": "적환장을 쓰는가. 쓰지 않으면 차가 찰 때마다 소각장까지 왕복한다.",
    "default": false
   },
   "transferRoundTripMinutes": {
    "type": "integer",
    "description": "적환장 왕복 시간(분). 기본값 없음."
   },
   "collectionStartMinutes": {
    "type": "integer",
    "description": "하루 수거 시작 시각(자정 기준 분).",
    "default": 360
   },
   "days": {
    "type": "integer",
    "default": 28
   },
   "seeds": {
    "type": "integer",
    "default": 30
   },
   "startDate": {
    "type": "string",
    "description": "시뮬레이션 0일차 날짜. 추석 연휴(2026-09-24~26)가 기간에 들도록 정했다.",
    "default": "2026-09-21"
   },
   "shiftMinutes": {
    "type": "integer",
    "description": "차량 한 대의 하루 근무 시간(분).",
    "default": 480
   },
   "disposalRoundTripMinutes": {
    "type": "integer",
    "description": "적환장이 없을 때 소각장 왕복 시간(분).",
    "default": 90
   },
   "zoneTravelMinutes": {
    "type": "number",
    "description": "이웃 구역 사이 이동시간(분, 5톤 기준).",
    "default": 8.0
   },
   "depotTravelMinutes": {
    "type": "number",
    "description": "차고지 → 첫 구역 이동시간(분, 5톤 기준).",
    "default": 15.0
   },
   "generationCv": {
    "type": "number",
    "description": "구역 하루 배출량의 변동계수.",
    "default": 0.15
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

## 사용자 요청 (V2-D1-B-r1)
"장량동 전체 아파트 단지를 대상으로 격일 수거와 매일 수거를 비교해 줘."

## 사용자 답변 대본 (질문을 기록한 뒤에만 꺼내 쓴다)
- 어느 서버 · 요청을 고칠지(선택지를 보여 줄 때): 행정동 전체(구역 단위)로 볼게
- 주거 구성: 아파트 3 : 단독주택 1
- 구역·세대: 기본값으로
- 차량: 5톤 2대
- 구역을 차량에 나누는 방식: 같은 구역 수로
- 공휴일: 반영 안 해도 돼
- 적환장: 안 써
- 기간·반복: 28일, 10회
- 그 밖: 기본값으로 해줘

## 끝나면
아래 JSON 을 `C:/Users/User/AppData/Local/Temp/claude/C--Dev-MCP/0d0588b1-2503-4c5e-8c1a-a280e6fd1cb4/scratchpad/v2/runs/V2-D1-B-r1.json` 에 UTF-8 로 써라(Write 도구 사용). 마지막 답장에는 이 JSON 만 그대로 붙여라.
{
  "id": "V2-D1-B-r1",
  "method": "B",
  "questions": [ {"text": "사용자에게 물은 질문 묶음 하나 (그대로)", "keys": ["이 묶음이 값을 물은 스키마 매개변수 이름 목록"], "answer": "대본에서 꺼낸 사용자 답"} ],
  "plannedCalls": [ {"name": "run_district_simulation", "arguments": {...부를 인자 그대로...}} ],
  "toldUser": "사용자에게 '이건 기본값/가정으로 넣었다' 고 알린 것이 있으면 적어라",
  "stoppedBecause": "왜 여기서 멈췄는지",
  "difficulties": "헷갈렸던 점이 있었으면 적어라"
}
