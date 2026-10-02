package com.wastesim.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 도구 호출마다 명세 그림의 몇 단계인지와 결과 요약이 한 줄로 남는가.
 */
@ExtendWith(OutputCaptureExtension.class)
class McpCallLogTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final McpCallLog log = new McpCallLog(mapper);

    private ObjectNode result(String text, boolean isError) {
        ObjectNode r = mapper.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", text);
        r.put("isError", isError);
        return r;
    }

    @Test
    void 시나리오_생성은_id_조건수_상태_미승인수로_요약한다(CapturedOutput out) throws Exception {
        // 도구 결과는 JSON 문자열이 한 번 더 문자열로 싸여 온다 — 그대로 풀어야 한다.
        String inner = "{\"scenarioId\":\"scn-1\",\"runCount\":5,\"state\":\"UNCONFIRMED\","
                + "\"unapprovedDefaults\":{\"seeds\":30,\"days\":30},\"blocks\":[]}";
        log.called("build_scenario", result(mapper.writeValueAsString(inner), false));
        assertTrue(out.getOut().contains("[9·10 시나리오] build_scenario → scn-1 · 5벌 · UNCONFIRMED · 미승인 2"),
                out.getOut());
    }

    @Test
    void 거절은_거절로_남긴다(CapturedOutput out) {
        log.called("run_scenario_by_token", result("검증 실패: 이미 실행한 시나리오입니다", true));
        assertTrue(out.getOut().contains("[13·14 실행] run_scenario_by_token → 거절: 검증 실패: 이미 실행한"),
                out.getOut());
    }

    @Test
    void 확인_흐름_밖의_도구는_기존_도구로_표시한다(CapturedOutput out) {
        log.called("run_waste_simulation", result("{\"confirmed\":false}", false));
        assertTrue(out.getOut().contains("[기존 도구] run_waste_simulation → confirmed=false"), out.getOut());
    }
}
