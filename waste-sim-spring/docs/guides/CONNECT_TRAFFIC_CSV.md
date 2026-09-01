# 교통 데이터 연동 가이드 — response_filtered.csv → 시스템 교통 레이어

> 이 문서는 원래 별개였던 `CONNECT_TRAFFIC_CSV.md`(CSV 스키마·변환 절차)와
> `SWITCH_TO_RESPONSE_A.md`(실측 데이터를 단일 진실 원천으로 채택하는 전환
> 작업 지침)를 하나로 합친 것이다 — 전환 작업(TASK 1~4)은 이미 완료돼 현재
> 코드베이스 상태 그 자체이므로, 여기서는 "지금 상태 요약"과 "새 CSV로
> 갱신할 때 재사용하는 절차"만 남기고 완료된 작업 지시문은 정리했다.

## 1. 현재 상태 (완료된 결정)

포항시 장량동 인근 실측 교통량(`response_filtered.csv`, 공공데이터포털 원자료)이
**유일한 교통 프로파일**(`jangryang-weekday`)이다. 시나리오 설계 당시의 가정(출근
08:30 피크, Node_B(양덕) 최혼잡)과 실측치는 다르며, 실측을 그대로 채택했다.

| 항목 | 값 |
|---|---|
| `congestionThresholdRed` | **1.7**(실측 데이터 스케일에 맞춤 — 최대 1.78이라 임계 2.0이면 RED가 하나도 안 뜬다) |
| 정체 피크 시각 | **점심 12~13시**(전역 `hourlyWeight[13]≈1.78`) |
| 최혼잡 노드 | **Node_A(장성초등학교)**, 12~13시 2.20 — RED |
| 비교적 한산 | Node_B(양덕사거리), 최대 1.46 |
| 08:30 시각 | 전역 1.54로 RED 아님(과거 가정 시나리오의 "출근 피크"와 다름) |
| `alleyNodeIds` | **제거됨(2026-09-01).** 대형 차량 진입 가능 여부는 교통량이 아니라 수거 지점의 성질이라 `collection/jangnyang-collection-sites.json`의 `largeTruckAllowed`로 옮겼다(§3.1) |
| 프로파일 id 구성 | `jangryang-weekday` 단일. 과거 가정용 시나리오와 병행하던 `jangryang-weekday-real.json`은 삭제, `SEED_IDS`도 단일 항목 |

관련 테스트(`SimulationConfigValidatorTest.redPeakTimeWarnsButDoesNotBlock` 등)는
이미 13:00 기준으로 갱신돼 있다.

## 2. 추출 CSV 실제 스키마

`response_filtered.csv` (UTF-8 **BOM 있음** → `utf-8-sig`로 읽을 것), 헤더 15행 데이터.

| 컬럼 | 내용 | 비고 |
|---|---|---|
| `spm_row` | 원본 행 번호 | 사용 안 함 |
| `std_dt` | (비어 있음) | — |
| `begin_node_nm` / `end_node_nm` | 도로 구간 시작/종료 지점명(한글) | **매핑 키** (예: 양덕교차로, 장성초등학교) |
| `link_id` | **전부 비어 있음** | 매핑에 못 씀 → 노드명으로 매핑 |
| `hour_01` … `hour_23`, `hour_00` | 시간대별 교통량(24개) | 순서 주의: `hour_00`이 맨 끝(자정) |
| `collection_dt` | 추출 시각 | 사용 안 함 |

`link_id`가 비어 있어 지점명(landmark) 키워드 매핑을 쓴다. 시간 컬럼은
`hour_01..hour_23, hour_00` 순이지만, 배열 인덱스 h(0~23)는 `hour_%02d`로 직접
참조하면 정확하다.

## 3. 지점명 → 시뮬레이션 노드 매핑

15개 링크를 4개 수거장 노드에 랜드마크 키워드로 귀속. **전 15건 매핑 성공(미매핑 0).**

| 시뮬 노드 | 키워드 | 매핑된 링크 수 | 실데이터 피크 |
|---|---|---|---|
| `Node_B` (양덕사거리) | `양덕` | 4 | 1.46 @ 13시 (비교적 한산) |
| `Node_A` (장성초등학교) | `장성초등학교` | 4 | **2.20 @ 12시 (RED)** |
| `Node_C` (장성초등사거리/창포) | `장성초등사거리`, `창포` | 5 | 1.72 @ 13시 |
| `Node_D` (두산위브/포항온천) | `두산위브`, `포항온천` | 2 | 1.81 @ 17시 |

## 3.1 alleyNodeIds를 걷어냈다 (2026-09-01)

`traffic/jangryang-traffic-zones.json`이 네 구역의 위치를 못박은 뒤 OSM으로 대조한 결과다.

| 노드 | 확정 좌표의 실제 도로 | 현재 표시 |
|---|---|---|
| Node_A (장성초등학교) | `residential` 새천년대로1123번길 | 골목 아님 |
| Node_B (양덕교차로) | **`primary` 새천년대로** | 골목 아님 |
| Node_C (장성초등사거리) | **`primary` 새천년대로(4차로)** × `residential` 대곡로 | **골목** |
| Node_D (두산위브↔포항온천) | **`secondary` 삼흥로(6차로)** | **골목** |

6차로 도로변인 Node_D를 "5톤 차량 진입 불가"로 두고 있다. Node_C도 4차로 간선과
만나는 교차로다.

6차로 도로변인 Node_D를 "5톤 차량 진입 불가"로 두고 있었다. Node_C도 4차로 간선과
만나는 교차로다. **골목으로 유지할 근거가 없으므로 운영 데이터에서 제거했다.**

옮긴 곳은 수거 지점이다. 대형 차량이 닿는지는 교통량의 성질이 아니라 **그 지점의 물리적
성질**이므로, `CollectionSite.largeTruckAllowed`가 그 자리다. 함께 따라온 변화가 둘 있다.

- **V-T3가 교통 게이트보다 앞으로 나왔다.** 골목은 교통 레이어를 꺼도 골목이다. 전에는
  교통을 켠 경우에만 판정됐다.
- **등록되지 않은 지점은 막지 않는다.** 접근성을 모르는 것과 못 들어간다는 것은 다르다.

수거 지점 좌표가 아직 비어 있으므로 **지금 V-T3는 어떤 실제 실행도 막지 않는다.** 그것이
실제 데이터에 맞는 결과다 — 확정 좌표 기준 장량동 네 지점은 모두 간선에 접한다.

검증 로직 자체는 `src/test/resources/collection/test-alley-sites.json`의 **가상 지점**으로
계속 지킨다(`TestSites.withAlleys()`). 테스트를 통과시키려고 운영 데이터를 사실과 다르게
두지 않기 위한 분리다.

## 3.2 노드는 이제 "교통 구역"이다 (2026-09-01)

`Node_A`~`Node_D`가 두 가지를 동시에 뜻하던 것을 갈랐다.

| | 무엇인가 | 어디에 있나 |
|---|---|---|
| **교통 구역** | 이 CSV의 링크를 키워드로 귀속시킨 **관측 지점**. `nodeHourlyWeight`의 키가 이것이다. 학교·사거리·아파트가 여기 오는 이유 | `traffic/jangryang-traffic-zones.json` (구 `jangryang-nodes.json`) |
| **수거 지점** | 쓰레기가 나오는 곳. 원본 DEVS 모델의 `GarbageCan`, 건물당 하나 | `collection/jangnyang-collection-sites.json` |

수거 지점은 자신이 속한 구역을 `trafficZone`으로 가리키고, **여러 지점이 한 구역을 공유할 수
있다** — 같은 골목의 원룸 여러 동은 같은 혼잡을 겪는다. 겹쳐 있던 시절에는 표현할 수 없던
관계다. 없는 구역을 가리키면 기동을 막는다(조용히 전역 가중치로 떨어지면 설정 오류가 정상
동작처럼 보인다).

라벨 체계(`Node_A~Z`)는 같지만 **별개의 이름공간**이다. 수거 지점 `Node_A`와 교통 구역
`Node_A`는 서로 다른 것을 가리킬 수 있다.

> **아직 계산 경로는 바뀌지 않았다.** 혼잡 가중치를 찾는 두 자리(`SimulationEngine`·
> `RouteDurationEstimator`)는 여전히 수거 지점 id를 그대로 구역 id로 넘긴다 — 겹쳐 있던
> 시절의 잔재이며, 매핑이 비어 있는 지금은 결과가 같다. 그 두 곳을
> `CollectionSiteRegistry.trafficZoneOf()`로 바꾸는 것이 이동시간 작업의 첫 단계다.

## 4. 변환 스크립트

**파일:** `scripts/preprocess_response_filtered.py`
**실행:** `python scripts/preprocess_response_filtered.py [response_filtered.csv]`
표준 라이브러리만 사용(pandas 불필요).

```python
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""response_filtered.csv(장량동 인근 교통량) → TrafficProfile JSON.
link_id가 비어 있어 지점명(begin/end) 키워드로 4개 시뮬 노드에 귀속한다.
"""
import sys, json, os, csv

SRC = sys.argv[1] if len(sys.argv) > 1 else "response_filtered.csv"
OUT = "src/main/resources/traffic/jangryang-weekday.json"
K = 1.2                              # 피크 지연 강도(글로벌 최대 대비). 1+K = 최대 가중치
ALLEY = ["Node_C", "Node_D"]        # 시뮬레이션상 골목(테스트 보존)

# 지점명 키워드 → 시뮬 노드. begin/end 노드명에 키워드가 있으면 귀속.
NODE_KEYWORDS = {
    "Node_B": ["양덕"],
    "Node_A": ["장성초등학교"],
    "Node_C": ["장성초등사거리", "창포"],
    "Node_D": ["두산위브", "포항온천"],
}
HOURS = ["hour_%02d" % h for h in range(24)]   # hour_00..hour_23 → index 0..23

def classify(begin, end):
    text = (begin or "") + " " + (end or "")
    for node, kws in NODE_KEYWORDS.items():
        if any(k in text for k in kws):
            return node
    return None

def main():
    rows = list(csv.DictReader(open(SRC, encoding="utf-8-sig")))
    agg = {n: [0.0] * 24 for n in NODE_KEYWORDS}
    cnt = {n: 0 for n in NODE_KEYWORDS}
    unmapped = 0
    for r in rows:
        node = classify(r.get("begin_node_nm"), r.get("end_node_nm"))
        if node is None:
            unmapped += 1
            continue
        cnt[node] += 1
        for h in range(24):
            try:
                agg[node][h] += float(r.get(HOURS[h]) or 0)
            except ValueError:
                pass
    node_vol = {n: [agg[n][h] / cnt[n] if cnt[n] else 0.0 for h in range(24)] for n in NODE_KEYWORDS}
    vmax = max((v for arr in node_vol.values() for v in arr), default=1.0) or 1.0
    node_hourly = {n: [round(1.0 + K * (node_vol[n][h] / vmax), 2) for h in range(24)] for n in NODE_KEYWORDS}
    global_hourly = [round(sum(node_hourly[n][h] for n in NODE_KEYWORDS) / len(NODE_KEYWORDS), 2) for h in range(24)]

    profile = {
        "id": "jangryang-weekday",
        "congestionThresholdRed": 1.7,
        "hourlyWeight": global_hourly,
        "nodeHourlyWeight": node_hourly,
        "alleyNodeIds": ALLEY,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    json.dump(profile, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print("WROTE", OUT, "| 매핑:", {n: cnt[n] for n in NODE_KEYWORDS}, "| 미매핑:", unmapped)

if __name__ == "__main__":
    main()
```

## 5. 새 CSV로 갱신하는 절차(재사용)

같은 지역의 최신 CSV로 교통 프로파일을 다시 만들고 싶을 때 반복하는 절차.

1. 새 CSV를 프로젝트 루트(또는 `scripts/`)에 둔다.
2. `python scripts/preprocess_response_filtered.py <csv경로>` 실행 →
   `src/main/resources/traffic/jangryang-weekday.json` 갱신.
3. 스키마 검증: `python scripts/validate_profile.py` → `PROFILE OK` 확인.
4. `mvn test` 실행 — RED 판정 관련 테스트가 새 데이터의 피크 시각과 어긋나면
   (`SimulationConfigValidatorTest`의 13:00 기준 등) 테스트의 기준 시각을 새
   피크로 맞춰 수정한다.
5. 서버 기동 후 MCP로 반영 확인:
   ```
   curl -s localhost:8080/mcp -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"run_waste_simulation","arguments":{"collectionTime":"12:00","trafficEnabled":true,"trafficProfileId":"jangryang-weekday","days":3,"seeds":3}}}'
   ```
   → 피크 시각(RED 노드) 반영으로 수거 완료시간·민원이 평시 대비 상승하는지 확인.

Java 엔진·검증·MCP 코드는 데이터 교체만으로 그대로 동작한다(인터페이스 안정) —
코드 변경이 필요한 유일한 경우는 RED 판정 테스트의 기준 시각이 데이터에 따라
달라질 때뿐이다.

다른 지역/새 CSV로 완전히 다른 프로파일을 병행하고 싶다면(가정용 시나리오와
실측을 동시에 유지하는 경우), `-real.json` 같은 별도 id로 저장하고
`TrafficDataService.SEED_IDS`에 추가하면 된다 — 다만 현재는 "실측 데이터가
유일한 진실 원천"이라는 원칙(§1)에 따라 이 방식을 쓰지 않는다.
