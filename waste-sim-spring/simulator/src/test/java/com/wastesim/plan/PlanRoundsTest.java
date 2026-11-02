package com.wastesim.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.capability.CapabilityCardLoader;
import com.wastesim.template.AnswerNormalizer;
import com.wastesim.template.SubtaskPlanner;
import com.wastesim.template.TemplateCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class PlanRoundsTest {

    private final ObjectMapper m = new ObjectMapper();
    private final TemplateCatalog catalog = new TemplateCatalog();
    private final SubtaskPlanner planner = new SubtaskPlanner(catalog, new AnswerNormalizer());
    private final PlanRounds rounds = new PlanRounds(catalog, new CapabilityCardLoader(), m);

    private PlanRounds.Step call(PlanRounds r, String planId, String answersJson, String originsJson) throws Exception {
        JsonNode answers = m.readTree(answersJson);
        JsonNode origins = m.readTree(originsJson);
        Map<String, Object> a = new LinkedHashMap<>();
        answers.fields().forEachRemaining(e -> a.put(e.getKey(), m.convertValue(e.getValue(), Object.class)));
        Map<String, String> o = new LinkedHashMap<>();
        origins.fields().forEachRemaining(e -> o.put(e.getKey(), e.getValue().asText()));
        var across = planner.planAcross(a, o, null, List.of(), null);
        return r.record(planId, null, null, answers, origins, null, m.createArrayNode(),
                across.plan(), across.waitingOn(), across.minRounds());
    }

    private PlanRounds.Step call(String planId, String answersJson) throws Exception {
        return call(rounds, planId, answersJson, "{}");
    }

    @Test
    void planId_없이_부르면_새로_발급하고_1단계() throws Exception {
        var s = call(null, "{}");
        assertTrue(s.planId().startsWith("plan-"));
        assertEquals(17, s.planId().length());
        assertEquals(1, s.round());
        assertTrue(s.newRound());
        assertEquals("round", s.event().get("event").asText());
        assertTrue(s.changes().stream().allMatch(c -> c.from() == null));
        assertEquals(Integer.valueOf(1), rounds.currentRound(s.planId()).orElseThrow());
        // 공통 칸과 round 칸
        JsonNode e = s.event();
        for (String f : List.of("ts", "planId", "matchId", "serverId", "sesId", "sesVersion", "event", "round",
                "instances", "changes", "counts", "complete", "completedAtRound", "minRounds"))
            assertTrue(e.has(f), f);
        assertEquals(catalog.sesId(), e.get("sesId").asText());
        assertFalse(e.get("serverId").asText().isBlank());
        for (JsonNode i : e.get("instances")) {
            String st = i.get("status").asText();
            assertEquals(st.equals("NOT_GENERATED"), i.has("condition"), i.toString());
            assertEquals(st.equals("DEFERRED"), i.has("waitingOn"), i.toString());
        }
    }

    @Test
    void 같은_답변은_recheck() throws Exception {
        var first = call(null, "{\"truckType\":\"LARGE_5TON\"}");
        var again = call(first.planId(), "{\"truckType\":\"LARGE_5TON\"}");
        assertEquals(1, again.round());
        assertFalse(again.newRound());
        assertEquals("recheck", again.event().get("event").asText());
        assertTrue(again.event().has("counts"));
        assertTrue(again.event().has("complete"));
        assertEquals(2, rounds.history(first.planId()).orElseThrow().size());
    }

    @Test
    void 값을_더하면_다음_단계와_changes() throws Exception {
        var first = call(null, "{}");
        var next = call(first.planId(), "{\"truckType\":\"LARGE_5TON\"}");
        assertEquals(2, next.round());
        assertTrue(next.newRound());
        assertTrue(next.changes().contains(new PlanRounds.Change("truckType", "UNFILLED", "FILLED")), next.changes().toString());
    }

    @Test
    void 키_순서가_달라도_같은_단계다() throws Exception {
        var first = call(null, "{\"truckType\":\"LARGE_5TON\",\"truckCount\":2}");
        var again = call(first.planId(), "{\"truckCount\":2,\"truckType\":\"LARGE_5TON\"}");
        assertFalse(again.newRound());
    }

    @Test
    void 완료된_단계를_기록한다() throws Exception {
        // 모든 결정에 기본값을 넣어 완료시킨다 — 실제로 완료되는지 planner 에게 묻는다.
        Map<String, Object> all = new LinkedHashMap<>();
        for (int k = 0; k < 10; k++) {
            var p = planner.planAcross(all, Map.of(), null, List.of(), null).plan();
            for (var i : p.toAsk()) all.put(i.answerKey(), planner.defaultFor(i.answerKey(), all));
        }
        assertTrue(planner.planAcross(all, Map.of(), null, List.of(), null).plan().complete());
        String done = m.writeValueAsString(all);

        var first = call(null, "{}");
        assertNull(first.completedAtRound());
        var second = call(first.planId(), done);
        assertEquals(Integer.valueOf(2), second.completedAtRound());
        assertEquals(2, second.event().get("completedAtRound").asInt());
        var third = call(first.planId(), "{}");
        assertEquals(Integer.valueOf(2), third.completedAtRound());
    }

    @Test
    void 모르는_planId_는_거절() {
        var e = assertThrows(IllegalArgumentException.class, () -> call("plan-000000000000", "{}"));
        assertEquals(PlanRounds.UNKNOWN, e.getMessage());
        assertTrue(e.getMessage().startsWith("planId 를 모릅니다(서버 재시작이나"));
    }

    @Test
    void sesVersion_이_다르면_거절() throws Exception {
        String[] version = {catalog.sesVersion()};
        TemplateCatalog moving = new TemplateCatalog() {
            @Override public String sesVersion() { return version[0]; }
        };
        PlanRounds r = new PlanRounds(moving, new CapabilityCardLoader(), m);
        var first = call(r, null, "{}", "{}");
        version[0] = "다른버전";
        assertThrows(IllegalArgumentException.class, () -> call(r, first.planId(), "{}", "{}"));
    }

    @Test
    void 이전_planId_는_첫_사건에만_싣는다() throws Exception {
        var s = rounds.record(null, "m-1", "plan-옛것", m.createObjectNode(), m.createObjectNode(), null,
                m.createArrayNode(), planner.planAcross(Map.of(), Map.of(), null, List.of(), null).plan(), Map.of(), 1);
        assertEquals("plan-옛것", s.event().get("previousPlanId").asText());
        assertEquals("m-1", s.event().get("matchId").asText());
    }

    @Test
    void 천건을_넘으면_오래된_것부터_밀려난다() throws Exception {
        String oldest = call(null, "{}").planId();
        for (int i = 0; i < 1000; i++) call(null, "{}");
        assertTrue(rounds.history(oldest).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> call(oldest, "{}"));
    }

    @Test
    void 같은_planId_동시_호출에도_단계가_한칸씩_오른다() throws Exception {
        String id = call(null, "{}").planId();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int n = t + 1;
                fs.add(pool.submit(() -> call(id, "{\"truckCount\":" + n + "}").round()));
            }
            Set<Integer> got = new TreeSet<>();
            for (var f : fs) got.add(f.get());
            assertEquals(Set.of(2, 3, 4, 5, 6, 7, 8, 9), got);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void attach_는_현재_단계에_공통_칸을_달아_붙인다() throws Exception {
        String id = call(null, "{}").planId();
        var ev = m.createObjectNode().put("event", "validate");
        rounds.attach(id, ev);
        var h = rounds.history(id).orElseThrow();
        JsonNode last = h.get(h.size() - 1);
        assertEquals("validate", last.get("event").asText());
        assertEquals(1, last.get("round").asInt());
        assertEquals(id, last.get("planId").asText());
    }
}
