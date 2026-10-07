package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;

/**
 * 브로커가 아는 시뮬레이터의 능력 카드를 모아 둔다 — 후보를 고르는 유일한 근거다.
 *
 * <p>카드는 두 길로 들어온다.
 * <ul>
 *   <li><b>등록</b> — 명세 0단계. 시뮬레이터 서버가 떠서 자기 카드를 보낸다
 *       ({@code POST /api/simulators}). 같은 서버가 다시 보내면 새 카드로 바꾼다 —
 *       시뮬레이터는 브로커가 다시 떠도 목록에 남도록 주기적으로 다시 등록한다.</li>
 *   <li><b>후보 파일</b> — {@code mcp/candidates/*.json}. 매칭을 시험하려고 지어낸 서버들이다.
 *       카드가 스스로 {@code fictional} 을 밝힌다. 등록으로 덮을 수 없다.</li>
 * </ul>
 *
 * <p>카드를 자바 모델로 옮기지 않고 원문 {@link JsonNode} 로 들고 있는다 — 중간에 모델을
 * 거치면 칸이 하나 늘 때마다 코드를 고쳐야 하고, 빠뜨린 칸은 조용히 사라진다. 매칭기는
 * 자기가 보는 칸만 꺼내 읽는다.
 *
 * <p>비어 있어도 된다. 브로커가 먼저 뜨면 아직 아무도 등록하지 않았고, 그것은 사실이다.
 */
@Component
public class CandidateRegistry {

    private static final String PATTERN = "classpath*:/mcp/candidates/*.json";

    private final Map<String, JsonNode> byServerId = new ConcurrentHashMap<>();
    private final Set<String> fromFiles = ConcurrentHashMap.newKeySet();

    public CandidateRegistry() {
        this(null, PATTERN);
    }

    /**
     * 처음부터 등록된 카드 한 장과 후보 파일 패턴을 지정하는 생성자. 시험이 쓰는 통로다.
     *
     * @param registered 등록된 것으로 칠 카드. 없으면 {@code null}
     */
    CandidateRegistry(JsonNode registered, String pattern) {
        ObjectMapper mapper = new ObjectMapper();
        Resource[] found;
        try {
            found = new PathMatchingResourcePatternResolver().getResources(pattern);
        } catch (IOException e) {
            throw new UncheckedIOException("후보 카드를 찾지 못했습니다: " + pattern, e);
        }
        // 클래스패스가 돌려주는 순서는 환경마다 다르다. 파일 이름으로 고정해야 겹침 오류가
        // 늘 같은 카드를 가리킨다.
        List<Resource> ordered = new ArrayList<>(List.of(found));
        ordered.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));

        for (Resource r : ordered) {
            JsonNode card;
            try (InputStream in = r.getInputStream()) {
                card = mapper.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException("후보 카드를 읽지 못했습니다: " + r.getFilename(), e);
            }
            String serverId = requireServerId(card, String.valueOf(r.getFilename()));
            if (byServerId.putIfAbsent(serverId, card) != null) {
                throw new IllegalStateException(
                        "serverId 가 겹칩니다: " + serverId + " (" + r.getFilename() + ")"
                                + " — 조용히 덮으면 어느 카드가 살아남았는지 알 수 없습니다");
            }
            fromFiles.add(serverId);
        }
        if (registered != null) {
            register(registered);
        }
    }

    /**
     * 시뮬레이터 서버의 등록을 받는다. 같은 서버의 재등록은 새 카드로 바꾼다.
     *
     * @return 처음 등록이면 {@code true}, 재등록이면 {@code false}
     * @throws IllegalArgumentException 카드에 serverId·endpoint 가 없거나, 후보 파일의 id 와 겹치거나, 거주민 유형 key 가 겹칠 때
     */
    public boolean register(JsonNode card) {
        String serverId = requireServerId(card, "등록 요청");
        if (card.path("endpoint").asText().isBlank()) {
            throw new IllegalArgumentException(
                    "카드에 endpoint 가 없습니다: " + serverId
                            + " — 브로커가 연결 정보를 내주지 못하면 LLM 이 고른 서버로 갈 수 없습니다");
        }
        if (fromFiles.contains(serverId)) {
            throw new IllegalArgumentException(
                    "후보 파일의 서버와 id 가 겹칩니다: " + serverId
                            + " — 지어낸 후보를 실제 서버로 덮으면 어느 쪽을 고른 것인지 알 수 없습니다");
        }
        Set<String> populationKeys = new HashSet<>();
        for (JsonNode t : card.path("populationTypes")) {
            String key = t.path("key").asText();
            if (!populationKeys.add(key)) {
                throw new IllegalArgumentException(
                        "거주민 유형 key 가 겹칩니다: " + serverId + " / " + key
                                + " — 같은 유형이 두 번 있으면 어느 모델로 간 것인지 알 수 없습니다");
            }
        }
        return byServerId.put(serverId, card) == null;
    }

    private static String requireServerId(JsonNode card, String where) {
        String serverId = card == null ? "" : card.path("serverId").asText();
        if (serverId.isBlank()) {
            throw new IllegalArgumentException(
                    "카드에 serverId 가 없습니다: " + where
                            + " — 이름 없는 후보는 고를 수도 되물을 수도 없습니다");
        }
        return serverId;
    }

    /** 알고 있는 카드 전부. 서버 id 오름차순으로 고정된다. */
    public List<JsonNode> cards() {
        return byServerId.values().stream()
                .sorted(Comparator.comparing(c -> c.path("serverId").asText()))
                .toList();
    }

    /** 알고 있는 서버 id 전부, 오름차순. */
    public List<String> serverIds() {
        return byServerId.keySet().stream().sorted().toList();
    }

    public Optional<JsonNode> byServerId(String serverId) {
        return Optional.ofNullable(byServerId.get(serverId));
    }
}
