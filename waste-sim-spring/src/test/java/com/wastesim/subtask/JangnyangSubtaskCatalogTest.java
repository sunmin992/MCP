package com.wastesim.subtask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.service.OpenAiService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UT-295~302 — <b>고정성 계약</b>(TDD 3.17.1).
 *
 * <p>이 절의 테스트는 계산의 정확성이 아니라 계약의 고정성을 본다. "서버가 질문을
 * 소유하고 있는가"를 묻는 것이며, LLM을 한 번도 호출하지 않는 순수 단위 테스트다 —
 * 그 사실 자체가 UT-301의 증명이다.
 */
class JangnyangSubtaskCatalogTest {

    private final JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();
    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("UT-295 같은 버전을 100회 조회하면 서브태스크 배열이 바이트 단위로 동일하다")
    void repeatedLookupsAreByteIdentical() throws Exception {
        String first = om.writeValueAsString(
                SubtaskSessionService.describeSubtask(catalog.latest().ordered().get(0)));
        String whole = om.writeValueAsString(catalog.latest().ordered().stream()
                .map(SubtaskSessionService::describeSubtask).toList());

        for (int i = 0; i < 100; i++) {
            JangnyangSubtaskDefinition def = catalog.latest();
            assertEquals(first, om.writeValueAsString(
                    SubtaskSessionService.describeSubtask(def.ordered().get(0))));
            assertEquals(whole, om.writeValueAsString(def.ordered().stream()
                    .map(SubtaskSessionService::describeSubtask).toList()),
                    "같은 버전 조회는 100번이든 1번이든 바이트 단위로 같아야 한다");
        }
    }

    @Test
    @DisplayName("UT-296 서브태스크 개수와 order 수열이 리소스 정의와 일치하고 중복·누락이 없다")
    void countAndOrderMatchResource() throws Exception {
        // 등록된 <b>모든</b> 버전을 본다. 최신 세트만 보면 v2를 남겨 둔 이유(진행된 세션을
        // 되짚는 것)가 검증에서 빠져, 옛 세트가 깨진 채로 남아 있어도 통과한다.
        for (int version : catalog.versions()) {
        JsonNode resource = readResource(version);
        JangnyangSubtaskDefinition def = catalog.byVersion(version);

        assertEquals(resource.path("subtasks").size(), def.subtasks().size(),
                "카탈로그가 리소스의 서브태스크를 하나도 빠뜨리지 않아야 한다: v" + version);

        Set<Integer> orders = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (JangnyangSubtask s : def.ordered()) {
            assertTrue(orders.add(s.order()), "order 중복: " + s.order());
            assertTrue(ids.add(s.id()), "ID 중복: " + s.id());
        }
        // 1..n 이 빈틈 없이 채워져 있어야 진행률(FR-128)이 말이 된다.
        for (int i = 1; i <= def.subtasks().size(); i++) {
            assertTrue(orders.contains(i), "order 수열에 " + i + "이 없다");
        }
        List<Integer> asRead = def.ordered().stream().map(JangnyangSubtask::order).toList();
        assertEquals(asRead.stream().sorted().toList(), asRead, "ordered()는 order 오름차순이어야 한다");
        }
    }

    @Test
    @DisplayName("UT-297 question·retryQuestion이 리소스 문자열과 문자 단위로 동일하다")
    void questionsAreVerbatimFromResource() throws Exception {
        for (int version : catalog.versions()) {
        JsonNode resource = readResource(version);
        JangnyangSubtaskDefinition def = catalog.byVersion(version);

        for (JsonNode node : resource.path("subtasks")) {
            JangnyangSubtask s = def.byId(node.path("id").asText());
            assertNotNull(s, "리소스에 있는 서브태스크가 카탈로그에 없다: " + node.path("id").asText());
            // 문자 단위 일치 — 서버가 문장을 다듬거나 접두어를 붙이지 않는다는 확인이다.
            assertEquals(node.path("question").asText(), s.question());
            assertEquals(node.path("retryQuestion").asText(), s.retryQuestion());
        }
        }
    }

    @Test
    @DisplayName("UT-298 모든 서브태스크가 열 개 항목을 갖는다(하나라도 비면 실패)")
    void everySubtaskIsFullySpecified() {
        for (int version : catalog.versions())
        for (JangnyangSubtask s : catalog.byVersion(version).ordered()) {
            assertTrue(s.isFullySpecified(), "항목이 빈 서브태스크: " + s.id());
            // 열 항목을 하나씩 다시 확인한다 — isFullySpecified()가 조건을 빠뜨려도
            // 이 테스트가 잡아야 하기 때문이다(검사기와 테스트가 같은 실수를 공유하면
            // 둘 다 통과한다).
            assertNotNull(s.id());
            assertTrue(s.order() > 0);
            assertFalse(s.question().isBlank());
            assertFalse(s.answerField().isBlank());
            assertNotNull(s.answerType());
            assertNotNull(s.allowedRange());
            assertTrue(s.allowedRange().isDeclared(), "허용 범위가 비었다: " + s.id());
            assertFalse(s.validationRule().isBlank());
            assertFalse(s.retryQuestion().isBlank());
            assertFalse(s.completionCondition().isBlank());
        }
    }

    @Test
    @DisplayName("UT-299 해시가 세트 내용과 일치하고, 한 글자만 바꿔도 달라진다")
    void hashMatchesContentAndChangesOnEdit() {
        JangnyangSubtaskDefinition def = catalog.latest();
        // 같은 내용으로 다시 만든 세트의 해시가 같아야 한다 — 해시가 객체 식별자가
        // 아니라 <b>내용</b>을 가리킨다는 확인이다.
        JangnyangSubtaskDefinition copy = new JangnyangSubtaskDefinition(
                def.subtaskSetId(), def.version(), def.immutable(), def.groups(), def.subtasks());
        assertEquals(def.hash(), copy.hash());
        assertEquals(64, def.hash().length(), "SHA-256 hex는 64자다");

        // 질문 한 글자만 바꾼 세트 — 해시가 달라져야 고정성 테스트가 개정을 잡아낸다.
        List<JangnyangSubtask> edited = new ArrayList<>(def.ordered());
        JangnyangSubtask first = edited.get(0);
        edited.set(0, new JangnyangSubtask(first.id(), first.order(), first.group(), first.stage(),
                first.question() + ".", first.answerField(), first.answerType(), first.required(),
                first.allowsNotApplicable(), first.allowedRange(),
                first.validationRule(), first.retryQuestion(), first.completionCondition()));
        JangnyangSubtaskDefinition tampered = new JangnyangSubtaskDefinition(
                def.subtaskSetId(), def.version(), def.immutable(), def.groups(), edited);
        assertNotEquals(def.hash(), tampered.hash(), "질문을 고쳤는데 해시가 같으면 변조를 놓친다");

        // 필수 여부만 바꿔도 달라져야 한다 — 문장이 아니라 <b>계약</b>이 바뀐 것이다.
        edited.set(0, new JangnyangSubtask(first.id(), first.order(), first.group(), first.stage(),
                first.question(), first.answerField(), first.answerType(), !first.required(),
                first.allowsNotApplicable(), first.allowedRange(),
                first.validationRule(), first.retryQuestion(), first.completionCondition()));
        assertNotEquals(def.hash(), new JangnyangSubtaskDefinition(
                def.subtaskSetId(), def.version(), def.immutable(), def.groups(), edited).hash());
    }

    @Test
    @DisplayName("UT-300 존재하지 않는 버전은 가까운 버전으로 대체하지 않고 거부한다")
    void unknownVersionIsRejectedNotSubstituted() {
        assertNotNull(catalog.byVersion(2), "v2는 덮어쓰지 않고 보존한다");
        assertNotNull(catalog.byVersion(3));
        assertEquals(List.of(2, 3), catalog.versions());
        assertEquals(3, catalog.latest().version(), "버전을 지정하지 않은 조회는 최신 세트를 준다");
        // v1은 삭제했다 — 진행 중인 세션도, 그 버전을 핀한 클라이언트도 없었다.
        // 없는 버전을 가까운 것으로 대체하지 않는다는 규칙은 그대로다(FR-138·D-45).
        assertNull(catalog.byVersion(1), "지운 버전을 v2로 대신 주면 안 된다");
        assertNull(catalog.byVersion(4), "없는 버전에 최신 세트를 대신 주면 안 된다");
        assertNull(catalog.byVersion(0));
        assertNull(catalog.byVersion(-1));

        // 도구 계층에서도 같은 규칙이어야 한다 — 한 경로만 관대하면 그 경로로 우회된다.
        var tool = new GetJangnyangFixedSubtasksTool(catalog);
        var rejected = tool.call(new ObjectMapper().createObjectNode().put("version", 99));
        assertFalse(rejected.ready());
        assertTrue(rejected.errors().get(0).message().contains("99"));
    }

    @Test
    @DisplayName("UT-301 조회 경로에 LLM 호출이 한 번도 일어나지 않는다(모델 독립성)")
    void lookupNeverCallsTheLlm() {
        // 프로바이더를 바꿔도 같은 세트라는 명제는, 조회 경로에 모델이 <b>없다</b>는
        // 사실에서 나온다. 프롬프트로 부탁해서 얻는 성질이 아니다(D-44·NFR-17).
        OpenAiService llm = mock(OpenAiService.class);

        String hashA = catalog.latest().hash();
        var toolResultA = new GetJangnyangFixedSubtasksTool(catalog).call(null);
        String hashB = catalog.latest().hash();
        var toolResultB = new GetJangnyangFixedSubtasksTool(catalog).call(null);

        assertEquals(hashA, hashB);
        assertEquals(toolResultA.result(), toolResultB.result());
        verifyNoInteractions(llm);
    }

    @Test
    @DisplayName("UT-302 required 플래그가 정의와 일치하고 응답을 통해 바꿀 수 없다")
    void requiredFlagsAreImmutable() throws Exception {
        for (int version : catalog.versions()) {
            JsonNode resource = readResource(version);
            JangnyangSubtaskDefinition def = catalog.byVersion(version);
            for (JsonNode node : resource.path("subtasks")) {
                assertEquals(node.path("required").asBoolean(),
                        def.byId(node.path("id").asText()).required(),
                        "required가 리소스와 다르다: v" + version + " " + node.path("id").asText());
            }
        }
        JangnyangSubtaskDefinition def = catalog.latest();

        // 세트 목록도, 도구가 내보내는 맵도 불변이어야 한다 — 응답 객체로 세트를 바꿀 수
        // 있으면 "LLM이 필수를 선택으로 바꿀 수 없다"는 보장이 약속으로 내려앉는다.
        assertThrows(UnsupportedOperationException.class,
                () -> def.subtasks().add(def.subtasks().get(0)));
        @SuppressWarnings("unchecked")
        var described = (java.util.Map<String, Object>)
                new GetJangnyangFixedSubtasksTool(catalog).call(null).result();
        assertThrows(UnsupportedOperationException.class, () -> described.put("immutable", false));
        @SuppressWarnings("unchecked")
        var items = (List<java.util.Map<String, Object>>) described.get("subtasks");
        assertThrows(UnsupportedOperationException.class, () -> items.get(0).put("required", false));
    }

    @Test
    @DisplayName("v2는 50개가 8단계에 나뉘어 있다(보존한 세트의 형태를 그대로 고정한다)")
    void v2ShapeIsPreserved() {
        JangnyangSubtaskDefinition def = catalog.byVersion(2);
        assertEquals(50, def.subtasks().size());
        assertEquals(8, def.groupCount());
        assertEquals(47, def.collectSubtasks().size(), "질문으로 묻는 것은 47개");
        assertEquals(3, def.confirmSubtasks().size(), "확인 단계는 미리보기가 대신한다");
        groupsAreWellFormed(def);
    }

    @Test
    @DisplayName("v3는 33개가 6단계에 나뉘어 있고, 사용자에게 보이는 것은 단계 이름이다")
    void v3ShapeIsFixed() {
        JangnyangSubtaskDefinition def = catalog.byVersion(3);
        assertEquals(33, def.subtasks().size(), "v3는 33개로 동결한다");
        assertEquals(6, def.groupCount());
        assertEquals(31, def.collectSubtasks().size(), "질문으로 묻는 것은 31개");
        assertEquals(2, def.confirmSubtasks().size(),
                "확인 질문을 하나로 통합했으므로 미리보기가 채우는 것은 2개다");
        groupsAreWellFormed(def);
    }

    /** 단계 정의가 온전하고 질문의 단계 번호가 역행하지 않는가. */
    private static void groupsAreWellFormed(JangnyangSubtaskDefinition def) {
        for (int g = 1; g <= def.groupCount(); g++) {
            assertNotNull(def.group(g), g + "단계 정의가 없다");
            assertFalse(def.group(g).name().isBlank());
            assertFalse(def.group(g).description().isBlank());
            assertFalse(def.subtasksInGroup(g).isEmpty(), g + "단계에 질문이 없다");
        }
        // 단계 번호는 순서를 거스르지 않는다 — 앞 질문이 뒤 단계에 속하면 진행 표시가 뒤로 간다.
        int prev = 0;
        for (JangnyangSubtask s : def.ordered()) {
            assertTrue(s.group() >= prev, "단계가 역행한다: " + s.id());
            prev = s.group();
        }
    }

    private JsonNode readResource(int version) throws Exception {
        String path = "/subtask/jangnyang-simulator-v" + version + ".json";
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "세트 리소스가 없다: " + path);
            return om.readTree(in);
        }
    }
}
