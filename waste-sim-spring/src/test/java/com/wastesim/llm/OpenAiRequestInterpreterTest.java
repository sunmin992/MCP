package com.wastesim.llm;

import com.wastesim.service.OpenAiService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 해석기가 모델에게 <b>JSON을 요구하는 방식으로</b> 묻는가.
 *
 * <p>처음 구현은 {@code answerPlain}을 썼는데, 그것은 산문 답변용 시스템 프롬프트에
 * JSON 모드를 끈 호출이다. 모델이 문장을 돌려주고 해석기는 그것을 파싱하려다 <b>매번</b>
 * 실패했다 — 즉 LLM 경로가 켜져 있어도 실제로는 한 번도 동작하지 않고 늘 문항 흐름으로
 * 폴백했다. 화면에서는 "그냥 34문항이 뜨는" 것으로만 보여서 알아채기 어려웠다.
 *
 * <p>그래서 이 테스트들은 <b>모델이 JSON을 주면 값이 나온다</b>와 <b>산문을 주면 예외로
 * 알린다</b> 두 가지를 고정한다. 조용히 빈 결과를 돌려주면 폴백과 구별되지 않는다.
 */
class OpenAiRequestInterpreterTest {

    private static final String JSON = """
            {"values":[{"field":"numBuildings","value":26,"span":"26개 동"}],
             "targetRegion":"장량동","targetDomain":"생활쓰레기","requestedConclusion":""}
            """;

    @Test
    void parsesValuesAndJudgementFieldsFromModelJson() throws Exception {
        OpenAiService openAi = mock(OpenAiService.class);
        when(openAi.extractJson(anyString(), anyString())).thenReturn(JSON);

        RequestExtraction e = new OpenAiRequestInterpreter(openAi)
                .extract("장량동 26개 동으로 돌려줘", List.of("numBuildings"));

        assertEquals(1, e.values().size());
        assertEquals("numBuildings", e.values().get(0).field());
        assertEquals("26개 동", e.values().get(0).span(),
                "인용 조각이 사라지면 SpanVerifier가 검사할 것이 없어진다");
        assertEquals("장량동", e.targetRegion());
    }

    /** 산문이 오면 예외로 알린다 — 조용히 빈 결과를 내면 폴백과 구별할 수 없다. */
    @Test
    void proseAnswerIsReportedAsFailureNotAsAnEmptyExtraction() {
        OpenAiService openAi = mock(OpenAiService.class);
        when(openAi.extractJson(anyString(), anyString()))
                .thenReturn("죄송합니다. 요청을 이해하지 못했습니다.");

        assertThrows(InterpreterException.class,
                () -> new OpenAiRequestInterpreter(openAi).extract("장량동", List.of("days")),
                "파싱 실패를 빈 결과로 바꾸면 '아무것도 못 읽었다'와 '해석기가 고장났다'가 같아진다");
    }

    /** 사용자 문장은 지시문과 섞지 않고 따로 보낸다 — 섞으면 지시문이 인용 대상이 된다. */
    @Test
    void theUserSentenceIsSentSeparatelyFromTheInstructions() throws Exception {
        OpenAiService openAi = mock(OpenAiService.class);
        when(openAi.extractJson(anyString(), anyString())).thenReturn(JSON);

        new OpenAiRequestInterpreter(openAi).extract("장량동 26개 동", List.of("numBuildings"));

        verify(openAi).extractJson(argThat(instr -> instr.contains("span")),
                eq("장량동 26개 동"));
    }
}
