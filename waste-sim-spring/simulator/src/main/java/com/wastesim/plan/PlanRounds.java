package com.wastesim.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wastesim.capability.CapabilityCardLoader;
import com.wastesim.template.SubtaskInstance;
import com.wastesim.template.SubtaskPlan;
import com.wastesim.template.SubtaskStatus;
import com.wastesim.template.TemplateCatalog;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 한 요청(planId)의 plan_subtasks 호출을 이어 단계를 센다.
 *
 * <p>원래 답변 맵 · 출처 맵 · 실험 변수가 직전 단계와 같으면 같은 단계(recheck)이고, 다르면 다음 단계다.
 * 근거만 달라진 호출은 사용자 답이 새로 온 것이 아니라서 단계를 올리지 않는다.
 *
 * <p>메모리에만 두고 오래된 것부터 버린다 — 브로커의 {@code MatchRounds} 와 같은 방식이다. 서버가 다시 뜨면 잊고,
 * 그때는 planId 없이 다시 불러 새 요청으로 시작하게 한다.
 */
@Component
public class PlanRounds {

    private static final int MAX_PLANS = 1000;

    public static final String UNKNOWN = "planId 를 모릅니다(서버 재시작이나 오래되어 메모리에서 밀려났을 수 있습니다) "
            + "— planId 없이 다시 부르면 새 요청으로 시작합니다";

    /** @param from 직전 단계의 상태 이름. 1단계이거나 직전에 없던 결정이면 null */
    public record Change(String answerKey, String from, String to) {}

    /**
     * 이번 호출의 결과.
     *
     * @param newRound         단계를 올렸으면 true, 같은 단계의 다시 확인(recheck)이면 false
     * @param changes          직전 단계 대비 상태가 바뀐 결정. recheck 면 비어 있다
     * @param completedAtRound complete 가 처음 참이 된 단계. 아직이면 null
     * @param event            이번 호출로 기록에 붙인 사건
     */
    public record Step(String planId, int round, boolean newRound, List<Change> changes,
                       Integer completedAtRound, int minRounds, ObjectNode event) {}

    /** 저장용 가변 상태. 밖으로는 내보내지 않는다. */
    private static final class Entry {
        final String sesId;
        final String sesVersion;
        String matchId;
        int round;
        JsonNode key;
        Map<String, String> statuses = Map.of();
        Integer completedAtRound;
        final List<ObjectNode> events = new ArrayList<>();

        Entry(String sesId, String sesVersion, String matchId) {
            this.sesId = sesId;
            this.sesVersion = sesVersion;
            this.matchId = matchId;
        }
    }

    private final TemplateCatalog catalog;
    private final ObjectMapper mapper;
    private final String serverId;

    private final Map<String, Entry> plans = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_PLANS;
        }
    };

    public PlanRounds(TemplateCatalog catalog, CapabilityCardLoader card, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.serverId = card.rawJson().path("serverId").asText(null);
    }

    /**
     * plan_subtasks 한 번을 기록한다.
     *
     * @param planIdOrNull null 이면 새로 발급해 1단계로 시작한다
     * @param answers      호출에 들어온 원래 답변 맵(정규화 전)
     * @param origins      호출에 들어온 원래 출처 맵
     * @throws IllegalArgumentException 모르는 planId 이거나 발급 때와 SES 가 다르면. 메시지는 {@link #UNKNOWN}
     */
    public synchronized Step record(String planIdOrNull, String matchId, String previousPlanId,
                                    JsonNode answers, JsonNode origins, String variableKey, JsonNode variableValues,
                                    SubtaskPlan plan, Map<String, List<String>> waitingOn, int minRounds) {
        String planId = planIdOrNull;
        Entry e;
        if (planId == null) {
            planId = "plan-" + UUID.randomUUID().toString().substring(0, 12);
            e = new Entry(catalog.sesId(), catalog.sesVersion(), matchId);
            plans.put(planId, e);
        } else {
            e = live(planId);
            if (matchId != null) e.matchId = matchId;
        }

        ObjectNode key = mapper.createObjectNode();
        key.set("answers", objectOrEmpty(answers));
        key.set("origins", objectOrEmpty(origins));
        key.put("variableKey", variableKey);
        key.set("variableValues", variableValues == null || !variableValues.isArray()
                ? mapper.createArrayNode() : variableValues);
        boolean newRound = !key.equals(e.key);

        if (plan.complete() && e.completedAtRound == null) e.completedAtRound = newRound ? e.round + 1 : e.round;

        if (!newRound) {
            ObjectNode ev = common(planId, e, "recheck");
            ev.set("counts", mapper.valueToTree(plan.counts()));
            ev.put("complete", plan.complete());
            e.events.add(ev);
            return new Step(planId, e.round, false, List.of(), e.completedAtRound, minRounds, ev);
        }

        e.round++;
        e.key = key;
        Map<String, String> now = new LinkedHashMap<>();
        for (SubtaskInstance i : plan.instances()) now.put(i.answerKey(), i.status().name());
        List<Change> changes = new ArrayList<>();
        now.forEach((k, to) -> {
            String from = e.statuses.get(k);
            if (!to.equals(from)) changes.add(new Change(k, from, to));
        });
        e.statuses = now;

        ObjectNode ev = common(planId, e, "round");
        if (e.round == 1 && previousPlanId != null) ev.put("previousPlanId", previousPlanId);
        var instances = ev.putArray("instances");
        for (SubtaskInstance i : plan.instances()) {
            ObjectNode n = instances.addObject();
            n.put("templateId", i.templateId());
            n.put("answerKey", i.answerKey());
            n.put("status", i.status().name());
            n.set("reason", mapper.valueToTree(i.reason()));
            n.put("origin", i.origin());
            n.put("evidence", i.evidence());
            if (i.status() == SubtaskStatus.DEFERRED)
                n.set("waitingOn", mapper.valueToTree(waitingOn.getOrDefault(i.answerKey(), List.of())));
            if (i.status() == SubtaskStatus.NOT_GENERATED)
                n.put("condition", catalog.byId(i.templateId()).map(t -> t.generateWhen().toString()).orElse(null));
        }
        ev.set("changes", mapper.valueToTree(changes));
        ev.set("counts", mapper.valueToTree(plan.counts()));
        ev.put("complete", plan.complete());
        if (e.completedAtRound == null) ev.putNull("completedAtRound");
        else ev.put("completedAtRound", e.completedAtRound);
        ev.put("minRounds", minRounds);
        e.events.add(ev);
        return new Step(planId, e.round, true, List.copyOf(changes), e.completedAtRound, minRounds, ev);
    }

    /**
     * 그 요청의 현재 단계에 사건(validate · build · confirm · run)을 붙인다. 공통 칸은 여기서 채우고,
     * 호출자는 {@code event} 와 사건별 칸만 준다.
     *
     * @throws IllegalArgumentException 모르는 planId 면
     */
    public synchronized void attach(String planId, ObjectNode event) {
        Entry e = live(planId);
        ObjectNode ev = common(planId, e, event.path("event").asText(null));
        event.fields().forEachRemaining(f -> {
            if (!f.getKey().equals("event")) ev.set(f.getKey(), f.getValue());
        });
        e.events.add(ev);
    }

    public synchronized Optional<List<ObjectNode>> history(String planId) {
        Entry e = plans.get(planId);
        return e == null ? Optional.empty() : Optional.of(List.copyOf(e.events));
    }

    public synchronized Optional<Integer> currentRound(String planId) {
        Entry e = plans.get(planId);
        return e == null ? Optional.empty() : Optional.of(e.round);
    }

    /** 모르거나 서버의 SES 와 다른 planId 는 거절한다. 실제로는 재시작해야 버전이 바뀌므로 메모리에서도 사라진다. */
    private Entry live(String planId) {
        Entry e = plans.get(planId);
        if (e == null || !Objects.equals(e.sesId, catalog.sesId()) || !Objects.equals(e.sesVersion, catalog.sesVersion()))
            throw new IllegalArgumentException(UNKNOWN);
        return e;
    }

    private ObjectNode common(String planId, Entry e, String event) {
        ObjectNode ev = mapper.createObjectNode();
        ev.put("ts", Instant.now().toString());
        ev.put("planId", planId);
        ev.put("matchId", e.matchId);
        ev.put("serverId", serverId);
        ev.put("sesId", e.sesId);
        ev.put("sesVersion", e.sesVersion);
        ev.put("event", event);
        ev.put("round", e.round);
        return ev;
    }

    private JsonNode objectOrEmpty(JsonNode n) {
        return n == null || !n.isObject() ? mapper.createObjectNode() : n;
    }
}
