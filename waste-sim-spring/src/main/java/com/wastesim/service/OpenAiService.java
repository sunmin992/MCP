package com.wastesim.service;

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

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            당신은 지역사회 생활쓰레기 시뮬레이션 어시스턴트입니다.
            포항시 북구 장량동 원룸촌의 쓰레기 배출·수거 패턴을 DEVS(이산사건시스템) 기반으로 시뮬레이션합니다.

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

            ## 응답 규칙
            사용자가 시뮬레이션 실행을 요청하면 **반드시** 아래 JSON 블록을 응답에 포함하세요:
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
            JSON 블록 앞뒤에 자연어 설명을 추가해도 됩니다.
            시뮬레이션 요청이 아닌 경우 일반 텍스트로 답변하세요.
            한국어로 답변하세요.
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
     * OpenAI 응답 텍스트에서 RUN_SIMULATION JSON 블록을 파싱한다.
     * ```json ... ``` 코드블록 또는 순수 JSON 객체 모두 처리. 없으면 null 반환.
     */
    public SimulationConfig extractSimulationConfig(String llmResponse) {
        // 1) ```json ... ``` 코드 블록 우선 탐색
        Pattern codeBlock = Pattern.compile("```json\\s*(\\{[\\s\\S]*?\\})\\s*```");
        Matcher m = codeBlock.matcher(llmResponse);
        if (m.find()) {
            SimulationConfig cfg = parseActionJson(m.group(1));
            if (cfg != null) return cfg;
        }

        // 2) "action" 키를 포함한 JSON 객체 탐색
        Pattern actionPat = Pattern.compile("\\{[^{}]*\"action\"[^{}]*\\{[\\s\\S]*?\\}[^{}]*\\}");
        Matcher m2 = actionPat.matcher(llmResponse);
        while (m2.find()) {
            SimulationConfig cfg = parseActionJson(m2.group());
            if (cfg != null) return cfg;
        }
        return null;
    }

    private SimulationConfig parseActionJson(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (!"RUN_SIMULATION".equals(node.path("action").asText(null))) return null;
            JsonNode p = node.path("params");
            SimulationConfig cfg = new SimulationConfig();
            if (p.has("collectionTime"))
                cfg.setCollectionTimeLabel(p.get("collectionTime").asText("12:00"));
            if (p.has("days"))       cfg.setDays(p.get("days").asInt(30));
            if (p.has("seeds"))      cfg.setSeeds(p.get("seeds").asInt(30));
            if (p.has("leaveSigma")) cfg.setLeaveSigma(p.get("leaveSigma").asDouble(30.0));
            if (p.has("wasteSigma")) cfg.setWasteSigma(p.get("wasteSigma").asDouble(0.3));
            if (p.has("threshold"))  cfg.setThreshold(p.get("threshold").asDouble(0.8));
            if (p.has("capacity"))   cfg.setCapacity(p.get("capacity").asDouble(30.0));
            return cfg;
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
