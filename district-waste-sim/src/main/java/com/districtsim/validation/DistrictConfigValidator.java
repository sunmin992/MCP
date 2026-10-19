package com.districtsim.validation;

import com.districtsim.model.DistrictConfig;
import com.districtsim.model.HolidayPolicy;
import com.districtsim.model.HousingType;
import com.districtsim.model.ScheduleMode;
import com.districtsim.model.TruckType;
import com.districtsim.tool.ErrorCode;
import com.districtsim.tool.ValidationError;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 실행 설정 검증 — 범위의 정본.
 *
 * <p>값 하나의 범위와 <b>값들의 조합</b>을 함께 본다. "수거일 하루에 실어야 할 양이 차량이 근무 시간 안에
 * 실을 수 있는 양을 크게 넘는다" 는 템플릿의 칸 하나로는 표현할 수 없다.
 *
 * <p>기본값이 없는 값(수거 요일 · 공휴일 정책 · 적환장 왕복 시간)이 필요한 설정에서 비어 있으면 <b>막는다.</b>
 */
@Component
public class DistrictConfigValidator {

    /** 수거일 부하 ÷ 수거 능력이 이 값을 넘으면 매번 크게 남는 실험이라 막는다. */
    static final double MAX_LOAD_RATIO = 1.5;

    public ValidationResult validate(DistrictConfig c) {
        List<ValidationError> errs = new ArrayList<>();
        List<ValidationError> warns = new ArrayList<>();

        if (c.getZoneCount() < 1 || c.getZoneCount() > 40) {
            errs.add(range("zoneCount", "구역 수는 1~40 이어야 합니다. 받은 값: " + c.getZoneCount()));
        }
        if (c.getHouseholdsPerZone() < 50 || c.getHouseholdsPerZone() > 5000) {
            errs.add(range("householdsPerZone", "구역당 세대 수는 50~5000 이어야 합니다. 받은 값: " + c.getHouseholdsPerZone()));
        }
        if (c.getTruckCount() < 1 || c.getTruckCount() > 20) {
            errs.add(range("truckCount", "차량 수는 1~20 대여야 합니다. 받은 값: " + c.getTruckCount()));
        }
        if (c.getCollectionStartMinutes() < 0 || c.getCollectionStartMinutes() > 1439) {
            errs.add(range("collectionStartMinutes", "수거 시작 시각은 0~1439 분이어야 합니다. 받은 값: " + c.getCollectionStartMinutes()));
        }
        if (c.getDays() < 7 || c.getDays() > 365) {
            errs.add(range("days", "기간은 7~365 일이어야 합니다. 받은 값: " + c.getDays()));
        }
        if (c.getSeeds() < 1 || c.getSeeds() > 100) {
            errs.add(range("seeds", "반복 횟수는 1~100 회여야 합니다. 받은 값: " + c.getSeeds()));
        }
        if (!"EQUAL_COUNT".equalsIgnoreCase(c.getZoneAllocation()) && !c.balancedAllocation()) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "zoneAllocation",
                    "알 수 없는 구역 배정 방식: " + c.getZoneAllocation() + " (허용: EQUAL_COUNT, BALANCED_LOAD)"));
        }
        if (!"INCLUDED".equalsIgnoreCase(c.getCommercialHandling()) && !c.commercialExcluded()) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "commercialHandling",
                    "알 수 없는 상가 처리 방식: " + c.getCommercialHandling() + " (허용: INCLUDED, EXCLUDED)"));
        }
        try {
            LocalDate.parse(c.getStartDate());
        } catch (DateTimeParseException | NullPointerException e) {
            errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "startDate", "시작 날짜를 읽을 수 없습니다: " + c.getStartDate()));
        }

        TruckType truck = null;
        try {
            truck = c.resolveTruckType();
        } catch (IllegalArgumentException e) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "truckType", e.getMessage()));
        }

        List<HousingType> mix = List.of();
        if (c.getHousingMix() == null || c.getHousingMix().isEmpty()) {
            errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "housingMix", "주거 유형 구성이 비어 있습니다."));
        } else {
            try {
                mix = c.resolveHousingMix();
            } catch (IllegalArgumentException e) {
                errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "housingMix", e.getMessage()));
            }
        }

        ScheduleMode mode = null;
        try {
            mode = c.resolveScheduleMode();
        } catch (IllegalArgumentException e) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "scheduleMode", e.getMessage()));
        }
        List<Integer> days = List.of();
        if (mode == ScheduleMode.WEEKDAYS) {
            if (c.getCollectionDays() == null || c.getCollectionDays().isEmpty()) {
                errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "collectionDays",
                        "요일 지정 수거인데 수거 요일이 정해지지 않았습니다."));
            } else {
                try {
                    days = c.resolveCollectionDays();
                } catch (IllegalArgumentException e) {
                    errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "collectionDays", e.getMessage()));
                }
            }
        } else if (mode != null) {
            days = mode.days;
        }

        if (c.isHolidayEnabled() && mode != null && mode != ScheduleMode.DAILY) {
            if (c.getHolidayPolicy() == null) {
                errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "holidayPolicy",
                        "공휴일을 반영하는데 수거일이 공휴일일 때 어떻게 할지 정해지지 않았습니다."));
            } else {
                try {
                    HolidayPolicy.fromName(c.getHolidayPolicy());
                } catch (IllegalArgumentException e) {
                    errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "holidayPolicy", e.getMessage()));
                }
            }
        }

        if (c.isTransferEnabled()) {
            Integer rt = c.getTransferRoundTripMinutes();
            if (rt == null) {
                errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "transferRoundTripMinutes",
                        "적환장을 쓰는데 왕복 시간이 정해지지 않았습니다."));
            } else if (rt < 10 || rt > 300) {
                errs.add(range("transferRoundTripMinutes", "적환장 왕복 시간은 10~300 분이어야 합니다. 받은 값: " + rt));
            } else if (rt >= c.getDisposalRoundTripMinutes()) {
                warns.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "transferRoundTripMinutes",
                        "적환장 왕복(" + rt + "분)이 소각장 직행 왕복(" + c.getDisposalRoundTripMinutes()
                                + "분)보다 짧지 않습니다. 적환장을 쓰는 이점이 없습니다."));
            }
        }

        // 조합 — 수거일 하루 부하가 수거 능력을 넘는가.
        if (truck != null && !mix.isEmpty() && !days.isEmpty() && c.getTruckCount() >= 1
                && (!c.isTransferEnabled() || c.getTransferRoundTripMinutes() != null)) {
            double ratio = loadRatio(c, truck, mix, days.size());
            if (ratio > MAX_LOAD_RATIO) {
                errs.add(new ValidationError(ErrorCode.CRITICAL_OVERLOAD, "truckCount",
                        String.format("수거일 하루에 실어야 할 양이 차량이 근무 시간 안에 실을 수 있는 양의 %.0f%% 입니다. "
                                + "매번 크게 남는 실험이라 실행하지 않습니다. 차량 · 수거 요일을 늘리거나 차종을 바꾸십시오.",
                                ratio * 100)));
            } else if (ratio > 1.0) {
                warns.add(new ValidationError(ErrorCode.CRITICAL_OVERLOAD, "truckCount",
                        String.format("수거일 부하가 수거 능력의 %.0f%% 입니다. 남는 구역이 생깁니다.", ratio * 100)));
            }
        }
        if (c.getZoneCount() * (long) c.getHouseholdsPerZone() > 40000) {
            warns.add(new ValidationError(ErrorCode.OUT_OF_RANGE, "householdsPerZone",
                    "전체 세대가 4만을 넘습니다. 포항 북구 행정동 하나로는 큰 규모입니다."));
        }
        return ValidationResult.of(errs, warns);
    }

    /**
     * 수거일 하루 부하 ÷ 수거 능력. 부하는 일 배출량 × 7 ÷ 주당 수거일. 능력은 차량마다 근무 시간 안에
     * (싣는 시간 + 비우러 다녀오는 시간) 을 몇 번 반복하는가로 근사한다.
     */
    public double loadRatio(DistrictConfig c, TruckType truck, List<HousingType> mix, int collectionsPerWeek) {
        double gen = 0, work = 0;
        for (int z = 0; z < c.getZoneCount(); z++) {
            HousingType t = mix.get(z % mix.size());
            if (t == HousingType.Commercial && c.commercialExcluded()) continue;
            double g = c.getHouseholdsPerZone() * t.kgPerHouseholdPerDay;
            gen += g;
            work += g / t.serviceKgPerMinute;
        }
        if (gen == 0) return 0;
        double rate = gen / work;
        double load = gen * 7 / collectionsPerWeek;
        double cycle = truck.capacityKg / rate + c.unloadRoundTripMinutes();
        double usable = c.getShiftMinutes() - c.getDepotTravelMinutes();
        double perTruck = Math.min(usable / cycle * truck.capacityKg, usable * rate);
        return load / (c.effectiveTrucks() * perTruck);
    }

    private static ValidationError range(String field, String message) {
        return new ValidationError(ErrorCode.OUT_OF_RANGE, field, message);
    }
}
