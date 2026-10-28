package com.parkingsim.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class ParkingApiClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void handlesPaginationAndEncodesKeyOnce() {
        var requested = new ArrayList<URI>();
        var api = new ParkingApiClient("a%2Bb%2Fc%3D", mapper) {
            protected String request(URI uri) {
                requested.add(uri);
                return "{\"resultCode\":\"0\",\"totalCount\":\"2\",\"PrkSttusInfo\":[{\"prk_center_id\":\""
                        + requested.size() + "\"}]}";
            }
        };
        assertThat(api.fetch("PrkSttusInfo", "47113")).hasSize(2);
        assertThat(requested.get(1).toString()).contains("/PrkSttusInfo_v1?", "serviceKey=a%2Bb%2Fc%3D", "pageNo=2", "format=2");
        assertThat(ParkingApiClient.encodedKey("a+b/c=")).isEqualTo("a%2Bb%2Fc%3D");
    }
    @Test void rejectsErrorsAndMissingNumbersRatherThanZeroFilling() {
        for (String body : new String[]{"<OpenAPI_ServiceResponse>secret</OpenAPI_ServiceResponse>",
                "{\"resultCode\":\"30\",\"resultMsg\":\"secret\"}", "not-json-secret"}) {
            assertThatThrownBy(() -> ParkingApiClient.parse(body, "PrkRealtimeInfo", mapper))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret");
        }
        assertThatThrownBy(() -> ParkingApiClient.integer(mapper.createObjectNode(), "available"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ParkingApiClient("", mapper).fetch("PrkSttusInfo", "47113"))
                .hasMessageContaining("PARKING_API_SERVICE_KEY");
    }
    @Test void xmlErrorShowsOnlyThePortalReasonCode() {
        String xml = "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>"
                + "<returnAuthMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</returnAuthMsg>"
                + "<returnReasonCode>22</returnReasonCode><secret>serviceKey=abc%2Bdef</secret></cmmMsgHeader></OpenAPI_ServiceResponse>";
        assertThatThrownBy(() -> ParkingApiClient.parse(xml, "PrkSttusInfo", mapper))
                .hasMessageContaining("LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR")
                .hasMessageContaining("22").hasMessageNotContaining("abc").hasMessageNotContaining("serviceKey");
        // 새 게이트웨이는 header 의 resultCode · resultMsg 로 알린다.
        assertThatThrownBy(() -> ParkingApiClient.parse("<response><header><resultCode>30</resultCode>"
                + "<resultMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</resultMsg></header></response>", "PrkSttusInfo", mapper))
                .hasMessageContaining("SERVICE_KEY_IS_NOT_REGISTERED_ERROR").hasMessageContaining("30");
    }
    @Test void followsTotalCountPastFiftyPagesAndStopsWhenEnough() {
        // 포항 북구 시설 목록은 77쪽이다. 50쪽에서 끊으면 공영을 가리기 전에 조회가 실패한다.
        var requested = new ArrayList<URI>();
        var api = new ParkingApiClient("dummy", mapper) {
            protected String request(URI uri) {
                requested.add(uri);
                return "{\"resultCode\":\"0\",\"totalCount\":\"77\",\"PrkSttusInfo\":[{\"prk_center_id\":\"p"
                        + requested.size() + "\"}]}";
            }
        };
        assertThat(api.fetch("PrkSttusInfo", "47113")).hasSize(77);
        requested.clear();
        assertThat(api.fetch("PrkSttusInfo", "47113",
                rows -> rows.stream().anyMatch(r -> r.path("prk_center_id").asText().equals("p3")))).hasSize(3);
        assertThat(requested).hasSize(3);
    }
    @Test void refusesRepeatedPages() {
        var api = new ParkingApiClient("dummy", mapper) {
            protected String request(URI uri) {
                return "{\"resultCode\":0,\"totalCount\":2,\"PrkSttusInfo\":[{\"prk_center_id\":\"same\"}]}";
            }
        };
        assertThatThrownBy(() -> api.fetch("PrkSttusInfo", "47113")).hasMessageContaining("중복");
    }
    /** 쪽마다 행 수를 정해 주는 가짜 API. totalCount 는 실제보다 크게 준다. */
    private ParkingApiClient pages(int total, int... sizes) {
        return new ParkingApiClient("dummy", mapper) {
            protected String request(URI uri) {
                int page = Integer.parseInt(uri.toString().replaceAll(".*pageNo=(\\d+).*", "$1"));
                int size = page <= sizes.length ? sizes[page - 1] : 0;
                var rows = new StringBuilder();
                for (int i = 0; i < size; i++) rows.append(i == 0 ? "" : ",").append("{\"prk_center_id\":\"p").append(page).append('-').append(i).append("\"}");
                return "{\"resultCode\":\"0\",\"totalCount\":\"" + total + "\",\"PrkSttusInfo\":[" + rows + "]}";
            }
        };
    }
    @Test void overstatedTotalCountEndsAtTheShortPage() {
        // 경주: totalCount 15,022 건이라 하나 151쪽 14행으로 끝나고 152쪽은 비어 있다(2026-10-27 실측).
        assertThat(pages(122, 100, 14).fetch("PrkSttusInfo", "47130")).hasSize(114);
        assertThatThrownBy(() -> pages(250, 100, 100).fetch("PrkSttusInfo", "47130"))
                .as("꽉 찬 쪽 뒤의 빈 쪽은 빠진 것일 수 있다").hasMessageContaining("누락").hasMessageContaining("200/250");
    }
}
