package com.wastesim.tool;

import com.wastesim.model.OccupationType;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.TrafficProfile;
import com.wastesim.model.TruckType;
import com.wastesim.model.WasteType;
import com.wastesim.service.TrafficDataService;
import com.wastesim.simulation.SimulationEngine;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SimulationConfig 서버측 검증 — 유일한 검증 진실 원천(single source of truth).
 * REST·MCP·채팅 파이프라인이 모두 이 검증기를 통과해야 실행된다(베이스라인 "서버가 검증을 소유").
 *
 * <p>교통·폐기물 교차 검증(TRAFFIC_EXTENSION_DESIGN.md §5)도 이 클래스가 담당한다
 * (별도 TrafficAwareValidator 클래스로 분리하지 않고 기존 단일 검증기를 확장 —
 * REST/MCP/채팅이 공유하는 단일 검증 진입점을 유지하기 위함).
 */
@Component
public class SimulationConfigValidator {

    private final TrafficDataService trafficData;

    public SimulationConfigValidator(TrafficDataService trafficData) {
        this.trafficData = trafficData;
    }

    public ValidationResult validate(SimulationConfig c) {
        List<ValidationError> errs = new ArrayList<>();
        if (c == null) {
            errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "config", "설정이 없습니다."));
            return ValidationResult.fail(errs);
        }

        int t = c.getCollectionTimeMinutes();
        if (t < 0 || t > 1439)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "collectionTime", "수거 시각은 00:00~23:59(0~1439분) 범위여야 합니다."));

        if (c.getDays() < 1 || c.getDays() > 365)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "days", "기간(days)은 1~365 사이여야 합니다."));

        if (c.getSeeds() < 1 || c.getSeeds() > 100)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "seeds", "반복(seeds)은 1~100 사이여야 합니다."));

        if (c.getLeaveSigma() < 0)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "leaveSigma", "외출 분산(leaveSigma)은 0 이상이어야 합니다."));

        if (c.getWasteSigma() < 0)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "wasteSigma", "배출 변동(wasteSigma)은 0 이상이어야 합니다."));

        if (c.getWasteMeanKg() <= 0)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "wasteMeanKg", "1인당 평균 배출량(wasteMeanKg)은 0보다 커야 합니다."));

        if (c.getCapacity() <= 0)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "capacity", "수거장 용량(capacity)은 0보다 커야 합니다."));

        if (c.getThreshold() < 0 || c.getThreshold() > 1)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "threshold", "민원 임계(threshold)는 0~1 사이여야 합니다."));

        validateCollectionTimes(c, errs);
        validateWasteTypes(c, errs);

        // 노드 ID를 'A' + 인덱스로 만들기 때문에 27번째부터 Node_[ 같은 값이 나오고,
        // 역변환(nodeIndex)은 알파벳 한 글자만 받아 규칙이 어긋난다. 지금 설계를 유지하는
        // 대신 범위를 명시적으로 막는다 — 조용히 깨진 ID로 경로·교통 대응이 틀어지는 것보다 낫다.
        if (c.getNumBuildings() > 26)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "numBuildings",
                    "건물 수는 26 이하여야 합니다(노드 ID가 Node_A~Node_Z 한 글자 체계입니다). 받은 값: "
                    + c.getNumBuildings()));

        if (c.getNumBuildings() < 1)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "numBuildings", "건물 수는 1 이상이어야 합니다."));

        if (c.getResidentsPerBuilding() < 1)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "residentsPerBuilding", "동당 인원은 1 이상이어야 합니다."));

        if (c.getOccupationMix() != null) {
            for (String occ : c.getOccupationMix()) {
                try {
                    OccupationType.fromName(occ);
                } catch (Exception ex) {
                    errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "occupationMix", "알 수 없는 직업: " + occ));
                }
            }
        }

        List<ValidationError> warns = new ArrayList<>();
        validateTraffic(c, errs, warns);
        return finish(errs, warns);
    }

    private ValidationResult finish(List<ValidationError> errs, List<ValidationError> warns) {

        if (!errs.isEmpty()) return ValidationResult.fail(errs);
        return warns.isEmpty() ? ValidationResult.ok() : ValidationResult.ok(warns);
    }

    /** 하루 중 시각으로 쓰이는 필드는 모두 같은 범위를 지켜야 한다. */
    private static final int MAX_MINUTE_OF_DAY = 1439;

    /**
     * 복수·주말 수거 시각도 단일 수거 시각과 같은 범위로 검증한다.
     *
     * <p>예전에는 {@code collectionTimeMinutes} 하나만 검사해서, 음수 시각은 시뮬레이션
     * 시작 전에 수거 이벤트를 만들고 1440 이상은 다음 날로 넘어갔다 — 일별 집계와
     * 교통 프로파일 적용 시각이 조용히 어긋난다.
     */
    private void validateCollectionTimes(SimulationConfig c, List<ValidationError> errs) {
        List<Integer> times = c.getCollectionTimesMinutes();
        if (times != null) {
            java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
            for (Integer m : times) {
                if (m == null || m < 0 || m > MAX_MINUTE_OF_DAY) {
                    errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "collectionTimesMinutes",
                            "수거 시각은 0~1439분 범위여야 합니다. 받은 값: " + m));
                } else if (!seen.add(m)) {
                    errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "collectionTimesMinutes",
                            "같은 수거 시각이 중복됐습니다: " + m + "분"));
                }
            }
        }
        Integer weekend = c.getWeekendCollectionTimeMinutes();
        if (weekend != null && (weekend < 0 || weekend > MAX_MINUTE_OF_DAY)) {
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "weekendCollectionTimeMinutes",
                    "주말 수거 시각은 0~1439분 범위여야 합니다. 받은 값: " + weekend));
        }
    }

    /**
     * 분리배출 유형별 설정을 검증한다.
     *
     * <p>예전에는 최상위 {@code capacity}·{@code threshold}만 검사해서, 유형 안의 값이
     * 비물리적이어도 통과했다. 그 결과가 오류가 아니라 <b>조용히 다른 실험</b>이 되는 것이
     * 문제였다 — 용량이 0이면 적재 비율이 0으로 처리돼 아무리 쌓여도 민원이 안 생기고,
     * 임계가 음수면 모든 배출이 민원이 되며, 비율이 음수면 그 유형이 통째로 사라진다.
     */
    private void validateWasteTypes(SimulationConfig c, List<ValidationError> errs) {
        List<WasteType> types = c.getWasteTypes();
        if (types == null || types.isEmpty()) return;

        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        double fractionSum = 0;
        for (WasteType w : types) {
            if (w == null) {
                errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "wasteTypes", "빈 폐기물 유형이 있습니다."));
                continue;
            }
            String key = w.getKey();
            if (key == null || key.isBlank()) {
                errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "wasteTypes.key", "폐기물 유형의 key가 비어 있습니다."));
            } else if (!keys.add(key)) {
                errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "wasteTypes.key",
                        "폐기물 유형 key가 중복됐습니다: " + key));
            }
            String at = " (" + (key == null ? "?" : key) + ")";

            if (!Double.isFinite(w.getFraction()) || w.getFraction() < 0 || w.getFraction() > 1) {
                errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "wasteTypes.fraction",
                        "배출 비율은 0~1 사이여야 합니다" + at + ". 받은 값: " + w.getFraction()));
            } else {
                fractionSum += w.getFraction();
            }
            if (!Double.isFinite(w.getCapacity()) || w.getCapacity() <= 0) {
                errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "wasteTypes.capacity",
                        "수거통 용량은 0보다 커야 합니다" + at + ". 받은 값: " + w.getCapacity()));
            }
            if (!Double.isFinite(w.getThreshold()) || w.getThreshold() < 0 || w.getThreshold() > 1) {
                errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "wasteTypes.threshold",
                        "민원 임계는 0~1 사이여야 합니다" + at + ". 받은 값: " + w.getThreshold()));
            }
            if (w.getIntervalDays() < 1) {
                errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "wasteTypes.intervalDays",
                        "수거 주기는 1일 이상이어야 합니다" + at + ". 받은 값: " + w.getIntervalDays()));
            }
        }
        // 합계가 1이 아니면 실제 배출량보다 많거나 적은 폐기물이 생성된다 — 부동소수
        // 오차를 감안해 허용 오차를 둔다.
        if (Math.abs(fractionSum - 1.0) > 0.001) {
            errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "wasteTypes.fraction",
                    String.format("배출 비율의 합이 1.0이어야 합니다. 현재 합계: %.3f", fractionSum)));
        }
    }

    // ── 교통·폐기물 교차 검증 (TRAFFIC_EXTENSION_DESIGN.md §5.2) ──────────────

    private void validateTraffic(SimulationConfig c, List<ValidationError> errs, List<ValidationError> warns) {
        // V-T1: 운행 대수 0 이하 → 수거 불가 (시나리오 4 차단)
        if (c.getTruckCount() < 1) {
            errs.add(new ValidationError(ErrorCode.TRUCK_COUNT_ZERO, "truckCount",
                    "운행 트럭 대수가 0대라 수거를 수행할 수 없습니다."));
        }

        // V-T2: 예측 적재율 > 1.2(120%) → 지속적 폐기물 누적(위생 붕괴)
        double overflow = predictOverflowRatio(c);
        if (overflow > 1.2) {
            errs.add(new ValidationError(ErrorCode.CRITICAL_WASTE_ACCUMULATION, "truckCount",
                    String.format("교통 정체는 방지되나 쓰레기 적재량이 한계를 초과(예측 적재율 %.0f%%)하여 " +
                            "요청을 수행할 수 없습니다.", overflow * 100)));
        }

        // truckType 파싱 실패(잘못된 값)는 별도 오류로 보고하고 이후 교통 검증은 건너뜀
        TruckType truckType;
        try {
            truckType = TruckType.fromName(c.getTruckType());
        } catch (Exception ex) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "truckType", "알 수 없는 차량 종류: " + c.getTruckType()));
            return;
        }

        // V-T4: routeSequence가 실제 수거장 노드 집합과 불일치
        List<String> seq = c.getRouteSequence();
        boolean routeValid = true;
        if (seq != null && !seq.isEmpty()) {
            Set<Integer> idxSet = new HashSet<>();
            routeValid = seq.size() == c.getNumBuildings();
            if (routeValid) {
                for (String s : seq) {
                    int idx = SimulationEngine.nodeIndex(s);
                    if (idx < 0 || idx >= c.getNumBuildings() || !idxSet.add(idx)) { routeValid = false; break; }
                }
            }
            if (!routeValid) {
                errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "routeSequence",
                        "routeSequence는 실제 수거장 노드 집합(0.." + (c.getNumBuildings() - 1) + "번 건물)과 " +
                        "정확히 일치하는 순열이어야 합니다."));
            }
        }

        // 교통 프로파일 미지정/미로드면 이후 교통 규칙(V-T3, V-T5)은 평가할 데이터가 없어 건너뜀
        if (!c.isTrafficEnabled() || c.getTrafficProfileId() == null) return;
        TrafficProfile tp = trafficData.find(c.getTrafficProfileId());
        if (tp == null) return;

        // V-T3: 대형트럭처럼 골목 진입 불가한 차종 + 대상 구역이 골목 → 교통상 실행 불가
        if (!truckType.alleyAccess) {
            Set<String> targetNodes = (routeValid && seq != null && !seq.isEmpty())
                    ? new HashSet<>(seq) : allNodeIds(c.getNumBuildings());
            for (String node : targetNodes) {
                if (tp.getAlleyNodeIds().contains(node)) {
                    errs.add(new ValidationError(ErrorCode.TRAFFIC_INFEASIBLE, "truckType",
                            truckType.labelKo + " 차량은 골목(" + node + ") 진입이 불가합니다. " +
                            "소형 차량(SMALL_1TON) 등으로 바꿔주세요."));
                    break;
                }
            }
        }

        // V-T5(경고, 비차단): 수거 시각이 RED 피크 구간이면 트레이드오프 사유 첨부
        if (tp.isRed(c.getCollectionTimeMinutes(), null)) {
            warns.add(new ValidationError(ErrorCode.OK, "collectionTime",
                    "수거 시각(" + c.getCollectionTimeLabel() + ")이 교통 정체가 심한 시간대(RED)입니다. " +
                    suggestOffPeak(tp, c.getCollectionTimeMinutes()) + " 수거를 고려해보세요."));
        }
    }

    /**
     * 별도 정밀 시뮬 없이 근사 계산하는 예측 적재율(수요/공급). 1.0 초과면
     * 배출량이 수거 용량을 지속적으로 초과한다는 뜻. (§5.2)
     *
     * <p>공급 측은 두 경로 중 큰 쪽을 취한다: (a) 트럭 선언 용량
     * (truckCount×truckType.capacityKg), (b) 실제 엔진이 강제하는 수거장(can)
     * 총 용량(numBuildings×capacity) — 둘 다 수거횟수만큼 반복. 설계서 원문은
     * (a)만 쓰지만, 이 엔진은 트럭 용량을 실제로 강제하지 않고(방문 시 무제한
     * 수거) 수거장 용량·임계치만 강제하므로, (a)만 쓰면 기본 설정(4동×25명,
     * LARGE_5TON 1대)조차 90kg/일 배출에 60kg/일 트럭 용량으로 "위험" 판정돼
     * 시스템 기본값이 검증에 걸리는 문제가 있었다. truckCount==0(트럭 자체가
     * 없음)은 수거장 용량과 무관하게 항상 과적으로 본다.
     */
    public double predictOverflowRatio(SimulationConfig c) {
        double dailyWasteKg = c.getNumBuildings() * c.getResidentsPerBuilding() * c.getWasteMeanKg();   // 거주민 평균 배출량(kg/일, 기본 0.9 — 캘리브레이션 시 wasteMeanKg로 조정)
        if (c.getTruckCount() <= 0) return 999.0;   // 트럭이 없으면 수거장 용량과 무관하게 과적

        int slotsPerDay = (c.getCollectionTimesMinutes() != null && !c.getCollectionTimesMinutes().isEmpty())
                ? c.getCollectionTimesMinutes().size() : 1;
        double collectionsPerDay = slotsPerDay / (double) Math.max(1, c.getCollectionIntervalDays());

        TruckType truckType;
        try {
            truckType = TruckType.fromName(c.getTruckType());
        } catch (Exception ex) {
            truckType = TruckType.LARGE_5TON;
        }
        double truckCapacityPerDay = c.getTruckCount() * truckType.capacityKg * collectionsPerDay;
        double canCapacityPerDay = c.getNumBuildings() * c.getCapacity() * collectionsPerDay;
        double supplyPerDay = Math.max(truckCapacityPerDay, canCapacityPerDay);
        if (supplyPerDay <= 0) return 999.0;
        return dailyWasteKg / supplyPerDay;
    }

    private static Set<String> allNodeIds(int numBuildings) {
        Set<String> out = new HashSet<>();
        for (int b = 0; b < numBuildings; b++) out.add(SimulationEngine.nodeId(b));
        return out;
    }

    /** 현재 시각에서 가장 가까운 비-RED 시간대를 앞뒤로 찾아 대안으로 제시(결정론적, LLM 미사용). */
    private static String suggestOffPeak(TrafficProfile tp, int minuteOfDay) {
        int hour = minuteOfDay / 60;
        Integer earlier = null, later = null;
        for (int h = hour - 1; h >= 0; h--) { if (!tp.isRed(h * 60, null)) { earlier = h; break; } }
        for (int h = hour + 1; h < 24; h++) { if (!tp.isRed(h * 60, null)) { later = h; break; } }
        StringBuilder sb = new StringBuilder();
        if (earlier != null) sb.append(String.format("%02d:00 이전", earlier + 1));
        if (earlier != null && later != null) sb.append(" 또는 ");
        if (later != null) sb.append(String.format("%02d:00 이후", later));
        return sb.length() > 0 ? sb.toString() : "다른 시각대";
    }
}
