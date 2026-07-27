package com.wastesim.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 자연어에서 <b>실험 조건을 결정하는 값</b>만 정규식으로 직접 뽑아내는 결정론적 안전망.
 *
 * <p>이 프로젝트의 채팅 파이프라인은 "판단은 서버가, 값 추출은 GPT가" 구조다. 다만
 * {@code ChatController}가 이미 겪은 실패(로컬 모델이 대화 이력에 낚여 이전 턴의
 * {@code trafficEnabled}·{@code truckType}을 그대로 이어받던 문제) 때문에, <b>어떤 실험을
 * 돌렸는지가 바뀌는 필드</b>는 LLM 출력을 신뢰하지 않고 이번 메시지에서 다시 판정한다.
 * 여기서도 같은 규칙을 적용한다 — 보드·냉각조건·운용모드·회복정책이 그렇다.
 * "Pi5 무냉각"이라고 했는데 Pi4 방열판 결과가 나오면 그건 틀린 답이 아니라 <b>다른 실험</b>이고,
 * R&E에서는 그게 가장 치명적인 오류다.
 *
 * <p>부수 효과가 하나 더 있다. 이 값들이 LLM 없이 정해지므로, <b>LLM 백엔드가 죽어 있어도
 * 엣지 요청은 그대로 동작한다</b>(추출 실패 시 나머지는 도구 기본값). 학생이 실험 중에
 * API 키 문제로 막히지 않는다.
 *
 * <p>숫자 중 온도·FPS는 단위가 붙어 있어 오해의 여지가 없을 때만 가져온다. 지속시간
 * ("10분")은 부하 시간인지 회복 시간인지 문장마다 달라 정규식으로 확정할 수 없으므로
 * LLM에 맡기고, 범위 검증은 {@link EdgeArgs}가 한다.
 */
public final class EdgeParamGuard {

    private EdgeParamGuard() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern PI4 = Pattern.compile("(pi\\s*4|파이\\s*4|4\\s*b\\b|라즈베리\\s*파이\\s*4)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PI5 = Pattern.compile("(pi\\s*5|파이\\s*5|라즈베리\\s*파이\\s*5)", Pattern.CASE_INSENSITIVE);

    private static final Pattern BARE = Pattern.compile("(무냉각|냉각\\s*없|방열판\\s*없|맨\\s*보드|기본\\s*상태|아무것도\\s*안|bare)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVE = Pattern.compile("(팬|쿨러|능동\\s*냉각|액티브|active\\s*cool)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSIVE = Pattern.compile("(방열판|히트\\s*싱크|heatsink|수동\\s*냉각|패시브|passive)", Pattern.CASE_INSENSITIVE);

    private static final Pattern MAX_MODE = Pattern.compile("(최대\\s*(처리량|부하|성능)|풀\\s*로드|full\\s*load|max\\s*throughput|한계까지)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_MODE = Pattern.compile("(목표\\s*fps|동일\\s*fps|고정\\s*fps|target\\s*fps)", Pattern.CASE_INSENSITIVE);

    private static final Pattern R1 = Pattern.compile("(\\br1\\b|완전\\s*중지|추론.{0,6}(중지|정지|멈)|부하.{0,4}(중지|정지|멈))", Pattern.CASE_INSENSITIVE);
    private static final Pattern R2 = Pattern.compile("(\\br2\\b|저부하|부하.{0,4}(낮|줄)|25\\s*%)", Pattern.CASE_INSENSITIVE);
    private static final Pattern R3 = Pattern.compile("(\\br3\\b|능동\\s*냉각|팬.{0,6}(100|최대|full|켜)|강제\\s*냉각)", Pattern.CASE_INSENSITIVE);

    private static final Pattern AMBIENT = Pattern.compile(
            "(?:실내|주변|상온|기온|외부|환경|ambient)\\s*(?:온도\\s*)?(?:가|는|이)?\\s*(\\d{1,2}(?:\\.\\d)?)\\s*(?:도|℃|°c|c\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FPS = Pattern.compile("(\\d{1,3}(?:\\.\\d)?)\\s*(?:fps|프레임)", Pattern.CASE_INSENSITIVE);

    /**
     * 이번 메시지에서 확정할 수 있는 값만 담은 노드를 만든다. LLM 추출 결과와 합칠 때
     * <b>이 값이 이긴다</b>({@link #merge}).
     */
    public static ObjectNode fromText(String text) {
        ObjectNode n = MAPPER.createObjectNode();
        if (text == null || text.isBlank()) return n;

        // 보드 — 둘 다 언급되면(비교 요청) 판정하지 않고 LLM/기본값에 맡긴다
        boolean p4 = PI4.matcher(text).find(), p5 = PI5.matcher(text).find();
        if (p4 ^ p5) n.put("board", p4 ? "pi4" : "pi5");

        // 회복 정책 — 구체적인 조치일수록 우선
        boolean r3 = R3.matcher(text).find();
        if (r3) n.put("recoveryPolicy", "r3_active_cooling");
        else if (R2.matcher(text).find()) n.put("recoveryPolicy", "r2_low_load");
        else if (R1.matcher(text).find()) n.put("recoveryPolicy", "r1_stop");

        // 냉각 — 구체적인 쪽(무냉각 > 팬 > 방열판) 우선. "방열판에 팬까지"는 active가 맞다.
        //
        // 단, 팬 언급이 '회복 조치'인 경우(R3: "스로틀링 걸리면 팬 100%로 켜면")는 부하
        // 구간의 냉각 조건으로 옮기면 안 된다 — 처음부터 팬이 돌고 있으면 스로틀링 자체가
        // 안 걸려서, 정작 학생이 물어본 회복 시간을 잴 수 없는 실행이 된다.
        if (BARE.matcher(text).find()) n.put("cooling", "bare");
        else if (ACTIVE.matcher(text).find() && !r3) n.put("cooling", "active");
        else if (PASSIVE.matcher(text).find()) n.put("cooling", "passive");

        if (MAX_MODE.matcher(text).find()) n.put("workloadMode", "max_throughput");
        else if (TARGET_MODE.matcher(text).find()) n.put("workloadMode", "target_fps");


        Matcher amb = AMBIENT.matcher(text);
        if (amb.find()) n.put("ambientTempC", Double.parseDouble(amb.group(1)));

        Matcher fps = FPS.matcher(text);
        if (fps.find()) {
            n.put("targetFps", Double.parseDouble(fps.group(1)));
            if (!n.has("workloadMode")) n.put("workloadMode", "target_fps");
        }
        return n;
    }

    /**
     * 회복(TRT) 실험 요청인데 조건이 비어 있으면, <b>스로틀링이 실제로 일어나는 조건</b>을
     * 기본값으로 잡아 준다.
     *
     * <p>"팬 켜면 얼마나 빨리 회복돼?"라고 물었는데 도구 기본값(방열판·목표 FPS)으로 돌리면
     * 애초에 스로틀링이 안 걸려서 "회복할 것이 없다"는 답이 나온다. 틀린 계산은 아니지만
     * 학생이 물어본 것에 대한 답도 아니다. 그래서 실험 설계 문서의 표준 회복 실험 조건
     * (무냉각 · 최대 처리량 — RE_엣지_발열실험_설계.md §3.1 우선순위 6번 셀)을 채운다.
     *
     * <p>가정한 값은 반드시 사용자에게 보이게 한다(호출측이 이 반환값으로 안내 문구를 붙인다)
     * — 서버가 조용히 조건을 바꾸면 학생이 자기가 요청한 조건의 결과라고 오해한다.
     *
     * @return 기본값을 채웠으면 true
     */
    public static boolean applyRecoveryExperimentDefaults(ObjectNode args) {
        String policy = args.path("recoveryPolicy").asText("none");
        if (policy.isBlank() || "none".equals(policy)) return false;
        boolean applied = false;
        if (!args.hasNonNull("cooling")) { args.put("cooling", "bare"); applied = true; }
        if (!args.hasNonNull("workloadMode")) { args.put("workloadMode", "max_throughput"); applied = true; }
        return applied;
    }

    /**
     * LLM이 뽑은 인자에 결정론 판정값을 덮어씌운다.
     *
     * @param llm   GPT가 만든 arguments JSON. {@code null}이면 결정론 값만으로 실행한다
     * @param guard {@link #fromText} 결과 — 겹치는 필드는 항상 이쪽이 이긴다
     */
    public static ObjectNode merge(JsonNode llm, ObjectNode guard) {
        ObjectNode out = MAPPER.createObjectNode();
        if (llm != null && llm.isObject()) out.setAll((ObjectNode) llm);
        out.setAll(guard);
        return out;
    }
}
