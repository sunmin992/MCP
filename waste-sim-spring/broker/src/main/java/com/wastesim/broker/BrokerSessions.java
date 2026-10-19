package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP 세션과 조회 조건별로 이미 어느 시뮬레이터로 넘겨줬는지 기억한다.
 *
 * <p>채팅 세션 하나가 곧 MCP 세션 하나다 — 클라이언트는 연결 시작 때 받은
 * {@code Mcp-Session-Id} 를 이후 요청마다 붙인다. 브로커가 "같은 대화" 를 알아볼 수 있는 단서는
 * 이것뿐이다. 같은 세션에서 같은 조건을 다시 조회하면 막지는 않고 알린다.
 * 다른 조건의 조회에는 이력을 섞지 않는다.
 *
 * <p>메모리에만 두고 오래된 것부터 버린다. 브로커가 다시 뜨면 잊는데, 그러면 다음 조회가 처음
 * 조회로 보일 뿐 판정은 같다.
 */
@Component
public class BrokerSessions {

    private static final int MAX_HANDOFFS = 1000;

    private record Lookup(String session, JsonNode arguments) {}

    /** 같은 세션이라도 조회 조건이 다르면 별개 요청이다. 키는 변경되지 않도록 복사한다. */
    private final Map<Lookup, String[]> handedOff = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Lookup, String[]> eldest) {
                    return size() > MAX_HANDOFFS;
                }
            });

    public String open() {
        return UUID.randomUUID().toString();
    }

    public void handOff(String session, JsonNode arguments, String serverId, String endpoint) {
        if (session != null && !session.isBlank()) {
            handedOff.put(new Lookup(session, arguments.deepCopy()), new String[]{serverId, endpoint});
        }
    }

    public Optional<String[]> handedOff(String session, JsonNode arguments) {
        if (session == null || session.isBlank()) return Optional.empty();
        return Optional.ofNullable(handedOff.get(new Lookup(session, arguments)));
    }
}
