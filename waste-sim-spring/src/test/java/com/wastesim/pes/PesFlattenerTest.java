package com.wastesim.pes;

import com.wastesim.model.SimulationConfig;
import com.wastesim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PES 의 값이 실행 설정에 그대로 실리는가, 그리고 <b>되읽어 같은지 확인하는가</b>.
 *
 * <p>역검증이 없으면 "사용자가 준 값으로 돌렸다"를 증명할 방법이 없다.
 */
class PesFlattenerTest {

    private final TemplateCatalog catalog = new TemplateCatalog();
    private final PesFlattener flattener = new PesFlattener(catalog);
    private final PesBackVerifier verifier = new PesBackVerifier(catalog);

    private Pes pes(Map<String, Object> values) {
        Map<String, String> origins = new LinkedHashMap<>();
        values.keySet().forEach(k -> origins.put(k, "USER"));
        return new Pes("jangnyang-ses", "1.0.0", values, origins);
    }

    @Test
    void 차종과_대수가_설정에_실린다() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("truckType", "SMALL_1TON");
        v.put("truckCount", 3);
        SimulationConfig cfg = flattener.flatten(pes(v));
        assertEquals("SMALL_1TON", cfg.getTruckType());
        assertEquals(3, cfg.getNumTrucks());
    }

    @Test
    void 교통반영이_불리언으로_바뀐다() {
        SimulationConfig on = flattener.flatten(pes(Map.of("trafficMode", "APPLY")));
        assertTrue(on.isTrafficEnabled());
        SimulationConfig off = flattener.flatten(pes(Map.of("trafficMode", "IGNORE")));
        assertFalse(off.isTrafficEnabled());
    }

    @Test
    void 수거시각과_기간이_실린다() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("collectionTimeMinutes", 900);
        v.put("days", 30);
        v.put("seeds", 30);
        SimulationConfig cfg = flattener.flatten(pes(v));
        assertEquals(900, cfg.getCollectionTimeMinutes());
        assertEquals(30, cfg.getDays());
        assertEquals(30, cfg.getSeeds());
    }

    @Test
    void 역검증이_일치를_확인한다() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("truckType", "SMALL_1TON");
        v.put("truckCount", 3);
        v.put("collectionTimeMinutes", 900);
        v.put("trafficMode", "APPLY");
        Pes p = pes(v);
        SimulationConfig cfg = flattener.flatten(p);
        assertEquals(List.of(), verifier.verify(p, cfg));
    }

    @Test
    void 설정이_바뀌면_역검증이_잡는다() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("truckCount", 3);
        Pes p = pes(v);
        SimulationConfig cfg = flattener.flatten(p);
        cfg.setNumTrucks(5);   // 평탄화 이후 누가 값을 바꾼 상황
        List<String> blocks = verifier.verify(p, cfg);
        assertEquals(1, blocks.size());
        assertTrue(blocks.get(0).contains("numTrucks"), "어느 필드가 어긋났는지 말해야 한다");
    }

    @Test
    void 템플릿에_없는_키는_평탄화가_거절한다() {
        Pes p = pes(Map.of("없는키", 1));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> flattener.flatten(p));
        assertTrue(e.getMessage().contains("없는키"),
                "조용히 무시하면 사용자 값이 실행에 반영되지 않은 채로 넘어간다");
    }

    // ── 인계 문서 §4(b) — 빈 식별자가 여기까지 흘러오지 못하게 한다 ─────────────
    //
    // TemplateCatalog 는 JSON 에 sesId/sesVersion 이 없으면 빈 문자열을 돌려줬다.
    // Pes 가 그 값을 식별자로 싣는 첫 자리이므로, 흘러들어온 빈 식별자를 여기서 막는다.
    // 막지 않으면 "어느 SES 로 만든 설정인가"를 되물을 수 없는 PES 가 실행까지 간다.

    @Test
    void 빈_sesId_로는_PES_를_만들_수_없다() {
        Map<String, String> origins = Map.of("days", "USER");
        Map<String, Object> values = Map.of("days", 30);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Pes("", "1.0.0", values, origins));
        assertTrue(e.getMessage().contains("sesId"), "어느 식별자가 비었는지 말해야 한다: " + e.getMessage());
    }

    @Test
    void 빈_sesVersion_으로는_PES_를_만들_수_없다() {
        Map<String, String> origins = Map.of("days", "USER");
        Map<String, Object> values = Map.of("days", 30);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Pes("jangnyang-ses", "  ", values, origins));
        assertTrue(e.getMessage().contains("sesVersion"), "어느 식별자가 비었는지 말해야 한다: " + e.getMessage());
    }

    @Test
    void 출처가_없는_값은_PES_가_거절한다() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new Pes("jangnyang-ses", "1.0.0", Map.of("days", 30), Map.of()));
        assertTrue(e.getMessage().contains("days"),
                "출처 없는 값은 사용자 승인 여부를 판정할 수 없다: " + e.getMessage());
    }

    @Test
    void 승인하지_않은_기본값만_따로_센다() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("days", 30);
        values.put("seeds", 30);
        Map<String, String> origins = new LinkedHashMap<>();
        origins.put("days", "USER");
        origins.put("seeds", "MODEL_DEFAULT");
        Pes p = new Pes("jangnyang-ses", "1.0.0", values, origins);
        assertEquals(Map.of("seeds", 30), p.unapprovedDefaults());
    }
}
