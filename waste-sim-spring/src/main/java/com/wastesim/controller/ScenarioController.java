package com.wastesim.controller;

import com.wastesim.model.ScenarioPreset;
import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.ScenarioService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 시나리오 실험 API. 모든 엔드포인트는 공통 base 설정(days/seeds/...)을 받아
 * 해당 축만 sweep/그리드로 변화시킨다. 미지정 인자는 합리적 기본값 사용.
 */
@RestController
@RequestMapping("/api/scenario")
public class ScenarioController {

    private final ScenarioService scenario;

    public ScenarioController(ScenarioService scenario) {
        this.scenario = scenario;
    }

    /** 사용 가능한 거주민 구성 프리셋 목록 (UI 표시용) */
    @GetMapping("/presets")
    public ResponseEntity<List<Map<String, Object>>> presets() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ScenarioPreset p : ScenarioPreset.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", p.name());
            m.put("label", p.labelKo);
            m.put("desc", p.desc);
            m.put("ratio", p.ratioPercent());
            m.put("mix", p.mix);
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** 1. 거주민 구성별 × 수거시각 비교 */
    @PostMapping("/occupation-mix")
    public ResponseEntity<ScenarioResponse> occupationMix(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);  // 시나리오 기본 시드 10
        @SuppressWarnings("unchecked")
        List<String> times = (List<String>) b.get("times");
        return ResponseEntity.ok(scenario.occupationMixComparison(base, times));
    }

    /** 2. 수거시각 sweep */
    @PostMapping("/collection-sweep")
    public ResponseEntity<ScenarioResponse> collectionSweep(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        int start = toMinutes(str(b, "start", "06:00"));
        int end   = toMinutes(str(b, "end", "18:00"));
        int step  = intVal(b, "stepMinutes", 60);
        // 구성 프리셋을 적용하고 싶으면 mixPreset 지정
        applyPreset(base, b);
        return ResponseEntity.ok(scenario.collectionSweep(base, start, end, step));
    }

    /** 3. 행동 변동: α × β */
    @PostMapping("/behavior-grid")
    public ResponseEntity<ScenarioResponse> behaviorGrid(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        return ResponseEntity.ok(scenario.behaviorGrid(base, doubleArr(b, "alphas"), doubleArr(b, "betas")));
    }

    /** 4. 인프라: 용량 C × 임계 θ */
    @PostMapping("/infra-grid")
    public ResponseEntity<ScenarioResponse> infraGrid(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        return ResponseEntity.ok(scenario.infraGrid(base, doubleArr(b, "capacities"), doubleArr(b, "thresholds")));
    }

    /** 5. 밀도: 저밀도 빌라촌 vs 고밀도 원룸촌 */
    @PostMapping("/density")
    public ResponseEntity<ScenarioResponse> density(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        List<int[]> densities = null;
        Object raw = b.get("densities");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            densities = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof List<?> pair && pair.size() >= 2) {
                    densities.add(new int[]{
                            ((Number) pair.get(0)).intValue(),
                            ((Number) pair.get(1)).intValue()});
                }
            }
        }
        return ResponseEntity.ok(scenario.densityComparison(base, densities));
    }

    /** 6. 수거 스케줄: 다회/격일/주말/공휴일 */
    @PostMapping("/collection-schedule")
    public ResponseEntity<ScenarioResponse> collectionSchedule(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        return ResponseEntity.ok(scenario.collectionSchedule(base));
    }

    /** 7. 다중 트럭 · 구역 분할 */
    @PostMapping("/multi-truck")
    public ResponseEntity<ScenarioResponse> multiTruck(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        double[] tc = doubleArr(b, "truckCounts");
        int[] counts = null;
        if (tc != null) { counts = new int[tc.length]; for (int i = 0; i < tc.length; i++) counts[i] = (int) tc[i]; }
        return ResponseEntity.ok(scenario.multiTruck(base, counts));
    }

    /** 8. 분리배출: 통합 vs 종류별 */
    @PostMapping("/waste-separation")
    public ResponseEntity<ScenarioResponse> wasteSeparation(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        return ResponseEntity.ok(scenario.wasteSeparation(base));
    }

    /** 9. 새 거주민 유형: 야간근무·1인직장인 */
    @PostMapping("/new-occupations")
    public ResponseEntity<ScenarioResponse> newOccupations(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        @SuppressWarnings("unchecked")
        List<String> times = (List<String>) b.get("times");
        return ResponseEntity.ok(scenario.newOccupations(base, times));
    }

    /** 10. 결합모델 변형: 외출/귀가 2회 · 임대인 */
    @PostMapping("/coupling-variants")
    public ResponseEntity<ScenarioResponse> couplingVariants(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        return ResponseEntity.ok(scenario.couplingVariants(base));
    }

    /** 11. 월별(계절) 배출량: 1년 중 배출 최다 달 */
    @PostMapping("/monthly-waste")
    public ResponseEntity<ScenarioResponse> monthlyWaste(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 8);
        applyPreset(base, b);
        return ResponseEntity.ok(scenario.monthlyWaste(base, doubleArr(b, "monthlyFactor")));
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────────

    private SimulationConfig baseConfig(Map<String, Object> b, int defaultSeeds) {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(intVal(b, "days", 30));
        cfg.setSeeds(intVal(b, "seeds", defaultSeeds));
        cfg.setLeaveSigma(dblVal(b, "leaveSigma", 30.0));
        cfg.setWasteSigma(dblVal(b, "wasteSigma", 0.3));
        cfg.setCapacity(dblVal(b, "capacity", 30.0));
        cfg.setThreshold(dblVal(b, "threshold", 0.8));
        cfg.setNumBuildings(intVal(b, "numBuildings", 4));
        cfg.setResidentsPerBuilding(intVal(b, "residentsPerBuilding", 25));
        if (b.get("collectionTime") instanceof String t) cfg.setCollectionTimeLabel(t);
        return cfg;
    }

    /** mixPreset(예: "UNIVERSITY") 또는 occupationMix(리스트)를 base에 적용 */
    @SuppressWarnings("unchecked")
    private void applyPreset(SimulationConfig base, Map<String, Object> b) {
        if (b.get("occupationMix") instanceof List<?> mix && !mix.isEmpty()) {
            base.setOccupationMix((List<String>) mix);
        } else if (b.get("mixPreset") instanceof String key) {
            base.setOccupationMix(ScenarioPreset.fromKey(key).mix);
        }
    }

    private static String str(Map<String, Object> b, String k, String def) {
        Object v = b.get(k);
        return v == null ? def : v.toString();
    }

    private static int intVal(Map<String, Object> b, String k, int def) {
        Object v = b.get(k);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static double dblVal(Map<String, Object> b, String k, double def) {
        Object v = b.get(k);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static double[] doubleArr(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (v instanceof List<?> list && !list.isEmpty()) {
            double[] arr = new double[list.size()];
            for (int i = 0; i < list.size(); i++) arr[i] = ((Number) list.get(i)).doubleValue();
            return arr;
        }
        return null;
    }

    private static int toMinutes(String hhmm) {
        String[] p = hhmm.split(":");
        return Integer.parseInt(p[0].trim()) * 60 + (p.length > 1 ? Integer.parseInt(p[1].trim()) : 0);
    }
}
