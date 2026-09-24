package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.capability.CapabilityCardLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 등록된 시뮬레이터의 능력 카드를 모아 둔다 — 브로커가 후보를 고르는 유일한 근거다.
 *
 * <p>카드를 자바 모델로 옮기지 않고 원문 {@link JsonNode} 로 들고 있는다.
 * {@code CapabilityCardLoader} 와 같은 이유다 — 중간에 모델을 거치면 칸이 하나 늘 때마다
 * 코드를 고쳐야 하고, 빠뜨린 칸은 조용히 사라진다. 매칭기는 자기가 보는 칸만 꺼내 읽는다.
 *
 * <p>클래스패스 패턴으로 읽으므로 jar 로 묶은 뒤에도 같은 카드를 찾는다. 디렉터리를
 * 파일로 훑으면 개발 중에만 되고 배포하면 빈 목록이 된다.
 *
 * <p><b>이 서버 자신의 카드가 항상 먼저 들어간다.</b> 명세 0단계가 "시뮬레이터가 브로커에
 * 등록" 인데, 장량동 서버와 브로커가 같은 프로세스에 있으므로 그 등록이 곧 자기 카드를
 * 싣는 일이다. 자기 카드를 복사해 후보 디렉터리에 또 두면 같은 JSON 이 세 벌이 되고,
 * 갈라졌을 때 어느 것이 정본인지 알 수 없다.
 *
 * <p>그래서 후보 디렉터리는 <b>비어 있어도 된다</b> — 아직 등록해 온 외부 서버가 없다는
 * 뜻이고, 그것은 사실이다. 자기 카드까지 없을 때만 예외를 던진다.
 */
@Component
public class CandidateRegistry {

    private static final String PATTERN = "classpath*:/mcp/candidates/*.json";

    private final Map<String, JsonNode> byServerId = new LinkedHashMap<>();

    @Autowired
    public CandidateRegistry(CapabilityCardLoader self) {
        this(self.rawJson(), PATTERN);
    }

    /**
     * 자기 카드와 패턴을 지정하는 생성자. 시험이 "못 읽으면 예외를 던진다" 를 직접 확인할
     * 통로다. 운영 코드는 {@link #PATTERN} 만 쓴다.
     *
     * @param selfCard 이 서버 자신의 카드. 없으면 {@code null}
     */
    CandidateRegistry(JsonNode selfCard, String pattern) {
        ObjectMapper mapper = new ObjectMapper();
        if (selfCard != null) {
            put(selfCard, "자기 카드");
        }
        Resource[] found;
        try {
            found = new PathMatchingResourcePatternResolver().getResources(pattern);
        } catch (IOException e) {
            throw new UncheckedIOException("후보 카드를 찾지 못했습니다: " + pattern, e);
        }
        // 클래스패스가 돌려주는 순서는 환경마다 다르다. 서버 id 로 고정해야 같은 요청이
        // 같은 후보 순위를 낸다고 말할 수 있다.
        List<Resource> ordered = new ArrayList<>(List.of(found));
        ordered.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));

        for (Resource r : ordered) {
            JsonNode card;
            try (InputStream in = r.getInputStream()) {
                card = mapper.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException("후보 카드를 읽지 못했습니다: " + r.getFilename(), e);
            }
            put(card, String.valueOf(r.getFilename()));
        }

        if (byServerId.isEmpty()) {
            throw new IllegalStateException(
                    "카드가 한 장도 없습니다: " + pattern
                            + " — 빈 레지스트리로 넘어가면 매칭이 늘 '후보 없음' 을 냅니다");
        }
    }

    private void put(JsonNode card, String where) {
        String serverId = card.path("serverId").asText();
        if (serverId.isBlank()) {
            throw new IllegalStateException(
                    "카드에 serverId 가 없습니다: " + where
                            + " — 이름 없는 후보는 고를 수도 되물을 수도 없습니다");
        }
        JsonNode prev = byServerId.put(serverId, card);
        if (prev != null) {
            throw new IllegalStateException(
                    "serverId 가 겹칩니다: " + serverId + " (" + where + ")"
                            + " — 조용히 덮으면 어느 카드가 살아남았는지 알 수 없습니다");
        }
    }

    /** 등록된 카드 전부. 서버 id 오름차순으로 고정된다. */
    public List<JsonNode> cards() {
        return byServerId.values().stream()
                .sorted(Comparator.comparing(c -> c.path("serverId").asText()))
                .toList();
    }

    /** 등록된 서버 id 전부, 오름차순. */
    public List<String> serverIds() {
        return byServerId.keySet().stream().sorted().toList();
    }

    public Optional<JsonNode> byServerId(String serverId) {
        return Optional.ofNullable(byServerId.get(serverId));
    }
}
