package com.wastesim.subtask;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 순서를 지키면서 불변으로 만드는 맵 복사.
 *
 * <p>{@link Map#copyOf}를 쓸 수 없다 — 그 구현은 <b>반복 순서를 보존하지 않고</b>, JVM
 * 실행마다 순서가 달라진다(ImmutableCollections의 순서 무작위화). 이 계층에서 순서는
 * 장식이 아니다.
 * <ul>
 *   <li>답변 맵의 순서는 <b>사용자가 답한 순서</b>이고, 감사(NFR-20)가 되짚는 것이 그
 *       순서다.</li>
 *   <li>MCP 응답 필드의 순서가 재시작마다 바뀌면 "같은 버전은 항상 같은 응답"이라는
 *       고정성 계약(FR-121)이 바이트 수준에서 깨진다.</li>
 * </ul>
 */
final class Ordered {

    private Ordered() {}

    /** 삽입 순서를 유지하는 불변 사본. {@code null}은 빈 맵으로 본다. */
    static <K, V> Map<K, V> copyOf(Map<K, V> src) {
        return Collections.unmodifiableMap(
                src == null ? new LinkedHashMap<>() : new LinkedHashMap<>(src));
    }
}
