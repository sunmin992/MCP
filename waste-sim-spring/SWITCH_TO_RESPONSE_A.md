# 대응 A 전환 지침서 — 실데이터를 정식 seed로 (Claude Code 실행용)

> 목표: 추출 CSV의 **실측 교통량**을 유일한 교통 프로파일(`jangryang-weekday`)로 삼는다.
> (현재는 대응 B 상태: 가정 seed + `jangryang-weekday-real` 병행 → 이를 A로 되돌린다.)
> 코드/테스트/문서를 순서대로 수정하면 되며, 각 TASK에 정확한 수치·diff를 담았다.

## 배경: 실데이터 스케일 (반드시 인지)
`response_filtered.csv`로 생성한 실데이터의 특성:
- 전역 `hourlyWeight` **최대 1.78 @ 13시**(점심), 08시=1.54, 이른 아침 ~1.0.
- 노드별 최혼잡 = **Node_A(장성초등학교) 2.2 @ 12–13시**. 양덕(Node_B)은 ~1.46로 한산.
- ⚠️ 기존 임계 `congestionThresholdRed=2.0`으로는 **전역 기준 어떤 시각도 RED가 안 된다**(최대 1.78). → 임계를 실데이터 스케일에 맞춰 **1.7**로 낮춘다.

---

## TASK 1 — 프로파일 승격 (데이터)
**파일:** `src/main/resources/traffic/jangryang-weekday.json` (덮어쓰기)

1. 전처리 스크립트를 `id=jangryang-weekday`, 출력 경로를 정식 seed로 지정해 실행:
   ```bash
   python scripts/preprocess_response_filtered.py response_filtered.csv \
       --id jangryang-weekday \
       --out src/main/resources/traffic/jangryang-weekday.json
   ```
2. 스크립트의 임계값을 실데이터 스케일로 조정: `preprocess_response_filtered.py`에서
   `"congestionThresholdRed": 2.0` → **`"congestionThresholdRed": 1.7`** (또는 생성된 JSON에서 그 값만 1.7로 수정).
3. `alleyNodeIds`는 `["Node_C","Node_D"]` 그대로 유지(골목 테스트 V-T3 보존).
4. 대응 B 잔재 제거: `src/main/resources/traffic/jangryang-weekday-real.json` **삭제**.
   `TrafficDataService`의 `SEED_IDS`는 **`{"jangryang-weekday"}`** 로 유지(‑real 미등록. 만약 B에서 이미 추가했다면 되돌린다).

**Acceptance:** 생성된 `jangryang-weekday.json`의 `id=jangryang-weekday`, `congestionThresholdRed=1.7`, `hourlyWeight[13]≈1.78`, `alleyNodeIds=["Node_C","Node_D"]`.

---

## TASK 2 — RED 임계 정합 (TASK 1에서 처리됨, 근거)
임계 1.7이면 RED 창 ≈ **11:00–17:00**(전역 1.74~1.78), 08:30(1.54)은 정상.
검증기(`SimulationConfigValidator`)의 V-T5가 전역 `hourlyWeight`를 보든 노드별을 보든 **13:00엔 RED**(전역 1.78, Node_A 2.2)라 견고하다. 별도 코드 변경 불필요.

> 확인 포인트: V-T5가 노드별 RED를 본다면 대상 노드가 Node_A일 때 확실히 RED다. 전역을 본다면 임계 1.7로 충분하다.

---

## TASK 3 — 테스트 시각 수정
**파일:** `src/test/java/com/wastesim/tool/SimulationConfigValidatorTest.java`
`redPeakTimeWarnsButDoesNotBlock()` 한 곳만 수정.

```java
// before
c.setCollectionTimeLabel("08:30");   // 08:30은 시드 데이터상 RED
// after
c.setCollectionTimeLabel("13:00");   // 13:00은 실측 데이터상 RED(점심 피크), 비차단 경고만
```
메서드 주석의 "08:30은 시드 데이터상 RED"도 "13:00은 실측 데이터상 RED(점심 피크)"로 갱신.
**그 외 테스트는 변경하지 않는다**(alley/overflow/truckCount 등은 데이터 스케일과 무관).

---

## TASK 4 — 시나리오·문서 재정합
실측과 어긋나는 서술을 실데이터 기준으로 수정한다.

- `TRAFFIC_EXTENSION_DESIGN.md` 시나리오 1·2:
  - "출근 08:30 피크" → "**점심 12–13시 피크**"
  - 정체 노드 "양덕사거리(Node_B)" → "**장성초등학교(Node_A)**"
  - 시나리오 1 대안 예시 `collectionTime="06:30"`(피크 전) → 실데이터 기준 "**피크(13시) 회피해 오전/저녁 수거**"로 재서술.
- `CONNECT_TRAFFIC_CSV.md` §4 표에 "**대응 A 채택**" 명시(가정 seed 폐기, 실데이터 단일 소스).

---

## 최종 수용 기준
1. `python scripts/preprocess_response_filtered.py response_filtered.csv --id jangryang-weekday --out src/main/resources/traffic/jangryang-weekday.json` → 실데이터 프로파일 생성(임계 1.7).
2. `jangryang-weekday-real.json` 삭제, `SEED_IDS={"jangryang-weekday"}`.
3. `mvn test` → **전부 GREEN** (redPeak는 13:00으로 통과, alley/overflow/truckCount 유지).
4. 검증:
   ```bash
   curl -s localhost:8080/mcp -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"run_waste_simulation","arguments":{"collectionTime":"13:00","trafficEnabled":true,"trafficProfileId":"jangryang-weekday","days":3,"seeds":3}}}'
   ```
   → 점심 피크(Node_A RED) 반영으로 수거 완료시간·민원 상승, 트레이드오프 경고 확인.

## 요약 (A의 의미)
단일 진실 원천 = **실측 포항 교통량**. 데모·논문에서 "가정값"이 아니라 실데이터로 돌린다는 정직성을 얻는 대신, 정체 서사가 "출근 양덕"에서 "**점심 장성초등**"으로 바뀐다. 임계(1.7)와 테스트 시각(13:00)만 실데이터 스케일에 맞추면 나머지 엔진·검증·MCP 코드는 그대로 동작한다.
