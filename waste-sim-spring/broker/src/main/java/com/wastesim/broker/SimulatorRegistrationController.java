package com.wastesim.broker;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 명세 0단계 — 시뮬레이터 MCP 서버가 자기 능력 카드를 브로커에 등록하는 채널.
 *
 * <p>MCP 도구가 아니다. LLM 이 부르는 문(/mcp)과 서버끼리 쓰는 문을 나눈다 — 같은 문에 두면
 * LLM 이 카드를 지어 등록하고 그 카드를 스스로 고를 수 있다.
 */
@RestController
@RequestMapping("/api/simulators")
public class SimulatorRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRegistrationController.class);

    private final CandidateRegistry registry;

    public SimulatorRegistrationController(CandidateRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(@RequestBody JsonNode card) {
        try {
            boolean first = registry.register(card);
            String serverId = card.path("serverId").asText();
            if (first) {
                log.info("시뮬레이터 등록: {} ({})", serverId, card.path("endpoint").asText());
            }
            return ResponseEntity.ok(Map.of("serverId", serverId, "registered", true, "first", first));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("registered", false, "reason", e.getMessage()));
        }
    }

    /** 브로커가 지금 아는 서버 id. 등록이 들어갔는지 사람이 확인하는 자리다. */
    @GetMapping
    public List<String> list() {
        return registry.serverIds();
    }
}
