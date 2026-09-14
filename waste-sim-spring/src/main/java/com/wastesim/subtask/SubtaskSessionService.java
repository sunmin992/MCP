package com.wastesim.subtask;

import com.wastesim.ledger.AnswerDecisions;
import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.LedgerRecalculator;
import com.wastesim.ledger.ParameterLedger;
import com.wastesim.ledger.Transformation;
import com.wastesim.ledger.ValueSource;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import com.wastesim.tool.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 수집 세션의 수명 주기를 한 곳에서 다룬다 — 시작·답변 제출·조립·실행 표시·취소.
 *
 * <p><b>왜 이 서비스가 필요한가</b>: 세션을 다루는 경로가 둘이다. 채팅({@code ChatController})과
 * 선택 MCP 도구 3종(FR-137)이 같은 세션을 같은 규칙으로 다뤄야 하는데, 각자 상태 전이를
 * 구현하면 한쪽만 전이를 빠뜨리는 순간 "채팅으로는 막히는데 MCP로는 통과하는" 경로가
 * 생긴다 — 상태 축의 fail-closed(D-52)가 경로마다 다른 강도로 적용되는 것이다.
 *
 * <p>상태 전이 실패는 예외가 아니라 결과값으로 돌려준다. 사용자가 순서를 건너뛴 것은
 * 서버 오류가 아니라 정상적인 거부이고, 호출부는 그 사실을 사용자에게 문장으로 알려야 한다.
 */
@Service
public class SubtaskSessionService {

    private final JangnyangSubtaskCatalog catalog;
    private final JangnyangSubtaskValidator validator;
    private final JangnyangCompletenessChecker checker;
    private final JangnyangScenarioBuilder builder;
    private final SubtaskSessionStore store;

    /**
     * 구조를 바꾸는 답변이 왔을 때 원장을 다시 계산한다.
     *
     * <p>배선이 고정돼 있어 인스턴스를 매번 만들 이유가 없다. 생성자에서 미등록 규칙 ID를
     * 걸러 내므로, 배선이 낡으면 서비스 조립 시점에 드러난다 — 실행 중에 조용히
     * {@code UNKNOWN}으로 떨어지는 것보다 낫다.
     */
    private final LedgerRecalculator recalculator = new LedgerRecalculator(
            JangnyangRules.registry(),
            JangnyangLedgerWiring.activeWhenByParameter(),
            JangnyangLedgerWiring.dependents());

    public SubtaskSessionService(JangnyangSubtaskCatalog catalog,
                                 JangnyangSubtaskValidator validator,
                                 JangnyangCompletenessChecker checker,
                                 JangnyangScenarioBuilder builder,
                                 SubtaskSessionStore store) {
        this.catalog = catalog;
        this.validator = validator;
        this.checker = checker;
        this.builder = builder;
        this.store = store;
    }

    public JangnyangCompletenessChecker checker() { return checker; }
    public SubtaskSessionStore store() { return store; }

    /** 진행 중인(살아 있는) 세션. 없거나 끝났으면 {@code null}. */
    public JangnyangSubtaskSession activeSession(String sessionKey) {
        JangnyangSubtaskSession s = store.find(sessionKey);
        return s != null && s.state().isActive() ? s : null;
    }

    /** 이 세션이 시작한 세트 — 진행 중에 세트가 새 버전으로 바뀌어도 시작한 버전을 쓴다(NFR-20). */
    public JangnyangSubtaskDefinition definitionOf(JangnyangSubtaskSession session) {
        return catalog.byVersion(session.version());
    }

    /**
     * 새 수집을 시작한다. 같은 키에 진행 중인 세션이 있으면 <b>덮어쓴다</b> — 생성 요청은
     * "처음부터 다시"라는 뜻이고, 이전 답변을 이어받으면 사용자가 지운 줄 아는 값이 남는다.
     */
    public Step start(String sessionKey) {
        JangnyangSubtaskDefinition def = catalog.latest();
        JangnyangSubtaskSession session = new JangnyangSubtaskSession(sessionKey, def);
        session.transitionTo(SubtaskState.COLLECTING);
        store.save(session);
        return step(session, def, List.of());
    }

    /**
     * 현재 서브태스크에 대한 답변 하나를 처리한다 — 정규화된 값을 받아 검증하고, 통과하면
     * 다음 질문으로, 실패하면 <b>같은 문장으로</b> 재질문한다(FR-127).
     *
     * @param subtaskId 답변 대상. {@code null}이면 세션이 지금 묻고 있는 서브태스크
     * @param version   답변이 주장하는 세트 버전. 세션 버전과 다르면 거부한다(FR-138·UT-311)
     */
    public Step submit(String sessionKey, String subtaskId, Object value, Integer version) {
        return submit(sessionKey, subtaskId, value, version, SubtaskAnswerSource.USER_DIRECT);
    }

    /**
     * 이 답을 누가 넣었는지 원장에 남긴다(FR-126 답변 출처).
     *
     * <p>출처를 주지 않는 기존 4인자 호출은 {@code USER_DIRECT}로 위임한다 — 이미 쌓인
     * 원장의 의미를 바꾸지 않기 위해서다.
     */
    public Step submit(String sessionKey, String subtaskId, Object value, Integer version,
                       SubtaskAnswerSource source) {
        JangnyangSubtaskSession session = store.find(sessionKey);
        if (session == null || !session.state().isActive()) {
            return Step.rejected("진행 중인 수집 세션이 없습니다. 시뮬레이터 구성을 먼저 요청해 주세요.");
        }
        JangnyangSubtaskDefinition def = definitionOf(session);
        if (version != null && version != session.version()) {
            // 조용히 맞춰 주지 않는다 — 다른 버전의 질문에 대한 답을 이 세션의 값으로
            // 받으면, 어떤 세트로 시작했는지 사후에 재구성할 수 없다(D-26·D-45).
            return Step.rejected("이 세션은 세트 버전 " + session.version()
                    + "로 시작했습니다. 버전 " + version + "의 답변은 받을 수 없습니다.");
        }
        String targetId = subtaskId != null ? subtaskId
                : idOf(session.nextSubtask(def, checker));
        if (targetId == null) {
            return Step.rejected("더 답할 서브태스크가 없습니다.");
        }

        session.transitionTo(SubtaskState.VALIDATING);
        SubtaskValidationResult result = validator.validate(
                def, Map.of(targetId, value == null ? "" : value), session.answers(), source);
        session.apply(result);
        recordDecision(session, def, targetId, value, source);
        recalculate(session, def, targetId);

        if (session.nextSubtask(def, checker) == null
                && checker.check(def, session.answers()).sufficient()) {
            session.transitionTo(SubtaskState.READY);
        } else {
            session.transitionTo(SubtaskState.COLLECTING);
        }
        store.save(session);
        return step(session, def, result.errors());
    }

    /**
     * 시나리오를 조립한다. READY 이전에는 거부한다(D-52·UT-317).
     */
    public BuildStep build(String sessionKey) {
        JangnyangSubtaskSession session = store.find(sessionKey);
        if (session == null || !session.state().isActive()) {
            return BuildStep.rejected("진행 중인 수집 세션이 없습니다.");
        }
        if (!session.state().canBuild()) {
            return BuildStep.rejected("아직 시나리오를 만들 수 없습니다(현재 상태: "
                    + session.state() + "). 남은 질문에 먼저 답해 주세요.");
        }
        JangnyangSubtaskDefinition def = definitionOf(session);
        JangnyangScenarioBuilder.BuildOutcome outcome = builder.build(def, session.answers());
        if (!outcome.ok()) {
            // 조립이 거부되면 상태를 올리지 않는다 — READY에 머물러야 사용자가 답을
            // 고쳐 다시 시도할 수 있다.
            return BuildStep.failed(outcome);
        }
        session.attachSpec(outcome.spec());
        session.transitionTo(SubtaskState.BUILT);
        store.save(session);
        return BuildStep.built(outcome.spec(), ledgerWarningsOf(session));
    }

    /**
     * 원장이 막을 이유로 보는 것들을 사람이 읽는 문장으로.
     *
     * <p>조립을 막지 않고 실어 보내기만 한다. 이 경고가 상시로 뜬다면 원장과 기존 checker의
     * 기준 차이가 크다는 뜻이고, 그때는 강제를 넓히기 전에 그 차이를 먼저 읽어야 한다.
     */
    private static List<String> ledgerWarningsOf(JangnyangSubtaskSession session) {
        return session.ledger().blocking().stream()
                .map(d -> d.parameterId() + ": " + d.blockingReason())
                .toList();
    }

    /**
     * 실행 승인. BUILT가 아니면 거부한다 — 조립을 거치지 않은 세션에는 실행할 설정이 없다
     * (FR-129·UT-317).
     *
     * <p>반환 계약은 그대로다 — 막히면 {@code null}. 사유가 필요하면
     * {@link #approveRunChecked(String)}을 쓴다.
     */
    public JangnyangScenarioSpec approveRun(String sessionKey) {
        return approveRunChecked(sessionKey).spec();
    }

    /**
     * 실행 승인과 그 사유.
     *
     * <p><b>여기서만 원장을 강제한다.</b> 조립 단계는 경고만 싣는다 — 원장과 기존 checker는
     * 기준이 달라, 조립부터 막으면 지금 통과하던 구성이 갑자기 막히고 그것이 진짜 결함인지
     * 두 기준의 차이인지 구분할 데이터가 없다. 실행만 막아도 "미해결 필수값이 있으면 실행
     * 패키지를 발행하지 않는다"는 요구는 달성된다.
     */
    public RunApproval approveRunChecked(String sessionKey) {
        JangnyangSubtaskSession session = store.find(sessionKey);
        if (session == null || !session.state().canRun()) {
            return RunApproval.blocked(List.of("아직 실행할 수 있는 상태가 아닙니다."));
        }

        List<String> blocks = ledgerWarningsOf(session);
        if (!blocks.isEmpty()) {
            // 상태를 올리지 않는다 — BUILT에 머물러야 사용자가 답을 고쳐 다시 시도할 수 있다.
            return RunApproval.blocked(blocks);
        }

        session.transitionTo(SubtaskState.RUNNING);
        store.save(session);
        return RunApproval.approved(session.spec());
    }

    /** 실행이 끝났다. 성공이면 COMPLETED, 실패면 BUILT로 되돌려 다시 시도할 수 있게 한다. */
    public void finishRun(String sessionKey, boolean success) {
        JangnyangSubtaskSession session = store.find(sessionKey);
        if (session == null) return;
        session.transitionTo(success ? SubtaskState.COMPLETED : SubtaskState.BUILT);
        store.save(session);
    }

    /**
     * 확인 단계(ST-048~050)를 기록한다 — 미리보기 화면이 그 셋을 대신하기 때문이다.
     * 세션이 없거나 이미 끝났으면 아무 일도 하지 않는다.
     */
    public void recordConfirmations(String sessionKey, String approval) {
        JangnyangSubtaskSession session = store.find(sessionKey);
        if (session == null || !session.state().isActive()) return;
        session.recordConfirmations(definitionOf(session), approval);
        store.save(session);
    }

    /** 취소·초기화 — 누적 답변까지 지운다(UT-315). */
    public void cancel(String sessionKey) {
        JangnyangSubtaskSession session = store.find(sessionKey);
        if (session != null) session.cancel();
        store.remove(sessionKey);
    }

    /** 진행 상태만 조회(FR-128). 세션이 없으면 {@code null}. */
    public SubtaskProgress progress(String sessionKey) {
        JangnyangSubtaskSession session = store.find(sessionKey);
        if (session == null) return null;
        return session.progress(definitionOf(session), checker);
    }

    private static String idOf(JangnyangSubtask s) {
        return s == null ? null : s.id();
    }

    /**
     * 이번 답변을 원장에 남긴다.
     *
     * <p><b>왜 검증 뒤에 남기는가</b>: 검증을 통과하지 못한 값을 확정으로 쌓으면, 세션은
     * 거부했는데 원장은 받아들인 상태가 된다. 원장이 실행을 여는 근거가 되므로 그 어긋남은
     * 곧 잘못된 값의 실행 경로가 된다.
     *
     * <p>13인자 조립을 여기서 다시 쓰지 않고 {@link AnswerDecisions#fromAnswer}에 맡긴다 —
     * 그 사본이 테스트에만 있던 것이 앞 작업에서 지적된 자리다.
     */
    private void recordDecision(JangnyangSubtaskSession session, JangnyangSubtaskDefinition def,
                                String subtaskId, Object rawValue, SubtaskAnswerSource source) {
        JangnyangSubtask subtask = def.byId(subtaskId);
        if (subtask == null) return;

        JangnyangSubtaskAnswer accepted = session.answers().get(subtaskId);
        // 검증기가 거부했으면 세션에 통과한 답이 없다. 원장에도 확정값을 남기지 않는다.
        if (accepted == null || !accepted.valid()) return;

        String parameterId = JangnyangLedgerWiring.parameterIdOf(subtask.answerField());
        ParameterLedger ledger = session.ledger();
        Instant now = Instant.now();

        // v3까지는 basis 선언이 없어 null이다(JangnyangSubtask 문서) — 선언이 없다는 뜻이지
        // 근거가 있다는 뜻이 아니므로 GapResolver와 같은 기준으로 FieldBasis.unknown()을 쓴다.
        FieldBasis basis = subtask.basis() != null ? subtask.basis() : FieldBasis.unknown();

        ledger.append(AnswerDecisions.fromAnswer(
                ledger.nextDecisionId(parameterId), parameterId,
                rawValue, accepted.value(),
                source, basis.kind(),
                new ValueSource(sourceTypeOf(source), subtaskId,
                        String.valueOf(def.version()), now),
                transformationOf(source, subtaskId), now));
    }

    /**
     * {@code LLM_NORMALIZED} 값이 실제로 겪은 변환을 규칙 ID로 남긴다.
     *
     * <p>{@code DecisionStateMapper}가 이 출처를 {@code DERIVED}로 옮기고,
     * {@code ParameterDecision}은 {@code DERIVED}면 변환 규칙이 반드시 있어야 한다고
     * 강제한다 — 유도했다고 적어 놓고 어떻게 유도했는지를 비워 두면 그 값의 출처를
     * 감사할 수 없기 때문이다. 여기서 말할 수 있는 사실은 딱 하나, "이 서브태스크의
     * 자유 문장 답을 그 서브태스크가 정한 단 하나의 답변 필드로 정규화했다"는 것뿐이다
     * — 그 이상(예: 어떤 모델을 썼는지, 무엇을 근거로 판단했는지)은 이 계층이 아는
     * 사실이 아니므로 규칙 이름에 넣지 않는다. {@code inputEventRefs}는 이 변환이 읽은
     * 것이 다른 결정이 아니라 사용자가 이 서브태스크에 낸 원문 그 자체라는 뜻으로
     * 서브태스크 ID를 담는다.
     */
    private static Transformation transformationOf(SubtaskAnswerSource source, String subtaskId) {
        if (source != SubtaskAnswerSource.LLM_NORMALIZED) return null;
        return new Transformation("llm_free_text_to_answer_field", List.of(subtaskId));
    }

    /**
     * 이번 답변을 기준으로 활성 구조와 종속 값을 다시 계산한다.
     *
     * <p>{@link SubtaskState}는 손대지 않는다 — {@code READY → COLLECTING} 전이가 이미
     * 허용돼 있다. 여기서 하는 일은 그 전이를 일으켜야 할 때를 알아내는 것이다.
     */
    private void recalculate(JangnyangSubtaskSession session, JangnyangSubtaskDefinition def,
                             String subtaskId) {
        JangnyangSubtask subtask = def.byId(subtaskId);
        if (subtask == null) return;
        recalculator.onAnswerChanged(session.ledger(),
                JangnyangLedgerWiring.parameterIdOf(subtask.answerField()),
                answersByField(session, def));
    }

    /**
     * 세션의 답변을 <b>답변 필드명</b>으로 펼친다.
     *
     * <p>등록된 활성 규칙은 {@code trafficMode} 같은 필드명을 본다. 서브태스크 ID를 그대로
     * 넘기면 규칙이 언제나 {@code UNKNOWN}을 돌려주고, 그러면 모든 조건부 가지가 영원히
     * 미해결로 남아 아무것도 실행할 수 없게 된다.
     */
    private static Map<String, Object> answersByField(JangnyangSubtaskSession session,
                                                      JangnyangSubtaskDefinition def) {
        Map<String, Object> byField = new LinkedHashMap<>();
        for (Map.Entry<String, JangnyangSubtaskAnswer> e : session.answers().entrySet()) {
            JangnyangSubtask s = def.byId(e.getKey());
            if (s == null || !e.getValue().valid()) continue;
            byField.put(s.answerField(), e.getValue().value());
        }
        return byField;
    }

    /** 답변 출처를 원장의 출처 종류로 옮긴다. 없는 이름을 지어내지 않는다. */
    private static String sourceTypeOf(SubtaskAnswerSource source) {
        return switch (source) {
            case USER_DIRECT -> "user_explicit";
            case LLM_NORMALIZED -> "llm_normalized";
            case SERVER_DEFAULT -> "asset_contract";
        };
    }

    /**
     * 이미 시작된 세션의 현재 질문. 값을 제출하지 않고 <b>지금 무엇을 묻고 있는지</b>만
     * 읽는다.
     *
     * <p>{@link #start(String)}은 세션을 새로 만들기 때문에, 다른 경로가 세션을 채워 둔
     * 뒤 화면에 질문을 띄우려면 여기가 필요하다 — 그때 start를 다시 부르면 방금 채운
     * 값이 전부 사라진다.
     */
    public Step currentStep(String sessionKey) {
        JangnyangSubtaskSession session = store.find(sessionKey);
        if (session == null || !session.state().isActive()) {
            return Step.rejected("진행 중인 수집 세션이 없습니다.");
        }
        return step(session, definitionOf(session), List.of());
    }

    private Step step(JangnyangSubtaskSession session, JangnyangSubtaskDefinition def,
                      List<SubtaskError> errors) {
        JangnyangSubtask next = session.nextSubtask(def, checker);
        return new Step(session, next, session.progress(def, checker), errors, null);
    }

    /**
     * 수집 한 걸음의 결과.
     *
     * @param session  갱신된 세션({@code rejection}이 있으면 {@code null})
     * @param question 다음에 물을 서브태스크. {@code null}이면 수집이 끝났다
     * @param progress 진행 상태(FR-128)
     * @param errors   직전 검증의 오류 항목(재질문 문장 포함)
     * @param rejection 요청 자체가 거부된 사유. {@code null}이면 정상 처리
     */
    public record Step(JangnyangSubtaskSession session,
                       JangnyangSubtask question,
                       SubtaskProgress progress,
                       List<SubtaskError> errors,
                       String rejection) {

        static Step rejected(String reason) {
            return new Step(null, null, null, List.of(), reason);
        }

        public boolean ok() { return rejection == null; }

        /** 수집이 끝나 조립할 수 있는 상태인가. */
        public boolean readyToBuild() {
            return ok() && question == null && session.state() == SubtaskState.READY;
        }
    }

    /**
     * 조립 한 걸음의 결과.
     *
     * @param ledgerWarnings 원장이 막을 이유로 본 것들. <b>조립을 막지는 않는다</b> —
     *                       원장과 기존 checker는 기준이 달라, 강제를 넓히기 전에 그
     *                       차이가 실제로 얼마나 나는지 볼 데이터가 먼저 필요하다
     */
    public record BuildStep(JangnyangScenarioSpec spec,
                            JangnyangScenarioBuilder.BuildOutcome outcome,
                            String rejection,
                            List<String> ledgerWarnings) {

        public BuildStep {
            ledgerWarnings = ledgerWarnings == null ? List.of() : List.copyOf(ledgerWarnings);
        }

        static BuildStep built(JangnyangScenarioSpec spec, List<String> warnings) {
            return new BuildStep(spec, null, null, warnings);
        }
        static BuildStep failed(JangnyangScenarioBuilder.BuildOutcome o) {
            return new BuildStep(null, o, null, List.of());
        }
        static BuildStep rejected(String reason) {
            return new BuildStep(null, null, reason, List.of());
        }

        public boolean ok() { return spec != null; }

        /** 실패 사유를 사람이 읽는 문장으로 — 재질문 문장을 잃지 않는다. */
        public String message() {
            if (rejection != null) return rejection;
            if (outcome == null) return "알 수 없는 오류";
            StringBuilder sb = new StringBuilder("아직 시나리오를 만들 수 없습니다:\n");
            for (SubtaskError e : outcome.retryPrompts()) {
                sb.append("- ").append(e.subtaskId()).append(": ").append(e.reason())
                  .append("\n  ").append(e.retryQuestion()).append('\n');
            }
            for (var e : outcome.configErrors()) {
                sb.append("- ").append(e.field()).append(": ").append(e.message()).append('\n');
            }
            return sb.toString().trim();
        }
    }

    /** MCP 선택 도구가 쓰는 진행 상태 직렬화(FR-128·137). */
    public static Map<String, Object> describe(SubtaskProgress p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subtaskSetId", p.subtaskSetId());
        m.put("version", p.version());
        m.put("hash", p.hash());
        m.put("state", p.state().name());
        m.put("currentSubtaskId", p.currentSubtaskId());
        m.put("order", p.order());
        m.put("total", p.total());
        m.put("progress", p.progress());
        m.put("groupOrder", p.groupOrder());
        m.put("groupTotal", p.groupTotal());
        m.put("groupName", p.groupName());
        m.put("groupDescription", p.groupDescription());
        m.put("questionInGroup", p.questionInGroup());
        m.put("questionsInGroup", p.questionsInGroup());
        m.put("answers", p.answers());
        m.put("errors", SubtaskToolSupport.describe(p.errors()));
        return Ordered.copyOf(m);
    }

    /** 질문 하나를 클라이언트가 입력 위젯을 고를 수 있는 스키마로(SDD 2.18.10). */
    public static Map<String, Object> describeSubtask(JangnyangSubtask s) {
        return SubtaskToolSupport.describe(s);
    }

    /** 오류 항목을 클라이언트 모양으로 — 재질문 문장이 함께 실린다(FR-127). */
    public static java.util.List<Map<String, Object>> describeErrors(List<SubtaskError> errors) {
        return SubtaskToolSupport.describe(errors);
    }

    /**
     * 정규화 LLM에 넘길 필드 명세 문장을 만든다(SDD 2.18.4).
     *
     * <p>이 문장을 <b>여기서</b> 만드는 이유는 {@code OpenAiService}가 서브태스크 도메인을
     * 몰라도 되게 하기 위해서다. 서비스가 {@link JangnyangSubtask}를 직접 받으면 두 패키지가
     * 서로를 참조하게 되고, 그러면 "LLM 계층은 이 계층의 규칙을 모른다"는 경계가 코드
     * 구조에서 사라진다.
     */
    public static String fieldSpecFor(JangnyangSubtask s) {
        StringBuilder sb = new StringBuilder();
        sb.append("자료형: ").append(s.answerType().name()).append('\n');
        sb.append("허용 범위: ").append(s.allowedRange().description()).append('\n');
        if (!s.allowedRange().valuesOrEmpty().isEmpty()) {
            sb.append("허용 값: ").append(String.join(", ", s.allowedRange().valuesOrEmpty())).append('\n');
        }
        // 질문 문장을 참고로만 넣는다. 모델이 이 문장을 고쳐 돌려줘도 사용자에게 나가는
        // 것은 카탈로그의 문장이므로(호출부가 그렇게 만든다) 변조 경로가 없다(UT-330).
        sb.append("참고(사용자가 받은 질문): ").append(s.question()).append('\n');
        return sb.toString();
    }

    /** 세션 없음을 구조화 오류로 — 선택 도구 3종이 공유한다. */
    static com.wastesim.tool.ValidationError noSession(String sessionKey) {
        return new com.wastesim.tool.ValidationError(ErrorCode.MISSING_FIELD, "sessionKey",
                "진행 중인 수집 세션이 없다: " + sessionKey);
    }
}
