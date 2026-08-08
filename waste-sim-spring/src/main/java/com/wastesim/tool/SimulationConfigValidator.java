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
        validateExtendedFields(c, errs);

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

    /** 건물 간 이동시간의 현실적 상한(분) — 이보다 크면 하루 안에 순회가 끝나지 않는다. */
    private static final int MAX_TRAVEL_MINUTES = 600;
    /** 월별 가중치 배열 길이 — {@code resolveMonthlyFactor}가 월 인덱스로 직접 접근한다. */
    private static final int MONTHS = 12;

    /**
     * 확장 설정 필드를 검증한다(DEBUGGING_ISSUES.md W-06).
     *
     * <p>이 필드들은 지금까지 검증기를 그냥 지나갔다. 일부는 setter나 사용 시점에서 조용히
     * 보정됐고(예전 {@code Math.max(0, v)}, {@code SimulationEngine.clamp01}) 나머지는
     * 보정도 없이 계산에 들어갔다. 둘 다 결과는 같다 — 요청한 것과 다른 실험이 돌아가고
     * 클라이언트는 알 수 없다. 보정 대신 오류로 돌려주는 편이 API 신뢰성에 유리하다.
     */
    private void validateExtendedFields(SimulationConfig c, List<ValidationError> errs) {
        if (c.getCollectionIntervalDays() < 1 || c.getCollectionIntervalDays() > 365)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "collectionIntervalDays",
                    "수거 주기는 1~365일이어야 합니다. 받은 값: " + c.getCollectionIntervalDays()));

        if (c.getRouteTravelMinutes() < 0 || c.getRouteTravelMinutes() > MAX_TRAVEL_MINUTES)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "routeTravelMinutes",
                    "건물 간 이동시간은 0~" + MAX_TRAVEL_MINUTES + "분이어야 합니다. 받은 값: "
                    + c.getRouteTravelMinutes()));

        if (c.getDispatchIntervalMinutes() < 0 || c.getDispatchIntervalMinutes() > MAX_MINUTE_OF_DAY)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "dispatchIntervalMinutes",
                    "배차 간격은 0~" + MAX_MINUTE_OF_DAY + "분이어야 합니다. 받은 값: "
                    + c.getDispatchIntervalMinutes()));

        // 점검 시각도 d*1440 + 이 값으로 이벤트를 만들므로 수거 시각과 같은 범위를 지켜야
        // 한다 — 벗어나면 점검일과 집계일이 하루씩 어긋난다.
        if (c.getLandlordInspectMinutes() < 0 || c.getLandlordInspectMinutes() > MAX_MINUTE_OF_DAY)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "landlordInspectMinutes",
                    "임대인 점검 시각은 0~" + MAX_MINUTE_OF_DAY + "분(00:00~23:59)이어야 합니다. 받은 값: "
                    + c.getLandlordInspectMinutes()));

        ratio01(c.getLandlordThreshold(), "landlordThreshold", "임대인 점검 임계", errs);
        ratio01(c.getReturnFraction(), "returnFraction", "귀가 배출 비율", errs);

        if (!Double.isFinite(c.getTrafficComplaintWeight()) || c.getTrafficComplaintWeight() < 0)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "trafficComplaintWeight",
                    "교통 민원 가중치는 0 이상의 유한한 값이어야 합니다. 받은 값: "
                    + c.getTrafficComplaintWeight()));

        validateHolidays(c, errs);
        validateMonthlyFactor(c, errs);
    }

    private static void ratio01(double v, String field, String label, List<ValidationError> errs) {
        if (!Double.isFinite(v) || v < 0 || v > 1)
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, field,
                    label + "은(는) 0~1 사이여야 합니다. 받은 값: " + v));
    }

    /**
     * 공휴일은 엔진의 날짜 인덱스와 같은 0-based여야 한다({@code for (int d = 0; d < days; d++)}).
     * 기간 밖 값은 아무 효과 없이 무시되므로, 지정했다고 믿은 날에 수거가 그대로 일어난다.
     */
    private static void validateHolidays(SimulationConfig c, List<ValidationError> errs) {
        List<Integer> holidays = c.getHolidays();
        if (holidays == null) return;
        java.util.Set<Integer> seen = new java.util.LinkedHashSet<>();
        for (Integer d : holidays) {
            if (d == null || d < 0 || d >= c.getDays()) {
                errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "holidays",
                        "공휴일은 0~" + (c.getDays() - 1) + "일차(시뮬레이션 기간 안)여야 합니다. 받은 값: " + d));
            } else if (!seen.add(d)) {
                errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "holidays",
                        "같은 공휴일이 중복됐습니다: " + d + "일차"));
            }
        }
    }

    /**
     * {@code resolveMonthlyFactor}가 {@code monthIndex % length}로 접근하므로, 길이가 12가
     * 아니면 월과 가중치의 대응이 어긋난다 — 길이 3이면 1·4·7·10월이 모두 같은 값이 된다.
     */
    private static void validateMonthlyFactor(SimulationConfig c, List<ValidationError> errs) {
        double[] f = c.getMonthlyWasteFactor();
        if (f == null) return;
        if (f.length != MONTHS) {
            errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "monthlyWasteFactor",
                    "월별 가중치는 1~12월 " + MONTHS + "개여야 합니다. 받은 길이: " + f.length));
            return;
        }
        for (int i = 0; i < f.length; i++) {
            if (!Double.isFinite(f[i]) || f[i] <= 0) {
                errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "monthlyWasteFactor",
                        "월별 가중치는 0보다 큰 유한한 값이어야 합니다(" + (i + 1) + "월). 받은 값: " + f[i]));
            }
        }
    }

    // ── 교통·폐기물 교차 검증 (TRAFFIC_EXTENSION_DESIGN.md §5.2) ──────────────

    private void validateTraffic(SimulationConfig c, List<ValidationError> errs, List<ValidationError> warns) {
        // V-T1: 운행 대수 0 이하 → 수거 불가 (시나리오 4 차단)
        if (c.getTruckCount() < 1) {
            errs.add(new ValidationError(ErrorCode.TRUCK_COUNT_ZERO, "truckCount",
                    "운행 트럭 대수가 0대라 수거를 수행할 수 없습니다."));
        }

        // truckType 파싱 실패(잘못된 값)는 별도 오류로 보고하고 이후 교통 검증은 건너뜀
        TruckType truckType;
        try {
            truckType = TruckType.fromName(c.getTruckType());
        } catch (Exception ex) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "truckType", "알 수 없는 차량 종류: " + c.getTruckType()));
            return;
        }

        Double allocated = c.getRouteAvailableCapacityKg();
        if (allocated != null && (!Double.isFinite(allocated) || allocated <= 0
                || allocated > truckType.capacityKg)) {
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "routeAvailableCapacityKg",
                    "경로 배정용량은 0보다 크고 선택 차종의 정격용량(" + truckType.capacityKg
                            + "kg) 이하여야 합니다. 받은 값: " + allocated));
        }
        double routeCapacity = c.resolveRouteCapacityKg(truckType.capacityKg);
        double initialLoad = c.getInitialTruckLoadKg();
        if (!Double.isFinite(initialLoad) || initialLoad < 0 || initialLoad > routeCapacity) {
            errs.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "initialTruckLoadKg",
                    "초기 적재량은 0 이상 경로 배정용량(" + routeCapacity
                            + "kg) 이하여야 합니다. 받은 값: " + initialLoad));
        }

        // V-T2: 초기 적재량을 제외한 실제 신규 수거 가능량을 공급으로 사용한다.
        double overflow = predictOverflowRatio(c);
        if (overflow > 1.2) {
            errs.add(new ValidationError(ErrorCode.CRITICAL_WASTE_ACCUMULATION, "routeAvailableCapacityKg",
                    String.format("쓰레기 적재량이 실제 신규 수거 가능량을 초과(예측 적재율 %.0f%%)하여 " +
                            "요청을 수행할 수 없습니다.", overflow * 100)));
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

        // 교통 적용을 요청했는데 프로파일이 없으면 조용히 비활성화하지 않고 차단한다.
        if (!c.isTrafficEnabled()) return;
        if (c.getTrafficProfileId() == null || c.getTrafficProfileId().isBlank()) {
            errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "trafficProfileId",
                    "교통 레이어를 사용하려면 trafficProfileId가 필요합니다."));
            return;
        }
        TrafficProfile tp = trafficData.find(c.getTrafficProfileId());
        if (tp == null) {
            errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "trafficProfileId",
                    "등록되지 않은 교통 프로파일입니다: " + c.getTrafficProfileId()));
            return;
        }

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
     * <p>공급은 실제 경로가 배정되는 트럭 수 × 운행별 신규 수거 가능량 ×
     * 일평균 운행횟수로 계산한다. 건물보다 트럭이 많으면 엔진은 빈 경로의
     * 운행을 생성하지 않으므로, 검증기도 방문 건물이 있는 트럭만 센다.
     * 엔진도 운행별 잔여 적재용량을 실제로 차감하므로 검증기와 실행기의 물리
     * 가정이 같다. 수거장 용량은 저장 한계이지 차량 운반 처리량이 아니므로
     * 공급량에 더하지 않는다. truckCount==0이면 항상 수거 불가로 본다.
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
        double pickupCapacityPerTrip = c.resolvePickupCapacityKg(truckType.capacityKg);
        int activeTruckCount = Math.min(c.getTruckCount(), c.getNumBuildings());
        double truckCapacityPerDay = activeTruckCount * pickupCapacityPerTrip * collectionsPerDay;
        // 엔진도 실제로 트럭 적재용량을 강제하므로 일일 수거능력은 트럭 용량 합계다.
        // 수거장 용량은 저장 한계이지 차량의 운반 처리량이 아니다.
        double supplyPerDay = truckCapacityPerDay;
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
