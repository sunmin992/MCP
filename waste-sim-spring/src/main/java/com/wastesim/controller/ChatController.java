package com.wastesim.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.wastesim.model.ChatMessage;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.JailbreakFilter;
import com.wastesim.service.OpenAiService;
import com.wastesim.service.TimeExpressionDetector;
import com.wastesim.tool.SimulationTool;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;

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
    private final SimulationTool tool;
    private final MeterRegistry metrics;

    // 간단한 in-memory 대화 이력 (sessionId → 메시지 목록)
    private final Map<String, List<Map<String, String>>> histories = new ConcurrentHashMap<>();
    // 확신도가 낮아 자동 실행을 보류한 설정 (sessionId → 대기 중인 설정)
    private final Map<String, SimulationConfig> pendingConfigs = new ConcurrentHashMap<>();

    public ChatController(SimpMessagingTemplate messaging,
                          OpenAiService openAiService,
                          SimulationTool tool,
                          MeterRegistry metrics) {
        this.messaging = messaging;
        this.openAiService = openAiService;
        this.tool = tool;
        this.metrics = metrics;
    }

    @MessageMapping("/chat.send")
    public void handleMessage(@Payload ChatMessage incoming) {
        MDC.put("requestId", "ws-" + UUID.randomUUID().toString().substring(0, 8));
        try {
        String sessionId = "default"; // 단일 채팅방
        List<Map<String, String>> history =
                histories.computeIfAbsent(sessionId, k -> new ArrayList<>());

        String userText = incoming.getContent();

        // 1. 사용자 메시지 echo
        ChatMessage userMsg = new ChatMessage(ChatMessage.MessageType.USER, userText);
        messaging.convertAndSend("/topic/messages", userMsg);

        try {
            // 2. 0단계 — 결정론적 시각 게이트(LLM 미사용). 베이스라인 제약 C2
            //    ("실행 여부 결정은 결정론적이고 LLM-free여야 한다")를 지키기
            //    위해, "이번 메시지에 파싱 가능한 시각이 정확히 1개 있는가"부터
            //    정규식으로 확정한다. 0개(시각 없음)나 2개 이상(순간값 조회 등)은
            //    이미 이 시점에 "실행 아님"이 확정되므로 LLM을 아예 호출하지
            //    않는다 — 히스토리에서 시각을 끌어와 실행해버리는 부류의 실패가
            //    구조적으로 불가능해진다.
            int timeCount = TimeExpressionDetector.count(userText);
            boolean isRunRequest;
            if (timeCount != 1) {
                isRunRequest = false;
                metrics.counter("waste.chat.classify", "result", "no", "source", "deterministic").increment();
            } else {
                // 1단계 — 의도 분류 (temperature=0, yes/no만). 시각이 정확히
                // 1개일 때만 호출하며, "그 시각이 순간값 조회인가" 같은 좁은
                // 의미 판단만 LLM에 맡긴다(판단과 생성을 분리해 작은 모델도
                // 안정적으로 만드는 것이 핵심).
                messaging.convertAndSend("/topic/messages",
                        new ChatMessage(ChatMessage.MessageType.SYSTEM, "의도를 분석하는 중..."));
                isRunRequest = openAiService.classifyIsRunRequest(history, userText);
                metrics.counter("waste.chat.classify", "result", isRunRequest ? "yes" : "no", "source", "llm").increment();
            }

            String reply;
            SimulationConfig cfgToRun = null;
            SimulationConfig cfgToConfirm = null;

            if (isRunRequest) {
                // 3. 2단계 — 1단계가 yes일 때만 JSON 모드로 파라미터 추출
                messaging.convertAndSend("/topic/messages",
                        new ChatMessage(ChatMessage.MessageType.SYSTEM, "파라미터를 추출하는 중..."));
                SimulationConfig cfg = openAiService.extractParamsStrict(history, userText);

                if (cfg != null && OpenAiService.isValidCollectionTime(cfg.getCollectionTimeLabel())) {
                    // 두 단계가 모두 성공 + 형식 검증까지 통과 → 바로 실행
                    reply = String.format(
                            "수거 시각 %s(으)로 시뮬레이션을 실행하겠습니다. (%d일 × %d시드)",
                            cfg.getCollectionTimeLabel(), cfg.getDays(), cfg.getSeeds());
                    cfgToRun = cfg;
                } else if (cfg != null) {
                    // 시각 형식이 이상한 경우에 한해 확인 버블(안전망)
                    reply = "설정을 추출했지만 값을 확인해 주세요.";
                    cfgToConfirm = cfg;
                } else {
                    // 1단계는 yes였는데 2단계가 시각을 못 뽑은 모순 상황 — 재질문
                    reply = "수거 시각을 정확히 파악하지 못했습니다. 몇 시로 실행할지 알려주시겠어요?";
                }
            } else {
                // 실행 요청이 아님 — JSON 없이 순수 대화 답변만 생성
                reply = cleanReply(openAiService.answerPlain(history, userText));

                // 역할탈취(지시 강제) 후처리 필터 — 프롬프트 규칙만으로 못 막은
                // 마지막 안전망(실측으로 확인된 실패 패턴 대응).
                String safeOverride = JailbreakFilter.checkAndReplace(userText, reply);
                if (safeOverride != null) {
                    log.warn("역할탈취 공격 패턴 감지 — 후처리 필터로 응답 교체");
                    metrics.counter("waste.chat.jailbreak_blocked").increment();
                    reply = safeOverride;
                }
            }

            // 4. 대화 이력 업데이트 (최근 10쌍만 유지)
            history.add(Map.of("role", "user", "content", userText));
            history.add(Map.of("role", "assistant", "content", reply));
            while (history.size() > 20) history.remove(0);

            messaging.convertAndSend("/topic/messages", new ChatMessage(ChatMessage.MessageType.BOT, reply));

            // 5. 실행 또는 확인 버블
            if (cfgToRun != null) {
                pendingConfigs.remove(sessionId);
                runSimulation(cfgToRun);
            } else if (cfgToConfirm != null) {
                metrics.counter("waste.chat.confirm").increment();
                pendingConfigs.put(sessionId, cfgToConfirm);
                ChatMessage confirmMsg = new ChatMessage(ChatMessage.MessageType.CONFIRM,
                        String.format("이 설정으로 실행할까요? (수거시각 %s, %d일 × %d시드)",
                                cfgToConfirm.getCollectionTimeLabel(), cfgToConfirm.getDays(), cfgToConfirm.getSeeds()));
                confirmMsg.setSimulationConfig(cfgToConfirm);
                messaging.convertAndSend("/topic/messages", confirmMsg);
            }

        } catch (Exception e) {
            log.error("채팅 처리 오류", e);
            ChatMessage errMsg = new ChatMessage(ChatMessage.MessageType.BOT,
                    "오류가 발생했습니다: " + e.getMessage());
            messaging.convertAndSend("/topic/messages", errMsg);
        }
        } finally {
            MDC.remove("requestId");
        }
    }

    /** 확신도 낮아 보류된 설정을 사용자가 확인 버튼으로 승인했을 때 실행 */
    @MessageMapping("/chat.confirmRun")
    public void confirmRun() {
        String sessionId = "default";
        SimulationConfig cfg = pendingConfigs.remove(sessionId);
        if (cfg == null) {
            messaging.convertAndSend("/topic/messages",
                    new ChatMessage(ChatMessage.MessageType.SYSTEM, "실행할 대기 중인 설정이 없습니다."));
            return;
        }
        try {
            runSimulation(cfg);
        } catch (Exception e) {
            log.error("확인 후 시뮬레이션 실행 오류", e);
            messaging.convertAndSend("/topic/messages",
                    new ChatMessage(ChatMessage.MessageType.BOT, "실행 중 오류가 발생했습니다: " + e.getMessage()));
        }
    }

    @MessageMapping("/chat.clear")
    public void clearHistory() {
        histories.clear();
        pendingConfigs.clear();
        ChatMessage sysMsg = new ChatMessage(ChatMessage.MessageType.SYSTEM, "대화 이력이 초기화되었습니다.");
        messaging.convertAndSend("/topic/messages", sysMsg);
    }

    private void runSimulation(SimulationConfig cfg) {
        ChatMessage runningMsg = new ChatMessage(ChatMessage.MessageType.SYSTEM,
                String.format("시뮬레이션 실행 중... (수거시각: %s, %d일 × %d시드)",
                        cfg.getCollectionTimeLabel(), cfg.getDays(), cfg.getSeeds()));
        messaging.convertAndSend("/topic/messages", runningMsg);

        // MCP·REST와 동일한 검증 게이트를 통과(툴 파사드). 검증 실패면 실행하지 않는다.
        ToolResult tr = tool.runSimulation(cfg);
        if (!tr.ready()) {
            StringBuilder sb = new StringBuilder("설정을 실행할 수 없습니다:\n");
            for (ValidationError e : tr.errors()) sb.append("- ").append(e.message()).append("\n");
            messaging.convertAndSend("/topic/messages",
                    new ChatMessage(ChatMessage.MessageType.BOT, sb.toString().trim()));
            return;
        }

        SimulationResult result = (SimulationResult) tr.result();
        ChatMessage resultMsg = new ChatMessage(ChatMessage.MessageType.RESULT, formatResult(result));
        resultMsg.setSimulationResult(result);
        resultMsg.setSimulationConfig(cfg);
        messaging.convertAndSend("/topic/messages", resultMsg);
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
