package com.wastesim.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 확인 화면이 바깥에 열려 있지 않은가.
 *
 * <p>{@code /confirm.html} 과 {@code /api/scenarios/**} 에는 인증이 없다. 이 서버에
 * Spring Security 가 없어 {@code /mcp}·{@code /actuator} 도 같은 처지이고, 그래서
 * <b>루프백 바인딩이 유일한 방어선</b>이다. 그 줄이 지워지면 남이 남의 시나리오를
 * 확인해 줄 수 있다 — 확인이 "사람이 보고 동의했다" 를 뜻한다는 주장이 무너진다.
 *
 * <p>인증을 새로 만들지 않는 대신 이 시험이 그 한 줄을 지킨다.
 */
class ScenarioConfirmExposureTest {

    private String properties() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(in, "application.properties 를 찾지 못했다");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 기본_바인딩이_루프백이다() throws IOException {
        assertTrue(properties().contains("server.address=${SERVER_ADDRESS:127.0.0.1}"),
                "기본 바인딩이 루프백이 아니면 확인 화면이 같은 네트워크 전체에 열린다");
    }

    @Test
    void 루프백_판정이_주소를_가른다() {
        assertTrue(ScenarioConfirmController.loopbackOnly("127.0.0.1"));
        assertTrue(ScenarioConfirmController.loopbackOnly("::1"));
        assertTrue(ScenarioConfirmController.loopbackOnly("localhost"));
        assertFalse(ScenarioConfirmController.loopbackOnly("0.0.0.0"),
                "0.0.0.0 은 모든 인터페이스다 — 가장 흔한 실수이므로 반드시 걸러야 한다");
        assertFalse(ScenarioConfirmController.loopbackOnly("192.168.0.10"));
        assertFalse(ScenarioConfirmController.loopbackOnly(""),
                "빈 값은 스프링 기본(모든 인터페이스)이다 — 안전한 쪽으로 넘겨짚지 않는다");
    }
}
