package com.wastesim.llm;

import com.wastesim.subtask.GapResolver;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import com.wastesim.subtask.SubtaskAnswerSource;
import com.wastesim.subtask.SubtaskSessionService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 요청 하나를 설계도로 옮긴다 — 이 흐름의 조립 지점.
 *
 * <p>순서가 설계의 전부다: 뽑고(LLM) → 판정하고(코드) → 인용을 확인하고(코드) →
 * 기존 검증기에 넘기고 → 남은 것을 근거로 가른다.
 */
@Service
public class BlueprintComposer {

    private final SubtaskSessionService sessions;
    private final RequestInterpreter interpreter;

    public BlueprintComposer(SubtaskSessionService sessions, RequestInterpreter interpreter) {
        this.sessions = sessions;
        this.interpreter = interpreter;
    }

    /**
     * @param usedFallback     LLM 추출을 쓰지 못해 문항 흐름으로 갔는가
     * @param fallbackNotice   사용자에게 알릴 문구. 조용히 넘기면 이유를 알 수 없다
     * @param unverifiedFields 자동으로 채웠지만 그 출처를 확인하지 않은 필드 목록. 시뮬레이션
     *                         결과에 붙이는 것은 실행 경로의 일이라 이 태스크에서는 다루지
     *                         않는다 — 여기서는 값을 만들어 넘기기만 한다
     */
    public record Outcome(FeasibilityVerdict verdict, List<String> mustAsk,
                          boolean usedFallback, String fallbackNotice,
                          List<String> unverifiedFields) {
        public Outcome {
            mustAsk = mustAsk == null ? List.of() : List.copyOf(mustAsk);
            unverifiedFields = unverifiedFields == null ? List.of() : List.copyOf(unverifiedFields);
        }
    }

    public Outcome compose(String sessionKey, String request) {
        List<String> fields = new ArrayList<>();
        sessions.start(sessionKey);
        JangnyangSubtaskDefinition def = sessions.definitionOf(sessions.activeSession(sessionKey));
        for (JangnyangSubtask s : def.subtasks()) fields.add(s.answerField());

        RequestExtraction extraction;
        try {
            extraction = interpreter.extract(request, fields);
            requireWellFormed(extraction);
        } catch (InterpreterException | IllegalArgumentException e) {
            // 조용히 기본값으로 채우지 않는다. 무엇으로 계산한 값인지 구별할 수 없게 된다.
            // 그래서 unverifiedFields도 비운다 — 폴백에서는 아무것도 자동으로 채우지 않는다.
            return new Outcome(FeasibilityVerdict.ok(), mustAskAll(def), true,
                    "요청 해석기를 쓸 수 없어 문항으로 진행합니다 (" + e.getMessage() + ")",
                    List.of());
        }

        FeasibilityVerdict verdict = FeasibilityGate.judge(extraction);
        if (!verdict.feasible()) {
            // 거부한 요청으로 세션을 남기면, 다음 요청이 그 세션을 이어받는다.
            sessions.cancel(sessionKey);
            return new Outcome(verdict, List.of(), false, null, List.of());
        }

        SpanVerifier.Verified verified = SpanVerifier.verify(request, extraction);
        for (ExtractedValue v : verified.accepted()) {
            String id = idOfField(def, v.field());
            if (id == null) continue;   // 없는 필드를 낸 것은 버린다
            // 기존 검증기를 그대로 통과해야 한다. LLM 값에 예외를 두면 근거 없는 값이 흘러든다.
            sessions.submit(sessionKey, id, v.value(), null, SubtaskAnswerSource.LLM_NORMALIZED);
        }

        Set<String> answered = sessions.activeSession(sessionKey).answers().keySet();
        GapResolver.Resolution gaps = GapResolver.resolve(def, answered);

        List<String> mustAsk = new ArrayList<>(gaps.mustAsk());
        for (ExtractedValue r : verified.rejected()) {
            if (!mustAsk.contains(r.field())) mustAsk.add(r.field());
        }
        return new Outcome(verdict, mustAsk, false, null, gaps.unverifiedFields());
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

    private static List<String> mustAskAll(JangnyangSubtaskDefinition def) {
        List<String> all = new ArrayList<>();
        for (JangnyangSubtask s : def.subtasks()) all.add(s.answerField());
        return all;
    }

    private static String idOfField(JangnyangSubtaskDefinition def, String field) {
        for (JangnyangSubtask s : def.subtasks()) {
            if (s.answerField().equals(field)) return s.id();
        }
        return null;
    }
}
