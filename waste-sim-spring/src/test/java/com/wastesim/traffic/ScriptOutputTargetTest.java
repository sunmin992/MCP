package com.wastesim.traffic;

import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 전처리 스크립트의 기본 출력 대상이 <b>실제로 로드되는 프로파일</b>과 같은지 고정한다.
 *
 * <p>이 테스트가 있는 이유는 실제로 어긋났기 때문이다. 스크립트 기본값이 한동안
 * {@code jangryang-weekday-real.json}이었는데 그 파일은
 * {@link TrafficDataService} 의 {@code SEED_IDS}에 없어서 로드되지 않았다. 그래서 문서에 적힌
 * 갱신 절차를 그대로 따르면 —
 *
 * <ol>
 *   <li>스크립트가 {@code WROTE ...}를 찍고 정상 종료한다</li>
 *   <li>테스트도 전부 통과한다(운영 프로파일이 그대로라 깨질 것이 없다)</li>
 *   <li>운영 프로파일은 갱신되지 않았다</li>
 * </ol>
 *
 * <p>세 단계 어디에도 경고가 없어서, "갱신했다고 믿는" 상태가 남는다. 사람이 지키는
 * 절차로는 이 종류의 어긋남을 못 막는다 — 절차는 잊히지만 테스트는 잊히지 않는다.
 *
 * <p>스크립트를 파싱하는 방식이 다소 거칠지만, 대안은 "다음 사람이 두 파일을 눈으로
 * 비교하기"뿐이다. 그건 이미 한 번 실패했다.
 */
class ScriptOutputTargetTest {

    private static final File SCRIPT = new File("scripts/preprocess_response_filtered.py");

    /** {@code X = opts.get("--flag", "값")} 에서 값만 뽑는다. */
    private static String defaultOf(String source, String variable) {
        Matcher m = Pattern.compile(
                "^\\s*" + variable + "\\s*=\\s*opts\\.get\\(\\s*\"--[a-z]+\"\\s*,\\s*\"([^\"]+)\"\\s*\\)",
                Pattern.MULTILINE).matcher(source);
        assertTrue(m.find(), variable + " 의 기본값을 스크립트에서 찾지 못했습니다. "
                + "스크립트의 형태가 바뀌었다면 이 테스트도 함께 고쳐야 합니다.");
        return m.group(1);
    }

    private static String script() throws IOException {
        assertTrue(SCRIPT.isFile(), "전처리 스크립트가 없습니다: " + SCRIPT.getAbsolutePath());
        return Files.readString(SCRIPT.toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void scriptDefaultProfileIdIsOneThatActuallyGetsLoaded() throws IOException {
        String id = defaultOf(script(), "PROFILE_ID");

        assertTrue(TrafficDataService.seedIds().contains(id),
                "스크립트가 기본으로 만드는 프로파일 id(" + id + ")가 로드되지 않습니다. "
                        + "로드되는 id: " + TrafficDataService.seedIds()
                        + " — 갱신 절차를 따라도 반영되지 않고, 그 사실이 아무 데도 드러나지 않습니다.");
    }

    @Test
    void scriptDefaultOutputPathIsTheFileTheLoaderReads() throws IOException {
        String out = defaultOf(script(), "OUT");
        String id = defaultOf(script(), "PROFILE_ID");

        assertEquals("src/main/resources/traffic/" + id + ".json", out,
                "출력 경로와 프로파일 id가 어긋나면 로더가 파일을 찾아도 id가 다르거나 그 반대가 됩니다.");
        assertTrue(new File(out).isFile(), "기본 출력 경로에 파일이 없습니다: " + out);
    }

    /**
     * 문서가 스크립트 코드를 다시 복사해 싣지 않는지 본다. 전문을 싣던 동안 진실 원천이
     * 둘이 되어 한쪽만 낡았고, 그게 위 결함의 직접 원인이었다.
     */
    @Test
    void guideDoesNotEmbedACopyOfTheScript() throws IOException {
        File guide = new File("docs/guides/CONNECT_TRAFFIC_CSV.md");
        assertTrue(guide.isFile(), "가이드 문서가 없습니다: " + guide.getAbsolutePath());
        List<String> lines = Files.readAllLines(guide.toPath(), StandardCharsets.UTF_8);

        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            assertFalse(l.contains("opts.get(") || l.contains("def classify(") || l.contains("json.dump("),
                    "가이드가 스크립트 코드를 다시 싣고 있습니다(" + (i + 1) + "행). "
                            + "경로와 옵션만 참조하고 코드는 scripts/ 한 곳에 두세요.");
        }
    }
}
