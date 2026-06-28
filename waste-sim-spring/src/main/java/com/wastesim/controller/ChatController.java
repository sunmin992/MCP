package com.wastesim.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.wastesim.model.ChatMessage;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.OpenAiService;
import com.wastesim.service.SimulationService;

/**
 * STOMP WebSocket 채팅 컨트롤러.
 *
 * 클라이언트: /app/chat.send → 서버 처리 → /topic/messages 브로드캐스트
 */
@Controller
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final SimpMessagingTemplate messaging;
    private final OpenAiService openAiService;
    private final SimulationService simulationService;

    // 간단한 in-memory 대화 이력 (sessionId → 메시지 목록)
    private final Map<String, List<Map<String, String>>> histories = new ConcurrentHashMap<>();

    public ChatController(SimpMessagingTemplate messaging,
                          OpenAiService openAiService,
                          SimulationService simulationService) {
        this.messaging = messaging;
        this.openAiService = openAiService;
        this.simulationService = simulationService;
    }

    @MessageMapping("/chat.send")
    public void handleMessage(@Payload ChatMessage incoming) {
        String sessionId = "default"; // 단일 채팅방
        List<Map<String, String>> history =
                histories.computeIfAbsent(sessionId, k -> new ArrayList<>());

        String userText = incoming.getContent();

        // 1. 사용자 메시지 echo
        ChatMessage userMsg = new ChatMessage(ChatMessage.MessageType.USER, userText);
        messaging.convertAndSend("/topic/messages", userMsg);

        try {
            // 2. OpenAI API 호출
            ChatMessage thinkingMsg = new ChatMessage(ChatMessage.MessageType.SYSTEM,
                    "AI가 분석 중입니다...");
            messaging.convertAndSend("/topic/messages", thinkingMsg);

            String llmReply = openAiService.chat(history, userText);

            // 3. 대화 이력 업데이트
            history.add(Map.of("role", "user", "content", userText));
            history.add(Map.of("role", "assistant", "content", llmReply));
            // 이력이 너무 길어지면 앞 부분 제거 (최근 10쌍만 유지)
            while (history.size() > 20) history.remove(0);

            // 4. LLM 응답에서 시뮬레이션 실행 요청 추출
            SimulationConfig cfg = openAiService.extractSimulationConfig(llmReply);

            // LLM 응답 텍스트에서 JSON 블록을 제거해서 표시
            String displayReply = cleanReply(llmReply);
            ChatMessage botMsg = new ChatMessage(ChatMessage.MessageType.BOT, displayReply);
            messaging.convertAndSend("/topic/messages", botMsg);

            // 5. 시뮬레이션 실행 (파라미터가 추출된 경우)
            if (cfg != null) {
                ChatMessage runningMsg = new ChatMessage(ChatMessage.MessageType.SYSTEM,
                        String.format("시뮬레이션 실행 중... (수거시각: %s, %d일 × %d시드)",
                                cfg.getCollectionTimeLabel(), cfg.getDays(), cfg.getSeeds()));
                messaging.convertAndSend("/topic/messages", runningMsg);

                SimulationResult result = simulationService.runExperiment(cfg);
                result.setSimulationConfig(cfg); // helper — 결과에 cfg 포함

                ChatMessage resultMsg = new ChatMessage(ChatMessage.MessageType.RESULT,
                        formatResult(result));
                resultMsg.setSimulationResult(result);
                resultMsg.setSimulationConfig(cfg);
                messaging.convertAndSend("/topic/messages", resultMsg);
            }

        } catch (Exception e) {
            log.error("채팅 처리 오류", e);
            ChatMessage errMsg = new ChatMessage(ChatMessage.MessageType.BOT,
                    "오류가 발생했습니다: " + e.getMessage());
            messaging.convertAndSend("/topic/messages", errMsg);
        }
    }

    @MessageMapping("/chat.clear")
    public void clearHistory() {
        histories.clear();
        ChatMessage sysMsg = new ChatMessage(ChatMessage.MessageType.SYSTEM, "대화 이력이 초기화되었습니다.");
        messaging.convertAndSend("/topic/messages", sysMsg);
    }

    private String cleanReply(String reply) {
        // ```json ... ``` 또는 순수 JSON 블록 제거
        return reply.replaceAll("```json[\\s\\S]*?```", "").trim();
    }

    private String formatResult(SimulationResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(" 시뮬레이션 결과 (수거시각: %s)\n", r.getCollectionTimeLabel()));
        sb.append(String.format("- 월간 평균 민원: %.1f건 (±%.1f)\n",
                r.getMeanComplaints(), r.getStdComplaints()));
        if (r.getByOccupationSummary() != null) {
            sb.append("- 직업별 평균:\n");
            r.getByOccupationSummary().forEach((occ, cnt) -> {
                String label = switch (occ) {
                    case "BlueCollar" -> "  생산직(일용직)";
                    case "Student"    -> "  학생";
                    case "Housewife"  -> "  전업주부";
                    default           -> "  " + occ;
                };
                sb.append(String.format("%s: %.1f건\n", label, ((Number) cnt).doubleValue()));
            });
        }
        return sb.toString();
    }
}
