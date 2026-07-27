package com.wastesim.controller;

import com.wastesim.model.ScenarioPreset;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.ScenarioService;
import com.wastesim.tool.SimulationTool;
import com.wastesim.web.ApiError;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 시나리오 실험 API. 검증·실행은 SimulationTool 파사드(runScenarioCustom)로
 * 위임해 MCP·채팅과 동일한 검증 게이트를 통과한다(검증 실패 시 400 + ApiError).
 * 모든 엔드포인트는 공통 base 설정(days/seeds/...)을 받아 해당 축만
 * sweep/그리드로 변화시킨다. 미지정 인자는 합리적 기본값 사용.
 */
@RestController
@RequestMapping("/api/scenario")
public class ScenarioController {

    private final ScenarioService scenario;
    private final SimulationTool tool;

    public ScenarioController(ScenarioService scenario, SimulationTool tool) {
        this.scenario = scenario;
        this.tool = tool;
    }

    /** 사용 가능한 거주민 구성 프리셋 목록 (UI 표시용, 검증 대상 아님) */
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
    public ResponseEntity<?> occupationMix(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);  // 시나리오 기본 시드 10
        @SuppressWarnings("unchecked")
        List<String> times = (List<String>) b.get("times");
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.occupationMixComparison(base, times)));
    }

    /** 2. 수거시각 sweep */
    @PostMapping("/collection-sweep")
    public ResponseEntity<?> collectionSweep(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        int start = SimulationConfig.hhmmToMinutes(str(b, "start", "06:00"));
        int end   = SimulationConfig.hhmmToMinutes(str(b, "end", "18:00"));
        int step  = intVal(b, "stepMinutes", 60);
        // 구성 프리셋을 적용하고 싶으면 mixPreset 지정
        applyPreset(base, b);
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.collectionSweep(base, start, end, step)));
    }

    /** 3. 행동 변동: α × β */
    @PostMapping("/behavior-grid")
    public ResponseEntity<?> behaviorGrid(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        double[] alphas = doubleArr(b, "alphas");
        double[] betas = doubleArr(b, "betas");
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.behaviorGrid(base, alphas, betas)));
    }

    /** 4. 인프라: 용량 C × 임계 θ */
    @PostMapping("/infra-grid")
    public ResponseEntity<?> infraGrid(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        double[] capacities = doubleArr(b, "capacities");
        double[] thresholds = doubleArr(b, "thresholds");
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.infraGrid(base, capacities, thresholds)));
    }

    /** 5. 밀도: 저밀도 빌라촌 vs 고밀도 원룸촌 */
    @PostMapping("/density")
    public ResponseEntity<?> density(@RequestBody(required = false) Map<String, Object> body) {
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
        List<int[]> finalDensities = densities;
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.densityComparison(base, finalDensities)));
    }

    /** 6. 수거 스케줄: 다회/격일/주말/공휴일 */
    @PostMapping("/collection-schedule")
    public ResponseEntity<?> collectionSchedule(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.collectionSchedule(base)));
    }

    /** 7. 다중 트럭 · 구역 분할 */
    @PostMapping("/multi-truck")
    public ResponseEntity<?> multiTruck(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        double[] tc = doubleArr(b, "truckCounts");
        int[] counts = null;
        if (tc != null) { counts = new int[tc.length]; for (int i = 0; i < tc.length; i++) counts[i] = (int) tc[i]; }
        int[] finalCounts = counts;
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.multiTruck(base, finalCounts)));
    }

    /** 8. 분리배출: 통합 vs 종류별 */
    @PostMapping("/waste-separation")
    public ResponseEntity<?> wasteSeparation(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.wasteSeparation(base)));
    }

    /** 9. 새 거주민 유형: 야간근무·1인직장인 */
    @PostMapping("/new-occupations")
    public ResponseEntity<?> newOccupations(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        @SuppressWarnings("unchecked")
        List<String> times = (List<String>) b.get("times");
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.newOccupations(base, times)));
    }

    /** 10. 결합모델 변형: 외출/귀가 2회 · 임대인 */
    @PostMapping("/coupling-variants")
    public ResponseEntity<?> couplingVariants(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.couplingVariants(base)));
    }

    /** 11. 월별(계절) 배출량: 1년 중 배출 최다 달 */
    @PostMapping("/monthly-waste")
    public ResponseEntity<?> monthlyWaste(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 8);
        applyPreset(base, b);
        double[] monthlyFactor = doubleArr(b, "monthlyFactor");
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.monthlyWaste(base, monthlyFactor)));
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────────

    private SimulationConfig baseConfig(Map<String, Object> b, int defaultSeeds) {
        SimulationConfig cfg = new SimulationConfig();
        cfg.setDays(intVal(b, "days", 30));
        cfg.setSeeds(intVal(b, "seeds", defaultSeeds));
        cfg.setLeaveSigma(dblVal(b, "leaveSigma", 30.0));
        cfg.setWasteSigma(dblVal(b, "wasteSigma", 0.3));
        cfg.setWasteMeanKg(dblVal(b, "wasteMeanKg", 0.9));
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
}
