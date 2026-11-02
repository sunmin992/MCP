package com.wastesim.broker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 한 매칭(첫 조회와 그것을 고쳐 다시 부른 조회들)을 matchId 로 이어 바퀴를 센다.
 *
 * <p>메모리에만 두고 오래된 것부터 버린다 — {@link BrokerSessions} 와 같은 방식이다. 브로커가
 * 다시 뜨면 잊고, 그때는 새 요청으로 다시 조회하게 한다.
 */
@Component
public class MatchRounds {

    private static final int MAX_MATCHES = 1000;

    /** 고친 조회 한도(첫 조회 포함 6바퀴). 이 뒤의 조회는 대조 없이 NONE 이라 기록을 늘리지 않는다. */
    private static final int MAX_STORED_ROUND = 6;

    /** 한 매칭의 지금까지를 잠금 안에서 떠 둔 불변 복사본. round 는 첫 조회가 1. */
    public record MatchThread(String session, int round, String targetServerId,
                              List<RequestProfile> profiles,
                              List<String> removedConditions, boolean purposeChanged) {}

    /** 저장용 가변 상태. 밖으로는 내보내지 않는다. */
    private static final class Entry {
        final String session;
        int round = 1;
        String targetServerId;
        final List<RequestProfile> profiles = new ArrayList<>();

        Entry(String session, RequestProfile first) {
            this.session = session;
            this.profiles.add(first);
        }

        MatchThread snapshot() {
            List<String> removed = new ArrayList<>();
            for (int i = 1; i < profiles.size(); i++) {
                removals(profiles.get(i - 1), profiles.get(i), removed);
            }
            String domain = profiles.get(0).domain();
            boolean domainChanged = profiles.stream().skip(1)
                    .anyMatch(p -> !Objects.equals(domain, p.domain()));
            return new MatchThread(session, round, targetServerId, List.copyOf(profiles),
                    List.copyOf(removed), domainChanged || !removed.isEmpty());
        }
    }

    /** 바퀴 r → r+1 에서 빠진 구절. 칸의 구절 수가 줄어야 제거다 — 같은 수로 글만 바뀐 것은 고쳐 쓴 것이다. */
    private static void removals(RequestProfile r, RequestProfile n, List<String> out) {
        list("environmentConditions", r.environmentConditions(), n.environmentConditions(), out);
        list("population", r.population(), n.population(), out);
        list("comparisonAxes", r.comparisonAxes(), n.comparisonAxes(), out);
        scalar("spatialScale", r.spatialScale(), n.spatialScale(), out);
        scalar("objective", r.objective(), n.objective(), out);
    }

    private static void list(String axis, List<String> before, List<String> after, List<String> out) {
        if (before == null || before.isEmpty()) return;
        if (after != null && after.size() >= before.size()) return;
        for (String phrase : before) {
            String item = axis + ": " + phrase;
            if ((after == null || !after.contains(phrase)) && !out.contains(item)) out.add(item);
        }
    }

    private static void scalar(String axis, String before, String after, List<String> out) {
        String item = axis + ": " + before;
        if (before != null && after == null && !out.contains(item)) out.add(item);
    }

    private final Map<String, Entry> threads = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > MAX_MATCHES;
                }
            });

    public String open(String session, RequestProfile first) {
        String id = UUID.randomUUID().toString();
        threads.put(id, new Entry(session, first));
        return id;
    }

    /** 세션이 다르거나 모르는 id 면 empty. 아니면 바퀴를 하나 늘린 매칭. */
    public Optional<MatchThread> next(String matchId, String session,
                                      RequestProfile revised, String targetServerId) {
        synchronized (threads) {
            Entry t = threads.get(matchId);
            if (t == null || !Objects.equals(t.session, session)) return Optional.empty();
            t.round++;
            t.targetServerId = targetServerId;
            if (t.round <= MAX_STORED_ROUND) t.profiles.add(revised);
            return Optional.of(t.snapshot());
        }
    }
}
