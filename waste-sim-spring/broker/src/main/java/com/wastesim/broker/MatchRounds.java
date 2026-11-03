package com.wastesim.broker;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
        /** profiles 와 같은 순서 — 그 바퀴의 도메인 구절이 걸린 등록 도메인 슬러그. */
        final List<Set<String>> domains = new ArrayList<>();

        Entry(String session, RequestProfile first, Set<String> firstDomains) {
            this.session = session;
            this.profiles.add(first);
            this.domains.add(firstDomains);
        }

        MatchThread snapshot() {
            List<String> removed = new ArrayList<>();
            for (int i = 1; i < profiles.size(); i++) {
                removals(profiles.get(i - 1), profiles.get(i), removed);
            }
            boolean domainChanged = false;
            for (int i = 1; i < profiles.size(); i++) {
                domainChanged |= domainChanged(profiles.get(0).domain(), domains.get(0),
                        profiles.get(i).domain(), domains.get(i));
            }
            return new MatchThread(session, round, targetServerId, List.copyOf(profiles),
                    List.copyOf(removed), domainChanged || !removed.isEmpty());
        }
    }

    /**
     * 도메인이 바뀌어 다른 질문이 됐는가. 등록 도메인 집합이 첫 바퀴의 부분집합이면 띄어쓰기를 고치거나
     * 좁힌 것이다. 어느 등록 도메인에도 안 걸리면 비교할 집합이 없어 글자가 달라졌는지만 본다.
     */
    private static boolean domainChanged(String firstDomain, Set<String> first, String domain, Set<String> now) {
        if (now.isEmpty()) return !Objects.equals(firstDomain, domain);
        return !first.containsAll(now);
    }

    /** 바퀴 r → r+1 에서 빠진 구절. 칸의 구절 수가 줄어야 제거다 — 같은 수로 글만 바뀐 것은 고쳐 쓴 것이다. */
    private static void removals(RequestProfile r, RequestProfile n, List<String> out) {
        list("environmentConditions", r.environmentConditions(), n.environmentConditions(), out);
        list("population", r.population(), n.population(), out);
        list("comparisonAxes", r.comparisonAxes(), n.comparisonAxes(), out);
        scalar("spatialScale", r.spatialScale(), n.spatialScale(), out);
        scalar("objective", r.objective(), n.objective(), out);
        r.requirements().fields().forEachRemaining(e -> {
            if (java.util.Set.of("schemaVersion", "evidence", "preferences").contains(e.getKey())) return;
            if (!e.getValue().equals(n.requirements().path(e.getKey()))) {
                String item = "requirements." + e.getKey() + ": " + e.getValue();
                if (!out.contains(item)) out.add(item);
            }
        });
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

    public String open(String session, RequestProfile first, Set<String> domains) {
        String id = UUID.randomUUID().toString();
        threads.put(id, new Entry(session, first, domains));
        return id;
    }

    /**
     * 세션이 다르거나 모르는 id 면 empty. 아니면 바퀴를 하나 늘린 매칭.
     * targetServerId 가 null 이면 저장된 목표를 그대로 쓰고, 값이 있으면 바꾼다.
     */
    public Optional<MatchThread> next(String matchId, String session, RequestProfile revised,
                                      String targetServerId, Set<String> domains) {
        synchronized (threads) {
            Entry t = threads.get(matchId);
            if (t == null || !Objects.equals(t.session, session)) return Optional.empty();
            t.round++;
            if (targetServerId != null) t.targetServerId = targetServerId;
            if (t.round <= MAX_STORED_ROUND) {
                t.profiles.add(revised);
                t.domains.add(domains);
            }
            return Optional.of(t.snapshot());
        }
    }
}
