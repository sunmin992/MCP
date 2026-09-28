package com.wastesim.capability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 명세 0단계 — 이 시뮬레이터 MCP 서버가 자기 능력 카드를 브로커 MCP 서버에 등록한다.
 *
 * <p>보내는 것은 {@link CapabilityCardLoader} 가 {@code get_capability} 로 내는 카드 원문
 * 그대로다. 브로커가 보고 고른 카드와 이 서버가 스스로 내는 카드가 다르면 무엇을 보고
 * 고른 것인지 알 수 없다.
 *
 * <p>실패해도 이 서버는 계속 돈다. 로그는 상태가 바뀔 때만 남긴다 — 브로커가 꺼져 있는 동안
 * 30초마다 경고가 쌓이면 정작 바뀐 순간을 찾을 수 없다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "broker.registration.enabled", havingValue = "true", matchIfMissing = true)
public class BrokerRegistrar {

    private static final Logger log = LoggerFactory.getLogger(BrokerRegistrar.class);

    private final CapabilityCardLoader card;
    private final URI target;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final int intervalSeconds;
    private volatile Boolean lastOk;

    public BrokerRegistrar(CapabilityCardLoader card,
                           @Value("${broker.url}") String brokerUrl,
                           @Value("${broker.registration.interval-seconds:30}") int intervalSeconds) {
        this.card = card;
        this.target = URI.create(brokerUrl.replaceAll("/+$", "") + "/api/simulators");
        this.intervalSeconds = intervalSeconds;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "#{${broker.registration.interval-seconds:30} * 1000}")
    public void register() {
        boolean ok;
        String detail;
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(target)
                            .timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    card.rawJson().toString(), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ok = res.statusCode() / 100 == 2;
            detail = res.statusCode() + " " + res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            ok = false;
            detail = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        if (!Boolean.valueOf(ok).equals(lastOk)) {
            if (ok) {
                log.info("브로커에 등록했습니다: {} → {}", card.card().serverId(), target);
            } else {
                log.warn("브로커에 등록하지 못했습니다({}) — {}. {}초마다 다시 시도합니다.",
                        target, detail, intervalSeconds);
            }
        }
        lastOk = ok;
    }

    /** 마지막 등록이 성공했는가. 아직 한 번도 안 했으면 {@code null}. */
    public Boolean lastOk() {
        return lastOk;
    }
}
