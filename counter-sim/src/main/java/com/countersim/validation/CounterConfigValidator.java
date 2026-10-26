package com.countersim.validation;

import com.countersim.model.CounterConfig;
import com.countersim.tool.ErrorCode;
import com.countersim.tool.ValidationError;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 실행 설정 검증 — 범위의 정본.
 *
 * <p>값 하나의 범위와 <b>값들의 조합</b>을 함께 본다. 서브태스크 템플릿은 칸 하나만 보므로
 * "방문객이 창구로 감당할 수 없을 만큼 많다" 같은 조합은 여기서만 막을 수 있다.
 *
 * <p>기본값이 없는 값(대기 한도 · 예약 비율)이 필요한 설정에서 비어 있으면 <b>막는다.</b>
 */
@Component
public class CounterConfigValidator {

    /** 하루 처리 수요(분) ÷ 창구 공급(분) 이 이 값을 넘으면 거의 모두 돌아가는 실험이라 막는다. */
    static final double MAX_OVERLOAD_RATIO = 3.0;

    public ValidationResult validate(CounterConfig c) {
        List<ValidationError> errs = new ArrayList<>();
        List<ValidationError> warns = new ArrayList<>();

        if (c.getCounterCount() < 1 || c.getCounterCount() > 20) {
            errs.add(range("counterCount", "창구 수는 1~20 개여야 합니다. 받은 값: " + c.getCounterCount()));
        }
        if (c.getDailyVisitors() < 1 || c.getDailyVisitors() > 5000) {
            errs.add(range("dailyVisitors", "하루 방문객은 1~5000 명이어야 합니다. 받은 값: " + c.getDailyVisitors()));
        }
        if (c.getServiceMinutes() < 1 || c.getServiceMinutes() > 60) {
            errs.add(range("serviceMinutes", "평균 처리 시간은 1~60 분이어야 합니다. 받은 값: " + c.getServiceMinutes()));
        }
        if (c.getDays() < 1 || c.getDays() > 60) {
            errs.add(range("days", "기간은 1~60 일이어야 합니다. 받은 값: " + c.getDays()));
        }
        if (c.getSeeds() < 1 || c.getSeeds() > 100) {
            errs.add(range("seeds", "반복 횟수는 1~100 회여야 합니다. 받은 값: " + c.getSeeds()));
        }

        try {
            c.resolveQueueDiscipline();
        } catch (IllegalArgumentException e) {
            errs.add(new ValidationError(ErrorCode.INVALID_ENUM, "queueDiscipline", e.getMessage()));
        }

        // 기본값이 없는 값 — 비어 있으면 막는다.
        Integer maxWait = c.getMaxWaitMinutes();
        if (maxWait == null) {
            errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "maxWaitMinutes",
                    "대기 한도가 정해지지 않았습니다. 기본값이 없는 값이라 사용자가 정해야 합니다."));
        } else if (maxWait < 1 || maxWait > 240) {
            errs.add(range("maxWaitMinutes", "대기 한도는 1~240 분이어야 합니다. 받은 값: " + maxWait));
        }

        // 예약 비율 — 예약제면 반드시 정한다. 예약제가 아닌 실행에 적혀 있으면 막지 않고 알린다 —
        // "예약제 적용 · 미적용" 비교는 한 PES 의 비율을 두 실행이 함께 들고 간다.
        Double share = c.getReservationShare();
        if (c.isReservationEnabled()) {
            if (share == null) {
                errs.add(new ValidationError(ErrorCode.MISSING_FIELD, "reservationShare",
                        "예약제를 쓰는데 예약 비율이 정해지지 않았습니다."));
            } else if (share < 0 || share > 1.0) {
                errs.add(range("reservationShare", "예약 비율은 0~1 이어야 합니다. 받은 값: " + share));
            }
        } else if (share != null && share > 0) {
            warns.add(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "reservationShare",
                    "예약제를 쓰지 않는 실행이라 예약 비율은 결과에 반영되지 않습니다."));
        }

        // 조합 — 수요가 창구 공급을 크게 넘는가.
        if (c.getCounterCount() > 0) {
            double ratio = overloadRatio(c);
            if (ratio > MAX_OVERLOAD_RATIO) {
                errs.add(new ValidationError(ErrorCode.CRITICAL_OVERLOAD, "dailyVisitors",
                        String.format("하루 처리 수요가 창구로 감당할 수 있는 양의 %.0f%% 입니다. 거의 모두 돌아가는 "
                                + "실험이라 실행하지 않습니다. 방문객을 줄이거나 창구를 늘리십시오.", ratio * 100)));
            } else if (ratio > 1.0) {
                warns.add(new ValidationError(ErrorCode.CRITICAL_OVERLOAD, "dailyVisitors",
                        String.format("하루 처리 수요가 창구 공급의 %.0f%% 입니다. 이탈과 초과 근무가 많이 나옵니다.",
                                ratio * 100)));
            }
        }
        return ValidationResult.of(errs, warns);
    }

    /** 하루 처리 수요(분) ÷ 하루 창구 공급(분). */
    public double overloadRatio(CounterConfig c) {
        return c.getDailyVisitors() * c.getServiceMinutes()
                / (c.getCounterCount() * (double) CounterConfig.OPEN_MINUTES);
    }

    private static ValidationError range(String field, String message) {
        return new ValidationError(ErrorCode.OUT_OF_RANGE, field, message);
    }
}
