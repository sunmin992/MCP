package com.wastesim.subtask;

import com.wastesim.tool.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-303~312 — <b>답변 수집과 검증</b>(TDD 3.17.2).
 *
 * <p>각 테스트가 대조군을 함께 들고 있다는 점이 설계다 — 구멍을 막으면서 정상 입력까지
 * 막지 않았는지를 같은 클래스에서 확인한다(v1.12 회귀 테스트들과 같은 규약).
 */
class JangnyangSubtaskValidatorTest {

    private final JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
    private final JangnyangSubtaskValidator validator = new JangnyangSubtaskValidator();
    private final JangnyangSubtaskDefinition def = catalog.latest();

    private SubtaskValidationResult validate(Map<String, Object> answers) {
        return validator.validate(def, answers, Map.of());
    }

    private SubtaskValidationResult validate(Map<String, Object> answers,
                                             Map<String, JangnyangSubtaskAnswer> existing) {
        return validator.validate(def, answers, existing);
    }

    /** 수집 단계 전부를 유효하게 채운 답변 묶음 — 공용 픽스처에 둔다. */
    public static Map<String, Object> allRequired() {
        return V2Answers.all();
    }

    @Test
    @DisplayName("UT-303 답변이 순서대로 누적되고 늦게 온 답변이 앞 답변을 지우지 않는다")
    void answersAccumulateInOrder() {
        SubtaskValidationResult first = validate(Map.of("ST-001", "목적입니다"));
        assertTrue(first.valid());
        assertEquals(1, first.accepted().size());

        SubtaskValidationResult second = validate(Map.of("ST-020", "08:30"), first.accepted());
        assertEquals(2, second.accepted().size(), "앞 답변이 남아 있어야 한다");
        assertEquals("목적입니다", second.accepted().get("ST-001").value());
        assertEquals(510, second.accepted().get("ST-020").value(), "08:30 = 510분");

        // 삽입 순서가 곧 답한 순서다 — 감사 로그가 순서를 보존해야 한다(NFR-20).
        assertEquals(List.of("ST-001", "ST-020"), List.copyOf(second.accepted().keySet()));
    }

    @Test
    @DisplayName("UT-304 필수 항목이 비면 missing에 담기고 complete=false")
    void missingRequiredIsReported() {
        SubtaskValidationResult r = validate(Map.of("ST-001", "목적"));
        assertFalse(r.complete());
        assertTrue(r.missing().contains("ST-020"), "수거 시각이 누락 목록에 있어야 한다");
        assertFalse(r.missing().contains("ST-001"), "답한 항목은 누락이 아니다");
        // v2는 관련 없는 항목도 생략하지 않고 묻는다 — 그래서 아직 안 답한 항목은
        // 선택·필수를 가리지 않고 전부 누락이다(v1의 "선택 항목은 세지 않는다"가 뒤집혔다).
        assertTrue(r.missing().contains("ST-032"));

        // 대조군 — 필수를 다 채우면 complete=true다.
        assertTrue(validate(allRequired()).complete());
    }

    @Test
    @DisplayName("UT-305 자료형 위반을 거부한다 — 소수를 정수로 조용히 절삭하지 않는다")
    void typeViolationsAreRejected() {
        SubtaskValidationResult decimal = validate(Map.of("ST-006", 30.9));
        assertFalse(decimal.valid());
        assertEquals(ErrorCode.INVALID_ARGUMENTS, decimal.errors().get(0).code());
        assertFalse(decimal.accepted().containsKey("ST-006"), "거부된 값은 누적되지 않는다");

        SubtaskValidationResult text = validate(Map.of("ST-043", "많이"));
        assertFalse(text.valid());

        // 대조군 — 정수는 그대로 통과하고, 정수로 표기된 실수(30.0)도 정수로 본다.
        assertTrue(validate(Map.of("ST-006", 30)).valid());
        assertTrue(validate(Map.of("ST-006", 30.0)).valid());
    }

    @Test
    @DisplayName("UT-306 범위 밖 값을 클램프하지 않고 오류로 돌려준다")
    void outOfRangeIsNotClamped() {
        SubtaskValidationResult over = validate(Map.of("ST-006", 400));
        assertFalse(over.valid());
        assertEquals(ErrorCode.OUT_OF_RANGE, over.errors().get(0).code());
        assertFalse(over.accepted().containsKey("ST-006"),
                "365로 잘라서 저장하면 사용자는 400일을 돌린 줄 안다");

        // 건물 27동은 노드 ID를 만들 수 없다(W-05) — 이 자리에서 막는다.
        assertFalse(validate(Map.of("ST-004", 27)).valid());
        // 12:99를 13:39로 정상화하지 않는다(W-04).
        assertFalse(validate(Map.of("ST-020", "12:99")).valid());

        // 대조군 — 경계값은 통과해야 한다.
        assertTrue(validate(Map.of("ST-006", 365)).valid());
        assertTrue(validate(Map.of("ST-004", 26)).valid());
        assertTrue(validate(Map.of("ST-020", "23:59")).valid());
    }

    @Test
    @DisplayName("UT-307 같은 항목을 세 번 틀려도 재질문 문장은 세 번 모두 같다")
    void retryQuestionIsIdenticalEveryTime() {
        String expected = def.byId("ST-020").retryQuestion();
        for (Object bad : List.of("25:00", "여덟시쯤", "12:99")) {
            SubtaskValidationResult r = validate(Map.of("ST-020", bad));
            assertFalse(r.valid());
            assertEquals(expected, r.errors().get(0).retryQuestion(),
                    "재질문이 상황마다 달라지면 사용자는 무엇을 다시 답하는지 알 수 없다(D-47)");
        }
    }

    @Test
    @DisplayName("UT-308 세 항목이 동시에 잘못되면 첫 오류에서 멈추지 않고 셋을 전부 반환한다")
    void errorsAreCollectedNotThrown() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("ST-020", "25:00");
        bad.put("ST-006", 400);
        bad.put("ST-024", "TRACTOR");

        SubtaskValidationResult r = validate(bad);
        assertEquals(3, r.errors().size(), "한 번에 다 알려주는 편이 시행착오가 적다");
        assertEquals(List.of("ST-020", "ST-006", "ST-024"),
                r.errors().stream().map(SubtaskError::subtaskId).toList());
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.INVALID_ENUM));
        assertTrue(r.errors().stream().anyMatch(e -> e.code() == ErrorCode.OUT_OF_RANGE));
    }

    @Test
    @DisplayName("UT-309 틀린 답변을 다시 제출하면 이전 값을 덮어쓰고 오류 목록에서 빠진다")
    void resubmissionOverwritesAndClearsError() {
        SubtaskValidationResult bad = validate(Map.of("ST-020", "25:00"));
        assertFalse(bad.valid());

        SubtaskValidationResult good = validate(Map.of("ST-020", "08:30"), bad.accepted());
        assertTrue(good.valid());
        assertTrue(good.errors().isEmpty());
        assertEquals(510, good.accepted().get("ST-020").value());

        // 유효한 값을 다른 유효한 값으로 고치는 경우도 덮어써야 한다.
        SubtaskValidationResult changed = validate(Map.of("ST-020", "09:00"), good.accepted());
        assertEquals(540, changed.accepted().get("ST-020").value());
        assertEquals(1, changed.accepted().size(), "같은 항목이 두 벌 남으면 안 된다");
    }

    @Test
    @DisplayName("UT-310 세트에 없는 서브태스크 ID·답변 필드는 무시하지 않고 거부한다")
    void unknownSubtaskIdIsRejected() {
        SubtaskValidationResult r = validate(Map.of("ST-999", "아무 값"));
        assertFalse(r.valid());
        assertEquals("ST-999", r.errors().get(0).subtaskId());
        assertFalse(r.accepted().containsKey("ST-999"),
                "조용히 버리면 사용자는 자기 답이 반영됐다고 믿는다");

        assertFalse(validate(Map.of("nonexistentField", "값")).valid());

        // 대조군 — 답변 필드명으로 보내는 것은 허용한다(외부 MCP 클라이언트 편의).
        SubtaskValidationResult byField = validate(Map.of("collectionTime", "08:30"));
        assertTrue(byField.valid());
        assertEquals(510, byField.accepted().get("ST-020").value());
    }

    @Test
    @DisplayName("UT-311 세션이 시작한 버전과 다른 버전으로 온 답변을 거부한다")
    void versionMismatchIsRejected() {
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("k1");

        SubtaskSessionService.Step wrongVersion = sessions.submit("k1", "ST-001", "목적", 1);
        assertFalse(wrongVersion.ok());
        assertTrue(wrongVersion.rejection().contains("버전"),
                "조용히 맞춰 주면 어떤 세트로 시작했는지 재구성할 수 없다(NFR-20)");

        // 대조군 — 같은 버전이면 정상 처리된다.
        assertTrue(sessions.submit("k1", "ST-001", "목적", 2).ok());
    }

    @Test
    @DisplayName("UT-312 수집 단계 전부에 답해야 complete=true — v2는 생략을 허용하지 않는다")
    void completeRequiresEveryCollectQuestion() {
        SubtaskValidationResult r = validate(allRequired());
        assertTrue(r.complete());
        assertTrue(r.missing().isEmpty());

        // 하나만 빼도 즉시 false다 — "관련 없으면 안 물어도 된다"가 아니라
        // "관련 없으면 해당 없음으로 답한다"가 v2의 규약이다.
        assertFalse(validate(V2Answers.without("ST-018")).complete());
        assertFalse(validate(V2Answers.without("ST-040")).complete());
    }

    @Test
    @DisplayName("해당 없음은 정식 답변이다 — 다만 없으면 실험이 성립하지 않는 항목은 거부한다")
    void notApplicableIsAnAnswerExceptWhereItCannotBe() {
        // 비교하지 않는 실험에서 최적화 기준은 실제로 해당 없다.
        SubtaskValidationResult ok = validate(Map.of("ST-040", "해당 없음"));
        assertTrue(ok.valid());
        assertEquals("해당 없음", ok.accepted().get("ST-040").value());

        // "기본값 사용"도 같은 뜻으로 받는다.
        assertTrue(validate(Map.of("ST-026", "기본값 사용")).valid());

        // 목록형 질문에 "해당 없음"이라고 답하면 정규화 LLM이 ["해당 없음"]으로 감싸
        // 돌려준다 — 그것도 같은 뜻으로 받아야 사용자가 빠져나갈 수 있다(실측 확인).
        SubtaskValidationResult wrapped = validate(Map.of("ST-021", List.of("해당 없음")));
        assertTrue(wrapped.valid(), () -> "목록으로 감싸인 해당 없음이 걸렸다: " + wrapped.errors());
        assertEquals("해당 없음", wrapped.accepted().get("ST-021").value());

        // 반대로 목적·수거 시각은 넘어갈 수 없다 — 값이 없으면 실험이 성립하지 않는다.
        SubtaskValidationResult no = validate(Map.of("ST-020", "해당 없음"));
        assertFalse(no.valid());
        assertEquals(ErrorCode.MISSING_FIELD, no.errors().get(0).code());
        assertFalse(validate(Map.of("ST-001", "해당 없음")).valid());
    }

    @Test
    @DisplayName("비율 맵은 합이 1이어야 한다 — 서버가 임의로 정규화하지 않는다")
    void ratioMapsMustSumToOne() {
        assertTrue(validate(Map.of("ST-011",
                "BlueCollar=0.5, Student=0.5")).valid());

        SubtaskValidationResult bad = validate(Map.of("ST-011",
                "BlueCollar=0.5, Student=0.2"));
        assertFalse(bad.valid(), "합이 0.7인데 통과하면 안 된다");
        assertEquals(ErrorCode.OUT_OF_RANGE, bad.errors().get(0).code());

        // 허용 목록에 없는 직업은 거부한다.
        assertFalse(validate(Map.of("ST-011", "Astronaut=1.0")).valid());
    }

    @Test
    @DisplayName("건물별 인원 맵은 정수만 받고 노드 형식을 지킨다")
    void integerMapsRejectFractionsAndBadKeys() {
        assertTrue(validate(Map.of("ST-010", "Node_A=25, Node_B=25")).valid());
        assertFalse(validate(Map.of("ST-010", "Node_A=25.5")).valid());
        assertFalse(validate(Map.of("ST-010", "일층=25")).valid());
    }

    @Test
    @DisplayName("시각 목록은 중복을 막고 정렬해 저장한다")
    void timeListsAreDedupedAndSorted() {
        SubtaskValidationResult r = validate(Map.of("ST-021", "18:00, 09:00"));
        assertTrue(r.valid());
        assertEquals(List.of(540, 1080), r.accepted().get("ST-021").value());

        assertFalse(validate(Map.of("ST-021", "09:00, 09:00")).valid());
        assertFalse(validate(Map.of("ST-021", "25:00")).valid());
    }
}
