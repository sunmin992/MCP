package com.wastesim.llm;

import com.wastesim.subtask.AppliedDefault;
import com.wastesim.subtask.GapResolver;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskAnswer;
import com.wastesim.subtask.JangnyangSubtaskCatalog;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import com.wastesim.subtask.JangnyangSubtaskSession;
import com.wastesim.subtask.JangnyangSubtaskValidator;
import com.wastesim.subtask.SubtaskAnswerSource;
import com.wastesim.subtask.SubtaskSessionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 요청 하나를 설계도로 옮긴다 — 이 흐름의 조립 지점.
 *
 * <p>순서가 설계의 전부다: 뽑고(LLM) → 판정하고(코드) → 인용을 확인하고(코드) →
 * 기존 검증기에 넘기고 → 남은 것을 근거로 가른다.
 *
 * <p><b>물어야 할 것은 세션이 소유한다.</b> {@link GapResolver}가 낸 자동 채움 값을
 * 세션에 제출하지 않고 결과에만 실으면, 세션은 여전히 34문항을 묻는데 결과는 8문항만
 * 남았다고 말한다 — 그 8개를 다 답한 호출부는 영원히 READY에 닿지 못한다. 이 프로젝트는
 * 같은 판단이 두 곳에 사는 문제로 이미 두 번 물렸으므로(검증기가 엔진과 다른 경로를 봤던
 * 일, 요약이 실행 하나가 지닌 출처를 떨어뜨렸던 일), 여기서는 채운 값을 반드시 세션에
 * 넣고 <b>물어야 할 목록은 세션에서 다시 읽는다</b>.
 */
@Service
public class BlueprintComposer {

    private final SubtaskSessionService sessions;
    private final JangnyangSubtaskCatalog catalog;
    private final RequestInterpreter interpreter;

    public BlueprintComposer(SubtaskSessionService sessions,
                             JangnyangSubtaskCatalog catalog,
                             RequestInterpreter interpreter) {
        this.sessions = sessions;
        this.catalog = catalog;
        this.interpreter = interpreter;
    }

    /**
     * @param usedFallback     LLM 추출을 쓰지 못해 문항 흐름으로 갔는가
     * @param fallbackNotice   사용자에게 알릴 문구. 조용히 넘기면 이유를 알 수 없다
     * @param modelDefaultFields 시뮬레이터 기본값으로 채운 필드 목록. 시뮬레이션
     *                         결과에 붙이는 것은 실행 경로의 일이라 이 태스크에서는 다루지
     *                         않는다 — 여기서는 값을 만들어 넘기기만 한다
     * @param appliedDefaults  서버가 채운 값과 그 근거. 채운 사실만 남고 <b>왜 그 값인지</b>가
     *                         사라지면, 사용자는 자기가 답하지 않은 값이 어디서 왔는지 되짚을
     *                         수 없다 — 거부·폴백 경로에서는 채운 것이 없으므로 비어 있다
     */
    public record Outcome(FeasibilityVerdict verdict, List<String> mustAsk,
                          boolean usedFallback, String fallbackNotice,
                          List<String> modelDefaultFields,
                          List<AppliedDefault> appliedDefaults) {
        public Outcome {
            mustAsk = mustAsk == null ? List.of() : List.copyOf(mustAsk);
            modelDefaultFields = modelDefaultFields == null ? List.of() : List.copyOf(modelDefaultFields);
            appliedDefaults = appliedDefaults == null ? List.of() : List.copyOf(appliedDefaults);
        }
    }

    public Outcome compose(String sessionKey, String request) {
        // 필드 목록은 카탈로그에서 직접 얻는다. 세션을 먼저 시작해서 거기서 읽으면,
        // start()가 진행 중인 세션을 덮어쓰기 때문에 20문항을 답해 온 사용자가 범위
        // 밖 문장 하나를 보내는 순간 그 답을 모두 잃는다 — 거부될 수도 있는 요청으로
        // 살아 있는 세션을 건드리지 않는다.
        JangnyangSubtaskDefinition latest = catalog.latest();
        List<String> fields = new ArrayList<>();
        for (JangnyangSubtask s : latest.subtasks()) fields.add(s.answerField());

        RequestExtraction extraction;
        try {
            extraction = interpreter.extract(request, fields);
            requireWellFormed(extraction);
        } catch (InterpreterException | IllegalArgumentException e) {
            // 조용히 기본값으로 채우지 않는다. 무엇으로 계산한 값인지 구별할 수 없게 된다.
            // 그래서 modelDefaultFields와 appliedDefaults도 비운다 — 폴백에서는 아무것도
            // 자동으로 채우지 않는다.
            JangnyangSubtaskSession fresh = startFresh(sessionKey);
            return new Outcome(FeasibilityVerdict.ok(), remainingFields(fresh), true,
                    "요청 해석기를 쓸 수 없어 문항으로 진행합니다 (" + e.getMessage() + ")",
                    List.of(), List.of());
        }

        FeasibilityVerdict verdict = FeasibilityGate.judge(extraction);
        if (!verdict.feasible()) {
            // 세션을 아직 시작하지 않았으므로 취소할 것도 없다. 예전에는 여기서 cancel을
            // 불렀는데, 그것이 곧 "거부된 요청 하나가 남의 진행을 지우는" 경로였다.
            return new Outcome(verdict, List.of(), false, null, List.of(), List.of());
        }

        JangnyangSubtaskSession session = startFresh(sessionKey);
        JangnyangSubtaskDefinition def = sessions.definitionOf(session);

        SpanVerifier.Verified verified = SpanVerifier.verify(request, extraction);
        for (ExtractedValue v : verified.accepted()) {
            String id = idOfField(def, v.field());
            if (id == null) continue;   // 없는 필드를 낸 것은 버린다
            // 기존 검증기를 그대로 통과해야 한다. LLM 값에 예외를 두면 근거 없는 값이 흘러든다.
            sessions.submit(sessionKey, id, v.value(), null, SubtaskAnswerSource.LLM_NORMALIZED);
        }

        Set<String> settled = new LinkedHashSet<>(sessions.activeSession(sessionKey).answers().keySet());
        // 인용을 확인하지 못한 필드는 자동 채움 대상에서 뺀다. 지어낸 값을 버린 자리를
        // 서버 기본값으로 메우면 사용자는 자기 값이 버려진 것도, 그 자리에 다른 값이
        // 들어간 것도 모른 채 진행한다 — 그 필드는 채우지 말고 되물어야 한다.
        for (ExtractedValue r : verified.rejected()) {
            String id = idOfField(def, r.field());
            if (id != null) settled.add(id);
        }

        GapResolver.Resolution gaps = GapResolver.resolve(def, settled);
        for (Map.Entry<String, Object> e : gaps.autoFilled().entrySet()) {
            String id = idOfField(def, e.getKey());
            if (id == null) continue;
            // 근거 값이 null인 것은 "채우지 못했다"가 아니라 "해당 없음으로 확정했다"는
            // 뜻이다. submit()은 null을 빈 값으로 바꾸고 빈 값은 거부되므로, 그 결론을
            // 세션이 답으로 인정하는 형태("해당 없음")로 옮겨 제출한다 — 여기서 건너뛰면
            // 그 필드들은 채워지지도 물어지지도 않은 채 영원히 남는다.
            Object value = e.getValue() == null ? JangnyangSubtaskValidator.NOT_APPLICABLE : e.getValue();
            sessions.submit(sessionKey, id, value, null, SubtaskAnswerSource.SERVER_DEFAULT);
        }

        // 물어야 할 목록을 세션에서 다시 읽는다 — 여기서 따로 세면 세션과 결과가 서로
        // 다른 수를 말하게 된다. 검증기가 거부한 자동 채움 값도 이 경로에서 자동으로
        // 되묻기 대상이 된다(조용히 통과했다고 적지 않는다).
        List<String> mustAsk = remainingFields(sessions.activeSession(sessionKey));
        return new Outcome(verdict, mustAsk, false, null,
                gaps.modelDefaultFields(), gaps.defaults());
    }

    /** 새 수집을 시작한다 — 판정을 통과한 뒤에만 부른다. */
    private JangnyangSubtaskSession startFresh(String sessionKey) {
        sessions.start(sessionKey);
        return sessions.activeSession(sessionKey);
    }

    private List<String> remainingFields(JangnyangSubtaskSession session) {
        return remainingFields(session, sessions.definitionOf(session));
    }

    /**
     * 이 세션이 <b>실제로</b> 아직 묻고 있는 필드들. 세션의 질문 계획(plan)과 원장을 그대로
     * 읽으므로, 결과가 말하는 "물어야 할 것"과 세션이 다음에 낼 질문이 어긋날 수 없다.
     */
    private List<String> remainingFields(JangnyangSubtaskSession session,
                                         JangnyangSubtaskDefinition def) {
        Map<String, JangnyangSubtaskAnswer> answers = session.answers();
        List<String> remaining = new ArrayList<>();
        for (JangnyangSubtask s : session.plan(def, sessions.checker())) {
            JangnyangSubtaskAnswer a = answers.get(s.id());
            if (a == null || !a.valid()) remaining.add(s.answerField());
        }
        return remaining;
    }

    /** 형식이 깨진 추출은 전체를 버린다 — 반쯤 읽은 결과를 쓰면 무엇이 빠졌는지 모른다. */
    private static void requireWellFormed(RequestExtraction e) {
        if (e == null) throw new IllegalArgumentException("추출 결과가 없습니다");
        for (ExtractedValue v : e.values()) {
            if (v.field() == null || v.field().isBlank()) {
                throw new IllegalArgumentException("필드 이름이 없는 추출값이 있습니다");
            }
        }
    }

    private static String idOfField(JangnyangSubtaskDefinition def, String field) {
        for (JangnyangSubtask s : def.subtasks()) {
            if (s.answerField().equals(field)) return s.id();
        }
        return null;
    }
}
