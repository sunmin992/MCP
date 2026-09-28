package com.wastesim.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 시뮬레이터 기본값으로 채운 항목이 결과에 드러나는가.
 *
 * <p>이 표시가 없으면 밖에서 대조할 수 있는 값(포항시 표준데이터·TMAP 측정)과 이 시뮬레이터가 정해 둔 값이 결과에서
 * 구별되지 않는다. 둘 다 "기본값"으로 보이지만 뒷받침의 성질이 다르다.
 */
class ModelDefaultFlagTest {

    @Test
    void flagNamesTheFieldsAndSaysWhatItMeans() {
        String msg = DataQualityFlag.MODEL_DEFAULT_USED.message("days, seeds");
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

        r.addDataQualityFlag(DataQualityFlag.MODEL_DEFAULT_USED, "days");
        assertTrue(r.isNotForOperationalUse(),
                "모델 기본값으로 낸 값을 운영 예측이라 부를 수 없다");
    }
}
