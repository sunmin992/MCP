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

import com.wastesim.model.ChatMessage;
import com.wastesim.model.ScenarioResponse;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.model.TrafficProfile;
import com.wastesim.model.TripMetric;
import com.wastesim.model.TruckType;
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
import com.wastesim.service.SimulatorCreationDetector;
import com.wastesim.service.TimeExpressionDetector;
import com.wastesim.service.TrafficDataService;
import com.wastesim.service.TrafficKeywordDetector;
import com.wastesim.subtask.JangnyangScenarioSpec;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskSession;
import com.wastesim.subtask.SubtaskSessionService;
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
    /** v1.13 고정 서브태스크 수집 계층(SDD 2.18.9). 세션·검증·조립은 전부 이 서비스가 소유한다. */
    private final SubtaskSessionService subtasks;
    /** 혼잡 가중치를 "그 지점이 속한 교통 구역"으로 찾기 위한 매핑(SimulationEngine과 같은 기준). */
    private final com.wastesim.site.CollectionSiteRegistry sites;

    /**
     * 자유 문장에서 설계도를 채우는 경로. {@code null}이면 기존 문항 흐름만 쓴다 —
     * 이 컨트롤러를 수집 계층 없이 만드는 호출부가 있어서 선택적으로 둔다.
     */
    private final com.wastesim.llm.BlueprintComposer composer;

    /**
     * LLM 경로를 쓸 것인가.
     *
     * <p>되돌릴 스위치 없이 켜 두지 않는다. LLM이 값을 잘못 뽑으면 문항을 처음부터 묻던
     * 예전보다 나빠질 수 있고, 그때 배포를 되돌리는 것 말고 방법이 없으면 곤란하다.
     * 꺼지면 해석기를 <b>호출조차 하지 않는다</b> — 끈 기능이 비용을 쓰면 안 된다.
     */
    private final boolean blueprintEnabled;

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
    // DESIGN_DECISIONS.md D-06(실행 중 재요청은 순차 처리) — 세션이 하나뿐이므로
    // (D-05) 전역 락 하나로 충분하다. 세션 분리 시 sessionId별 락으로 승격.
    private final Object sessionLock = new Object();

    /** 이 서버가 다루는 유일한 도메인. 클라이언트가 화면을 맞추는 데 쓴다. */
    private static final String WASTE_DOMAIN = "waste";


    /**
     * LLM 경로 없이 만드는 생성자. 자유 문장 해석을 쓰지 않고 <b>기존 문항 흐름만</b> 돈다.
     *
     * <p>이 경로를 남겨 두는 이유는 기존 테스트들이 검증하는 성질이 "생성 요청이 아닌 문장은
     * 수집 계층을 건드리지 않는다"이기 때문이다 — 거기에 해석기를 끼우면 그 테스트들이
     * 무엇을 보고 있는지가 흐려진다. 스프링은 아래 {@code @Autowired} 생성자를 쓴다.
     */
    public ChatController(SimpMessagingTemplate messaging,
                          OpenAiService openAiService,
                          SimulationTool tool,
                          MeterRegistry metrics,
                          TrafficDataService trafficData,
                          SubtaskSessionService subtasks,
                          com.wastesim.site.CollectionSiteRegistry sites) {
        this(messaging, openAiService, tool, metrics, trafficData, subtasks, sites, null, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ChatController(SimpMessagingTemplate messaging,
                          OpenAiService openAiService,
                          SimulationTool tool,
                          MeterRegistry metrics,
                          TrafficDataService trafficData,
                          SubtaskSessionService subtasks,
                          com.wastesim.site.CollectionSiteRegistry sites,
                          com.wastesim.llm.BlueprintComposer composer,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${wastesim.llm.blueprint.enabled:true}") boolean blueprintEnabled) {
        this.messaging = messaging;
        this.openAiService = openAiService;
        this.tool = tool;
        this.metrics = metrics;
        this.trafficData = trafficData;
        this.subtasks = subtasks;
        this.sites = sites;
        this.composer = composer;
        this.blueprintEnabled = blueprintEnabled;
    }

    @MessageMapping("/chat.send")
    public void handleMessage(@Payload ChatMessage incoming,
                              java.security.Principal principal,
                              org.springframework.messaging.simp.stomp.StompHeaderAccessor headers) {
        // D-49 — 수집 세션 키는 연결 단위다. 대화 이력·대기 설정은 종전대로 "default"를
        // 쓰지만(기존 동작을 바꾸지 않는다), 서브태스크 수집만은 STOMP 세션 ID로 가른다.
        // 두 사용자가 각자 열 개 넘는 질문에 답하는 중에 답변이 섞이면 두 사람 모두의
        // 실험이 조용히 망가지고, 그 사실은 결과를 다 받은 뒤에야 드러난다(NFR-18).
        subtaskKey.set(resolveSubtaskKey(principal, headers));
        try {
            handleMessageInternal(incoming);
        } finally {
            subtaskKey.remove();
        }
    }

    /**
     * 이번 요청의 수집 세션 키. 인증이 있으면 사용자 ID로 승격하고, 없으면 STOMP 세션
     * ID를 쓴다(D-49). 둘 다 없는 경로(단위 테스트 등)에서는 {@code "default"}로 떨어지되,
     * 그것이 <b>폴백</b>이라는 사실이 이 메서드에 남아 있게 한다.
     */
    private static String resolveSubtaskKey(java.security.Principal principal,
                                            org.springframework.messaging.simp.stomp.StompHeaderAccessor headers) {
        if (principal != null && principal.getName() != null) return "user:" + principal.getName();
        if (headers != null && headers.getSessionId() != null) return "ws:" + headers.getSessionId();
        return "default";
    }

    /** 현재 스레드가 처리 중인 요청의 수집 세션 키. STOMP 처리는 요청당 한 스레드다. */
    private final ThreadLocal<String> subtaskKey = ThreadLocal.withInitial(() -> "default");

    /**
     * 세션 키 없이 한 메시지를 처리한다 — STOMP 헤더가 없는 호출부(단위 테스트, 스크립트)용.
     * 수집 세션 키는 {@code "default"}로 떨어지므로, 이 경로로는 세션 격리(NFR-18)를
     * 검증할 수 없다는 점이 이 오버로드에 남아 있어야 한다.
     */
    public void handleMessage(ChatMessage incoming) {
        handleMessage(incoming, null, null);
    }

    private void handleMessageInternal(ChatMessage incoming) {
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

        // 사용자 메시지 echo. 도메인 판정 단계는 없다 — 이 서버는 장량동 하나만
        // 다루므로 "어느 모델로 보낼 것인가"라는 질문 자체가 성립하지 않는다.
        // 라즈베리파이 엣지 도메인이 함께 있던 시절의 도메인 게이트·되묻기·슬러그
        // 전환은 전부 걷어냈다(엣지 분리).
        ChatMessage userMsg = new ChatMessage(ChatMessage.MessageType.USER, userText);
        userMsg.setDomain(WASTE_DOMAIN);
        messaging.convertAndSend("/topic/messages", userMsg);

        try {
            // 1.45단계 — 고정 서브태스크 수집 계층(v1.13, SDD 2.18.9). 아래의 <b>모든</b>
            // 장량동 게이트보다 먼저 본다.
            //
            // 진행 중인 세션 확인이 먼저인 이유: 수집 중에 사용자가 보내는 문장은 답변이지
            // 새 요청이 아니다. "8시 30분"이라는 답변이 아래의 시각·실행 의도 게이트에
            // 걸려 즉시 실행으로 새면 수집이 그 자리에서 중단되고, 사용자는 자기가 답한
            // 질문의 결과가 아니라 엉뚱한 실행 결과를 받는다(IT-86).
            JangnyangSubtaskSession active = subtasks.activeSession(subtaskKey.get());
            if (active != null) {
                handleSubtaskAnswer(active, userText, incoming.getCurrentSubtaskId(), history);
                return;
            }
            // 생성 요청 판별(FR-119)은 즉시 실행 판정과 <b>다른</b> 판정기다 — 값이 이미
            // 문장에 있는 요청과, 값을 모으는 것부터 시작해야 하는 요청은 묻는 것이 다르다.
            if (SimulatorCreationDetector.isCreationRequest(userText)) {
                startSubtaskCollection(userText, history);
                return;
            }

            // 명시된 복수 시각 비교는 단일 실행 게이트(timeCount == 1)로 보내면
            // 일반 답변으로 탈락한다. "10시와 11시에 각각 수거"처럼 비교 의도가
            // 분명한 경우에는 두 시각을 같은 기본 조건으로 직접 실행한다.
            List<Integer> comparisonTimes = KoreanTimeParser.parseAllDistinct(userText);
            boolean collectionComparisonIntent = userText.matches("(?s).*수거.*")
                    && userText.matches("(?s).*(각각|비교|대조|어느|차이).*");
            if (KoreanTimeParser.hasInvalidTimeExpression(userText)) {
                reply("수거 시각 형식이 올바르지 않습니다. 00:00~23:59 범위에서 다시 입력해 주세요."
                        + " 예: 10:00과 11:00 비교", userText, history);
                return;
            }
            if (collectionComparisonIntent && TimeExpressionDetector.count(userText) >= 2) {
                if (comparisonTimes.size() < 2) {
                    reply("서로 다른 수거 시각을 두 개 이상 입력해 주세요. 같은 시각을 반복한 값은 비교할 수 없습니다.",
                            userText, history);
                } else {
                    runCollectionTimeComparison(comparisonTimes, history, userText);
                }
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

    // ── v1.13 고정 서브태스크 수집 계층 (SDD 2.18.9) ──────────────────────────

    /**
     * 생성 요청(FR-119)을 받아 수집을 시작하고 첫 질문을 보낸다.
     *
     * <p>질문 문장은 카탈로그의 것을 <b>그대로</b> 싣는다 — LLM이 만든 텍스트가 아니다
     * (FR-122·D-44). 이 메서드에 LLM 호출이 없다는 사실이 그 보장의 근거다.
     */
    private void startSubtaskCollection(String userText, List<Map<String, String>> history) {
        metrics.counter("waste.chat.subtask", "event", "start").increment();

        // 스위치가 꺼져 있거나 조립기가 없으면 예전 그대로 — 해석기를 부르지도 않는다.
        if (!blueprintEnabled || composer == null) {
            SubtaskSessionService.Step step = subtasks.start(subtaskKey.get());
            sendSubtaskQuestion(step, "시뮬레이터를 구성하겠습니다. 필요한 값을 순서대로 여쭤보겠습니다.",
                    userText, history);
            return;
        }

        com.wastesim.llm.BlueprintComposer.Outcome outcome =
                composer.compose(subtaskKey.get(), userText);

        // 만들 수 없는 요청은 수집을 시작하지 않는다. 시작해 버리면 사용자가 34문항을
        // 다 답한 뒤에야 자기 요청이 애초에 불가능했다는 것을 알게 된다.
        if (!outcome.verdict().feasible()) {
            metrics.counter("waste.chat.subtask", "event", "refused").increment();
            reply(refusalText(outcome.verdict()), userText, history);
            return;
        }

        SubtaskSessionService.Step step = subtasks.currentStep(subtaskKey.get());
        if (outcome.usedFallback()) {
            // 조용히 문항으로 넘어가지 않는다 — 아무 말 없이 34문항이 시작되면 사용자는
            // 자기 문장이 무시된 줄 모른다.
            metrics.counter("waste.chat.subtask", "event", "fallback").increment();
            sendSubtaskQuestion(step,
                    outcome.fallbackNotice() + " 필요한 값을 순서대로 여쭤보겠습니다.",
                    userText, history);
            return;
        }
        sendSubtaskQuestion(step, composedIntro(outcome), userText, history);
    }

    /**
     * 요청에서 무엇을 읽었고 무엇이 남았는지 알린다.
     *
     * <p>읽어낸 값을 말하지 않으면 사용자는 자기 문장이 반영됐는지 알 수 없고, 남은 개수를
     * 말하지 않으면 얼마나 더 답해야 하는지 알 수 없다.
     */
    private static String composedIntro(com.wastesim.llm.BlueprintComposer.Outcome outcome) {
        int filled = outcome.appliedDefaults().size();
        int asking = outcome.mustAsk().size();
        StringBuilder sb = new StringBuilder("시뮬레이터를 구성하겠습니다.");
        if (filled > 0) {
            sb.append(" 요청과 기본값에서 ").append(filled).append("개를 채웠습니다.");
        }
        sb.append(" 남은 ").append(asking).append("개를 여쭤보겠습니다.");
        if (!outcome.unverifiedFields().isEmpty()) {
            sb.append(" (기본값 중 ").append(outcome.unverifiedFields().size())
              .append("개는 출처를 확인하지 않은 값입니다)");
        }
        return sb.toString();
    }

    /**
     * 거부 사유와 <b>무엇이 있으면 되는지</b>를 함께 낸다.
     *
     * <p>자동으로 얻을 수 있는 것과 사람이 채워야 하는 것을 갈라 보여 준다 — 후자를 감추면
     * 전부 자동으로 될 것처럼 읽힌다.
     */
    private static String refusalText(com.wastesim.llm.FeasibilityVerdict verdict) {
        StringBuilder sb = new StringBuilder(verdict.message());
        List<com.wastesim.llm.FeasibilityVerdict.Missing> needs = verdict.whatWouldBeNeeded();
        if (needs.isEmpty()) return sb.toString();

        sb.append("\n\n필요한 것:");
        for (com.wastesim.llm.FeasibilityVerdict.Missing m : needs) {
            sb.append("\n· ").append(m.item())
              .append(m.obtainable() ? " — 자동 수집 가능" : " — 사람이 채워야 함");
            if (m.note() != null && !m.note().isBlank()) sb.append(": ").append(m.note());
        }
        return sb.toString();
    }

    /**
     * 수집 중에 온 메시지를 현재 서브태스크의 답변으로 처리한다.
     *
     * <p>흐름은 정규화(LLM) → 검증(서버) → 다음 질문 또는 재질문이다. 두 단계를 합치지
     * 않는 것이 요점이다(D-46) — 합치면 "LLM이 통과라고 했으니 통과"가 되어 fail-closed가
     * 무너진다.
     */
    private void handleSubtaskAnswer(JangnyangSubtaskSession session, String userText,
                                     String answeredSubtaskId,
                                     List<Map<String, String>> history) {
        // BUILT 상태에서 오는 문장은 답변이 아니라 승인·수정 요청이다.
        if (session.state() == com.wastesim.subtask.SubtaskState.BUILT) {
            if (userText != null && userText.matches("(?s).*(취소|그만|초기화|다시\\s*시작).*")) {
                cancelSubtaskCollection();
                return;
            }
            reply("시나리오 미리보기를 확인하고 실행 버튼을 눌러 주세요. 조건을 바꾸려면 '취소'라고 알려 주세요.",
                    userText, history);
            return;
        }
        if (userText != null && userText.matches("(?s).*(수집\\s*)?(취소|그만할래|그만둘래|초기화).*")) {
            cancelSubtaskCollection();
            return;
        }

        // 이 답변이 <b>어느 질문에 대한 것인지</b>는 클라이언트가 실어 보낸 ID를 우선한다.
        //
        // 세션의 "지금 물어보는 항목"을 그대로 쓰면 안 되는 이유: STOMP 인바운드 채널은
        // 스레드풀이라 같은 연결에서 온 두 메시지가 <b>도착 순서대로 처리된다는 보장이
        // 없다</b>. 세션 락은 동시 실행만 막을 뿐 순서를 세우지는 않는다. 두 답변의 순서가
        // 뒤집히면 N번째 답이 N번째 질문이 아닌 곳에 들어가고, 그 값이 그 필드에서 우연히
        // 유효하면(예: 시각 자리에 LLM이 지어낸 00:00) 오류 없이 조용히 어긋난 채 끝까지
        // 간다 — 실측으로 재현된 결함이다.
        //
        // 서버가 보낸 SUBTASK 메시지에 이미 currentSubtaskId가 실려 있으므로, 클라이언트가
        // 그것을 되돌려 보내면 답변은 항상 자기 질문을 찾아간다. 순서가 뒤집혀도 값이
        // 엉뚱한 칸에 들어가지 않고, 이미 답한 항목으로 오면 그건 수정이다(2.18.10의
        // "이전 답변 확인·수정"과 같은 경로).
        JangnyangSubtask current = resolveAnsweredSubtask(session, answeredSubtaskId);
        Object value = userText;
        // "해당 없음"은 정규화할 것이 없다. LLM에 보내면 목록형 질문에서는 ["해당 없음"]으로
        // 감싸 돌려주고, 그러면 형식 검증에 걸려 사용자가 빠져나갈 수 없는 질문에 갇힌다.
        // 값이 아니라 "답하지 않기로 했다"는 표시이므로 그대로 검증기에 넘긴다.
        boolean skipsNormalization =
                com.wastesim.subtask.JangnyangSubtaskValidator.isNotApplicable(userText);
        if (current != null && !skipsNormalization) {
            // 정규화는 지정된 필드 하나만 채운다(FR-125). 실패하면 원문을 그대로
            // 검증기에 넘긴다 — LLM 백엔드가 죽어도 수집이 멈추지 않아야 한다(NFR-05·UT-332).
            Object normalized = openAiService.normalizeToField(
                    current.answerField(), SubtaskSessionService.fieldSpecFor(current), userText);
            if (normalized != null) value = normalized;
        }

        SubtaskSessionService.Step step = subtasks.submit(
                subtaskKey.get(), current == null ? null : current.id(), value, session.version());
        if (!step.ok()) {
            reply(step.rejection(), userText, history);
            return;
        }

        if (step.readyToBuild()) {
            // 충분성 판정은 서버가 한다 — LLM이 "이제 충분합니다"라고 해도 이 분기에
            // 도달하지 못한다(FR-123·UT-331).
            buildAndPreview(userText, history);
            return;
        }
        String lead = step.errors().isEmpty() ? null : "입력을 다시 확인해 주세요.";
        sendSubtaskQuestion(step, lead, userText, history);
    }

    /** 수집이 끝나 시나리오를 조립하고 미리보기를 보낸다. 승인 전에는 엔진을 부르지 않는다(FR-133). */
    private void buildAndPreview(String userText, List<Map<String, String>> history) {
        SubtaskSessionService.BuildStep build = subtasks.build(subtaskKey.get());
        if (!build.ok()) {
            // 조립이 거부되면 재질문 문장을 그대로 다시 보낸다.
            reply(build.message(), userText, history);
            SubtaskSessionService.Step step = subtasks.submit(subtaskKey.get(), null, null, null);
            if (step.ok() && step.question() != null) sendSubtaskQuestion(step, null, userText, history);
            return;
        }
        metrics.counter("waste.chat.subtask", "event", "preview").increment();
        JangnyangScenarioSpec spec = build.spec();
        ChatMessage msg = new ChatMessage(ChatMessage.MessageType.PREVIEW, spec.previewText());
        msg.setDomain("waste");
        msg.setSubtaskSetId(spec.subtaskSetId());
        msg.setSubtaskVersion(spec.version());
        msg.setScenarioPreview(spec.toPreviewMap());
        msg.setScenarioType(spec.scenarioType());
        msg.setSimulationConfig(spec.toSimulationConfig());
        msg.setProgress(1.0);
        messaging.convertAndSend("/topic/messages", msg);
        appendHistory(history, userText, spec.previewText());
    }

    /**
     * 미리보기 승인 — 여기서 처음으로 엔진이 호출된다(FR-133·134).
     *
     * <p>실행은 기존 도구를 그대로 부른다. 새 실행 경로를 만들지 않는 이유는, 만들면
     * 기존 검증 게이트(SimulationTool 파사드)를 우회하는 두 번째 문이 생기기 때문이다.
     */
    @MessageMapping("/chat.subtaskRun")
    public void runSubtaskScenario(java.security.Principal principal,
                                   org.springframework.messaging.simp.stomp.StompHeaderAccessor headers) {
        subtaskKey.set(resolveSubtaskKey(principal, headers));
        try {
            synchronized (sessionLock) {
                // 미리보기 화면이 확인 단계 셋을 대신한다 — 승인하는 순간 ST-048·049·050이
                // 함께 기록된다. 화면에는 실행 승인만 보이지만 세트의 50개는 그대로 채워진다.
                subtasks.recordConfirmations(subtaskKey.get(), "RUN");
                JangnyangScenarioSpec spec = subtasks.approveRun(subtaskKey.get());
                if (spec == null) {
                    // BUILT가 아닌 세션의 실행 요청은 거부한다(FR-129·D-52·UT-317).
                    messaging.convertAndSend("/topic/messages", new ChatMessage(
                            ChatMessage.MessageType.SYSTEM,
                            "실행할 수 있는 시나리오가 없습니다. 구성을 먼저 마쳐 주세요."));
                    return;
                }
                boolean ok = false;
                try {
                    ok = executeSpec(spec);
                } catch (Exception e) {
                    log.error("서브태스크 시나리오 실행 오류", e);
                    messaging.convertAndSend("/topic/messages", new ChatMessage(
                            ChatMessage.MessageType.BOT, "실행 중 오류가 발생했습니다: " + e.getMessage()));
                } finally {
                    subtasks.finishRun(subtaskKey.get(), ok);
                }
            }
        } finally {
            subtaskKey.remove();
        }
    }

    /** 수집 취소·초기화(FR-129). 누적 답변까지 지워 다음 시작이 깨끗하게 한다. */
    @MessageMapping("/chat.subtaskCancel")
    public void cancelSubtask(java.security.Principal principal,
                              org.springframework.messaging.simp.stomp.StompHeaderAccessor headers) {
        subtaskKey.set(resolveSubtaskKey(principal, headers));
        try {
            cancelSubtaskCollection();
        } finally {
            subtaskKey.remove();
        }
    }

    private void cancelSubtaskCollection() {
        subtasks.recordConfirmations(subtaskKey.get(), "CANCEL");
        subtasks.cancel(subtaskKey.get());
        metrics.counter("waste.chat.subtask", "event", "cancel").increment();
        ChatMessage msg = new ChatMessage(ChatMessage.MessageType.SYSTEM,
                "시뮬레이터 구성을 취소했습니다. 누적된 답변은 모두 지웠습니다.");
        msg.setDomain("waste");
        messaging.convertAndSend("/topic/messages", msg);
    }

    /**
     * 조립된 명세를 <b>기존</b> 실행 경로로 돌린다(FR-134).
     *
     * @return 결과를 정상적으로 냈으면 true
     */
    private boolean executeSpec(JangnyangScenarioSpec spec) {
        SimulationConfig cfg = spec.toSimulationConfig();
        messaging.convertAndSend("/topic/messages", new ChatMessage(ChatMessage.MessageType.SYSTEM,
                "구성한 시나리오를 실행하는 중... (" + spec.scenarioType() + ", " + spec.toolName() + ")"));

        if (spec.isSingleRun()) {
            runSimulation(cfg, spec.engineId(), true);   // 미리보기에서 이미 승인받았다
            return true;
        }
        ToolResult tr = tool.runScenario(spec.scenarioType(), cfg);
        if (!tr.ready()) {
            StringBuilder sb = new StringBuilder("시나리오를 실행할 수 없습니다:\n");
            appendBullets(sb, tr.errors());
            messaging.convertAndSend("/topic/messages",
                    new ChatMessage(ChatMessage.MessageType.BOT, sb.toString().trim()));
            return false;
        }
        ScenarioResponse resp = (ScenarioResponse) tr.result();
        ChatMessage msg = new ChatMessage(ChatMessage.MessageType.SCENARIO,
                "[" + resp.getTitle() + "] 시나리오 결과입니다.");
        msg.setScenarioResponse(resp);
        msg.setScenarioType(spec.scenarioType());
        // 실행 조건·가정을 결과와 함께 싣는다(FR-131·135·D-53) — 조건 없이 결과만 남으면
        // 사용자는 자기 실험의 전제를 모른 채 숫자를 읽는다.
        msg.setScenarioPreview(spec.toPreviewMap());
        msg.setSubtaskSetId(spec.subtaskSetId());
        msg.setSubtaskVersion(spec.version());
        messaging.convertAndSend("/topic/messages", msg);
        return true;
    }

    /** 지금 답해야 할 서브태스크. 없으면 {@code null}. */
    private JangnyangSubtask currentSubtaskOf(JangnyangSubtaskSession session) {
        return session.nextSubtask(subtasks.definitionOf(session), subtasks.checker());
    }

    /**
     * 이번 답변이 대상으로 하는 서브태스크를 정한다.
     *
     * <p>클라이언트가 ID를 실어 보냈고 그것이 이 세션의 세트에 있는 항목이면 그 항목이다.
     * 없거나(자유 입력창으로 친 답변) 세트에 없는 ID면 세션이 지금 묻고 있는 항목으로
     * 되돌아간다 — 알 수 없는 ID를 들고 실패시키면, 채팅창에 그냥 답을 친 사용자가
     * 이유 없이 거부당한다. ID의 유효성 자체는 검증기가 다시 본다(FR-138).
     */
    private JangnyangSubtask resolveAnsweredSubtask(JangnyangSubtaskSession session, String id) {
        if (id != null && !id.isBlank()) {
            JangnyangSubtask target = subtasks.definitionOf(session).byId(id);
            if (target != null) return target;
        }
        return currentSubtaskOf(session);
    }

    /**
     * 질문·재질문을 {@code SUBTASK} 메시지로 보낸다.
     *
     * <p>{@code question}에 들어가는 문장은 항상 카탈로그의 것이다 — 정규화 LLM의 응답에
     * 질문 문장이 섞여 있어도 이 자리에 오지 않는다(UT-330). 재질문도 마찬가지로
     * {@code retryQuestion}을 그대로 쓰므로, 같은 항목을 몇 번 틀려도 같은 문장이
     * 나간다(FR-127·UT-307).
     */
    private void sendSubtaskQuestion(SubtaskSessionService.Step step, String lead,
                                     String userText, List<Map<String, String>> history) {
        if (step.question() == null) return;
        JangnyangSubtask q = step.question();
        boolean retry = !step.errors().isEmpty();
        String text = retry ? q.retryQuestion() : q.question();

        ChatMessage msg = new ChatMessage(ChatMessage.MessageType.SUBTASK,
                lead == null ? text : lead + "\n\n" + text);
        msg.setDomain("waste");
        msg.setSubtaskSetId(step.progress().subtaskSetId());
        msg.setSubtaskVersion(step.progress().version());
        msg.setCurrentSubtaskId(q.id());
        msg.setSubtaskOrder(step.progress().order());
        msg.setSubtaskTotal(step.progress().total());
        msg.setQuestion(text);
        msg.setInputSchema(SubtaskSessionService.describeSubtask(q));
        msg.setValidationErrors(SubtaskSessionService.describeErrors(step.errors()));
        msg.setProgress(step.progress().progress());
        msg.setGroupOrder(step.progress().groupOrder());
        msg.setGroupTotal(step.progress().groupTotal());
        msg.setGroupName(step.progress().groupName());
        msg.setGroupDescription(step.progress().groupDescription());
        msg.setQuestionInGroup(step.progress().questionInGroup());
        msg.setQuestionsInGroup(step.progress().questionsInGroup());
        messaging.convertAndSend("/topic/messages", msg);
        appendHistory(history, userText, text);
    }

    private void appendHistory(List<Map<String, String>> history, String userText, String reply) {
        history.add(Map.of("role", "user", "content", userText == null ? "" : userText));
        history.add(Map.of("role", "assistant", "content", reply));
        while (history.size() > 20) history.remove(0);
    }

    @MessageMapping("/chat.clear")
    public void clearHistory() {
        synchronized (sessionLock) {   // D-06
            histories.clear();
            pendingConfigs.clear();
            pendingModelIds.clear();
            // 진행 중인 수집도 함께 지운다. 남겨 두면 "새 대화"인데 다음 메시지가
            // 지난 세션의 답변으로 먹힌다(수집 확인이 모든 게이트보다 먼저이므로).
            subtasks.store().clear();
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
            appendBullets(sb, tr.errors());
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

    private void runCollectionTimeComparison(List<Integer> times,
                                             List<Map<String, String>> history,
                                             String userText) {
        String labels = times.stream().map(KoreanTimeParser::toHHMM)
                .reduce((a, b) -> a + ", " + b).orElse("");
        messaging.convertAndSend("/topic/messages", new ChatMessage(
                ChatMessage.MessageType.SYSTEM, "수거 시각 비교 실행 중... (" + labels + ")"));

        SimulationConfig base = new SimulationConfig();
        base.setDays(30);
        base.setSeeds(30);
        ToolResult tr = tool.compareCollectionTimes(base, times);
        String reply;
        if (!tr.ready()) {
            StringBuilder sb = new StringBuilder("수거 시각을 비교할 수 없습니다:\n");
            appendBullets(sb, tr.errors());
            reply = sb.toString().trim();
            messaging.convertAndSend("/topic/messages", new ChatMessage(ChatMessage.MessageType.BOT, reply));
        } else {
            ScenarioResponse resp = (ScenarioResponse) tr.result();
            reply = "[" + resp.getTitle() + "] 비교 결과입니다.";
            ChatMessage msg = new ChatMessage(ChatMessage.MessageType.SCENARIO, reply);
            msg.setScenarioResponse(resp);
            msg.setScenarioType("collection-time-comparison");
            messaging.convertAndSend("/topic/messages", msg);
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
                    truckType, profile, sites);
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
            sb.append(String.format("- %s: 수거 시작 %s\n",
                    route.get(0), KoreanTimeParser.toHHMM(startMinute)));
        } else {
            sb.append(String.format("차종: %s (수거 시각 미지정 — 혼잡 가중치 미반영, 구간당 기준 이동시간만 적용)\n",
                    truckType.labelKo));
        }
        for (RouteDurationEstimator.Hop h : est.hops) {
            String weightNote = est.trafficApplied
                    ? String.format(" (혼잡 가중치 ×%.2f%s)", h.congestionWeight, h.red ? ", 정체 심함" : "")
                    : "";
            String arrivalNote = startMinute != null
                    ? String.format(" · %s 도착 %s", h.to, KoreanTimeParser.toHHMM(h.arriveMinuteOfDay))
                    : "";
            sb.append(String.format("- %s → %s: %d분%s%s\n",
                    h.from, h.to, h.minutes, arrivalNote, weightNote));
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
            appendBullets(sb, tr.errors());
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
            appendBullets(sb, tr.warnings());
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

    /** 검증 오류·경고 목록을 "- 메시지" 불릿으로 이어 붙인다(검증 실패/확인 버블 공통). */
    private static void appendBullets(StringBuilder sb, List<ValidationError> items) {
        for (ValidationError e : items) sb.append("- ").append(e.message()).append("\n");
    }

    /** 실행 검증 실패 사유를 불릿으로 정리해 되돌려주는 공통 응답(실행 경로 여러 곳에서 재사용). */
}
