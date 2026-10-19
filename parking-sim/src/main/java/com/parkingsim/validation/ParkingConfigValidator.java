package com.parkingsim.validation;

import com.parkingsim.engine.CongestionProfiles;
import com.parkingsim.model.GuidanceMode;
import com.parkingsim.model.ParkingConfig;
import com.parkingsim.model.PricingPolicy;
import com.parkingsim.model.UserType;
import com.parkingsim.tool.ErrorCode;
import com.parkingsim.tool.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 실행 설정 검증 — 범위의 정본.
 *
 * <p>값 하나의 범위와 <b>값들의 조합</b>을 함께 본다. 서브태스크 템플릿은 칸 하나만 보므로
 * "도착 차량이 면수로 감당할 수 없을 만큼 많다" 같은 조합은 여기서만 막을 수 있다.
 *
 * <p>기본값이 없는 값(할증률 · 거주자 우선 비율 · 탐색 포기 시간)이 필요한 설정에서 비어 있으면
 * <b>막는다.</b> 대신 쓸 값을 두면 사용자가 정하지 않은 가정으로 실행된다.
 */
@Component
public class ParkingConfigValidator {

    /** 수요(면·분) ÷ 공급(면·분) 이 이 값을 넘으면 거의 모두 포기하는 실험이라 막는다. */
    static final double MAX_OVERLOAD_RATIO = 3.0;

    private final CongestionProfiles profiles;

    public ParkingConfigValidator(CongestionProfiles profiles) {
        this.profiles = profiles;
    }

    public ValidationResult validate(ParkingConfig c) {
        List<ValidationError> errs = new ArrayList<>();
        List<ValidationError> warns = new ArrayList<>();

        if (c.getLotCount() < 1 || c.getLotCount() > 12) {
            errs.add(range("lotCount", "주차장 수는 1~12 곳이어야 합니다. 받은 값: " + c.getLotCount()));
        }
        if (c.getSpacesPerLot() < 10 || c.getSpacesPerLot() > 1000) {
            errs.add(range("spacesPerLot", "주차장 면수는 10~1000 이어야 합니다. 받은 값: " + c.getSpacesPerLot()));
        }
        if (c.getDailyArrivals() < 1 || c.getDailyArrivals() > 20000) {
            errs.add(range("dailyArrivals", "하루 도착 차량은 1~20000 대여야 합니다. 받은 값: " + c.getDailyArrivals()));
        }
        if (c.getDays() < 1 || c.getDays() > 90) {
            errs.add(range("days", "기간은 1~90 일이어야 합니다. 받은 값: " + c.getDays()));
        }
        if (c.getSeeds() < 1 || c.getSeeds() > 100) {
            errs.add(range("seeds", "반복 횟수는 1~100 회여야 합니다. 받은 값: " + c.getSeeds()));
        }
        if (c.getGuidanceRefreshMinutes() < 1 || c.getGuidanceRefreshMinutes() > 60) {
            errs.add(range("guidanceRefreshMinutes",
                    "안내 갱신 주기는 1~60 분이어야 합니다. 받은 값: " + c.getGuidanceRefreshMinutes()));
        }
        if (c.getBaseFeePer30Min() < 0 || c.getBaseFeePer30Min() > 10000) {
            errs.add(range("baseFeePer30Min",
                    "30분당 기본 요금은 0~10000 원이어야 합니다. 받은 값: " + c.getBaseFeePer30Min()));
        }

        // 기본값이 없는 값 — 비어 있으면 막는다.
        Integer maxSearch = c.getMaxSearchMinutes();
        if (maxSearch == null) {
            errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "maxSearchMinutes",
                    "탐색 포기 시간이 정해지지 않았습니다. 기본값이 없는 값이라 사용자가 정해야 합니다."));
        } else if (maxSearch < 1 || maxSearch > 60) {
            errs.add(range("maxSearchMinutes", "탐색 포기 시간은 1~60 분이어야 합니다. 받은 값: " + maxSearch));
        }

        PricingPolicy pricing = null;
        try {
            pricing = c.resolvePricingPolicy();
        } catch (IllegalArgumentException e) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "pricingPolicy", e.getMessage()));
        }
        if (pricing == PricingPolicy.TIME_OF_DAY) {
            Double rate = c.getPeakSurchargeRate();
            if (rate == null) {
                errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "peakSurchargeRate",
                        "시간대 차등 요금인데 할증률이 정해지지 않았습니다."));
            } else if (rate < 0 || rate > 3.0) {
                errs.add(range("peakSurchargeRate", "할증률은 0~3.0 이어야 합니다. 받은 값: " + rate));
            }
        }

        GuidanceMode guidance = null;
        try {
            guidance = c.resolveGuidanceMode();
        } catch (IllegalArgumentException e) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "guidanceMode", e.getMessage()));
        }

        List<UserType> mix = List.of();
        if (c.getUserMix() == null || c.getUserMix().isEmpty()) {
            errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "userMix", "이용자 구성이 비어 있습니다."));
        } else {
            try {
                mix = c.resolveUserMix();
            } catch (IllegalArgumentException e) {
                errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "userMix", e.getMessage()));
            }
        }

        // 거주자 우선 구획 — 거주자가 있으면 반드시 정하고, 없으면 두지 않는다.
        Double share = c.getResidentPriorityShare();
        if (c.residentsPresent()) {
            if (share == null) {
                errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "residentPriorityShare",
                        "거주자가 이용자 구성에 있는데 거주자 우선 구획 비율이 정해지지 않았습니다."));
            } else if (share < 0 || share > 0.9) {
                errs.add(range("residentPriorityShare", "거주자 우선 비율은 0~0.9 여야 합니다. 받은 값: " + share));
            }
        } else if (share != null && share > 0) {
            errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "residentPriorityShare",
                    "거주자가 이용자 구성에 없는데 거주자 우선 구획을 두었습니다. 아무도 쓰지 못하는 면이 됩니다."));
        }

        if (c.isCongestionEnabled() && profiles.multipliers(c.getCongestionProfileId()).isEmpty()) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "congestionProfileId",
                    "알 수 없는 혼잡 프로파일: " + c.getCongestionProfileId() + " (허용: " + profiles.ids() + ")"));
        }

        // 조합 — 실시간 안내가 탐색에 쓰일 수 있는가.
        if (guidance == GuidanceMode.REALTIME_APP && c.getLotCount() >= 2 && maxSearch != null
                && c.getGuidanceRefreshMinutes() > maxSearch) {
            errs.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "guidanceRefreshMinutes",
                    "안내 갱신 주기(" + c.getGuidanceRefreshMinutes() + "분)가 탐색 포기 시간(" + maxSearch
                            + "분)보다 깁니다. 운전자가 포기하기 전에 안내가 한 번도 바뀌지 않습니다."));
        }

        // 조합 — 수요가 공급을 크게 넘는가.
        if (!mix.isEmpty() && c.totalSpaces() > 0) {
            double ratio = overloadRatio(c, mix);
            if (ratio > MAX_OVERLOAD_RATIO) {
                errs.add(new ValidationError(ErrorCode.CRITICAL_OVERLOAD, "dailyArrivals",
                        String.format("하루 주차 수요가 면수로 감당할 수 있는 양의 %.0f%% 입니다. 거의 모든 차량이 "
                                + "포기하는 실험이라 실행하지 않습니다. 도착 차량을 줄이거나 주차장 · 면수를 늘리십시오.",
                                ratio * 100)));
            } else if (ratio > 1.0) {
                warns.add(new ValidationError(ErrorCode.CRITICAL_OVERLOAD, "dailyArrivals",
                        String.format("하루 주차 수요가 공급의 %.0f%% 입니다. 포기 차량이 많이 나옵니다.", ratio * 100)));
            }
        }
        return ValidationResult.of(errs, warns);
    }

    /** 하루 주차 수요(면·분) ÷ 하루 공급(면·분). 요금 회피는 빼지 않는다 — 보수적으로 본다. */
    public double overloadRatio(ParkingConfig c, List<UserType> mix) {
        double meanStay = mix.stream().mapToInt(u -> u.meanStayMinutes).average().orElse(0);
        double demand = c.getDailyArrivals() * meanStay;
        double supply = c.totalSpaces() * 1440.0;
        return demand / supply;
    }

    private static ValidationError range(String field, String message) {
        return new ValidationError(ErrorCode.OUT_OF_RANGE, field, message);
    }
}
