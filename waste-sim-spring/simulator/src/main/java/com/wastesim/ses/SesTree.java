package com.wastesim.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 시뮬레이터 하나의 SES 트리 원본. 템플릿과 카드 digest 는 여기서 파생한다.
 *
 * <p>형식은 docs/superpowers/specs/2026-11-02-SES-원본화-design.md §6-1 이 정본이다.
 *
 * @param raw       읽은 JSON 전체
 * @param decisions 원본 순서 그대로의 결정 목록 (= 템플릿 순서)
 */
public record SesTree(String sesId, String sesVersion, JsonNode raw, List<Decision> decisions) {

    public static final String DEFAULT_RESOURCE = "/ses/jangnyang-ses.json";

    /**
     * @param sesPath  결정의 {@code at}
     * @param nodeKind {@code at} 을 트리에서 푼 결과
     * @param fields   결정 객체에서 {@code at} · {@code card} 를 뺀 것 (템플릿 칸)
     * @param card     카드 decisionPoint 칸. 없으면 null
     */
    public record Decision(String sesPath, String nodeKind, ObjectNode fields, ObjectNode card) {
    }

    public static SesTree load() {
        return load(DEFAULT_RESOURCE);
    }

    public static SesTree load(String classpathResource) {
        try (InputStream in = SesTree.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("SES 원본이 없습니다: " + classpathResource);
            }
            return parse(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("SES 원본을 읽지 못했습니다: " + classpathResource, e);
        }
    }

    public static SesTree parse(JsonNode root) {
        String sesId = text(root, "sesId");
        String sesVersion = text(root, "sesVersion");
        String rootName = text(root, "root");
        JsonNode entities = root.get("entities");
        if (entities == null || !entities.isObject() || !entities.has(rootName)) {
            throw new IllegalArgumentException("entities 가 없거나 루트 '" + rootName + "' 가 없습니다");
        }
        checkReachable(entities, rootName);

        JsonNode decisionsNode = root.get("decisions");
        if (decisionsNode == null || !decisionsNode.isArray()) {
            throw new IllegalArgumentException("decisions 배열이 없습니다");
        }
        List<Decision> decisions = new ArrayList<>();
        for (JsonNode d : decisionsNode) {
            if (!d.isObject() || !d.path("at").isTextual()) {
                throw new IllegalArgumentException("at 이 없는 결정입니다: " + d);
            }
            String at = d.get("at").asText();
            String nodeKind = resolve(entities, at, d);
            ObjectNode fields = ((ObjectNode) d).deepCopy();
            fields.remove("at");
            JsonNode card = fields.remove("card");
            if (card != null && !card.isObject()) {
                throw new IllegalArgumentException("card 는 객체여야 합니다: " + at);
            }
            decisions.add(new Decision(at, nodeKind, fields, (ObjectNode) card));
        }
        return new SesTree(sesId, sesVersion, root, List.copyOf(decisions));
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException(field + " 가 없습니다");
        }
        return n.asText();
    }

    /** 루트를 뺀 모든 엔티티 키는 다른 엔티티의 분해 children 에 나와야 한다. */
    private static void checkReachable(JsonNode entities, String rootName) {
        Set<String> children = new HashSet<>();
        entities.forEach(e -> e.path("decompositions")
                .forEach(dec -> dec.path("children").forEach(c -> children.add(c.asText()))));
        entities.fieldNames().forEachRemaining(key -> {
            if (!key.equals(rootName) && !children.contains(key)) {
                throw new IllegalArgumentException("어느 분해에서도 닿지 않는 엔티티입니다: " + key);
            }
        });
    }

    /** {@code at} 과 같거나 {@code at} 의 앞부분인 엔티티 키 중 가장 긴 것으로 푼다. */
    private static String resolve(JsonNode entities, String at, JsonNode decision) {
        String key = null;
        for (var it = entities.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if ((at.equals(k) || at.startsWith(k + "/")) && (key == null || k.length() > key.length())) {
                key = k;
            }
        }
        if (key == null) {
            throw new IllegalArgumentException("at 을 풀 엔티티가 없습니다: " + at);
        }
        JsonNode entity = entities.get(key);
        if (at.equals(key)) {
            for (JsonNode dec : entity.path("decompositions")) {
                if ("multi".equals(dec.path("kind").asText())) return "MULTI_ASPECT";
            }
            throw new IllegalArgumentException("엔티티에 multi 분해가 없습니다: " + at);
        }
        String name = at.substring(key.length() + 1);
        for (JsonNode spec : entity.path("specs")) {
            if (name.equals(spec.path("name").asText())) {
                if (!spec.path("children").equals(decision.path("allowed"))) {
                    throw new IllegalArgumentException("spec 선택지와 allowed 가 다릅니다: " + at);
                }
                return "SPECIALIZATION";
            }
        }
        if (contains(entity.path("attrs"), name)) return "ATTRIBUTE";
        if (contains(entity.path("couplingDecisions"), name)) return "COUPLING_CONDITION";
        throw new IllegalArgumentException("트리에 없는 노드입니다: " + at);
    }

    private static boolean contains(JsonNode array, String name) {
        for (JsonNode n : array) {
            if (name.equals(n.asText())) return true;
        }
        return false;
    }
}
