package com.wastesim.ses;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Map;

/** {@code answerField}를 키로 문면을 읽는다 — ID가 아니라 필드명이 안정적이기 때문이다. */
public final class ProseCatalog {

    private static final String RESOURCE = "/subtask/jangnyang-prose-v5.json";

    private ProseCatalog() { }

    public static Map<String, ProseEntry> load() {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = ProseCatalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("문면 리소스가 없다: " + RESOURCE);
            JsonNode root = mapper.readTree(in);
            return mapper.convertValue(root.get("entries"),
                    new TypeReference<Map<String, ProseEntry>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("문면을 읽지 못했다: " + RESOURCE, e);
        }
    }
}
