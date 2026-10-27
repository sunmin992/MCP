# 공영주차장 시뮬레이터 · Parking_v1 연결

Java 21 / Spring Boot MCP 서버. 기본 포트는 8092이며 `/mcp`와 `/confirm.html`을 제공한다.

## 인증키와 실행

[한국교통안전공단 주차정보 API](https://www.data.go.kr/data/15099883/openapi.do)를 활용신청한 뒤,
서버를 실행하는 환경에 `PARKING_API_SERVICE_KEY`를 설정한다. 포털의 일반 키와 URL 인코딩 키 모두 지원한다.
키는 MCP 인자, 소스, 설정 파일에 넣지 않는다. 키가 없어도 기존 가정값 실험은 동작한다.

PowerShell에서 키를 화면·명령 기록에 남기지 않고 현재 프로세스에 설정하는 예:

```powershell
$parkingKey = Read-Host '공공데이터포털 인증키' -AsSecureString
$env:PARKING_API_SERVICE_KEY = [System.Net.NetworkCredential]::new('', $parkingKey).Password
Set-Location C:\Dev\MCP\parking-sim
.\mvnw.cmd -q spring-boot:run
```

## 사용 순서

1. `fetch_parking_snapshot`에 공식 5자리 `sigunguCd`를 전달한다. `lotIds`를 생략하면 공영 후보와 사용 가능 여부가 나온다.
2. 사용자가 노외 주차장임을 확인한 1~12곳의 관리번호를 `lotIds`로 다시 전달한다. 배열 순서가 엔진의 주차장 순서다.
3. 반환된 `snapshot.snapshotId`를 `plan_subtasks`와 `build_scenario`의 `parkingSnapshotId`로 전달한다.
4. API에 없는 기존 차량의 잔여 체류시간을 사용자에게 물어 `initialVehicleRemainingMinutes`로 지정한다(1~1440분). 모든 기존 차량이 해당 시간에 출차한다는 명시적 가정이다.
5. 기존과 같이 실험 조건을 채우고 확인 화면에서 승인한 뒤 `run_scenario_by_token`으로 실행한다.

API 모드에서는 `answers`/`values`의 `lotCount`, `spacesPerLot`를 생략한다. 주차장 수와 개별 면수는 스냅샷이 정한다.
면수를 실험 변수로 바꾸려면 기존 가정값 모드를 사용한다. 거주자 우선 비율은 0만 허용한다(API에 기존 점유의 구획 구분이 없음).

`build_scenario` 인자 예(관리번호와 스냅샷 ID는 조회 결과를 사용):

```json
{
  "request": "선택한 공영주차장의 실시간 잔여면수로 안내 효과를 비교해 줘",
  "parkingSnapshotId": "조회 결과의 snapshotId",
  "initialVehicleRemainingMinutes": 60,
  "values": {
    "pricingPolicy": "FREE", "userMix": ["Visitor"], "dailyArrivals": 100,
    "congestionMode": "IGNORE", "maxSearchMinutes": 10, "lotSpacingMinutes": 3,
    "guidanceRefreshMinutes": 5, "days": 1, "seeds": 10
  },
  "variableAnswerKey": "guidanceMode",
  "variableValues": ["NONE", "REALTIME_APP"]
}
```

이 예의 요금·수요·출차 가정은 예시일 뿐이다. 사용자와 정한 값을 넣고 출처 규칙에 따라 `origins`를 작성한다.

## 데이터 의미와 적용 범위

공식 기술문서 `주차정보시스템_기술문서_20261001.docx`(서비스 1.5)를 기준으로 구현했다.

| API | 사용 필드 | 용도 |
|---|---|---|
| `PrkSttusInfo_v1` | `prk_center_id`, `prk_manage_agent`, `prk_plce_nm`, `prk_plce_adres`, `prk_cmprt_co`, 위경도 | 관리번호 결합, 공영(1) 필터, 시설 식별·참고 정보 |
| `PrkRealtimeInfo_v1` | `prk_center_id`, `pkfc_ParkingLots_total`, `pkfc_Available_ParkingLots_total` | 주차장별 총면수 및 초기 점유 = 총면수 − 잔여면수 |

- 두 목록은 **관리번호로 결합**한다. 민영·실시간 미제공·숫자 누락·음수·잔여면수 초과는 실행 입력으로 사용하지 않는다. 잔여면수 0은 정상 만차다.
- 시설 총면수와 실시간 총면수가 다르면 **실시간 총면수**를 적용하고 차이를 확인 화면과 결과에 표시한다. API 모드는 한 곳당 1~10,000면을 지원한다.
- `fetchedAt`은 조회 완료 시각(UTC)이다. 공식 실시간 응답에는 관측 시각이 없다. `SOURCE_TIMESTAMP_UNAVAILABLE`로 표시하며 최신 실측임을 보장하지 않는다.
- 시작 시간대는 조회 시각을 한국 시간으로 환산해 분 단위로 적용한다. 초기 점유는 실험 첫 시점에만 반영하고, 이후는 모의 차량의 입출차로 계산한다. 기존 점유 차량은 새 도착 차량 수지와 요금 수입에서 제외한다.
- 승인된 스냅샷은 실행 도중 갱신하지 않는다. 새 조회는 새 시나리오·확인이 필요하다. 동일 시나리오를 다시 실행하면 당시 입력을 재사용한다.
- 스냅샷은 메모리에 최대 100개 보관한다. 조회 후 10분이 지나면 새 시나리오 생성에 사용할 수 없으며 서버 재시작 시 사라진다. 이미 만든 시나리오는 기존 입력으로 재현 가능하다.
- 지역마다 운영정보·실시간 정보 제공 범위가 다르다. 포항의 실제 대상 주차장 지원 여부는 승인된 키로 조회해야 확인할 수 있다.
- **운영정보 API `PrkOprInfo_v1`의 요금·영업시간은 이번 연결에서 적용하지 않는다.** 요금은 실험 설정이며 24시간 이용 가능 가정을 유지한다. 좌표는 참고 정보이고 엔진 이동은 기존 한 줄 배치·이동시간 가정이다. 시설 API에는 노외 여부 필드가 없어 사용자가 확인한다.
- `REALTIME_APP`은 시뮬레이션 내 안내 갱신 방식이다. 외부 API를 주기적으로 호출하거나 실시간 운영을 예측한다는 의미가 아니다.

페이지당 100건, 데이터 종류당 최대 50페이지를 조회한다. 제한 초과·중복 페이지·오류 응답은 부분 결과 대신 오류를 반환한다. 외부 오류 본문이나 인증키 포함 URL은 사용자 오류 메시지에 노출하지 않는다.

## 검증

```powershell
.\mvnw.cmd -q test
```

API 응답 fixture로 페이지 처리·키 인코딩·오류·결측·공영 필터를 검증하고, 비균일 면수·초기 점유·출차·차량 수지와 계획→확인→실행 흐름을 시험한다. fixture 시험은 실서비스 연결 성공을 의미하지 않는다.
