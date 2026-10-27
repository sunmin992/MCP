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
    @Test void refusesRepeatedPages() {
        var api = new ParkingApiClient("dummy", mapper) {
            protected String request(URI uri) {
                return "{\"resultCode\":0,\"totalCount\":2,\"PrkSttusInfo\":[{\"prk_center_id\":\"same\"}]}";
            }
        };
        assertThatThrownBy(() -> api.fetch("PrkSttusInfo", "47113")).hasMessageContaining("중복");
    }
}
