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
import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.ExecutionIntentDetector;
import com.wastesim.service.JailbreakFilter;
import com.wastesim.service.LanguagePurityFilter;
import com.wastesim.service.OpenAiService;
import com.wastesim.service.RouteAwarenessDetector;
import com.wastesim.service.ScenarioIntentDetector;
import com.wastesim.service.TimeExpressionDetector;
import com.wastesim.service.TrafficDataService;
import com.wastesim.service.TrafficKeywordDetector;
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
    private final TrafficDataService trafficData;

    // 간단한 in-memory 대화 이력 (sessionId → 메시지 목록).
    //
    // DESIGN_DECISIONS.md D-05: 현재는 모든 클라이언트가 sessionId="default"
    // 하나를 공유한다(WebSocket 페이로드에 세션 구분자가 없음) — 즉 동시
    // 다중 사용자는 아직 지원하지 않으며, 한 사용자의 이력·대기 설정이 다른
    // 사용자에게 그대로 보일 수 있다는 게 현재 단계의 알려진 한계다. 향후
    // STOMP 세션별 sessionId를 페이로드에 실어 histories/pendingConfigs를
    // 분리하는 것이 로드맵.
    private final Map<String, List<Map<String, String>>> histories = new ConcurrentHashMap<>();
    // 확신도가 낮아 자동 실행을 보류한 설정 (sessionId → 대기 중인 설정)
    private final Map<String, SimulationConfig> pendingConfigs = new ConcurrentHashMap<>();
    // DESIGN_DECISIONS.md D-06(실행 중 재요청은 순차 처리) — 세션이 하나뿐이므로
    // (D-05) 전역 락 하나로 충분하다. 세션 분리 시 sessionId별 락으로 승격.
    private final Object sessionLock = new Object();

    public ChatController(SimpMessagingTemplate messaging,
                          OpenAiService openAiService,
                          SimulationTool tool,
                          MeterRegistry metrics,
                          TrafficDataService trafficData) {
        this.messaging = messaging;
        this.openAiService = openAiService;
        this.tool = tool;
        this.metrics = metrics;
        this.trafficData = trafficData;
    }

    @MessageMapping("/chat.send")
    public void handleMessage(@Payload ChatMessage incoming) {
        MDC.put("requestId", "ws-" + UUID.randomUUID().toString().substring(0, 8));
        try {
        // DESIGN_DECISIONS.md D-06: 실행 중 재요청은 큐잉/거절이 아니라 순차
        // 처리로 정했다 — 동시에 여러 메시지가 도착해도(예: STOMP 브로커의
        // 스레드풀이 여러 요청을 동시에 처리) histories/pendingConfigs를
        // 한 번에 하나의 요청만 읽고-쓰도록 세션 락으로 직렬화한다. 그렇지
        // 않으면 두 요청이 뒤섞여 "요청 A의 결과에 요청 B의 설정이 실린다"
        // 류의 경쟁 조건이 생길 수 있다.
        synchronized (sessionLock) {
        String sessionId = "default"; // 단일 채팅방
        List<Map<String, String>> history =
                histories.computeIfAbsent(sessionId, k -> new ArrayList<>());

        String userText = incoming.getContent();

        // 1. 사용자 메시지 echo
        ChatMessage userMsg = new ChatMessage(ChatMessage.MessageType.USER, userText);
        messaging.convertAndSend("/topic/messages", userMsg);

        try {
            // 1.5. 시나리오 실험 게이트(결정론, LLM 미사용) — 사이드바 "시나리오
            // 실험" 버튼 11종과 동일한 요청을 자연어로도 받는다
            // (ScenarioIntentDetector). 단일 실행(0/1단계)보다 먼저 검사한다 —
            // 시나리오 요청은 특정 시각 하나를 지정하는 게 아니라 여러 축을
            // sweep/비교하는 요청이라, 시각 개수 게이트와는 독립적인 판단이다.
            String scenarioType = ScenarioIntentDetector.detect(userText);
            if (scenarioType != null) {
                runChatScenario(scenarioType, history, userText);
                return;
            }

            // 2. 0단계 — 결정론적 시각 게이트(LLM 미사용). 베이스라인 제약 C2
            //    ("실행 여부 결정은 결정론적이고 LLM-free여야 한다")를 지키기
            //    위해, "이번 메시지에 파싱 가능한 시각이 정확히 1개 있는가"부터
            //    정규식으로 확정한다. 0개(시각 없음)나 2개 이상(순간값 조회 등)은
            //    이미 이 시점에 "실행 아님"이 확정되므로 이후 단계를 아예 밟지
            //    않는다 — 히스토리에서 시각을 끌어와 실행해버리는 부류의 실패가
            //    구조적으로 불가능해진다.
            //
            // 1단계 — 실행 의도 판정도 ExecutionIntentDetector로 결정론적으로
            // 처리한다. 원래는 여기서 LLM(temperature=0, yes/no)을 호출했지만,
            // 로컬 모델이 온도 0에서도 완전히 결정론적이지 않아 "교통 정체
            // 반영해서 방문 순서까지 지정한" 것처럼 조건절이 여러 개 겹친
            // 문장을 실측으로 반복 재현되는 빈도로 오분류했다(실행 요청인데
            // 결과가 아예 안 나오는 사용자 체감 버그) — C2 원칙을 이 단계까지
            // 확장해 LLM 의존을 완전히 제거함으로써 근본적으로 해결한다.
            int timeCount = TimeExpressionDetector.count(userText);
            boolean isRunRequest = timeCount == 1 && ExecutionIntentDetector.isExecutionRequest(userText);
            metrics.counter("waste.chat.classify", "result", isRunRequest ? "yes" : "no", "source", "deterministic").increment();

            String reply;
            SimulationConfig cfgToRun = null;
            SimulationConfig cfgToConfirm = null;

            if (isRunRequest) {
                // 3. 2단계 — 1단계가 yes일 때만 JSON 모드로 파라미터 추출
                messaging.convertAndSend("/topic/messages",
                        new ChatMessage(ChatMessage.MessageType.SYSTEM, "파라미터를 추출하는 중..."));
                SimulationConfig cfg = openAiService.extractParamsStrict(history, userText);

                // 결정론적 안전망: EXTRACTION_SYSTEM_PROMPT에 "이번 메시지에서
                // 새로 언급된 것만 반영하라"는 지시를 넣어도, 로컬 모델이
                // temperature>0에서 대화 히스토리에 낚여 이전 턴의 trafficEnabled·
                // truckType·routeSequence를 그대로 이어받는 경우가 실측으로 반복
                // 확인됐다(예: "소형 트럭으로 8시반 수거해줘"만 다시 보내도
                // 이전 턴의 trafficEnabled까지 같이 새어나옴). ExecutionIntentDetector와
                // 같은 이유로, 이 세 필드는 LLM 출력을 신뢰하지 않고 이번 메시지
                // 자체를 정규식으로 다시 판정해 완전히 덮어쓴다 — "이어받기"가
                // 구조적으로 불가능해진다.
                if (cfg != null) {
                    cfg.setTrafficEnabled(TrafficKeywordDetector.mentioned(userText));
                    if (!cfg.isTrafficEnabled()) {
                        cfg.setTrafficProfileId(null);
                    } else if (cfg.getTrafficProfileId() == null) {
                        cfg.setTrafficProfileId(trafficData.defaultProfileId());
                    }
                    if (!RouteAwarenessDetector.truckTypeMentioned(userText)) {
                        cfg.setTruckType("LARGE_5TON");
                    }
                    cfg.setRouteSequence(RouteAwarenessDetector.extractRouteSequence(userText));
                }
                // 건물 간 이동시간이 0이면(기본값, LLM이 잘 안 채워줌) 트럭 종류의
                // 기동성(mobilityFactor)이나 방문 순서(routeSequence)가 결과에
                // 반영될 물리적 여지 자체가 없다 — 이동에 걸리는 시간이 0이면
                // 트럭이 빠르든 느리든, 어떤 순서로 방문하든 도착 시각이 똑같기
                // 때문이다. 그래서 "소형 트럭으로 실행해줘"처럼 교통 정체는
                // 언급 안 해도 트럭 종류·경로를 명시했으면(=그 파라미터가
                // 결과에 영향을 주길 기대한 것), 결정론적으로 최소 이동시간을
                // 부여해 항상 체감 가능하게 한다(실측으로 확인된 동일 결과 버그).
                boolean routeAware = cfg != null && (cfg.isTrafficEnabled()
                        || cfg.getRouteSequence() != null
                        || !"LARGE_5TON".equals(cfg.getTruckType()));
                if (routeAware && cfg.getRouteTravelMinutes() <= 0) {
                    cfg.setRouteTravelMinutes(15);
                }

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

                // 언어 순수성 후처리 필터 — PLAIN_ANSWER_SYSTEM_PROMPT의 "가장
                // 중요, 최우선" 한국어 규칙도 로컬 모델이 100%는 못 지킨다
                // (실측: 답변 전체가 중국어로 나온 사례 확인).
                String langOverride = LanguagePurityFilter.checkAndReplace(reply);
                if (langOverride != null) {
                    log.warn("일반 답변 언어 순수성 위반 감지 — 후처리 필터로 응답 교체");
                    metrics.counter("waste.chat.language_blocked").increment();
                    reply = langOverride;
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
                runSimulation(cfgToRun, false);   // V-T5 등 비차단 경고가 있으면 확인부터 유도
            } else if (cfgToConfirm != null) {
                metrics.counter("waste.chat.confirm").increment();
                putPendingConfig(sessionId, cfgToConfirm);
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
        } // synchronized(sessionLock)
        } finally {
            MDC.remove("requestId");
        }
    }

    /** 확신도 낮아 보류된 설정을 사용자가 확인 버튼으로 승인했을 때 실행 */
    @MessageMapping("/chat.confirmRun")
    public void confirmRun() {
        synchronized (sessionLock) {   // D-06 — handleMessage와 동일 락으로 직렬화
            String sessionId = "default";
            SimulationConfig cfg = pendingConfigs.remove(sessionId);
            if (cfg == null) {
                messaging.convertAndSend("/topic/messages",
                        new ChatMessage(ChatMessage.MessageType.SYSTEM, "실행할 대기 중인 설정이 없습니다."));
                return;
            }
            try {
                runSimulation(cfg, true);   // 사용자가 이미 확인했으므로 경고 재확인 없이 강행
            } catch (Exception e) {
                log.error("확인 후 시뮬레이션 실행 오류", e);
                messaging.convertAndSend("/topic/messages",
                        new ChatMessage(ChatMessage.MessageType.BOT, "실행 중 오류가 발생했습니다: " + e.getMessage()));
            }
        }
    }

    @MessageMapping("/chat.clear")
    public void clearHistory() {
        synchronized (sessionLock) {   // D-06
            histories.clear();
            pendingConfigs.clear();
        }
        ChatMessage sysMsg = new ChatMessage(ChatMessage.MessageType.SYSTEM, "대화 이력이 초기화되었습니다.");
        messaging.convertAndSend("/topic/messages", sysMsg);
    }

    /**
     * ScenarioIntentDetector가 잡아낸 자연어 요청을 사이드바 "시나리오 실험"
     * 버튼과 동일한 경로(SimulationTool.runScenario)로 실행한다. 축별 세부
     * 파라미터(alphas/capacities 등)는 자연어에서 신뢰성 있게 뽑아낼 방법이
     * 없어(수치 나열을 강제로 파싱하면 LLM 없이는 오류 위험이 크다) MCP의
     * run_scenario 도구와 동일하게 각 시나리오의 기본 축 값을 그대로 쓴다 —
     * "어떤 실험을 돌릴지"는 결정론으로 확정하고, "축을 얼마나 세밀하게
     * 조정할지"는 필요하면 사이드바 버튼(또는 REST)에서 직접 조정하는 역할
     * 분담이다.
     */
    private void runChatScenario(String type, List<Map<String, String>> history, String userText) {
        messaging.convertAndSend("/topic/messages",
                new ChatMessage(ChatMessage.MessageType.SYSTEM, "시나리오 실행 중..."));

        SimulationConfig base = new SimulationConfig();
        base.setDays(30);
        base.setSeeds("monthly-waste".equals(type) ? 8 : 10);   // ScenarioController 기본값과 동일

        ToolResult tr = tool.runScenario(type, base);
        String reply;
        if (!tr.ready()) {
            StringBuilder sb = new StringBuilder("시나리오를 실행할 수 없습니다:\n");
            for (ValidationError e : tr.errors()) sb.append("- ").append(e.message()).append("\n");
            reply = sb.toString().trim();
            messaging.convertAndSend("/topic/messages", new ChatMessage(ChatMessage.MessageType.BOT, reply));
        } else {
            ScenarioResponse resp = (ScenarioResponse) tr.result();
            reply = "[" + resp.getTitle() + "] 시나리오 결과입니다.";
            ChatMessage scnMsg = new ChatMessage(ChatMessage.MessageType.SCENARIO, reply);
            scnMsg.setScenarioResponse(resp);
            scnMsg.setScenarioType(type);
            messaging.convertAndSend("/topic/messages", scnMsg);
        }

        history.add(Map.of("role", "user", "content", userText));
        history.add(Map.of("role", "assistant", "content", reply));
        while (history.size() > 20) history.remove(0);
    }

    /**
     * @param skipWarnings false면 비차단 경고(V-T5 교통 피크 등)가 있을 때
     *   바로 실행하지 않고 CONFIRM 버블로 사용자 확인을 유도한다
     *   (TRAFFIC_EXTENSION_DESIGN.md §7.2). true는 확인 후 강행 경로.
     */
    private void runSimulation(SimulationConfig cfg, boolean skipWarnings) {
        ChatMessage runningMsg = new ChatMessage(ChatMessage.MessageType.SYSTEM,
                String.format("시뮬레이션 실행 중... (수거시각: %s, %d일 × %d시드)",
                        cfg.getCollectionTimeLabel(), cfg.getDays(), cfg.getSeeds()));
        messaging.convertAndSend("/topic/messages", runningMsg);

        // MCP·REST와 동일한 검증 게이트를 통과(툴 파사드). 검증 실패면 실행하지 않는다.
        ToolResult tr = tool.runSimulation(cfg, skipWarnings);
        if (!tr.ready()) {
            StringBuilder sb = new StringBuilder("설정을 실행할 수 없습니다:\n");
            for (ValidationError e : tr.errors()) sb.append("- ").append(e.message()).append("\n");
            messaging.convertAndSend("/topic/messages",
                    new ChatMessage(ChatMessage.MessageType.BOT, sb.toString().trim()));
            return;
        }

        if (tr.needsConfirm()) {
            // 실행은 아직 안 함 — 경고 사유를 브리핑하고 확인 버블로 유도.
            // LLM은 이 브리핑 문구를 만들지 않는다(결정론적 경고 메시지를 그대로
            // 전달) — C1/C2 준수: 대안 제안은 서버가 계산한 사실이지 LLM 생성물이 아니다.
            metrics.counter("waste.chat.needs_confirm").increment();
            putPendingConfig("default", cfg);
            StringBuilder sb = new StringBuilder("바로 실행하지 않고 확인을 요청드립니다:\n");
            for (ValidationError w : tr.warnings()) sb.append("- ").append(w.message()).append("\n");
            ChatMessage confirmMsg = new ChatMessage(ChatMessage.MessageType.CONFIRM, sb.toString().trim());
            confirmMsg.setSimulationConfig(cfg);
            messaging.convertAndSend("/topic/messages", confirmMsg);
            return;
        }

        SimulationResult result = (SimulationResult) tr.result();
        ChatMessage resultMsg = new ChatMessage(ChatMessage.MessageType.RESULT, formatResult(result));
        resultMsg.setSimulationResult(result);
        resultMsg.setSimulationConfig(cfg);
        messaging.convertAndSend("/topic/messages", resultMsg);
    }

    /**
     * DESIGN_DECISIONS.md D-04: 이미 확인 대기 중인 설정이 있는 상태에서 새
     * 확인-대기 요청이 오면, 최신 요청으로 덮어쓰되(원래도 Map.put이 그렇게
     * 동작했다) 이전 요청이 조용히 사라지지 않도록 폐기 사실을 먼저 알린다
     * — 안 그러면 사용자가 "아니오"로 첫 요청을 취소한 줄 알고 있다가,
     * 나중에 "예"를 누르면 자신이 잊고 있던 두 번째 요청이 실행되는 혼란이
     * 생길 수 있다.
     */
    private void putPendingConfig(String sessionId, SimulationConfig cfg) {
        SimulationConfig discarded = pendingConfigs.put(sessionId, cfg);
        if (discarded != null) {
            messaging.convertAndSend("/topic/messages", new ChatMessage(ChatMessage.MessageType.SYSTEM,
                    String.format("이전에 확인을 기다리던 설정(수거시각 %s)은 새 요청으로 대체되어 폐기되었습니다.",
                            discarded.getCollectionTimeLabel())));
        }
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
