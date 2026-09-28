package com.wastesim.simulation;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.wastesim.model.SimulationConfig;
import com.wastesim.model.SimulationResult;
import com.wastesim.service.TrafficDataService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔진 결과를 못 박는다. 2단계에서 {@code SimulationEngine.run()}을 엔티티별 컴포넌트로
 * 쪼갤 때, 숫자가 흔들렸는지를 이 스냅샷이 판정한다.
 *
 * <p>스냅샷을 <b>파일</b>로 두는 이유: 기댓값을 테스트 코드에 적으면 값을 고칠 때 코드를
 * 고치게 되고, 그러면 "고쳐도 되는 변화"와 "고치면 안 되는 변화"가 같은 diff에 섞인다.
 * 파일이 바뀐 diff는 리뷰에서 바로 보인다.
 */
class SimulationEngineGoldenTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/golden");

    /** 값을 새로 뜨려면 이 값을 true로 바꿔 한 번 돌리고 <b>반드시 되돌린다</b>. */
    private static final boolean REGENERATE = false;

    private static Map<String, Consumer<SimulationConfig>> cases() {
        Map<String, Consumer<SimulationConfig>> cases = new LinkedHashMap<>();
        cases.put("baseline", cfg -> { });
        cases.put("tight-capacity", cfg -> {
            cfg.setCapacity(40.0);
            cfg.setThreshold(0.6);
            cfg.setDays(14);
        });
        cases.put("dense", cfg -> {
            cfg.setNumBuildings(8);
            cfg.setResidentsPerBuilding(30);
            cfg.setDays(10);
        });
        return cases;
    }

    @Test
    void engineResultsMatchGoldenSnapshots() throws Exception {
        // SORT_PROPERTIES_ALPHABETICALLY가 필요한 이유: SimulationResult에는 백킹 필드가 없는
        // 파생 getter(예: resultUseLabel, coordinateQualityLabel)가 섞여 있는데, 이런 getter의
        // 직렬화 순서는 선언 순서가 아니라 리플렉션이 메서드를 돌려주는 순서를 따른다. 이 순서는
        // 같은 코드를 실행해도 JVM 실행마다 달라질 수 있어(baseline을 만든 실행과 비교하는 실행이
        // 서로 다른 JVM 프로세스다), 정렬 없이는 엔진 로직이 그대로여도 순서만 바뀌어 골든
        // 테스트가 실패한다. 알파벳 순으로 고정해 이 흔들림을 없앤다.
        ObjectMapper mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        SimulationEngine engine = new SimulationEngine(new TrafficDataService());

        for (Map.Entry<String, Consumer<SimulationConfig>> e : cases().entrySet()) {
            SimulationConfig cfg = new SimulationConfig();
            e.getValue().accept(cfg);
            SimulationResult result = engine.run(cfg, 1);
            String actual = mapper.writeValueAsString(result);
            Path snapshot = GOLDEN_DIR.resolve(e.getKey() + ".json");

            if (REGENERATE) {
                Files.createDirectories(GOLDEN_DIR);
                Files.writeString(snapshot, actual, StandardCharsets.UTF_8);
                continue;
            }

            assertTrue(Files.exists(snapshot), "스냅샷이 없다: " + snapshot
                    + " — REGENERATE를 true로 두고 한 번 돌려 만든다");
            assertEquals(Files.readString(snapshot, StandardCharsets.UTF_8).replace("\r\n", "\n"),
                    actual.replace("\r\n", "\n"),
                    e.getKey() + " 결과가 스냅샷과 다르다");
        }
    }
}
