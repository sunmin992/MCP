package com.wastesim.broker;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Predicate;

/**
 * 시뮬레이터 서버가 지금 응답하는가 — MCP {@code ping} 하나를 보내 본다.
 *
 * <p>등록 해제는 서버가 정상 종료될 때만 나간다. 강제 종료된 서버는 브로커에 남아 있고, 그대로 두면
 * 응답하지 않는 서버로 MATCH 가 나가 LLM 이 부를 수 없는 주소로 간다. 그래서 추천하기 전에 본다.
 * 등록은 지우지 않는다 — 잠깐 내려간 서버가 다시 뜨면 등록을 다시 보내지 않기 때문이다.
 */
@Component
public class EndpointProbe implements Predicate<String> {

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":\"broker-probe\",\"method\":\"ping\"}";

    // ponytail: 조회마다 후보를 하나씩 차례로 본다. 후보가 늘어 지연이 보이면 병렬 · 짧은 캐시로
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    @Override
    public boolean test(String endpoint) {
        try {
            // Windows 는 localhost 를 ::1 부터 붙여 보다가 IPv4 로 넘어가는 데 약 2초를 쓴다. 시뮬레이터는
            // 127.0.0.1 에만 열려 있어 조회마다 그만큼 늦어진다.
            // ponytail: localhost 만 바꾼다. 다른 이름이 같은 지연을 보이면 이름 해석을 IPv4 우선으로
            URI uri = URI.create(endpoint.replace("//localhost:", "//127.0.0.1:"));
            HttpResponse<Void> r = http.send(HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(2))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(PING)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
