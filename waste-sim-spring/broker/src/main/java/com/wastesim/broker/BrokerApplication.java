package com.wastesim.broker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 브로커 MCP 서버 — 명세 §1 의 "브로커 MCP 서버".
 *
 * <p>등록된 시뮬레이터의 능력 카드만 들고 있고, LLM 의 3단계 조회에 4단계 매칭으로 답한다.
 * 시뮬레이터의 코드는 이 프로세스에 없다 — 고르는 근거가 카드뿐이라는 것을 구조로 보장한다.
 */
@SpringBootApplication
public class BrokerApplication {
    public static void main(String[] args) {
        SpringApplication.run(BrokerApplication.class, args);
    }
}
