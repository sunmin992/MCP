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

    /** 두 보드를 나란히 놓는 접속 표현 — "4<b>와</b> 5", "pi4 <b>vs</b> pi5". */
    private static final String BOARD_JOINER = "(와|과|랑|이랑|vs|대)";

    /**
     * 보드 언급 패턴. 앞의 네 대안은 이름이 온전히 적힌 경우고, 뒤의 두 대안(lookbehind·
     * lookahead)이 <b>병렬 표현에서 한쪽이 숫자만 남는 경우</b>를 잡는다.
     *
     * <p>왜 필요한가: "라즈베리파이 4와 5"에서 뒤쪽 5는 "파이" 바로 뒤에 있지 않아 기존
     * {@code 파이\s*5}로는 안 걸린다. 그러면 {@code p4 ^ p5}가 참이 되어 <b>비교 요청이
     * 단일 Pi4 실행으로 조용히 바뀐다</b> — 실제로 재현된 버그다(두 보드 비교를 물었는데
     * Pi4 결과 하나만 돌아왔다). 앞쪽만 숫자인 "4와 5 중에 뭐가 더 뜨거워?"도 있어서
     * lookbehind와 lookahead가 모두 필요하다.
     *
     * <p>그냥 {@code \b[45]\b}로 열지 않고 접속 표현으로 좁힌 이유는 "20분", "목표 4 FPS",
     * "10분과 20분 중" 같은 무관한 숫자를 보드로 오인하지 않기 위해서다.
     */
    private static final Pattern PI4 = Pattern.compile(
            "(pi\\s*4|파이\\s*4|4\\s*b\\b|라즈베리\\s*파이\\s*4"
            + "|(?<=\\d\\s{0,3}" + BOARD_JOINER + "\\s{0,3})4"
            + "|4(?=\\s{0,3}" + BOARD_JOINER + "\\s{0,3}\\d))", Pattern.CASE_INSENSITIVE);
    private static final Pattern PI5 = Pattern.compile(
            "(pi\\s*5|파이\\s*5|라즈베리\\s*파이\\s*5"
            + "|(?<=\\d\\s{0,3}" + BOARD_JOINER + "\\s{0,3})5"
            + "|5(?=\\s{0,3}" + BOARD_JOINER + "\\s{0,3}\\d))", Pattern.CASE_INSENSITIVE);

    /**
     * 이번 메시지가 <b>두 보드를 비교</b>해 달라는 요청인가.
     *
     * <p>{@link #fromText}가 {@code board}를 확정하지 않는 경우는 두 가지다 — 보드를 아예 안
     * 적었거나, 둘 다 적었거나. 앞의 것은 되물어야 하고 뒤의 것은 <b>두 번 실행</b>해야 하므로
     * 호출측이 구분할 수 있어야 한다. 판정은 이 프로젝트의 C2 원칙대로 LLM 없이 정규식으로만
     * 한다 — 같은 문장이 실행할 때마다 다른 실험으로 가면 결과 재현이 불가능해진다.
     */
    public static boolean isBoardComparison(String text) {
        if (text == null || text.isBlank()) return false;
        return PI4.matcher(text).find() && PI5.matcher(text).find();
    }

    private static final Pattern BARE = Pattern.compile("(무냉각|냉각\\s*없|방열판\\s*없|맨\\s*보드|기본\\s*상태|아무것도\\s*안|bare)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVE = Pattern.compile("(팬|쿨러|능동\\s*냉각|액티브|active\\s*cool)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSIVE = Pattern.compile("(방열판|히트\\s*싱크|heatsink|수동\\s*냉각|패시브|passive)", Pattern.CASE_INSENSITIVE);

    private static final Pattern MAX_MODE = Pattern.compile("(최대\\s*(처리량|부하|성능)|풀\\s*로드|full\\s*load|max\\s*throughput|한계까지)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_MODE = Pattern.compile("(목표\\s*fps|동일\\s*fps|고정\\s*fps|target\\s*fps)", Pattern.CASE_INSENSITIVE);

    private static final Pattern R1 = Pattern.compile("(\\br1\\b|완전\\s*중지|추론.{0,6}(중지|정지|멈)|부하.{0,4}(중지|정지|멈))", Pattern.CASE_INSENSITIVE);
    private static final Pattern R2 = Pattern.compile("(\\br2\\b|저부하|부하.{0,4}(낮|줄)|25\\s*%)", Pattern.CASE_INSENSITIVE);
    private static final Pattern R3 = Pattern.compile("(\\br3\\b|능동\\s*냉각|팬.{0,6}(100|최대|full|켜)|강제\\s*냉각)", Pattern.CASE_INSENSITIVE);

    // ── AI 부하 패턴 ────────────────────────────────────────────────────
    // 어느 패턴으로 돌렸는지는 "어떤 실험을 돌렸는지"를 바꾸는 값이라(같은 방열판도
    // 패턴에 따라 순위가 뒤집힌다) 다른 실험 조건과 똑같이 정규식으로 확정한다.
    //
    // 셋 다 "부하가 시간에 따라 어떻게 변하는가"를 명시적으로 가리키는 어휘만 넣었다.
    // "최대 부하"·"고부하"처럼 세기만 말하는 표현은 넣지 않는다 — 그건 workloadMode의
    // 몫이고, 여기에 넣으면 "최대 처리량으로 돌려줘"가 대조군 패턴을 강제로 켜서
    // 사용자가 지정하지도 않은 조건이 결과에 붙는다.

    /** 몰렸다 빠지는 짧은 변동. */
    private static final Pattern LOAD_BURST = Pattern.compile(
            "(버스트|burst|몰리|몰렸|몰림|간헐|출렁|들쭉|튀는\\s*부하"
            + "|부하\\s*(변동|진동|스파이크)|스파이크|요청.{0,4}(몰|폭주)|피크\\s*부하)",
            Pattern.CASE_INSENSITIVE);

    /** 느린 흐름 위에 버스트가 얹힌 실사용형. */
    private static final Pattern LOAD_MIXED = Pattern.compile(
            "(혼합\\s*부하|mixed|실사용|실제\\s*(서비스|사용|운영)|하루\\s*(흐름|패턴|주기)"
            + "|시간대별\\s*부하|데이터\\s*센터\\s*(부하|패턴)|한가.{0,6}피크|피크.{0,6}한가)",
            Pattern.CASE_INSENSITIVE);

    /** 변동 없는 대조군. "최대로 돌려줘"만으로는 켜지 않는다(그건 이미 기본 동작이다). */
    private static final Pattern LOAD_STEADY = Pattern.compile(
            "(일정한?\\s*부하|상수\\s*부하|steady|변동\\s*없|일정하게\\s*(유지|계속)"
            + "|대조군|부하\\s*패턴\\s*없)",
            Pattern.CASE_INSENSITIVE);

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

        // AI 부하 패턴 — 구체적인 쪽(혼합 > 버스트 > 일정) 우선. "혼합"은 정의상 버스트를
        // 품고 있어서 두 어휘가 한 문장에 같이 나오는 일이 잦은데, 그때 원하는 것은
        // 언제나 더 구체적인 혼합 쪽이다. 부하 패턴은 workloadMode와 독립된 축이라
        // 둘 다 지정될 수 있고, 그게 정상이다(예: 최대 처리량 + 버스트).
        if (LOAD_MIXED.matcher(text).find()) n.put("aiLoadProfileId", "mixed");
        else if (LOAD_BURST.matcher(text).find()) n.put("aiLoadProfileId", "burst");
        else if (LOAD_STEADY.matcher(text).find()) n.put("aiLoadProfileId", "steady");


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
