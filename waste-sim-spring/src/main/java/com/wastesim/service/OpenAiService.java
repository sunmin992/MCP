package com.wastesim.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.model.SimulationConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * OpenAI 호환 API(gpt-4o-mini / Ollama qwen2.5 등) 연동 서비스.
 *
 * <p>2단계 파이프라인으로 "판단"과 "생성"을 분리한다:
 * <ol>
 *   <li>1단계 — {@link #classifyIsRunRequest}: temperature=0으로 "실행 요청인가?"만
 *       yes/no로 판단. 창의성이 필요 없는 순수 분류라 작은 모델도 안정적이다.</li>
 *   <li>2단계 — {@link #extractParamsStrict}: 1단계가 yes일 때만 호출.
 *       response_format=json_object(Ollama의 format:json과 동일 효과)로 프리텍스트
 *       없이 구조화된 파라미터만 뽑는다. 산문·헤징 표현·다중 JSON 블록이 섞일 수
 *       없어, 기존의 정규식 기반 사후 검증(헤징 문구 탐지, 블록 개수 세기 등)이
 *       구조적으로 불필요해진다.</li>
 * </ol>
 * 실행 요청이 아니면 {@link #answerPlain}으로 JSON 없이 순수 대화 답변만 생성한다.
 */
@Service
public class OpenAiService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiService.class);

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.api.url}")
    private String apiUrl;

    @Value("${openai.model}")
    private String model;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    // 로컬 LLM이 JSON 안에 // 주석·후행 콤마를 넣는 경우가 많아 관대하게 파싱
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    /** "HH:MM" 24시간 형식인지 검증 — 2단계 추출 결과의 최종 안전망 */
    public static boolean isValidCollectionTime(String s) {
        return s != null && HHMM.matcher(s).matches();
    }

    // ── 1단계: 의도 분류 전용 프롬프트 (판단만, 생성 없음) ─────────────────
    private static final String INTENT_SYSTEM_PROMPT = """
            당신은 쓰레기 수거 시뮬레이션 챗봇의 의도 분류기입니다. 창의적으로
            답하지 말고 아래 기준으로만 판단하세요.

            yes (구체적 조건의 실행 요청):
            - 수거 시각이 숫자·자연어 어떤 형태로든 명시됨
              (예: "12시", "8시 반", "10:00", "낮 12시", "저녁 7시")
            - 그 시각 조건에서 월간 민원 수·직업별 민원 등을 계산해 달라는 요청
            - 판단 기준은 오직 "문장에 숫자로 특정 가능한 시각이 있는가"입니다.
              "12시", "8시 반", "10:00", "낮 12시"처럼 실제 시각 하나를 콕
              집을 수 있어야 yes입니다.

            no (실행 요청 아님) — 아래 중 하나라도 해당하면 no:
            - "시간대별로", "시각에 따라", "패턴", "어떻게 돼?"처럼 시각의
              '개념'만 언급하고 실제 숫자 시각은 하나도 안 준 경우
              (예: "시간대별로 직업별 배출 패턴 알려줘" → 특정 시각 없음 → no)
            - 시각이 2개 이상 언급되며 그 순간의 값 자체(배출량 등)를 묻는
              경우 — 이는 수거 시각 설정이 아니라 순간값 조회이므로 no
              (예: "12시 배출량과 17시 배출량을 알려줘/실행해줘" → no)
            - 모델 설명, 인사, 일반 대화

            "실행해줘/알려줘/돌려줘" 같은 동사는 판단 근거로 쓰지 마세요.
            오직 "특정 숫자 시각이 정확히 하나, 수거 시각 설정 목적으로
            쓰였는가"만 보세요.

            정확히 yes 또는 no 한 단어만 출력하세요. 설명·구두점·따옴표·다른
            언어 없이 그 한 단어만 출력합니다.
            """;

    // ── 2단계: JSON 모드 파라미터 추출 전용 프롬프트 (1단계가 yes일 때만 호출) ──
    private static final String EXTRACTION_SYSTEM_PROMPT = """
            사용자 메시지에서 쓰레기 수거 시뮬레이션 실행 파라미터를 추출하세요.
            이미 "실행 요청"으로 확인된 메시지이므로 판단은 필요 없고 추출만
            하면 됩니다. 아래 스키마의 JSON 객체 하나만 출력하세요. 설명·코드
            펜스·다른 텍스트는 절대 포함하지 마세요.

            {
              "collectionTime": "HH:MM",
              "days": 30,
              "seeds": 30,
              "leaveSigma": 30.0,
              "wasteSigma": 0.3,
              "threshold": 0.8,
              "capacity": 30.0
            }

            - collectionTime: 사용자가 언급한 수거 시각을 24시간 HH:MM 형식으로
              변환. 예: "8시 반"→"08:30", "낮 12시"→"12:00", "저녁 7시"→"19:00".
              반드시 포함해야 합니다.
            - 나머지 값은 사용자가 명시하지 않으면 위 기본값을 그대로 사용하세요.
            """;

    // ── "실행 요청 아님"일 때 순수 대화 답변 전용 프롬프트 (JSON 지시 없음) ──
    private static final String PLAIN_ANSWER_SYSTEM_PROMPT = """
            ## 언어 규칙 (가장 중요, 반드시 최우선으로 지킬 것)
            반드시 한국어로만 답변하세요. 중국어(汉语)·영어·일본어 등 다른 언어를
            단 한 글자도 섞지 마세요. 답변 중간에 언어가 바뀌는 것도 금지입니다.

            당신은 지역사회 생활쓰레기 시뮬레이션 어시스턴트입니다.
            포항시 북구 장량동 원룸촌의 쓰레기 배출·수거 패턴을 DEVS(이산사건시스템)
            기반으로 시뮬레이션합니다. 이 대화 턴은 이미 "실행 요청이 아님"으로
            분류되었으므로, 절대 JSON을 출력하지 말고 순수 한국어 텍스트로만
            답변하세요.

            ## 시뮬레이션 모델 개요
            - 거주민 100명, 4개 건물, 건물당 25명
            - 직업: 생산직(일용직, 07:22 출발), 학생(08:58), 전업주부(14:00)
            - 건물당 30kg 임시 수거통, 수거 차량이 매일 지정 시각에 전체 수거
            - 수거통 적재율이 임계치(기본 80%) 이상일 때 배출하면 민원 발생으로 집계
            - 계산 가능한 것: 특정 수거 시각 조건에서의 월간 총 민원 수·직업별
              민원·최대 적재량뿐. 특정 순간의 배출량 같은 순간값은 계산하지 않음.

            ## 서식 규칙
            마크다운 서식을 사용하지 마세요. 별표(**, *), 백틱(`), 머리말 기호(#)를
            쓰지 말고 순수한 평문으로 작성하세요. 번호(1. 2. 3.)와 줄바꿈만 쓰세요.

            사용자가 조건 없이 막연히 실행을 원하는 것처럼 보이면, 어떤 수거
            시각으로 시뮬레이션할지 되물어보세요.
            """;

    /**
     * 1단계 — 이 메시지가 구체적 조건의 실행 요청인지 판단한다.
     * temperature=0으로 호출해 "yes"/"no" 한 단어만 받는다. 실패 시 안전하게
     * false(실행하지 않음)로 처리한다.
     */
    public boolean classifyIsRunRequest(List<Map<String, String>> history, String userText) {
        try {
            String raw = callChat(INTENT_SYSTEM_PROMPT, history, userText, 0.0, 5, false);
            String norm = raw == null ? "" : raw.trim().toLowerCase().replaceAll("[^a-z]", "");
            return norm.startsWith("yes");
        } catch (Exception e) {
            log.error("1단계(의도 분류) 호출 실패", e);
            return false;
        }
    }

    /**
     * 2단계 — 1단계가 yes일 때만 호출. response_format=json_object(JSON 모드)로
     * 프리텍스트 없는 구조화된 파라미터만 받는다. 실패하거나 collectionTime이
     * 없으면 null을 반환한다(호출 측에서 재질문 처리).
     */
    public SimulationConfig extractParamsStrict(List<Map<String, String>> history, String userText) {
        try {
            String raw = callChat(EXTRACTION_SYSTEM_PROMPT, history, userText, 0.1, 300, true);
            if (raw == null) return null;
            JsonNode p = mapper.readTree(stripCodeFence(raw));
            if (!p.has("collectionTime")) return null;

            SimulationConfig cfg = new SimulationConfig();
            cfg.setCollectionTimeLabel(p.get("collectionTime").asText("12:00"));
            if (p.has("days"))       cfg.setDays(p.get("days").asInt(30));
            if (p.has("seeds"))      cfg.setSeeds(p.get("seeds").asInt(30));
            if (p.has("leaveSigma")) cfg.setLeaveSigma(p.get("leaveSigma").asDouble(30.0));
            if (p.has("wasteSigma")) cfg.setWasteSigma(p.get("wasteSigma").asDouble(0.3));
            if (p.has("threshold"))  cfg.setThreshold(p.get("threshold").asDouble(0.8));
            if (p.has("capacity"))   cfg.setCapacity(p.get("capacity").asDouble(30.0));
            return cfg;
        } catch (Exception e) {
            log.debug("2단계(파라미터 추출) 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    /** 실행 요청이 아닐 때(1단계=no) 순수 대화형 답변을 생성한다. JSON을 내지 않는다. */
    public String answerPlain(List<Map<String, String>> history, String userText) {
        try {
            String raw = callChat(PLAIN_ANSWER_SYSTEM_PROMPT, history, userText, 0.2, 1024, false);
            return raw != null ? raw : "응답을 처리할 수 없습니다.";
        } catch (Exception e) {
            log.error("답변 생성 실패", e);
            return "⚠️ 응답 생성 중 오류: " + e.getMessage();
        }
    }

    /**
     * OpenAI 호환 /chat/completions 공통 호출.
     *
     * @param jsonMode true면 response_format={"type":"json_object"} 전달
     *                 (OpenAI JSON 모드 / Ollama format:json과 동일 효과 — 두
     *                 백엔드 모두 동일한 OpenAI 호환 엔드포인트를 쓰므로 이
     *                 필드 하나로 양쪽 다 적용된다).
     */
    private String callChat(String systemPrompt, List<Map<String, String>> history, String userText,
                            double temperature, int maxTokens, boolean jsonMode) throws java.io.IOException {
        ArrayNode messages = mapper.createArrayNode();

        ObjectNode systemMsg = mapper.createObjectNode();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        for (Map<String, String> m : history) {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", m.get("role"));
            msg.put("content", m.get("content"));
            messages.add(msg);
        }
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userText);
        messages.add(userMsg);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        if (jsonMode) {
            ObjectNode responseFormat = mapper.createObjectNode();
            responseFormat.put("type", "json_object");
            body.set("response_format", responseFormat);
        }
        body.set("messages", messages);

        RequestBody requestBody = RequestBody.create(
                mapper.writeValueAsString(body),
                MediaType.parse("application/json; charset=utf-8"));

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(requestBody)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "{}";
            if (!response.isSuccessful()) {
                log.error("API 오류 {}: {}", response.code(), responseBody);
                return null;
            }
            JsonNode json = mapper.readTree(responseBody);
            return json.path("choices").path(0).path("message").path("content").asText(null);
        }
    }

    /** 일부 로컬 모델은 JSON 모드에서도 습관적으로 코드펜스를 씌우므로 방어적으로 제거 */
    private static String stripCodeFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```(json)?", "").trim();
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3).trim();
        }
        return t;
    }
}
