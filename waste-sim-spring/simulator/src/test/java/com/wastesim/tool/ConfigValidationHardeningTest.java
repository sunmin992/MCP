package com.wastesim.tool;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.WasteType;
import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 입력 검증 강화 검증 (DEBUGGING_ISSUES.md W-01·W-02·W-05).
 *
 * <p>세 결함의 공통점은 <b>잘못된 입력이 오류가 아니라 다른 실험이 된다</b>는 것이다.
 * 용량 0은 "민원이 안 생기는 실험", 음수 시각은 "시작 전에 수거하는 실험", 27번째 건물은
 * "노드 ID가 깨진 실험"이 되고 결과는 정상적으로 반환된다. R&E에서는 그게 가장 위험하다.
 */
class ConfigValidationHardeningTest {

    private final SimulationConfigValidator validator =
            new SimulationConfigValidator(new TrafficDataService());

    /** 나머지 필드가 전부 정상인 기준 설정 — 검증 대상 필드만 바꿔 가며 쓴다. */
    private SimulationConfig base() {
        SimulationConfig c = new SimulationConfig();
        c.setCollectionTimeLabel("12:00");
        c.setDays(7);
        c.setSeeds(3);
        return c;
    }

    private boolean rejects(SimulationConfig c, String field) {
        ValidationResult r = validator.validate(c);
        return !r.ready() && r.errors().stream().anyMatch(e -> e.field().contains(field));
    }

    @Test
    @DisplayName("기준 설정은 통과한다 — 검증이 과하게 조이지 않았는지 확인")
    void baselineConfigPasses() {
        assertTrue(validator.validate(base()).ready());
    }

    // ── W-01: 분리배출 유형 내부 값 ──────────────────────────────────────

    private SimulationConfig withTypes(WasteType... types) {
        SimulationConfig c = base();
        c.setWasteTypes(List.of(types));
        return c;
    }

    private WasteType type(String key, double fraction, double capacity, double threshold, int interval) {
        WasteType w = new WasteType();
        w.setKey(key);
        w.setFraction(fraction);
        w.setCapacity(capacity);
        w.setThreshold(threshold);
        w.setIntervalDays(interval);
        return w;
    }

    @Test
    @DisplayName("정상적인 분리배출 구성은 통과한다(비율 합 1.0)")
    void validWasteTypesPass() {
        assertTrue(validator.validate(withTypes(
                type("GENERAL", 0.6, 30, 0.8, 1),
                type("RECYCLE", 0.4, 20, 0.8, 2))).ready());
    }

    @Test
    @DisplayName("용량 0은 거부한다 — 적재 비율이 0으로 처리돼 민원이 영원히 안 생긴다")
    void zeroCapacityRejected() {
        assertTrue(rejects(withTypes(type("GENERAL", 1.0, 0, 0.8, 1)), "capacity"));
    }

    @Test
    @DisplayName("음수 임계는 거부한다 — 모든 배출이 민원이 된다")
    void negativeThresholdRejected() {
        assertTrue(rejects(withTypes(type("GENERAL", 1.0, 30, -1, 1)), "threshold"));
    }

    @Test
    @DisplayName("비율이 0~1 밖이면 거부한다 — 음수는 그 유형을 조용히 없앤다")
    void fractionOutOfRangeRejected() {
        assertTrue(rejects(withTypes(type("GENERAL", -0.2, 30, 0.8, 1)), "fraction"));
        assertTrue(rejects(withTypes(type("GENERAL", 1.5, 30, 0.8, 1)), "fraction"));
    }

    @Test
    @DisplayName("비율 합이 1이 아니면 거부한다 — 실제보다 많거나 적은 폐기물이 생성된다")
    void fractionSumMustBeOne() {
        assertTrue(rejects(withTypes(type("A", 0.5, 30, 0.8, 1), type("B", 0.4, 30, 0.8, 1)), "fraction"));
        assertTrue(rejects(withTypes(type("A", 0.7, 30, 0.8, 1), type("B", 0.4, 30, 0.8, 1)), "fraction"));
    }

    @Test
    @DisplayName("key가 비었거나 중복되면 거부한다 — 결과 식별이 불가능해진다")
    void keyMustBePresentAndUnique() {
        assertTrue(rejects(withTypes(type("", 1.0, 30, 0.8, 1)), "key"));
        assertTrue(rejects(withTypes(type("A", 0.5, 30, 0.8, 1), type("A", 0.5, 30, 0.8, 1)), "key"));
    }

    /** setIntervalDays가 이미 1로 보정하므로 setter 경로로는 0이 들어올 수 없다.
     *  검증은 그 보정을 우회하는 경로를 위한 방어로 남겨 두고, 여기서는 보정 자체를 고정한다. */
    @Test
    @DisplayName("수거 주기는 setter가 1 미만을 1로 보정한다")
    void intervalDaysIsClampedBySetter() {
        WasteType w = type("GENERAL", 1.0, 30, 0.8, 0);
        assertEquals(1, w.getIntervalDays());
    }

    // ── W-02: 복수·주말 수거 시각 ────────────────────────────────────────

    @Test
    @DisplayName("복수 수거 시각도 0~1439 범위를 지켜야 한다")
    void multipleCollectionTimesRangeChecked() {
        SimulationConfig neg = base();
        neg.setCollectionTimesMinutes(List.of(-30, 600));
        assertTrue(rejects(neg, "collectionTimesMinutes"));

        SimulationConfig over = base();
        over.setCollectionTimesMinutes(List.of(600, 1500));
        assertTrue(rejects(over, "collectionTimesMinutes"));

        SimulationConfig ok = base();
        ok.setCollectionTimesMinutes(List.of(540, 1080));
        assertTrue(validator.validate(ok).ready());
    }

    @Test
    @DisplayName("같은 수거 시각이 중복되면 거부한다")
    void duplicateCollectionTimesRejected() {
        SimulationConfig c = base();
        c.setCollectionTimesMinutes(List.of(540, 540));
        assertTrue(rejects(c, "collectionTimesMinutes"));
    }

    @Test
    @DisplayName("주말 수거 시각도 같은 범위를 지켜야 한다")
    void weekendCollectionTimeRangeChecked() {
        SimulationConfig neg = base();
        neg.setWeekendCollectionTimeMinutes(-1);
        assertTrue(rejects(neg, "weekendCollectionTimeMinutes"));

        SimulationConfig over = base();
        over.setWeekendCollectionTimeMinutes(1440);
        assertTrue(rejects(over, "weekendCollectionTimeMinutes"));

        SimulationConfig ok = base();
        ok.setWeekendCollectionTimeMinutes(600);
        assertTrue(validator.validate(ok).ready());
    }

    // ── W-05: 건물 수 상한 ──────────────────────────────────────────────

    @Test
    @DisplayName("건물 27개 이상은 거부한다 — 노드 ID가 Node_[ 처럼 깨진다")
    void buildingCountCappedAtAlphabet() {
        SimulationConfig over = base();
        over.setNumBuildings(27);
        assertTrue(rejects(over, "numBuildings"));

        SimulationConfig ok = base();
        ok.setNumBuildings(26);
        assertTrue(validator.validate(ok).ready());
    }
}
