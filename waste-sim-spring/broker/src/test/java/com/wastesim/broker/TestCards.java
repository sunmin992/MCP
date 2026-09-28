package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * 시험이 "등록된" 장량동 카드로 쓰는 것.
 *
 * <p>브로커는 시뮬레이터 모듈을 볼 수 없으므로 문서의 정본을 읽는다. 시뮬레이터가 서빙하는
 * 카드가 이 정본과 같다는 것은 시뮬레이터 쪽 시험({@code 리소스와_문서의_능력카드가_같다})이
 * 지킨다 — 그래서 여기서 읽는 카드가 실제로 등록되는 카드다.
 */
final class TestCards {

    private TestCards() {}

    static JsonNode jangnyang() {
        try {
            return new ObjectMapper().readTree(
                    new File("../docs/superpowers/specs/jangnyang-capability-card.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
