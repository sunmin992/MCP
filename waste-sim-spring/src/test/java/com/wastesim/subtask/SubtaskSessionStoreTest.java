package com.wastesim.subtask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UT-313~319 — <b>세션</b>(TDD 3.17.3).
 *
 * <p>여기서 보는 것은 계산이 아니라 격리와 순서다 — 두 사용자의 답변이 섞이지 않는가,
 * 준비되지 않은 세션이 엔진에 도달하지 못하는가.
 */
class SubtaskSessionStoreTest {

    private final JangnyangSubtaskCatalog catalog = new JangnyangSubtaskCatalog();

    /** 세트 수준 필수를 전부 채운 뒤 READY까지 밀어 올린다. */
    private static void answerAllRequired(SubtaskSessionService sessions, String key) {
        Map<String, Object> answers = new LinkedHashMap<>(V3Answers.all());
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            sessions.submit(key, e.getKey(), e.getValue(), null);
        }
    }

    @Test
    @DisplayName("UT-313 두 세션이 같은 세트를 동시에 진행해도 답변이 섞이지 않는다")
    void sessionsAreIsolated() {
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("ws:A");
        sessions.start("ws:B");

        sessions.submit("ws:A", "ST-016", "08:30", null);
        sessions.submit("ws:B", "ST-016", "20:00", null);

        assertEquals(510, sessions.store().find("ws:A").answers().get("ST-016").value());
        assertEquals(1200, sessions.store().find("ws:B").answers().get("ST-016").value());

        // A만 목적을 답했으면 B에는 없어야 한다.
        sessions.submit("ws:A", "ST-001", "A의 목적", null);
        assertNotNull(sessions.store().find("ws:A").answers().get("ST-001"));
        assertNull(sessions.store().find("ws:B").answers().get("ST-001"));
    }

    @Test
    @DisplayName("UT-314 현재 서브태스크 ID·순서·전체 개수·진행률이 누적 답변과 일치한다")
    void progressMatchesAccumulatedAnswers() {
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        SubtaskSessionService.Step start = sessions.start("k");

        // 아직 아무것도 안 답했으면 첫 질문이고 진행률은 0이다.
        assertEquals("ST-001", start.progress().currentSubtaskId());
        assertEquals(1, start.progress().order());
        assertEquals(0.0, start.progress().progress(), 1e-9);

        int total = start.progress().total();
        // 시나리오 유형과 무관하게 수집 단계 전부를 묻는다 — 분모가 처음부터 끝까지
        // 같아야 사용자가 남은 질문 수를 알 수 있다(v1의 "필요한 것만 묻는다"가 뒤집혔다).
        assertEquals(catalog.latest().collectSubtasks().size(), total);
        assertEquals(1, start.progress().groupOrder());
        assertEquals(catalog.latest().groupCount(), start.progress().groupTotal());
        assertFalse(start.progress().groupName().isBlank());

        SubtaskSessionService.Step after = sessions.submit("k", "ST-001", "목적", null);
        assertEquals("ST-002", after.progress().currentSubtaskId(), "다음 질문으로 넘어가야 한다");
        assertEquals(2, after.progress().order());
        assertEquals(1.0 / total, after.progress().progress(), 1e-9);
        assertEquals(1, after.progress().answers().size());
    }

    @Test
    @DisplayName("UT-315 CANCELLED 후 새로 시작하면 이전 답변이 남지 않는다")
    void cancelClearsAccumulatedAnswers() {
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("k");
        sessions.submit("k", "ST-001", "지난 실험의 목적", null);
        assertEquals(1, sessions.store().find("k").answers().size());

        sessions.cancel("k");
        assertNull(sessions.activeSession("k"));

        SubtaskSessionService.Step restarted = sessions.start("k");
        assertTrue(restarted.progress().answers().isEmpty(),
                "지운 줄 아는 값이 남으면 사용자는 모르는 조건으로 결과를 읽는다");
        assertEquals("ST-001", restarted.progress().currentSubtaskId());
    }

    @Test
    @DisplayName("UT-316 연결이 끊겼다 돌아온 세션이 중단 지점부터 이어진다")
    void sessionResumesFromWhereItStopped() {
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("ws:A");
        sessions.submit("ws:A", "ST-001", "목적", null);
        sessions.submit("ws:A", "ST-002", "single-run", null);

        // "연결이 끊겼다 돌아옴" = 같은 키로 다시 조회하는 것. 저장소가 세션을 들고 있으면
        // 진행이 이어진다.
        JangnyangSubtaskSession resumed = sessions.activeSession("ws:A");
        assertNotNull(resumed);
        assertEquals(2, resumed.answers().size());
        assertEquals("ST-003", sessions.progress("ws:A").currentSubtaskId(),
                "중단 지점의 다음 질문부터 이어져야 한다");
    }

    @Test
    @DisplayName("UT-317 COLLECTING의 실행 요청과 BUILT 없는 RUNNING 요청을 거부한다")
    void skippedStatesAreRejected() {
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("k");

        // 아직 수집 중인데 조립을 요청 — 거부한다.
        assertFalse(sessions.build("k").ok());
        assertEquals(SubtaskState.COLLECTING, sessions.store().find("k").state());

        // 조립 없이 실행 승인을 요청 — 거부한다(null = 실행할 것이 없다).
        assertNull(sessions.approveRun("k"));

        answerAllRequired(sessions, "k");
        assertEquals(SubtaskState.READY, sessions.store().find("k").state());
        // READY만으로도 실행은 안 된다 — 조립을 거쳐야 실행할 설정이 생긴다.
        assertNull(sessions.approveRun("k"));

        assertTrue(sessions.build("k").ok());
        assertEquals(SubtaskState.BUILT, sessions.store().find("k").state());
        assertNotNull(sessions.approveRun("k"), "BUILT 이후에만 실행이 열린다");
    }

    @Test
    @DisplayName("UT-318 default 고정이 아니라 연결별 키로 분리된다 — 서로의 진행이 보이지 않는다")
    void sessionKeyIsPerConnection() {
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog);
        sessions.start("ws:conn-1");
        sessions.submit("ws:conn-1", "ST-001", "1번 연결의 목적", null);

        // 아직 시작하지 않은 다른 연결에는 아무 진행도 없어야 한다.
        assertNull(sessions.activeSession("ws:conn-2"));
        assertNull(sessions.progress("ws:conn-2"));
        // v1.12까지의 단일 키로 조회해도 남의 진행이 보이면 안 된다(D-05 → D-49).
        assertNull(sessions.activeSession("default"));

        sessions.start("ws:conn-2");
        assertTrue(sessions.progress("ws:conn-2").answers().isEmpty());
        assertEquals(1, sessions.progress("ws:conn-1").answers().size());
    }

    @Test
    @DisplayName("UT-319 in-memory 저장소는 재시작 복구를 만족하지 못한다(NFR-19 미충족을 명시)")
    void inMemoryStoreDoesNotSurviveRestart() {
        SubtaskSessionStore store = new InMemorySubtaskSessionStore();
        SubtaskSessionService sessions = TestSubtaskFixtures.service(catalog, store);
        sessions.start("ws:A");
        sessions.submit("ws:A", "ST-001", "목적", null);
        assertEquals(1, store.find("ws:A").answers().size());

        // 저장소를 갈아 끼우는 것 = 프로세스 재시작. in-memory 구현에서는 세션이 사라진다.
        SubtaskSessionStore restarted = new InMemorySubtaskSessionStore();
        SubtaskSessionService afterRestart = TestSubtaskFixtures.service(catalog, restarted);

        assertFalse(store.durable(),
                "이 구현은 NFR-19를 만족하지 못한다 — 결함이 아니라 의도적으로 미룬 범위다(D-51)");
        assertNull(afterRestart.activeSession("ws:A"),
                "in-memory 저장소에서는 재시작 후 복구되지 않는다(문서가 먼저 인정한 상태)");

        // 저장소가 durable로 바뀌면 이 단언이 뒤집혀야 한다 — 그때 이 테스트를 고치는 것이
        // 곧 "NFR-19를 만족했다"는 선언이 된다. 지금은 미충족을 <b>고정</b>해 둔다.
        assertFalse(restarted.durable());
    }
}
