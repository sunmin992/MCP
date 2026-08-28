package com.wastesim.controller;

import com.wastesim.model.ScenarioPreset;
import com.wastesim.model.SimulationConfig;
import com.wastesim.service.ScenarioService;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ValidationError;
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

    /** 한 번의 스윕에서 허용할 최대 후보 수 — 후보마다 다중 시드를 실행한다. */
    static final int MAX_SWEEP_POINTS = 96;

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
        int start = sweepMinute(b, "start", "06:00");
        int end   = sweepMinute(b, "end", "18:00");
        int step  = intVal(b, "stepMinutes", 60);
        validateSweep(start, end, step);
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
        // 검증 게이트가 보게 하려면 base에 실어야 한다. 예전에는 이 값을 base에 넣지 않고
        // 시나리오 안에서 복사본에만 주입해서, validateMonthlyFactor가 항상 null을 보고
        // 즉시 return했다 — 길이 12 강제·유한 양수 검사가 이 경로에서만 통째로 우회됐다.
        // 그 결과 5개짜리 배열이 monthlyWasteFactor[month % length]로 조용히 순환 적용돼
        // 1·6·11월이 같은 값이 되고도 아무 경고가 없었다(D-26 조용한 보정 금지 위반).
        base.setMonthlyWasteFactor(monthlyFactor);
        return ApiError.respond(tool.runScenarioCustom(base, () -> scenario.monthlyWaste(base, monthlyFactor)));
    }

    /** 12. 차종 × 방문 순서 탐색: 민원이 가장 적은 조합 */
    @PostMapping("/truck-route")
    public ResponseEntity<?> truckRoute(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        SimulationConfig base = baseConfig(b, 10);
        applyPreset(base, b);
        // 이동시간을 명시하면 그대로 쓴다 — 지정하지 않으면 시나리오가 기본값을 채우고
        // 무엇을 가정했는지 결과에 밝힌다(조용히 바꾸지 않는다).
        if (b.get("routeTravelMinutes") instanceof Number n) base.setRouteTravelMinutes(n.intValue());
        List<List<String>> routes = routeSequences(b);
        List<String> truckTypes = stringList(b, "truckTypes");
        return ApiError.respond(tool.runScenarioCustom(base,
                () -> scenario.truckRouteSearch(base, routes, truckTypes)));
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────────

    /** {@code routeSequences: [["Node_A","Node_B"], ...]} — 형식이 아니면 null(자동 생성). */
    private static List<List<String>> routeSequences(Map<String, Object> b) {
        if (!(b.get("routeSequences") instanceof List<?> outer) || outer.isEmpty()) return null;
        List<List<String>> out = new ArrayList<>();
        for (Object o : outer) {
            if (o instanceof List<?> inner && !inner.isEmpty()) {
                List<String> seq = new ArrayList<>(inner.size());
                for (Object node : inner) seq.add(String.valueOf(node));
                out.add(seq);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> stringList(Map<String, Object> b, String k) {
        if (!(b.get(k) instanceof List<?> list) || list.isEmpty()) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) out.add(String.valueOf(o));
        return out;
    }


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

    private static int sweepMinute(Map<String, Object> b, String key, String defaultValue) {
        String value = str(b, key, defaultValue);
        try {
            return SimulationConfig.hhmmToMinutes(value);
        } catch (RuntimeException e) {
            throw new ScenarioArgException(new ValidationError(ErrorCode.INVALID_ARGUMENTS, key,
                    key + "는 HH:MM 형식이어야 합니다. 받은 값: " + value));
        }
    }

    private static void validateSweep(int start, int end, int step) {
        if (start < 0 || start > 1439 || end < 0 || end > 1439) {
            throw new ScenarioArgException(new ValidationError(ErrorCode.OUT_OF_RANGE, "start/end",
                    "수거시각 스윕 범위는 00:00~23:59여야 합니다."));
        }
        if (start > end) {
            throw new ScenarioArgException(new ValidationError(ErrorCode.INVALID_ARGUMENTS, "start/end",
                    "스윕 시작 시각은 종료 시각보다 늦을 수 없습니다."));
        }
        if (step <= 0) {
            throw new ScenarioArgException(new ValidationError(ErrorCode.OUT_OF_RANGE, "stepMinutes",
                    "stepMinutes는 1분 이상이어야 합니다. 받은 값: " + step));
        }
        int points = ((end - start) / step) + 1;
        if (points > MAX_SWEEP_POINTS) {
            throw new ScenarioArgException(new ValidationError(ErrorCode.OUT_OF_RANGE, "stepMinutes",
                    "스윕 후보는 " + MAX_SWEEP_POINTS + "개 이하여야 합니다. 현재 후보 수: " + points
                    + " — stepMinutes를 늘려 주세요."));
        }
    }

    private static int intVal(Map<String, Object> b, String k, int def) {
        Object v = b.get(k);
        if (v == null) return def;
        if (!(v instanceof Byte || v instanceof Short || v instanceof Integer || v instanceof Long)) {
            throw invalidScalar(k, "정수", v);
        }
        long value = ((Number) v).longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ScenarioArgException(new ValidationError(ErrorCode.OUT_OF_RANGE, k,
                    k + "가 정수 범위를 벗어났습니다. 받은 값: " + v));
        }
        return (int) value;
    }

    private static double dblVal(Map<String, Object> b, String k, double def) {
        Object v = b.get(k);
        if (v == null) return def;
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) {
            throw invalidScalar(k, "유한한 숫자", v);
        }
        return n.doubleValue();
    }

    private static ScenarioArgException invalidScalar(String field, String expected, Object value) {
        return new ScenarioArgException(new ValidationError(ErrorCode.INVALID_ARGUMENTS, field,
                field + "은(는) " + expected + "여야 합니다. 받은 값: " + value));
    }

    /**
     * 축 배열 인자를 읽는다. 값이 없거나 빈 배열이면 null(=시나리오 기본 축).
     *
     * <p>원소가 숫자가 아니면 <b>구조화된 400</b>으로 떨어뜨린다. 예전에는
     * {@code ((Number) list.get(i))}가 그대로 {@code ClassCastException}을 던졌고, 이 파싱이
     * {@code runScenarioCustom}(검증 게이트) <b>바깥</b>에서 일어나므로 ApiError를 거치지 못해
     * 500이 나갔다 — 사용자 입력 오류인데 서버 장애처럼 보였다. "잘못된 입력은 실행 전에
     * 모두 거부한다"는 fail-closed 원칙은 축 배열에도 똑같이 적용돼야 한다.
     */
    private static double[] doubleArr(Map<String, Object> b, String k) {
        Object v = b.get(k);
        if (v == null) return null;
        if (!(v instanceof List<?> list)) {
            throw new ScenarioArgException(new ValidationError(ErrorCode.INVALID_ARGUMENTS, k,
                    k + "은(는) 숫자 배열이어야 합니다. 받은 형식: " + v.getClass().getSimpleName()));
        }
        if (list.isEmpty()) return null;
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object e = list.get(i);
            if (!(e instanceof Number n) || !Double.isFinite(n.doubleValue())) {
                throw new ScenarioArgException(new ValidationError(ErrorCode.INVALID_ARGUMENTS, k,
                        k + "의 " + (i + 1) + "번째 원소가 유한한 숫자가 아닙니다. 받은 값: " + e));
            }
            arr[i] = n.doubleValue();
        }
        return arr;
    }

    /** 축 배열 파싱 실패 — {@link #badScenarioArg}가 400 ApiError로 바꾼다. */
    static class ScenarioArgException extends RuntimeException {
        private final transient ValidationError error;
        ScenarioArgException(ValidationError error) {
            super(error.message());
            this.error = error;
        }
        ValidationError error() { return error; }
    }

    /**
     * 축 배열 파싱 오류를 검증 실패와 <b>같은 형태</b>로 내보낸다 — 사용자 입장에서
     * "capacities에 문자열을 넣은 것"과 "capacity가 음수인 것"은 같은 종류의 실수이므로
     * 응답 모양이 달라질 이유가 없다.
     */
    @ExceptionHandler(ScenarioArgException.class)
    public ResponseEntity<?> badScenarioArg(ScenarioArgException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION", "설정 검증 실패", List.of(e.error())));
    }
}
