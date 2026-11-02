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

    /** 한 매칭의 지금까지. round 는 첫 조회가 1. */
    public static final class MatchThread {
        private final String session;
        private int round = 1;
        private String targetServerId;
        private final List<RequestProfile> profiles = new ArrayList<>();

        private MatchThread(String session, RequestProfile first) {
            this.session = session;
            this.profiles.add(first);
        }

        public String session() { return session; }
        public int round() { return round; }
        public String targetServerId() { return targetServerId; }
        public List<RequestProfile> profiles() { return List.copyOf(profiles); }
    }

    private final Map<String, MatchThread> threads = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, MatchThread> eldest) {
                    return size() > MAX_MATCHES;
                }
            });

    public String open(String session, RequestProfile first) {
        String id = UUID.randomUUID().toString();
        threads.put(id, new MatchThread(session, first));
        return id;
    }

    /** 세션이 다르거나 모르는 id 면 empty. 아니면 바퀴를 하나 늘린 매칭. */
    public Optional<MatchThread> next(String matchId, String session,
                                      RequestProfile revised, String targetServerId) {
        synchronized (threads) {
            MatchThread t = threads.get(matchId);
            if (t == null || !Objects.equals(t.session, session)) return Optional.empty();
            t.round++;
            t.targetServerId = targetServerId;
            t.profiles.add(revised);
            return Optional.of(t);
        }
    }
}
