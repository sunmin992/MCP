package com.wastesim.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.edge.EdgeChatFormatter;
import com.wastesim.edge.EdgeParamGuard;
import com.wastesim.edge.EdgeThermalProfileStore;
import com.wastesim.edge.HeatsinkPresets;
import com.wastesim.mcp.McpDomain;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.mcp.McpToolRegistry;
import com.wastesim.model.ChatMessage;
import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.TrafficProfile;
import com.wastesim.model.TripMetric;
import com.wastesim.model.TruckType;
import com.wastesim.service.DomainIntentDetector;
import com.wastesim.service.EdgeToolSelector;
import com.wastesim.service.EngineSelectionDetector;
import com.wastesim.service.ExecutionIntentDetector;
import com.wastesim.service.JailbreakFilter;
import com.wastesim.service.KoreanTimeParser;
import com.wastesim.service.LanguagePurityFilter;
import com.wastesim.service.OpenAiService;
import com.wastesim.service.RouteAwarenessDetector;
import com.wastesim.service.RouteDurationEstimator;
import com.wastesim.service.RouteDurationQueryDetector;
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
    /** 장량동과 무관한 독립 도구(라즈베리파이 엣지 발열 3종)를 이름으로 찾기 위한 레지스트리. */
    private final McpToolRegistry independentTools;
    private final EdgeThermalProfileStore edgeProfiles;
    private final com.fasterxml.jackson.databind.ObjectMapper edgeMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

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
    // 위 pendingConfigs와 짝을 이루는 모델 선택(sessionId → modelId). null이면
    // 기본 모델(Java 엔진). confirmRun()에서 확인 시 이 값으로 재실행한다
    // (EngineSelectionDetector — 어떤 엔진으로 실행할지도 D-03처럼 이번 메시지
    // 기준으로만 판정하고, 확인 대기 동안에는 그 판정을 그대로 들고 있는다).
    private final Map<String, String> pendingModelIds = new ConcurrentHashMap<>();
    // 세션이 마지막으로 확정한 도메인(sessionId → Domain). 클라이언트가 domain을
    // 실어 보내지 않는 경우에만 쓰이는 폴백이다 — 브라우저 UI는 항상 실어 보내므로
    // 여기에 의존하지 않지만, 스크립트나 예전 클라이언트에서 도메인 어휘가 없는
    // 후속 메시지("12시에 실행해줘")가 계속 되물음에 걸리는 걸 막는다.
    private final Map<String, DomainIntentDetector.Domain> sessionDomains = new ConcurrentHashMap<>();
    // DESIGN_DECISIONS.md D-06(실행 중 재요청은 순차 처리) — 세션이 하나뿐이므로
    // (D-05) 전역 락 하나로 충분하다. 세션 분리 시 sessionId별 락으로 승격.
    private final Object sessionLock = new Object();

    /** 팬 유무 비교에서 "있음" 쪽으로 쓸 회전수 — 도구의 fanRatedRpm 기본값과 같아야 한다. */
    private static final double DEFAULT_FAN_RATED_RPM = 5000.0;

    public ChatController(SimpMessagingTemplate messaging,
                          OpenAiService openAiService,
                          SimulationTool tool,
                          MeterRegistry metrics,
                          TrafficDataService trafficData,
                          McpToolRegistry independentTools,
                          EdgeThermalProfileStore edgeProfiles) {
        this.messaging = messaging;
        this.openAiService = openAiService;
        this.tool = tool;
        this.metrics = metrics;
        this.trafficData = trafficData;
        this.independentTools = independentTools;
        this.edgeProfiles = edgeProfiles;
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

        // 1.0단계 — 도메인 확정. echo보다 먼저 하는 이유: 확정 결과를 echo 메시지에
        // 실어 보내야 클라이언트가 답변이 도착하기 전에 사이드바·URL을 전환할 수
        // 있다. 나중에 실으면 화면이 장량동인 채로 엣지 답변이 먼저 그려진다.
        //
        // 클라이언트가 도메인을 실어 보냈으면(= 사용자가 시작화면에서 골랐거나
        // 이미 /waste·/edge 화면에 있음) 그 값이 키워드 추측을 이긴다.
        DomainIntentDetector.Domain domain = resolveDomain(sessionId, incoming.getDomain(), userText);

        // 1. 사용자 메시지 echo
        ChatMessage userMsg = new ChatMessage(ChatMessage.MessageType.USER, userText);
        userMsg.setDomain(slugOf(domain));
        messaging.convertAndSend("/topic/messages", userMsg);

        try {
            // 1.4단계 — 도메인 게이트(결정론, LLM 미사용). 이 MCP 서버는 장량동
            // 쓰레기 모델과 라즈베리파이 엣지 발열 모델을 함께 들고 있고, 사용자
            // 요청이 둘 중 어디로 가야 하는지를 여기서 확정한다.
            //
            // 아래 장량동 게이트들보다 먼저 검사하는 이유: 엣지 요청에도 "배치",
            // "비교" 같은 단어가 흔히 섞여 시나리오 게이트에 잘못 걸릴 수 있다.
            // 반대 방향의 오탐(장량동 요청이 엣지로 새는 것)은 DomainIntentDetector가
            // 양쪽 키워드 수를 비교해 막는다 — 엣지 키워드가 더 많을 때만 전환하므로
            // 기존 장량동 대화는 이 게이트가 없던 때와 완전히 동일하게 동작한다.
            //
            // 도메인 자체는 위 1.0단계에서 이미 확정했다(echo에 실어 보내야 해서).
            if (domain == DomainIntentDetector.Domain.UNKNOWN) {
                // 도메인 중립 시작화면에서 단서 없는 첫 메시지 — 장량동으로 흘려보내지
                // 않고 되묻는다. 여기서 폴백하면 사용자가 고르지도 않은 도메인의
                // 시뮬레이터에 갇힌다(DomainIntentDetector#classify 주석 참고).
                askWhichDomain(history, userText);
                return;
            }
            if (domain == DomainIntentDetector.Domain.EDGE_THERMAL) {
                runEdgeTool(userText, history);
                return;
            }

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

            // 1.6단계 — 경로 소요시간 질의 게이트(결정론, LLM 미사용). "Node_A,
            // Node_C, Node_B, Node_D 순서로 방문하면 얼마나 걸려?"류의 질문은
            // 민원 집계까지 도는 전체 시뮬레이션이 아니라, 방문 순서(+선택
            // 수거시각)만으로 이동시간 근사값을 계산해 답한다
            // (RouteDurationEstimator). 시나리오 게이트와 마찬가지로 아래의
            // 시각-개수 게이트(0/1단계)보다 먼저 검사한다 — 이 질의는 수거
            // 시각이 아예 없어도(그러면 혼잡 가중치만 미반영) 답할 수 있어서,
            // "시각 정확히 1개"라는 실행 게이트 기준과는 독립적인 판단이다.
            List<String> routeSeqForDuration = RouteAwarenessDetector.extractRouteSequence(userText);
            if (RouteDurationQueryDetector.isRouteDurationQuery(userText, routeSeqForDuration)) {
                answerRouteDuration(routeSeqForDuration, userText, history);
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

            // 1.7단계 — 엔진(모델) 선택도 결정론적으로 판정한다(EngineSelectionDetector,
            // C2 원칙 동일 적용). null이면 기본 모델(Java 엔진, 하위호환).
            String modelId = EngineSelectionDetector.detect(userText);
            if (modelId != null) {
                metrics.counter("waste.chat.engine_selected", "model", modelId).increment();
            }

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
                pendingModelIds.remove(sessionId);
                runSimulation(cfgToRun, modelId, false);   // V-T5 등 비차단 경고가 있으면 확인부터 유도
            } else if (cfgToConfirm != null) {
                metrics.counter("waste.chat.confirm").increment();
                putPendingConfig(sessionId, cfgToConfirm, modelId);
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
            String modelId = pendingModelIds.remove(sessionId);
            if (cfg == null) {
                messaging.convertAndSend("/topic/messages",
                        new ChatMessage(ChatMessage.MessageType.SYSTEM, "실행할 대기 중인 설정이 없습니다."));
                return;
            }
            try {
                runSimulation(cfg, modelId, true);   // 사용자가 이미 확인했으므로 경고 재확인 없이 강행
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
            pendingModelIds.clear();
            // 기억해 둔 도메인도 함께 지운다 — 대화를 초기화했는데 이전 도메인이
            // 남아 있으면, 클라이언트가 도메인을 안 보내는 경우 "새 대화"가 예전
            // 도메인으로 조용히 이어진다.
            sessionDomains.clear();
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
    /**
     * 라즈베리파이 엣지 발열 도메인 요청을 처리한다 — MCP {@code tools/call}과 <b>같은
     * 도구 구현체</b>({@link McpToolProvider})를 그대로 호출한다. 채팅용 계산 경로를 따로
     * 두지 않는 것이 중요하다: 그러면 "채팅으로 물었을 때와 MCP로 불렀을 때 답이 다른"
     * 상황이 원천적으로 생기지 않는다(SimulationTool 파사드를 세 진입점이 공유하는 것과
     * 같은 이유).
     *
     * <p>인자를 만드는 순서가 이 메서드의 핵심이다.
     * <ol>
     *   <li>{@link EdgeToolSelector} — 어느 도구인지 결정론적으로 확정(LLM 아님)</li>
     *   <li>{@link OpenAiService#extractEdgeParams} — GPT가 숫자·enum을 JSON으로 추출</li>
     *   <li>{@link EdgeParamGuard} — 실험 조건을 좌우하는 필드는 이번 메시지 기준으로 덮어씀</li>
     *   <li>도구의 자체 검증({@code EdgeArgs}) — 범위 밖이면 실행 없이 거부(fail-closed)</li>
     * </ol>
     */
    /**
     * 이번 메시지를 어느 도메인으로 보낼지 확정한다.
     *
     * <p><b>클라이언트가 실어 보낸 도메인이 항상 이긴다.</b> 사용자가 시작화면에서
     * 카드를 고르거나 {@code /waste}·{@code /edge} 화면에 들어와 있으면 화면 자체가
     * 명시적 선언이므로, 서버가 문장 어휘로 그 선택을 뒤집으면 안 된다 — 엣지 화면에서
     * "수거 트럭 영상 추론할 때 발열"처럼 양쪽 어휘가 섞인 문장을 쳤을 때 화면과 다른
     * 도메인의 답이 돌아오면 사용자는 방금 한 선택이 무시됐다고 느낀다.
     *
     * <p>도메인이 없을 때(= 루트 시작화면의 첫 메시지)만 키워드로 판정하며, 이때는
     * 장량동 폴백이 없는 {@link DomainIntentDetector#classify}를 쓴다.
     *
     * @param clientDomain 클라이언트가 보낸 슬러그({@code "waste"}/{@code "edge"}), 없으면 {@code null}
     * @return 세 값 중 하나. {@code null}은 반환하지 않는다.
     */
    private DomainIntentDetector.Domain resolveDomain(String sessionId, String clientDomain, String userText) {
        McpDomain declared = McpDomain.fromSlug(clientDomain);
        if (declared == McpDomain.EDGE) return DomainIntentDetector.Domain.EDGE_THERMAL;
        if (declared == McpDomain.WASTE) return DomainIntentDetector.Domain.WASTE_SIM;

        DomainIntentDetector.Domain guess = DomainIntentDetector.classify(userText);
        if (guess != DomainIntentDetector.Domain.UNKNOWN) {
            sessionDomains.put(sessionId, guess);
            return guess;
        }
        // 단서가 없는 문장이라도 대화가 이미 한 도메인에서 진행 중이면 그 도메인을
        // 이어간다. 이게 없으면 "12시에 실행해줘"처럼 도메인 어휘가 하나도 없는
        // 후속 메시지마다 되묻게 되어, 도메인을 안 실어 보내는 클라이언트에서는
        // 대화가 진행되지 않는다. 되묻는 건 정말 처음부터 아무 단서도 없을 때만이다.
        DomainIntentDetector.Domain remembered = sessionDomains.get(sessionId);
        return remembered != null ? remembered : DomainIntentDetector.Domain.UNKNOWN;
    }

    /** 도메인 → 클라이언트가 URL·사이드바 전환에 쓰는 슬러그. UNKNOWN이면 {@code null}. */
    private String slugOf(DomainIntentDetector.Domain domain) {
        if (domain == DomainIntentDetector.Domain.EDGE_THERMAL) return McpDomain.EDGE.slug();
        if (domain == DomainIntentDetector.Domain.WASTE_SIM) return McpDomain.WASTE.slug();
        return null;
    }

    /**
     * 도메인을 판정할 단서가 전혀 없는 첫 메시지에 되묻는다 — 추측해서 한쪽으로
     * 보내지 않는다.
     *
     * <p>이 프로젝트가 실행 판정에서 지켜온 태도(C2 — 근거가 확실할 때만 실행)를
     * 도메인 선택에도 그대로 적용한 것이다. "안녕하세요"를 장량동 시뮬레이터로
     * 흘려보내면 사용자는 고르지도 않은 도메인의 사이드바를 마주하게 된다.
     */
    private void askWhichDomain(List<Map<String, String>> history, String userText) {
        metrics.counter("waste.chat.domain", "result", "unknown").increment();
        StringBuilder sb = new StringBuilder("어느 시뮬레이션을 도와드릴까요?\n\n");
        for (McpDomain d : McpDomain.values()) {
            sb.append("- **").append(d.label()).append("** — ").append(d.description()).append("\n");
        }
        sb.append("\n위 카드를 고르거나, 하고 싶은 실험을 한 문장으로 적어 주세요.");
        reply(sb.toString(), userText, history);
    }

    private void runEdgeTool(String userText, List<Map<String, String>> history) {
        String toolName = EdgeToolSelector.select(userText);
        metrics.counter("waste.chat.edge_tool", "tool", toolName).increment();

        // 캘리브레이션은 측정 시계열이 있어야 하는데 채팅 메시지로는 실어 나를 수 없다.
        // 실행하는 척하는 대신 보내는 방법을 알려주고, 저장된 프로파일을 보여준다.
        if (EdgeToolSelector.TOOL_CALIBRATE.equals(toolName)) {
            reply(EdgeChatFormatter.calibrationGuide(edgeProfiles.all()), userText, history);
            return;
        }

        McpToolProvider provider = independentTools.byToolName(toolName);
        if (provider == null) {
            reply("엣지 발열 도구가 이 서버에 등록돼 있지 않습니다.", userText, history);
            return;
        }

        messaging.convertAndSend("/topic/messages",
                new ChatMessage(ChatMessage.MessageType.SYSTEM, "엣지 발열 모델 실행 중..."));

        ObjectNode args = EdgeParamGuard.merge(
                openAiService.extractEdgeParams(history, userText), EdgeParamGuard.fromText(userText));

        // 팬 속도 스윕은 결과가 지표 한 벌이 아니라 곡선이라 비교 분기(보드·재질·팬 유무)를
        // 타지 않는다 — 스윕 자체가 이미 한 축(회전수)을 훑는 비교다.
        if (EdgeToolSelector.TOOL_SWEEP.equals(toolName)) {
            runEdgeFanSweep(provider, args, userText, history);
            return;
        }

        // 보드를 둘 다 언급했으면 비교 요청이다 — 한쪽만 골라 한 번 돌리면 사용자가
        // 물어본 것에 대한 답이 아니다. 나머지 조건을 그대로 둔 채 board만 바꿔 두 번
        // 실행한다(runEdgeComparison).
        //
        // 발열 시뮬레이션 도구에만 적용한다. 방열판 도구는 결과가 지표 한 벌이 아니라
        // 후보 순위표라, 두 보드 것을 같은 표에 나란히 놓으면 "1위 후보"가 두 개인
        // 읽을 수 없는 표가 된다 — 그 비교는 별도 포맷이 필요하고 지금은 지원하지 않는다.
        if (EdgeToolSelector.TOOL_THROTTLING.equals(toolName)
                && EdgeParamGuard.isBoardComparison(userText)) {
            runEdgeComparison(provider, args, userText, history);
            return;
        }
        // 재질을 둘 다 언급했으면 같은 이유로 두 번 실행한다 — 질량이 같아도 비열이
        // 달라 열용량이 달라지므로(2노드), 한쪽만 골라 돌리면 물어본 비교가 아니다.
        if (EdgeToolSelector.TOOL_THROTTLING.equals(toolName)
                && EdgeParamGuard.isMaterialComparison(userText)) {
            runEdgeMaterialComparison(provider, args, userText, history);
            return;
        }
        // 팬 유무도 마찬가지다 — 팬은 열저항을 낮추는 대신 전력을 쓰므로, 유무를 나란히
        // 놓아야 "얼마를 더 써서 몇 도를 벌었는가"라는 트레이드오프가 드러난다.
        if (EdgeToolSelector.TOOL_THROTTLING.equals(toolName)
                && EdgeParamGuard.isFanComparison(userText)) {
            runEdgeFanComparison(provider, args, userText, history);
            return;
        }
        // "방열판 없이 팬만"은 프리셋에 없는 조합이라 그대로 돌리면 무냉각 값이 나오고
        // 팬을 켜든 끄든 같은 결과가 된다 — 조용히 돌리면 사용자는 그게 팬의 효과라고
        // 읽는다. 무엇이 안 되는지와 대신 물을 수 있는 조건을 알려준다.
        if (EdgeToolSelector.TOOL_THROTTLING.equals(toolName)
                && EdgeParamGuard.isUnsupportedCoolingCombo(userText)) {
            reply("방열판 없이 팬만 다는 조건은 아직 계산할 수 없습니다.\n\n"
                + "보드의 냉각 기준값이 무냉각 / 방열판 / 방열판+팬 세 가지로만 보정돼 있어서, "
                + "방열판 없이 팬만 부는 경우의 열저항 기준이 없습니다. 그대로 돌리면 무냉각 값이 나와 "
                + "팬을 켜든 끄든 같은 결과가 됩니다.\n\n"
                + "대신 이렇게 물어보실 수 있습니다:\n"
                + "- \"팬 있을 때와 없을 때 비교해줘\" — 방열판을 단 상태에서 팬만 켜고 끈 비교\n"
                + "- \"무냉각으로 돌려줘\" — 방열판도 팬도 없는 기준선\n\n"
                + "방열판 없이 팬만 부는 조건이 꼭 필요하면 그 조건을 실측해서 "
                + "\"실측 데이터로 모델 보정해줘\"로 열저항을 넣어 주셔야 합니다.",
                userText, history);
            return;
        }
        if (!args.has("board")) {
            reply("어느 보드인지 알려주세요 — 라즈베리파이 4와 5는 발열 특성이 많이 다릅니다.",
                    userText, history);
            return;
        }
        // 회복 실험인데 조건이 비어 있으면 스로틀링이 실제로 일어나는 표준 조건을 채운다.
        // 무엇을 가정했는지는 아래에서 답변 앞에 명시한다(조용히 바꾸지 않는다).
        boolean assumed = EdgeParamGuard.applyRecoveryExperimentDefaults(args);

        if (EdgeToolSelector.TOOL_HEATSINK.equals(toolName)) {
            // 후보 치수까지 자연어로 받지 않고 서버가 들고 있는 표준 후보로 비교한다
            // (HeatsinkPresets — 값이 고정이라 언제 돌려도 같은 표가 나온다).
            try {
                args.set("layouts", edgeMapper.readTree(HeatsinkPresets.LAYOUTS_JSON));
            } catch (Exception e) {
                log.error("방열판 프리셋 파싱 실패", e);
                reply("방열판 후보를 준비하지 못했습니다: " + e.getMessage(), userText, history);
                return;
            }
        }

        ToolResult tr = provider.call(args);
        if (!tr.ready()) {
            StringBuilder sb = new StringBuilder("요청한 조건으로는 실행할 수 없습니다:\n");
            for (ValidationError e : tr.errors()) sb.append("- ").append(e.message()).append("\n");
            reply(sb.toString().trim(), userText, history);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tr.result();
        String text = EdgeToolSelector.TOOL_HEATSINK.equals(toolName)
                ? EdgeChatFormatter.heatsink(out)
                : EdgeChatFormatter.throttling(out);
        if (assumed) {
            text = "회복 시간을 재려면 스로틀링이 실제로 걸리는 조건이어야 해서, "
                 + "무냉각·최대 처리량으로 가정하고 실행했습니다.\n\n" + text;
        }
        // 배치 비교는 결과가 후보 순위표라 구성도(보드+방열판+팬)로 그릴 대상이 아니다 —
        // 발열 시뮬레이션만 구조화해서 보낸다.
        if (EdgeToolSelector.TOOL_HEATSINK.equals(toolName)) {
            reply(text, userText, history);
        } else {
            replyEdge(text, List.of(out), userText, history);
        }
    }

    /**
     * 보드 비교 — 같은 도구를 {@code board}만 바꿔 두 번 호출하고 결과를 나란히 보여준다.
     *
     * <p><b>왜 인자를 복제하는가</b>: 비교가 성립하려면 보드 외의 모든 조건(냉각·주변온도·
     * 워크로드·부하시간)이 완전히 같아야 한다. 같은 {@code ObjectNode}를 재사용하면 첫 실행에서
     * 도구가 채운 기본값이 두 번째 실행에 섞여 들어가 "무엇을 통제하고 무엇을 바꿨는지"가
     * 흐려진다 — R&E에서는 그게 실험이 아니라 그냥 두 번 돌린 게 된다. 그래서 매번
     * {@code deepCopy()}로 같은 출발점에서 시작한다.
     *
     * <p>비교 축을 보드로 고정한 것은 이 도구의 입력 중 <b>결과를 가장 크게 가르는 축</b>이기
     * 때문이다(Pi5는 동적 소비전력이 Pi4의 2배 이상이라 같은 냉각에서도 거동이 완전히 다르다).
     * 냉각·주변온도 비교가 필요해지면 이 메서드의 축만 바꿔 일반화하면 된다.
     */
    private void runEdgeComparison(McpToolProvider provider, ObjectNode baseArgs,
                                   String userText, List<Map<String, String>> history) {
        metrics.counter("waste.chat.edge_tool", "tool", "board_comparison").increment();

        List<Map<String, Object>> results = new ArrayList<>();
        for (String board : List.of("pi4", "pi5")) {
            ObjectNode args = baseArgs.deepCopy();
            args.put("board", board);
            ToolResult tr = provider.call(args);
            if (!tr.ready()) {
                StringBuilder sb = new StringBuilder("요청한 조건으로는 실행할 수 없습니다:\n");
                for (ValidationError e : tr.errors()) sb.append("- ").append(e.message()).append("\n");
                reply(sb.toString().trim(), userText, history);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) tr.result();
            results.add(out);
        }
        replyEdge(EdgeChatFormatter.boardComparison(results), results, userText, history);
    }

    /**
     * 재질만 바꿔 두 번 실행한다(알루미늄 → 구리 순서). 보드 비교와 구조가 같고
     * 바꾸는 필드만 다르다 — 같은 질량이라도 비열 차이로 열용량이 달라지므로
     * 2노드 모델에서 과도응답이 갈린다.
     */
    private void runEdgeMaterialComparison(McpToolProvider provider, ObjectNode baseArgs,
                                           String userText, List<Map<String, String>> history) {
        metrics.counter("waste.chat.edge_tool", "tool", "material_comparison").increment();

        List<Map<String, Object>> results = new ArrayList<>();
        for (String material : List.of("aluminum", "copper")) {
            ObjectNode args = baseArgs.deepCopy();
            args.put("heatsinkMaterial", material);
            ToolResult tr = provider.call(args);
            if (!tr.ready()) {
                StringBuilder sb = new StringBuilder("요청한 조건으로는 실행할 수 없습니다:\n");
                for (ValidationError e : tr.errors()) sb.append("- ").append(e.message()).append("\n");
                reply(sb.toString().trim(), userText, history);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) tr.result();
            results.add(out);
        }
        replyEdge(EdgeChatFormatter.materialComparison(results), results, userText, history);
    }

    /**
     * 팬을 끈 상태(0 RPM)와 정격으로 두 번 실행한다.
     *
     * <p>냉각 조건을 방열판(passive)으로 고정하는 이유: 팬 모델이 회전수에서 열저항을
     * 직접 계산하므로, "팬"이라는 단어 때문에 냉각 조건이 active로 잡히면 0 RPM 실행에
     * "팬 냉각"이라는 라벨이 붙어 표가 스스로 모순된다. 실제 열저항은 어차피 회전수가
     * 정하므로 라벨만 정직하게 맞춘다.
     */
    private void runEdgeFanComparison(McpToolProvider provider, ObjectNode baseArgs,
                                      String userText, List<Map<String, String>> history) {
        metrics.counter("waste.chat.edge_tool", "tool", "fan_comparison").increment();

        double ratedRpm = baseArgs.path("fanRatedRpm").asDouble(DEFAULT_FAN_RATED_RPM);
        List<Map<String, Object>> results = new ArrayList<>();
        for (double rpm : List.of(0.0, ratedRpm)) {
            ObjectNode args = baseArgs.deepCopy();
            args.put("cooling", "passive");
            args.put("fanRpm", rpm);
            args.put("fanRatedRpm", ratedRpm);
            ToolResult tr = provider.call(args);
            if (!tr.ready()) {
                StringBuilder sb = new StringBuilder("요청한 조건으로는 실행할 수 없습니다:\n");
                for (ValidationError e : tr.errors()) sb.append("- ").append(e.message()).append("\n");
                reply(sb.toString().trim(), userText, history);
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> out = (Map<String, Object>) tr.result();
            results.add(out);
        }
        replyEdge(EdgeChatFormatter.fanComparison(results), results, userText, history);
    }

    /**
     * 팬 속도 스윕 — 회전수를 훑어 "제약을 지키면서 에너지가 가장 적게 드는 운전점"을 찾는다.
     *
     * <p>비교 실행들(보드·재질·팬 유무)과 달리 <b>도구 한 번</b>으로 끝난다. 반복은 서버가
     * 여기서 하는 것이 아니라 도구 안에서 하는데, 그래야 채팅으로 물으나 MCP로 부르나 같은
     * 지점·같은 제약·같은 최적점이 나온다(계산 경로를 두 벌로 만들지 않는다는 원칙).
     */
    private void runEdgeFanSweep(McpToolProvider provider, ObjectNode args,
                                 String userText, List<Map<String, String>> history) {
        metrics.counter("waste.chat.edge_tool", "tool", "fan_sweep").increment();

        if (!args.has("board")) {
            reply("어느 보드인지 알려주세요 — 라즈베리파이 4와 5는 발열 특성이 많이 달라서 최적 팬 속도도 달라집니다.",
                    userText, history);
            return;
        }
        ToolResult tr = provider.call(args);
        if (!tr.ready()) {
            StringBuilder sb = new StringBuilder("요청한 조건으로는 실행할 수 없습니다:\n");
            for (ValidationError e : tr.errors()) sb.append("- ").append(e.message()).append("\n");
            reply(sb.toString().trim(), userText, history);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) tr.result();

        String text = EdgeChatFormatter.fanSweep(out);
        ChatMessage msg = new ChatMessage(ChatMessage.MessageType.EDGE_SWEEP, text);
        msg.setEdgeSweep(out);
        msg.setDomain("edge");
        messaging.convertAndSend("/topic/messages", msg);
        history.add(Map.of("role", "user", "content", userText));
        history.add(Map.of("role", "assistant", "content", text));
        while (history.size() > 20) history.remove(0);
    }

    /** 봇 답변 전송 + 대화 이력 갱신(엣지 경로 공통) — runChatScenario 말미와 같은 처리. */
    /**
     * 엣지 결과를 <b>텍스트와 구조화된 원본을 함께</b> 보낸다.
     *
     * <p>화면이 보드·방열판·팬 구성을 그리고 지표를 표로 정리하려면 값이 필요한데,
     * 클라이언트가 포매터 문장을 다시 파싱하게 하면 문구를 고칠 때마다 조용히 깨진다.
     * 그래서 사람이 읽는 텍스트는 그대로 두고 원본을 곁들인다 — 렌더러가 없는
     * 클라이언트는 {@code content}만 읽어도 지금과 똑같이 동작한다.
     *
     * @param runs 도구 결과 원본. 비교 실행은 둘, 단일 실행은 하나짜리 목록
     */
    private void replyEdge(String text, List<Map<String, Object>> runs,
                           String userText, List<Map<String, String>> history) {
        ChatMessage msg = new ChatMessage(ChatMessage.MessageType.EDGE_RESULT, text);
        msg.setEdgeRuns(runs);
        msg.setDomain("edge");
        messaging.convertAndSend("/topic/messages", msg);
        history.add(Map.of("role", "user", "content", userText));
        history.add(Map.of("role", "assistant", "content", text));
        while (history.size() > 20) history.remove(0);
    }

    private void reply(String text, String userText, List<Map<String, String>> history) {
        messaging.convertAndSend("/topic/messages", new ChatMessage(ChatMessage.MessageType.BOT, text));
        history.add(Map.of("role", "user", "content", userText));
        history.add(Map.of("role", "assistant", "content", text));
        while (history.size() > 20) history.remove(0);
    }

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
     * 경로 소요시간 질의에 답한다 — 전체 시뮬레이션(SimulationTool.runSimulation)을
     * 거치지 않고 RouteDurationEstimator로 이동시간만 계산한다. 방문 순서가
     * 바뀌거나(routeSequence) 수거 시각이 달라지면(KoreanTimeParser로 파싱)
     * 그에 맞춰 결과도 달라진다 — 순서가 바뀌면 각 구간의 도착 노드가 바뀌어
     * 그 노드의 시간대별 혼잡 가중치가 달라지고, 시각이 바뀌면 구간마다
     * 적용되는 혼잡 가중치 자체가 달라진다.
     */
    private void answerRouteDuration(List<String> routeSequence, String userText,
                                      List<Map<String, String>> history) {
        Integer startMinute = KoreanTimeParser.parseFirst(userText);
        boolean trafficMentioned = TrafficKeywordDetector.mentioned(userText);
        // 혼잡 가중치는 "교통/정체"를 명시했거나 수거 시각을 알 때만 적용한다.
        // 시각이 없으면 몇 시 기준 가중치인지 정의할 수 없어 기준 이동시간만 쓴다.
        TrafficProfile profile = (trafficMentioned || startMinute != null)
                ? trafficData.find(trafficData.defaultProfileId()) : null;
        TruckType truckType = RouteAwarenessDetector.truckTypeMentioned(userText)
                ? guessTruckType(userText) : TruckType.LARGE_5TON;

        String reply;
        try {
            RouteDurationEstimator.Estimate est = RouteDurationEstimator.estimate(
                    routeSequence, startMinute, RouteDurationEstimator.DEFAULT_ROUTE_TRAVEL_MINUTES,
                    truckType, profile);
            reply = formatRouteDuration(routeSequence, startMinute, truckType, est);
        } catch (IllegalArgumentException e) {
            reply = "방문 순서를 인식하지 못했습니다: " + e.getMessage();
        }

        messaging.convertAndSend("/topic/messages", new ChatMessage(ChatMessage.MessageType.BOT, reply));

        history.add(Map.of("role", "user", "content", userText));
        history.add(Map.of("role", "assistant", "content", reply));
        while (history.size() > 20) history.remove(0);
    }

    /** 메시지에 언급된 차종 키워드로 TruckType을 결정론적으로 판정. 언급 없으면 기본 대형(5톤). */
    private TruckType guessTruckType(String text) {
        if (text.contains("소형") || text.contains("1톤")) return TruckType.SMALL_1TON;
        if (text.contains("중형") || text.contains("2.5톤") || text.contains("2톤")) return TruckType.MEDIUM_2P5T;
        return TruckType.LARGE_5TON;
    }

    private String formatRouteDuration(List<String> route, Integer startMinute, TruckType truckType,
                                        RouteDurationEstimator.Estimate est) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("경로 소요시간(근사) — %s\n", String.join(" → ", route)));
        if (startMinute != null) {
            sb.append(String.format("출발(수거) 시각: %s, 차종: %s\n",
                    KoreanTimeParser.toHHMM(startMinute), truckType.labelKo));
        } else {
            sb.append(String.format("차종: %s (수거 시각 미지정 — 혼잡 가중치 미반영, 구간당 기준 이동시간만 적용)\n",
                    truckType.labelKo));
        }
        for (RouteDurationEstimator.Hop h : est.hops) {
            String weightNote = est.trafficApplied
                    ? String.format(" (혼잡 가중치 ×%.2f%s)", h.congestionWeight, h.red ? ", 정체 심함" : "")
                    : "";
            sb.append(String.format("- %s → %s: %d분%s\n", h.from, h.to, h.minutes, weightNote));
        }
        sb.append(String.format("총 이동시간: 약 %d분", est.totalMinutes));
        if (est.endMinuteOfDay != null) {
            sb.append(String.format(" (도착 예상 %s)", KoreanTimeParser.toHHMM(est.endMinuteOfDay)));
        }
        sb.append("\n\n※ 근사값입니다 — 구간별 실제 거리·도로 데이터가 아니라, 기본 이동시간(")
                .append(RouteDurationEstimator.DEFAULT_ROUTE_TRAVEL_MINUTES)
                .append("분/구간)에 차종 기동성과 시간대별 혼잡 가중치만 곱한 시뮬레이션 추정치입니다.");
        return sb.toString();
    }

    /**
     * @param modelId 실행할 모델(EngineSelectionDetector 판정 결과). {@code null}이면
     *   기본 모델(Java 엔진) — 하위호환(기존 호출부·테스트 그대로 동작).
     * @param skipWarnings false면 비차단 경고(V-T5 교통 피크 등)가 있을 때
     *   바로 실행하지 않고 CONFIRM 버블로 사용자 확인을 유도한다
     *   (TRAFFIC_EXTENSION_DESIGN.md §7.2). true는 확인 후 강행 경로.
     */
    private void runSimulation(SimulationConfig cfg, String modelId, boolean skipWarnings) {
        boolean pythonEngine = EngineSelectionDetector.PYTHON_MODEL_ID.equals(modelId);
        ChatMessage runningMsg = new ChatMessage(ChatMessage.MessageType.SYSTEM,
                String.format("시뮬레이션 실행 중... (수거시각: %s, %d일 × %d시드%s)",
                        cfg.getCollectionTimeLabel(), cfg.getDays(), cfg.getSeeds(),
                        pythonEngine ? ", Python 엔진" : ""));
        messaging.convertAndSend("/topic/messages", runningMsg);

        // MCP·REST와 동일한 검증 게이트를 통과(툴 파사드). 검증 실패면 실행하지 않는다.
        // 모델과 무관하게 항상 같은 검증(SimulationConfigValidator)을 거친다
        // (MCP_모델_연결_방법.md §3.4) — SimulationTool의 3-인자 오버로드가
        // modelId==null이면 SimulationModelRegistry.DEFAULT_MODEL_ID로 처리한다.
        ToolResult tr = tool.runSimulation(cfg, modelId, skipWarnings);
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
            putPendingConfig("default", cfg, modelId);
            StringBuilder sb = new StringBuilder("바로 실행하지 않고 확인을 요청드립니다:\n");
            for (ValidationError w : tr.warnings()) sb.append("- ").append(w.message()).append("\n");
            ChatMessage confirmMsg = new ChatMessage(ChatMessage.MessageType.CONFIRM, sb.toString().trim());
            confirmMsg.setSimulationConfig(cfg);
            messaging.convertAndSend("/topic/messages", confirmMsg);
            return;
        }

        // Java 엔진은 ToolResult.result()가 이미 SimulationResult이고, Python
        // 엔진(PythonWasteSimAdapter)은 필드명을 억지로 맞추지 않은 원본
        // JsonNode를 그대로 감싸 돌려준다(MCP 클라이언트가 어느 엔진인지 그대로
        // 구분하게 하려는 의도적 설계). 채팅 렌더링은 두 엔진을 구분 없이
        // 보여줘야 하므로, 여기서만 JsonNode -> SimulationResult로 변환한다.
        Object raw = tr.result();
        SimulationResult result = (raw instanceof SimulationResult sr)
                ? sr
                : toSimulationResult((JsonNode) raw, cfg);
        ChatMessage resultMsg = new ChatMessage(ChatMessage.MessageType.RESULT,
                formatResult(result, pythonEngine ? "Python(pyevsim) 참조 엔진" : null));
        resultMsg.setSimulationResult(result);
        resultMsg.setSimulationConfig(cfg);
        messaging.convertAndSend("/topic/messages", resultMsg);
    }

    /** Python 엔진(mcp_bridge.py)의 집계 결과 JSON을 채팅 렌더링용 SimulationResult로 변환. */
    private SimulationResult toSimulationResult(JsonNode node, SimulationConfig cfg) {
        SimulationResult r = new SimulationResult();
        r.setCollectionTimeLabel(node.path("collectionTime").asText(cfg.getCollectionTimeLabel()));
        r.setMeanComplaints(node.path("totalComplaintsMean").asDouble());
        r.setStdComplaints(node.path("totalComplaintsStd").asDouble());
        r.setPeakFillKg(node.path("peakFillKgMax").asDouble());
        r.setAvgCompletionMinutes(node.path("avgCompletionMinutesMean").asDouble());
        List<Integer> totals = new ArrayList<>();
        for (JsonNode n : node.path("allTotals")) totals.add(n.asInt());
        r.setAllTotals(totals);
        Map<String, Object> byOcc = new LinkedHashMap<>();
        node.path("byOccupationMean").fields()
                .forEachRemaining(e -> byOcc.put(e.getKey(), e.getValue().asDouble()));
        r.setByOccupationSummary(byOcc);
        r.setSimulationConfig(cfg);
        return r;
    }

    /**
     * DESIGN_DECISIONS.md D-04: 이미 확인 대기 중인 설정이 있는 상태에서 새
     * 확인-대기 요청이 오면, 최신 요청으로 덮어쓰되(원래도 Map.put이 그렇게
     * 동작했다) 이전 요청이 조용히 사라지지 않도록 폐기 사실을 먼저 알린다
     * — 안 그러면 사용자가 "아니오"로 첫 요청을 취소한 줄 알고 있다가,
     * 나중에 "예"를 누르면 자신이 잊고 있던 두 번째 요청이 실행되는 혼란이
     * 생길 수 있다.
     *
     * @param modelId 이 확인-대기 설정을 나중에 실행할 모델. pendingConfigs와
     *   짝을 맞춰 pendingModelIds에도 함께 저장한다.
     */
    private void putPendingConfig(String sessionId, SimulationConfig cfg, String modelId) {
        SimulationConfig discarded = pendingConfigs.put(sessionId, cfg);
        // ConcurrentHashMap은 null 값을 허용하지 않는다 — modelId==null(기본 모델,
        // 가장 흔한 경우)은 그냥 항목을 지워서 "없음=기본 모델"로 표현한다.
        // 이전 요청이 특정 엔진(예: python-devs)을 대기 중이었는데 이번 요청이
        // 엔진을 언급하지 않았다면, 그 낡은 엔진 지정이 새 설정에 잘못
        // 적용되지 않도록 반드시 지워야 한다.
        if (modelId != null) {
            pendingModelIds.put(sessionId, modelId);
        } else {
            pendingModelIds.remove(sessionId);
        }
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

    private String formatResult(SimulationResult r, String engineLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append(engineLabel == null
                ? String.format(" 시뮬레이션 결과 (수거시각: %s)\n", r.getCollectionTimeLabel())
                : String.format(" 시뮬레이션 결과 (수거시각: %s, %s)\n", r.getCollectionTimeLabel(), engineLabel));
        sb.append(String.format("- 월간 평균 민원: %.1f건 (±%.1f)\n",
                r.getMeanComplaints(), r.getStdComplaints()));
        // 트럭 용량 지표 — 발생량이 집계된 경우에만 표시(TRUCK_CAPACITY_ENHANCEMENT_PLAN.md).
        if (r.getGeneratedWasteKg() > 0) {
            sb.append(String.format("- 수거/잔류: 수거 %.1fkg · 잔류 %.1fkg (트럭 이용률 %.1f%%)\n",
                    r.getCollectedWasteKg(), r.getResidualWasteKg(), r.getTruckUtilizationPercent()));
            // 용량 부족 진단은 실제로 부분수거·미수거가 있었을 때만 알린다(§3.3).
            if (r.getPartialPickupCount() > 0 || r.getUnservedPickupCount() > 0) {
                sb.append(String.format("- 용량 부족: 부분수거 %d회 · 미수거 %d회 · 미수거 수요 %.1fkg\n",
                        r.getPartialPickupCount(), r.getUnservedPickupCount(), r.getUncollectedDemandKg()));
            }
            appendTruckRollup(sb, r.getTripMetrics());
            if (r.getMaxResidualBuilding() != null && r.getMaxResidualBuildingKg() > 0) {
                sb.append(String.format("- 최대 잔류 건물: %s %.1fkg\n",
                        r.getMaxResidualBuilding(), r.getMaxResidualBuildingKg()));
            }
        }
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

    /**
     * 트럭별 운행 롤업(§3.4) — 병목 트럭 식별용. 운행 지표를 truckId로 묶어 운행 횟수·
     * 총 수거량·부분수거 발생 운행 수를 요약한다. 운행이 없으면 아무것도 덧붙이지 않는다.
     */
    private void appendTruckRollup(StringBuilder sb, List<TripMetric> trips) {
        if (trips == null || trips.isEmpty()) return;
        // truckId → [수거량 합, 운행 수, 부분수거 운행 수]
        Map<String, double[]> byTruck = new LinkedHashMap<>();
        for (TripMetric t : trips) {
            double[] agg = byTruck.computeIfAbsent(t.truckId(), k -> new double[3]);
            agg[0] += t.collectedKg();
            agg[1] += 1;
            if (t.partialPickupCount() > 0) agg[2] += 1;
        }
        sb.append("- 트럭별 운행:\n");
        byTruck.forEach((truck, agg) -> sb.append(String.format(
                "  %s: 운행 %d회 · 수거 %.1fkg%s\n",
                truck, (int) agg[1], agg[0],
                agg[2] > 0 ? String.format(" · 부분수거 %d회", (int) agg[2]) : "")));
    }
}
