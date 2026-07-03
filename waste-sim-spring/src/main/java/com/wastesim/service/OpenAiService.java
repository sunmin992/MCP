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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI API (gpt-4o-mini) 연동 서비스.
 *
 * 사용자 메시지를 분석해 시뮬레이션 파라미터를 추출하거나
 * 시뮬레이션 결과에 대한 자연어 해설을 생성한다.
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

    // 로컬 LLM(Qwen 등)이 JSON 안에 // 주석·후행 콤마를 넣는 경우가 많아 관대하게 파싱
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

    private static final String SYSTEM_PROMPT = """
            당신은 지역사회 생활쓰레기 시뮬레이션 어시스턴트입니다.
            포항시 북구 장량동 원룸촌의 쓰레기 배출·수거 패턴을 DEVS(이산사건시스템) 기반으로 시뮬레이션합니다.

            ## 언어 규칙 (가장 중요)
            반드시 한국어로만 답변하세요. 중국어(汉语)·영어·일본어 등 다른 언어를 절대 사용하지 마세요.
            사용자가 다른 언어로 질문해도 답변은 한국어로 합니다.

            ## 시뮬레이션 모델 개요
            - 거주민 100명, 4개 건물, 건물당 25명
            - 직업: 생산직(일용직, 07:22 출발), 학생(08:58), 전업주부(14:00)
            - 건물당 30kg 임시 수거통, 수거 차량이 매일 지정 시각에 전체 수거
            - 수거통 적재율이 임계치(기본 80%) 이상일 때 배출하면 민원 발생으로 집계

            ## 조정 가능한 파라미터
            - collectionTime: 수거 시각 (예: "10:00", "12:00", "14:00")
            - days: 시뮬레이션 기간(일), 기본 30
            - seeds: 반복 횟수, 기본 30
            - leaveSigma: 출발 시각 표준편차(분), 기본 30
            - wasteSigma: 일일 쓰레기 표준편차(kg), 기본 0.3
            - threshold: 청결도 임계치(0~1), 기본 0.8
            - capacity: 수거통 용량(kg), 기본 30

            ## 이 시뮬레이션이 계산할 수 있는 것 / 없는 것
            계산 가능(=RUN_SIMULATION으로 실행): 사용자가 수거 시각 등 구체적
            조건을 하나라도 지정하면서 그 조건에서의 "한 달간 총 민원 수·직업별
            민원·최대 적재량"을 구하려는 요청. 시각이 "07:22" 같은 정형 표기가
            아니라 "아침 8시 반"처럼 자연어라도, 특정 수거 시각을 가리키면 유효한
            collectionTime입니다 — 이런 경우는 절대 거절하지 말고 JSON을 내세요.

            계산 불가능(JSON 내지 말 것) — 아래 두 경우만 해당:
            (a) 특정 순간의 미집계 수치를 직접 묻는 경우 (예: "12시 시점 배출량",
                "17시 시점 배출량" 그 자체 값). 이 모델은 순간값을 출력하지 않고
                월간 집계만 계산하므로, 한계를 설명하세요.
            (b) 수거 시각 등 조건을 하나도 지정하지 않고 막연히 묻는 경우
                (예: "패턴 알려줘", "어떻게 돼?", "분석해줘"). 이때는 임의로
                기본값을 정해 실행하지 말고, 어떤 수거 시각을 원하는지 되물어보세요.

            "실행해줘"라는 단어가 있어도 동사만으로 판단하지 말고, 위 (a)(b)에
            해당하는지만 보세요. 구체적 시각이 이미 있다면 반드시 JSON을 냅니다.

            ## JSON 출력 엄격 규칙 (반드시 지킬 것)
            - collectionTime(수거 시각)이 응답 어디에도 명시되지 않았다면
              JSON을 절대 내지 말고 "몇 시 수거로 시뮬레이션할까요?"처럼
              되물으세요. 짐작으로 기본값(예: 12:00)을 정해 채우지 마세요.
            - "알려줘", "설명해줘", "뭐야", "패턴", "어때" 등 설명·조회성
              질문에는 JSON을 내지 마세요. 실제로 파라미터를 바꿔 실행할
              때만 JSON을 냅니다.
            - 예시나 제안으로도 JSON 블록을 넣지 마세요. "예를 들어 이렇게
              실행해볼 수 있습니다"처럼 말하면서 JSON을 덧붙이는 것도
              금지입니다. JSON은 정말로 지금 실행할 때만, 정확히 1개만
              출력하세요. 2개 이상의 JSON 블록을 내는 것도 금지입니다.

            ## 응답 규칙
            사용자가 위 "계산 가능" 범위의 시뮬레이션 실행을 요청하면 **반드시**
            아래 JSON 블록을 정확히 1개 응답에 포함하세요:
            ```json
            {
              "action": "RUN_SIMULATION",
              "params": {
                "collectionTime": "12:00",
                "days": 30,
                "seeds": 30,
                "leaveSigma": 30.0,
                "wasteSigma": 0.3,
                "threshold": 0.8,
                "capacity": 30.0
              }
            }
            ```
            JSON 블록 앞뒤에 자연어 설명을 추가해도 됩니다(설명도 한국어).
            JSON 안에는 주석(//, /* */)을 절대 넣지 마세요. 순수 JSON만 출력하세요.
            collectionTime 같은 값 뒤에 // 설명을 붙이면 안 됩니다.
            시뮬레이션 요청이 아니거나 위 (a)(b)에 해당하면 JSON 없이
            일반 텍스트(한국어)로만 답변하세요.

            ## 판단 예시 (few-shot)
            - "12시 수거로 시뮬레이션 돌려줘" → 수거시각(12시) 명시됨 →
              JSON 정확히 1개 포함
            - "시간대별로 직업별 배출 패턴 알려줘" → 수거시각 미지정 + "알려줘"
              (조회성) → JSON 없이 "어떤 수거 시각으로 시뮬레이션할지
              알려주시면 실행하겠습니다"처럼 되물음
            - "아침 8시 반에 수거하면 민원이 어떻게 되는지 실행해줘" → 수거시각
              (8:30)이 자연어로라도 명시됨 → JSON 포함 (거절 금지)
            - "12시 배출량이랑 17시 배출량을 실행해줘" → (a) 순간값 조회, 두
              시각을 "예시로 비교해보자"며 JSON 2개를 내지 말 것 → JSON
              없이, "이 모델은 특정 시각의 순간 배출량이 아니라 수거
              시각별 월간 민원 수를 계산합니다"처럼 한계만 설명

            ## 서식 규칙 (중요)
            마크다운 서식을 사용하지 마세요. 별표(**, *), 백틱(`), 머리말 기호(#)를
            쓰지 말고 순수한 평문으로 작성하세요. 강조가 필요하면 따옴표나 줄바꿈,
            번호(1. 2. 3.)만 사용하세요. (단, 위의 시뮬레이션 실행용 ```json 코드블록은 예외)
            """;

    /**
     * 대화 이력을 포함해 OpenAI에 메시지를 전송하고 응답 텍스트를 반환한다.
     *
     * @param history  이전 대화 [{role:"user"|"assistant", content:"..."}]
     * @param userText 새 사용자 메시지
     */
    public String chat(List<Map<String, String>> history, String userText) {
        try {
            ArrayNode messages = mapper.createArrayNode();

            // system 메시지 (OpenAI는 messages 배열 안에 포함)
            ObjectNode systemMsg = mapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", SYSTEM_PROMPT);
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
            body.put("max_tokens", 1024);
            // 낮은 temperature로 JSON 유무·형식 준수의 실행 간 편차를 줄임
            body.put("temperature", 0.2);
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
                    log.error("OpenAI API error {}: {}", response.code(), responseBody);
                    return "⚠️ OpenAI API 오류가 발생했습니다 (코드 " + response.code() + "). API 키를 확인해주세요.";
                }
                JsonNode json = mapper.readTree(responseBody);
                return json.path("choices").path(0).path("message").path("content")
                        .asText("응답을 처리할 수 없습니다.");
            }
        } catch (Exception e) {
            log.error("OpenAI API 호출 실패", e);
            return "⚠️ OpenAI API 호출 중 오류: " + e.getMessage();
        }
    }

    /**
     * 추출 결과 + "자동 실행해도 될 만큼 확신할 수 있는가"를 함께 반환.
     * confident=false 인 경우 자동 실행 대신 사용자 확인을 거쳐야 한다.
     */
    public record ExtractionResult(SimulationConfig config, boolean confident) {}

    private record ParsedAction(SimulationConfig config, boolean hasCollectionTime) {}

    // 실제 실행이 아니라 예시·제안·추측으로 JSON을 곁들일 때 흔히 쓰는 표현.
    // 관측된 오탐 로그(qwen/llama)에서 실제로 나온 문구 기준.
    private static final String[] HEDGE_PHRASES = {
            "해 보세요", "해보세요", "해 볼까요", "해볼까요", "가정해볼까요", "가정해 볼까요",
            "예를 들어", "다만", "비교해볼 수 있습니다", "비교해 볼 수 있습니다"
    };

    private static boolean containsHedgeLanguage(String text) {
        for (String phrase : HEDGE_PHRASES) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    private static int countJsonBlocks(String text) {
        Matcher m = Pattern.compile("```json").matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    /**
     * OpenAI 응답 텍스트에서 RUN_SIMULATION JSON 블록을 파싱하고,
     * 자동 실행 가능 여부(confident)를 함께 판단한다.
     *
     * confident 조건 — 셋 다 만족해야 자동 실행:
     *   1) ```json 블록이 정확히 1개
     *   2) 예시/추측성 헤징 표현이 응답에 없음
     *   3) 파싱된 params에 collectionTime이 명시적으로 존재(기본값 추측 아님)
     */
    public ExtractionResult extractSimulationConfig(String llmResponse) {
        int blockCount = countJsonBlocks(llmResponse);
        boolean hedged = containsHedgeLanguage(llmResponse);

        ParsedAction parsed = null;

        // 1) ```json ... ``` 코드 블록 우선 탐색 (greedy: 중첩된 params 까지 균형 있게 캡처)
        Pattern codeBlock = Pattern.compile("```json\\s*(\\{[\\s\\S]*\\})\\s*```");
        Matcher m = codeBlock.matcher(llmResponse);
        if (m.find()) {
            parsed = parseActionJson(m.group(1));
        }

        // 2) 실패 시 "action" 키를 포함한 JSON 객체 탐색 (블록이 여러 개라 뭉쳐 깨진 경우 등)
        if (parsed == null) {
            Pattern actionPat = Pattern.compile("\\{[^{}]*\"action\"[^{}]*\\{[\\s\\S]*?\\}[^{}]*\\}");
            Matcher m2 = actionPat.matcher(llmResponse);
            while (m2.find()) {
                parsed = parseActionJson(m2.group());
                if (parsed != null) break;
            }
        }

        if (parsed == null) return new ExtractionResult(null, false);

        boolean confident = blockCount <= 1 && !hedged && parsed.hasCollectionTime();
        return new ExtractionResult(parsed.config(), confident);
    }

    private ParsedAction parseActionJson(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (!"RUN_SIMULATION".equals(node.path("action").asText(null))) return null;
            JsonNode p = node.path("params");
            SimulationConfig cfg = new SimulationConfig();
            boolean hasCollectionTime = p.has("collectionTime");
            if (hasCollectionTime)
                cfg.setCollectionTimeLabel(p.get("collectionTime").asText("12:00"));
            if (p.has("days"))       cfg.setDays(p.get("days").asInt(30));
            if (p.has("seeds"))      cfg.setSeeds(p.get("seeds").asInt(30));
            if (p.has("leaveSigma")) cfg.setLeaveSigma(p.get("leaveSigma").asDouble(30.0));
            if (p.has("wasteSigma")) cfg.setWasteSigma(p.get("wasteSigma").asDouble(0.3));
            if (p.has("threshold"))  cfg.setThreshold(p.get("threshold").asDouble(0.8));
            if (p.has("capacity"))   cfg.setCapacity(p.get("capacity").asDouble(30.0));
            return new ParsedAction(cfg, hasCollectionTime);
        } catch (Exception e) {
            log.debug("JSON 파싱 시도 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 시뮬레이션 결과에 대한 자연어 해설 생성
     */
    public String explainResult(String collectionTime, double mean, double std,
                                 Map<String, Object> byOcc) {
        String prompt = String.format(
                "수거 시각 %s 시뮬레이션 결과: 월간 평균 민원 %.1f건 (표준편차 %.1f). " +
                "직업별 민원: %s. 결과를 2-3문장으로 해설해주세요.",
                collectionTime, mean, std, byOcc);

        return chat(List.of(), prompt);
    }
}
