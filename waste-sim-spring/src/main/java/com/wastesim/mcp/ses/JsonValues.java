package com.wastesim.mcp.ses;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JSON 노드를 답변값으로 쓸 수 있는 평범한 자바 값으로 옮긴다.
 *
 * <p>평탄화가 세터의 매개변수 타입으로 값을 고르므로 여기서 정수와 실수를 뭉개면
 * 안 된다 — {@code 150} 을 실수로 올리면 {@code setDays(int)} 를 못 찾고,
 * {@code 150.5} 를 정수로 깎으면 배정 몫이 달라진다.
 */
final class JsonValues {

    private JsonValues() {}

    static Object plain(JsonNode v) {
        if (v.isInt()) return v.asInt();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber() && !v.isIntegralNumber()) return v.asDouble();
        if (v.isLong()) return v.asLong();
        return v.asText();
    }
}
