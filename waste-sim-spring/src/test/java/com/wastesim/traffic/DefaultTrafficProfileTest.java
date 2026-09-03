package com.wastesim.traffic;

import com.wastesim.model.SimulationConfig;
import com.wastesim.model.TrafficProfile;
import com.wastesim.service.TrafficDataService;
import com.wastesim.tool.SimulationConfigValidator;
import com.wastesim.tool.ValidationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 기본 교통 프로파일이 <b>이름으로</b> 정해지는지 고정한다.
 *
 * <p>예전에는 {@code profiles.keySet().findFirst()}로 골랐다. 그 맵은
 * {@code ConcurrentHashMap}이라 순회 순서가 등록 순서도 사전 순서도 아닌 해시 순서이고,
 * 프로파일이 하나였을 때는 답이 하나뿐이라 아무 문제가 없었다. 2026-09-02에 통행량 기반
 * 구 프로파일이 비교용으로 함께 등록되면서, 기본값이 어느 쪽인지가 <b>코드로 결정되지
 * 않는 상태</b>가 됐다.
 *
 * <p>두 프로파일은 서로 다른 답을 낸다 — 구 프로파일의 피크는 13시, 실측은 18시이고 그
 * 차이 때문에 "가장 나쁜 수거 시각"이 뒤집힌다. 순회 순서가 실험 결론을 바꿀 수 있었다는
 * 뜻이라, 여기서 이름을 고정한다.
 */
class DefaultTrafficProfileTest {

    private final TrafficDataService traffic = new TrafficDataService();

    @Test
    @DisplayName("기본 프로파일은 TMAP 실측판이고, 프로파일이 여러 개여도 흔들리지 않는다")
    void defaultProfileIsTheMeasuredOne() {
        assertEquals("jangryang-weekday", TrafficDataService.DEFAULT_PROFILE_ID);
        assertEquals(TrafficDataService.DEFAULT_PROFILE_ID, traffic.defaultProfileId());
        assertTrue(TrafficDataService.seedIds().contains(TrafficDataService.DEFAULT_PROFILE_ID),
                "기동 시 읽지 않는 프로파일을 기본값으로 선언하면 기본값이 영영 null이다");

        // 등록된 프로파일이 둘 이상인 상태에서 반복 조회해도 같은 답이어야 한다 —
        // 해시 순서에 기대던 예전 구현은 이 성질이 없었다.
        assertTrue(TrafficDataService.seedIds().size() > 1, "비교용 프로파일이 함께 등록돼 있어야 이 검증이 의미가 있다");
        for (int i = 0; i < 50; i++) {
            assertEquals("jangryang-weekday", traffic.defaultProfileId());
        }
    }

    @Test
    @DisplayName("실측판과 구판은 실제로 다른 값이다 — 어느 쪽이 기본이냐가 결과를 바꾼다")
    void theTwoProfilesDisagreeSoTheChoiceMatters() {
        TrafficProfile measured = traffic.find("jangryang-weekday");
        TrafficProfile volume = traffic.find("jangryang-volume-weekday");
        assertNotNull(measured);
        assertNotNull(volume, "비교용 구 프로파일을 지우면 이 결함의 재발을 볼 수 없다");
        assertNotEquals(measured.getId(), volume.getId());

        // 피크 시각이 다르다는 것이 이 결함의 무게다. 두 프로파일에서 가장 혼잡한 시각을
        // 뽑아 서로 다름을 확인한다.
        assertNotEquals(peakHourOf(measured), peakHourOf(volume),
                "두 프로파일의 피크 시각이 같아졌다면 이 테스트의 전제가 바뀐 것이다");
    }

    @Test
    @DisplayName("기본 프로파일이 없으면 아무거나 대신 주지 않고 null — 그 null이 실행을 막는다")
    void missingDefaultFailsClosedInsteadOfSubstituting() {
        // 선언한 기본값을 못 읽은 환경에서 이 메서드는 null을 준다(남은 프로파일 중
        // 아무거나 대신 주지 않는다). 그 null이 실행으로 흘러가지 않는지가 관건이다 —
        // 교통을 켠 채 프로파일이 없는 설정이 통과하면 "교통이 반영되지 않은 결과"를
        // 반영된 결과로 읽게 된다.
        SimulationConfig cfg = new SimulationConfig();
        cfg.setTrafficEnabled(true);
        cfg.setTrafficProfileId(null);
        ValidationResult vr = new SimulationConfigValidator(traffic).validate(cfg);
        assertFalse(vr.ready());
        assertTrue(vr.errors().stream().anyMatch(e -> "trafficProfileId".equals(e.field())),
                () -> "교통 프로파일 누락을 막아야 한다: " + vr.errors());
    }

    /** 프로파일에서 혼잡 가중치 합이 가장 큰 시각(0~23) — 노드별 값이 있으면 전부 더한다. */
    private static int peakHourOf(TrafficProfile p) {
        java.util.Collection<String> nodes = p.getNodeHourlyWeight() == null
                ? java.util.List.of("Node_A") : p.getNodeHourlyWeight().keySet();
        int peak = 0;
        double best = Double.NEGATIVE_INFINITY;
        for (int hour = 0; hour < 24; hour++) {
            double sum = 0;
            for (String node : nodes) {
                sum += p.weightAt(hour * 60, node);
            }
            if (sum > best) {
                best = sum;
                peak = hour;
            }
        }
        return peak;
    }
}
