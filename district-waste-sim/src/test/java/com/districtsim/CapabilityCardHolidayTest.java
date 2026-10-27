package com.districtsim;

import com.districtsim.model.DistrictConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 카드가 말하는 공휴일과 실제로 모의 기간에 들어오는 공휴일이 같은가.
 *
 * <p>기간은 시작일부터라 그 앞의 공휴일은 들어오지 않는다. 카드가 "설날" 을 지원한다고 하면 브로커가 MATCH 를
 * 내고, LLM 은 템플릿을 읽고서야 설날을 넣을 수 없다는 것을 안다(2026-10-27 NT-D8). 브로커는 미지원 선언을
 * 먼저 보므로, 기간 밖 공휴일 이름은 미지원 키에 걸리고 기간 안 이름은 걸리지 않아야 한다.
 */
class CapabilityCardHolidayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode read(String resource) throws Exception {
        try (var in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            return mapper.readTree(in);
        }
    }

    private static boolean hits(JsonNode keys, String phrase) {
        for (JsonNode k : keys) if (phrase.contains(k.asText())) return true;
        return false;
    }

    @Test
    void 기간_밖_공휴일은_미지원에_걸리고_기간_안_공휴일은_지원에_걸린다() throws Exception {
        JsonNode card = read("/mcp/district-capability-card.json");
        LocalDate start = LocalDate.parse(new DistrictConfig().getStartDate());
        JsonNode holidayKeys = null;
        for (JsonNode e : card.path("applicability").path("environmentMatchKeys")) {
            if (e.path("key").asText().equals("holidays")) holidayKeys = e.path("matchKeys");
        }
        assertNotNull(holidayKeys);

        for (JsonNode h : read("/schedule/kr-holidays-2026.json").path("holidays")) {
            String name = h.path("name").asText();
            boolean blocked = false;
            for (JsonNode u : card.path("unsupported")) blocked |= hits(u.path("matchKeys"), name);
            if (LocalDate.parse(h.path("date").asText()).isBefore(start)) {
                assertTrue(blocked, name + " 은 기간 밖인데 미지원으로 걸리지 않는다");
            } else {
                assertFalse(blocked, name + " 은 기간 안인데 미지원으로 걸린다");
                assertTrue(hits(holidayKeys, name), name + " 이 공휴일 조건에 걸리지 않는다");
            }
        }
    }
}
