package com.wastesim.ses;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 커밋된 템플릿 JSON 이 SES 원본에서 만든 것과 같은가.
 *
 * <p>{@code -Dses.regenerate=true} 로 돌면 비교하지 않고 파일을 다시 쓴다.
 */
class SesDerivationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Path TEMPLATES = Path.of("src/main/resources/ses/jangnyang-templates.json");

    @Test
    void 템플릿은_원본에서_만든_것과_같다() throws Exception {
        ObjectNode derived = SesDerivation.templates(SesTree.load(SesTree.DEFAULT_RESOURCE));
        if (Boolean.getBoolean("ses.regenerate")) {
            // 시스템 줄바꿈(Windows CRLF)이 아니라 LF 로, 배열도 한 줄에 한 원소로 쓴다.
            DefaultIndenter lf = new DefaultIndenter("  ", "\n");
            DefaultPrettyPrinter pp = new DefaultPrettyPrinter().withObjectIndenter(lf);
            pp.indentArraysWith(lf);
            Files.writeString(TEMPLATES, M.writer(pp).writeValueAsString(derived) + "\n");
            return;
        }
        assertEquals(read("/ses/jangnyang-templates.json"), derived,
                "jangnyang-ses.json 과 템플릿이 어긋납니다 — ./mvnw -pl simulator test -Dtest=SesDerivationTest -Dses.regenerate=true 로 다시 만드십시오");
    }

    /** 원본화가 동작을 바꾸지 않았다는 증거: sesVersion 말고는 1.0.0 템플릿과 칸 하나하나가 같다. */
    @Test
    void sesVersion_외에는_예전_템플릿과_같다() throws Exception {
        ObjectNode derived = SesDerivation.templates(SesTree.load(SesTree.DEFAULT_RESOURCE));
        ObjectNode before = (ObjectNode) read("/ses/templates-1.0.0.json");
        derived.remove("sesVersion");
        before.remove("sesVersion");
        assertEquals(before, derived);
    }

    private static JsonNode read(String resource) throws Exception {
        try (InputStream in = SesDerivationTest.class.getResourceAsStream(resource)) {
            return M.readTree(in);
        }
    }
}
