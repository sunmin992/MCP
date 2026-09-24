package com.wastesim.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.ses.ConfirmScenarioTool;
import com.wastesim.mcp.ses.ScenarioStore;
import com.wastesim.tool.ToolResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 확인 화면이 쓰는 경로.
 *
 * <p>토큰은 사람이 설정을 보고 동의했음을 뜻해야 한다. 그 동의가 일어나는 자리가
 * 여기다 — 화면이 없으면 {@code confirm_scenario} 를 LLM 이 혼자 불러도 막을 근거가 없고,
 * 그러면 토큰이 확인을 뜻한다고 말할 수 없다.
 *
 * <p>확인 로직을 다시 쓰지 않고 {@link ConfirmScenarioTool} 을 그대로 부른다. 두 벌로
 * 나뉘면 화면으로 확인한 것과 도구로 확인한 것이 달라질 수 있다.
 */
@RestController
@RequestMapping("/api/scenarios")
public class ScenarioConfirmController {

    /** 스프링이 바인딩한 주소. 지정하지 않으면 모든 인터페이스다. */
    private final String boundAddress;

    private final ScenarioStore store;
    private final ConfirmScenarioTool confirmTool;
    private final ObjectMapper mapper;

    public ScenarioConfirmController(ScenarioStore store, ConfirmScenarioTool confirmTool,
                                     ObjectMapper mapper,
                                     @Value("${server.address:}") String boundAddress) {
        this.boundAddress = boundAddress;
        this.store = store;
        this.confirmTool = confirmTool;
        this.mapper = mapper;
    }

    /** 보관 중인 시나리오 목록. 확인 화면의 왼쪽이다. */
    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> rows = store.all().stream()
                .map(e -> Map.<String, Object>of(
                        "scenarioId", e.scenario().scenarioId(),
                        "state", e.state(),
                        "runCount", e.scenario().runs().size(),
                        "unapprovedCount", e.pes().unapprovedDefaults().size()))
                .toList();

        // 노출 상태를 화면이 알 수 있게 함께 낸다. 이 서버에는 인증이 없어 루프백
        // 바인딩이 유일한 방어선인데, 그것이 풀린 것을 화면이 조용히 넘기면 확인하는
        // 사람은 자기 화면이 남에게도 열려 있다는 사실을 모른다.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenarios", rows);
        out.put("boundAddress", boundAddress == null || boundAddress.isBlank() ? "(모든 인터페이스)" : boundAddress);
        out.put("loopbackOnly", loopbackOnly(boundAddress));
        return out;
    }

    /**
     * 이 주소가 이 기기에서만 닿는가.
     *
     * <p>빈 값은 스프링 기본이고 그것은 모든 인터페이스다 — 안전한 쪽으로 넘겨짚지 않는다.
     */
    public static boolean loopbackOnly(String address) {
        if (address == null || address.isBlank()) return false;
        String a = address.trim();
        return a.equals("127.0.0.1") || a.equals("::1") || a.equals("localhost")
                || a.startsWith("127.");
    }

    /** 한 시나리오의 전부. 사용자가 무엇에 동의하는지 여기서 본다. */
    @GetMapping("/{scenarioId}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String scenarioId) {
        return store.entry(scenarioId)
                .<ResponseEntity<Map<String, Object>>>map(e -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("scenarioId", e.scenario().scenarioId());
                    out.put("state", e.state());
                    out.put("sesId", e.pes().sesId());
                    out.put("sesVersion", e.pes().sesVersion());
                    out.put("values", e.pes().values());
                    out.put("origins", e.pes().origins());
                    out.put("unapprovedDefaults", e.pes().unapprovedDefaults());
                    out.put("blocks", e.scenario().blocks());
                    out.put("runs", e.scenario().runs());
                    // 발급된 토큰은 상태의 일부다. 확인 직후에만 띄우면 화면을 새로
                    // 그리는 순간 사라지고, 사용자는 무엇을 들고 실행해야 할지 모른다.
                    out.put("confirmToken", e.scenario().confirmToken());
                    return ResponseEntity.ok(out);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 사용자가 "이 설정으로 돌린다" 를 누른 자리. */
    @PostMapping(value = "/{scenarioId}/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> confirm(@PathVariable String scenarioId) throws Exception {
        ToolResult result = confirmTool.call(
                mapper.createObjectNode().put("scenarioId", scenarioId));
        if (!result.ready()) {
            return ResponseEntity.badRequest()
                    .body(mapper.writeValueAsString(Map.of("errors", result.errors())));
        }
        return ResponseEntity.ok(String.valueOf(result.result()));
    }
}
