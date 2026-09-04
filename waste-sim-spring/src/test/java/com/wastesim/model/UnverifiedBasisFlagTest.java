package com.wastesim.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 출처를 확인하지 않은 기본값이 결과에 드러나는가.
 *
 * <p>이 표시가 없으면 확인된 값(논문 §4.2)과 확인하지 않은 값(관행상 30일)이 결과에서
 * 구별되지 않는다. 표시가 남아 있는 동안은 남은 문헌 대조 작업이 보인다.
 */
class UnverifiedBasisFlagTest {

    @Test
    void flagNamesTheFieldsAndSaysWhatItMeans() {
        String msg = DataQualityFlag.DEFAULT_BASIS_UNVERIFIED.message("days, seeds");
        assertTrue(msg.contains("days, seeds"), "어느 필드인지 알 수 없으면 확인할 수 없다: " + msg);
        assertTrue(msg.contains("출처"), msg);
    }

    /** 결과가 이 표시를 실으면 운영 예측이 아니게 된다. */
    @Test
    void resultWithUnverifiedBasisIsNotOperational() {
        SimulationResult r = new SimulationResult("08:30", 0, java.util.Map.of(),
                java.util.Map.of(), 0.0, 1);
        r.setCoordinateQuality(CoordinateQuality.MEASURED_SITE);
        assertFalse(r.isNotForOperationalUse(), "지점 실측 좌표에 가정이 없으면 운영 후보다");

        r.addDataQualityFlag(DataQualityFlag.DEFAULT_BASIS_UNVERIFIED, "days");
        assertTrue(r.isNotForOperationalUse(),
                "출처를 확인하지 않은 기본값으로 낸 값을 운영 예측이라 부를 수 없다");
    }
}
