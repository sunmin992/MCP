# 추출 CSV 연결 가이드 — response_filtered.csv → 시스템 교통 레이어

> 추출하신 `response_filtered.csv`(장량동 인근 15개 도로 링크)를 현행 시스템의
> 교통 레이어(`TrafficDataService`가 읽는 `src/main/resources/traffic/jangryang-weekday.json`)로
> 변환·연결한다. Claude Code가 그대로 실행하도록 작성. 검증 완료된 스크립트 포함.

---

## 1. 추출 CSV 실제 스키마 (중요)

`response_filtered.csv` (UTF-8 **BOM 있음** → `utf-8-sig`로 읽을 것), 헤더 15행 데이터.

| 컬럼 | 내용 | 비고 |
|---|---|---|
| `spm_row` | 원본 행 번호 | 사용 안 함 |
| `std_dt` | (비어 있음) | — |
| `begin_node_nm` / `end_node_nm` | 도로 구간 시작/종료 지점명(한글) | **매핑 키** (예: 양덕교차로, 장성초등학교) |
| `link_id` | **전부 비어 있음** | 매핑에 못 씀 → 노드명으로 매핑 |
| `hour_01` … `hour_23`, `hour_00` | 시간대별 교통량(24개) | 순서 주의: `hour_00`이 맨 끝(자정) |
| `collection_dt` | 추출 시각 | 사용 안 함 |

**핵심 2가지:**
1. `link_id`가 비어 있으므로 이전 문서(`TRAFFIC_DATA_PIPELINE.md`)의 link_id 기반 매핑 대신 **지점명(landmark) 키워드 매핑**을 쓴다.
2. 시간 컬럼은 `hour_01..hour_23, hour_00` 순이지만, 배열 인덱스 h(0~23)는 `hour_%02d` 로 직접 참조하면 정확하다(`hour_00`→0, `hour_23`→23).

---

## 2. 지점명 → 시뮬레이션 노드 매핑 (검증 완료)

15개 링크를 4개 수거장 노드에 랜드마크 키워드로 귀속. **전 15건 매핑 성공(미매핑 0).**

| 시뮬 노드 | 키워드 | 매핑된 링크 수 | 실데이터 피크 |
|---|---|---|---|
| `Node_B` (양덕사거리) | `양덕` | 4 | 1.46 @ 13시 (비교적 한산) |
| `Node_A` (장성초등학교) | `장성초등학교` | 4 | **2.20 @ 12시 (RED)** |
| `Node_C` (장성초등사거리/창포) | `장성초등사거리`, `창포` | 5 | 1.72 @ 13시 |
| `Node_D` (두산위브/포항온천) | `두산위브`, `포항온천` | 2 | 1.81 @ 17시 |

> `alleyNodeIds`는 데이터와 무관한 **물리적 속성**(골목 진입 가능 여부)이므로 기존대로 `["Node_C","Node_D"]`로 고정한다(관련 테스트 보존).

---

## 3. 변환 스크립트 (검증 완료)

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
        "congestionThresholdRed": 2.0,
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

**출력(실데이터 기준):** `매핑: {Node_B:4, Node_A:4, Node_C:5, Node_D:2} | 미매핑: 0`

---

## 4. ⚠️ 실데이터 vs 시나리오 가정 (반드시 확인)

추출된 실제 교통량은 설계 시나리오의 가정과 **다르다**. 데이터를 왜곡하지 말고 아래처럼 처리한다.

| 항목 | 시나리오 설계 가정 | 실데이터(response_filtered) |
|---|---|---|
| 정체 피크 시각 | 출근 08:30 | **점심 12–13시** |
| 최혼잡 노드 | Node_B(양덕) | **Node_A(장성초등학교)**, 양덕은 한산 |
| 08:30 RED 여부 | RED | Node_A 1.83 등 — RED 아님 |

**대응(둘 중 택1):**
- **(A) 실데이터 우선(권장):** 위 스크립트로 seed를 덮어쓴다. 단, 기존 테스트 `redPeakTimeWarnsButDoesNotBlock`이 08:30를 RED로 가정하므로, **테스트의 시각을 실 피크(예: 12:00)로 수정**하거나 대상 노드를 Node_A로 바꾼다. 시나리오 1/2의 서술도 "점심 피크·장성초등 정체"로 재정합한다.
- **(B) 시나리오 충실 우선:** 기존 가정용 seed(현재 `jangryang-weekday.json`)를 유지하고, 실데이터는 `jangryang-weekday-real.json`로 **별도 id**로 저장해 병행한다(`SEED_IDS`에 추가). 데모는 가정 seed, 근거자료로 실데이터.

> 어느 쪽이든 `alleyNodeIds=[Node_C,Node_D]`는 유지해 골목 관련 테스트(UT-T5)를 보존한다.

---

## 5. 연결·검증 절차

1. `response_filtered.csv`를 프로젝트 루트(또는 `scripts/`)에 둔다.
2. `python scripts/preprocess_response_filtered.py response_filtered.csv` 실행 → `src/main/resources/traffic/jangryang-weekday.json` 갱신(대응 A) 또는 `-real.json` 별도 저장(대응 B).
3. 스키마 검증: `python scripts/validate_profile.py` → `PROFILE OK`.
4. `mvn test` 실행. 대응 A에서 `redPeakTimeWarnsButDoesNotBlock`이 깨지면 §4대로 시각을 실 피크로 수정.
5. 서버 기동 후 MCP로 교통 반영 확인:
   ```
   curl -s localhost:8080/mcp -H "Content-Type: application/json" \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"run_waste_simulation","arguments":{"collectionTime":"12:00","trafficEnabled":true,"trafficProfileId":"jangryang-weekday","days":3,"seeds":3}}}'
   ```
   → 점심 피크(Node_A RED) 반영으로 수거 완료시간·민원이 평시 대비 상승하는지 확인.

Java 엔진·검증·MCP 코드는 **수정 없이** 데이터 교체만으로 동작한다(인터페이스 안정). 유일한 예외는 §4의 테스트 시각 조정이다.
