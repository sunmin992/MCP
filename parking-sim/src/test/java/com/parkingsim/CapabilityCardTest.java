package com.parkingsim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityCardTest {
    @Test
    void timeOfDayAloneDoesNotClaimRoadCongestion() throws Exception {
        try (var input = getClass().getResourceAsStream("/mcp/parking-capability-card.json")) {
            assertNotNull(input);
            JsonNode card = new ObjectMapper().readTree(input);
            JsonNode road = null;
            for (JsonNode environment : card.path("applicability").path("environmentMatchKeys")) {
                if (environment.path("key").asText().equals("road-congestion")) road = environment;
            }
            assertNotNull(road);
            for (String phrase : new String[]{"주말 오후", "평일 오전", "출근길", "퇴근길"}) {
                assertFalse(matches(road, phrase), phrase + "만으로 도로 혼잡을 추정하면 안 된다");
            }
            for (String phrase : new String[]{"주말 오후 교통량", "평일 도로 혼잡", "퇴근길 정체"}) {
                assertTrue(matches(road, phrase), phrase);
            }
        }
    }

    private boolean matches(JsonNode environment, String phrase) {
        for (JsonNode key : environment.path("matchKeys")) {
            if (phrase.contains(key.asText())) return true;
        }
        return false;
    }
}
