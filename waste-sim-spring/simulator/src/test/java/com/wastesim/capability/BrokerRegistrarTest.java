package com.wastesim.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 명세 0단계 — 시뮬레이터가 브로커에 무엇을 어디로 보내는가.
 */
class BrokerRegistrarTest {

    private final MockWebServer broker = new MockWebServer();
    private final CapabilityCardLoader loader = new CapabilityCardLoader();

    @BeforeEach void start() throws Exception { broker.start(); }
    @AfterEach void stop() throws Exception { broker.shutdown(); }

    private BrokerRegistrar registrar() {
        return new BrokerRegistrar(loader, broker.url("/").toString(), 30);
    }

    @Test
    void 자기_카드_원문을_등록_경로로_보낸다() throws Exception {
        broker.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        registrar().register();

        RecordedRequest req = broker.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(req);
        assertEquals("POST", req.getMethod());
        assertEquals("/api/simulators", req.getPath());
        assertEquals(loader.rawJson(), new ObjectMapper().readTree(req.getBody().readUtf8()),
                "브로커가 보고 고른 카드와 get_capability 가 내는 카드가 다르면 무엇을 보고 고른 것인지 알 수 없다");
    }

    @Test
    void 브로커가_거절하거나_없어도_예외_없이_실패로_남는다() throws Exception {
        broker.enqueue(new MockResponse().setResponseCode(400).setBody("{\"registered\":false}"));
        BrokerRegistrar r = registrar();
        r.register();
        assertEquals(Boolean.FALSE, r.lastOk());

        broker.shutdown();
        assertDoesNotThrow(r::register, "브로커가 없다고 이 서버가 멈추면 안 된다");
        assertEquals(Boolean.FALSE, r.lastOk());
    }

    @Test
    void 성공하면_성공으로_남는다() {
        broker.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        BrokerRegistrar r = registrar();
        r.register();
        assertEquals(Boolean.TRUE, r.lastOk());
    }
}
