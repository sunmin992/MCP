package com.wastesim.ses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 코드에 선언한 트리와 연구 정답지 {@code reference-ses.json}이 갈라졌는지 본다.
 *
 * <p><b>정답지를 런타임에 읽지 않는 이유</b>: 그 파일은 LLM 추출 실험의 채점 기준이다.
 * 제품 코드가 그것을 읽으면 채점 기준을 고치는 일이 시뮬레이터 동작을 바꾸게 된다. 둘을
 * 떼어 놓되 서로를 붙잡게 하는 자리가 이 테스트다 — 갈라지면 깨지고, 어느 쪽이 옳은지는
 * 사람이 정한다.
 */
class JangnyangStructureMatchesReferenceTest {

    private static final Path REFERENCE =
            Path.of("docs/research/s1-ses-extraction/reference-ses.json");

    @Test
    void declaredStructureMatchesReferenceSes() throws Exception {
        assertTrue(Files.exists(REFERENCE), "정답지를 찾을 수 없다: " + REFERENCE.toAbsolutePath());
        JsonNode ref = new ObjectMapper().readTree(Files.readString(REFERENCE));
        EntityStructure declared = JangnyangEntityStructure.get();

        assertEquals(ref.get("root").asText(), declared.root(), "루트 이름이 다르다");

        JsonNode refEntities = ref.get("entities");
        List<String> refNames = new ArrayList<>();
        for (Iterator<String> it = refEntities.fieldNames(); it.hasNext(); ) refNames.add(it.next());
        List<String> declaredNames = declared.entities().stream().map(SesEntity::name).toList();
        assertEquals(refNames, declaredNames, "엔티티 목록 또는 순서가 다르다");

        for (String name : refNames) {
            JsonNode re = refEntities.get(name);
            SesEntity de = declared.entity(name);
            assertEquals(textList(re.get("attrs")), de.attributes(), name + "의 속성이 다르다");

            List<String> refDecs = new ArrayList<>();
            if (re.has("decompositions")) {
                for (JsonNode d : re.get("decompositions")) {
                    refDecs.add(d.get("kind").asText().toUpperCase() + "|" + d.get("name").asText()
                            + "|" + String.join(",", textList(d.get("children"))));
                }
            }
            List<String> decDecs = de.decompositions().stream()
                    .map(d -> d.kind().name() + "|" + d.name() + "|" + String.join(",", d.children()))
                    .toList();
            assertEquals(refDecs, decDecs, name + "의 분해가 다르다");
        }

        assertEquals(ref.get("couplings").size(), declared.couplings().size(), "결합 개수가 다르다");
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null) for (JsonNode n : node) out.add(n.asText());
        return out;
    }
}
