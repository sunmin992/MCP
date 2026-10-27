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

    @Test
    void timeOfDayPhrasesMatchDemandAndCongestionStillWins() throws Exception {
        try (var input = getClass().getResourceAsStream("/mcp/parking-capability-card.json")) {
            JsonNode card = new ObjectMapper().readTree(input);
            // 브로커는 위에서부터 처음 걸리는 키를 고른다.
            for (String phrase : new String[]{"주말 오후", "평일 오전", "출근길", "퇴근 시간대"}) {
                assertEquals("time-of-day-demand", firstMatch(card, phrase), phrase);
            }
            for (String phrase : new String[]{"주말 오후 교통량", "퇴근길 정체"}) {
                assertEquals("road-congestion", firstMatch(card, phrase), phrase);
            }
        }
    }

    @Test
    void realDataPhrasesMatchApiOccupancyAndAreNotDeclaredUnsupported() throws Exception {
        // 에이전트가 실제로 보낸 구절(2026-10-27 AP-A1 · A2 · A3). 걸리지 않으면 브로커가 "이 조건을 빼면 됩니다" 라고
        // 해, 서버가 할 수 있는 API 모드를 사용자 요청에서 지우게 만든다.
        try (var input = getClass().getResourceAsStream("/mcp/parking-capability-card.json")) {
            JsonNode card = new ObjectMapper().readTree(input);
            for (String phrase : new String[]{"공영주차장 실제 잔여면수", "실제 잔여면수", "지금 상태", "실제 데이터",
                    "공영주차장 실시간 잔여면수", "현재 점유 상태"}) {
                for (JsonNode u : card.path("unsupported")) {
                    assertFalse(matches(u, phrase), phrase + " 이 미지원 " + u.path("capability").asText() + " 에 걸린다");
                }
                assertEquals("api-initial-occupancy", firstMatch(card, phrase), phrase);
            }
        }
    }

    private String firstMatch(JsonNode card, String phrase) {
        for (JsonNode environment : card.path("applicability").path("environmentMatchKeys")) {
            if (matches(environment, phrase)) return environment.path("key").asText();
        }
        return null;
    }

    private boolean matches(JsonNode environment, String phrase) {
        for (JsonNode key : environment.path("matchKeys")) {
            if (phrase.contains(key.asText())) return true;
        }
        return false;
    }
}
