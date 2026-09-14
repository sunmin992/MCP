package com.wastesim.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 벤치마크가 폐기된 의도 분류가 아니라 최신 서브태스크 계약을 읽는지 고정한다. */
class TemplateBenchmarkContractTest {
    private static String source() throws Exception {
        return Files.readString(Path.of("llm_benchmark.py"), StandardCharsets.UTF_8);
    }

    @Test @DisplayName("프롬프트는 v4 템플릿의 제약과 invalid 정책을 읽는다")
    void promptComesFromTemplate() throws Exception {
        String p=source();
        for (String token : new String[]{"jangnyang-simulator-v4.json", "answerType", "allowedRange",
                "retryQuestion", "invalidValuePolicy"}) assertTrue(p.contains(token), token);
    }

    @Test @DisplayName("추출·span·제약·재질문·시나리오 보존을 측정한다")
    void measuresBlueprintPipeline() throws Exception {
        String p=source();
        for (String metric : new String[]{"필드 재현", "값 정확", "span", "제약판정", "재질문", "시나리오"})
            assertTrue(p.contains(metric), metric);
        assertFalse(p.contains("run_intent_benchmark"));
        assertFalse(p.contains("run_fidelity_benchmark"));
        assertFalse(p.contains("run_jailbreak_benchmark"));
    }

    @Test @DisplayName("유효값과 제약 위반값 케이스를 템플릿과 대조한다")
    void casesAreTemplateBound() throws Exception {
        String p=source();
        assertTrue(p.contains("check_cases(fields)"));
        assertTrue(p.contains("\"invalid\""));
        assertTrue(p.contains("\"reask\""));
        assertTrue(p.contains("forbidden_all"));
    }
}
