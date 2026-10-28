package com.parkingsim.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StandardParkingApiClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void readsLiveItemsWrapper() {
        var api = new StandardParkingApiClient("test", mapper) {
            protected String request(URI uri) { return """
                {"header":{"resultCode":"00"},"body":{"totalCount":2,"items":{"item":[
                {"prkplceNo":"350-2-000020","prkplceNm":"이동4공원 공영주차장"},
                {"prkplceNo":"351-2-000031","prkplceNm":"동빈부두"}]}}}
                """; }
        };
        assertThat(api.fetch("포항시시설관리공단")).hasSize(2);
    }
    @Test void acceptsLiveUnwrappedResponseAndNoData() {
        assertThat(StandardParkingApiClient.parse("{\"header\":{\"resultCode\":\"03\"},\"body\":null}", mapper)).isNull();
        var body = StandardParkingApiClient.parse("{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":1,\"items\":[{\"prkplceNo\":\"test\"}]}}", mapper);
        assertThat(body.path("items").get(0).path("prkplceNo").asText()).isEqualTo("test");
        assertThatThrownBy(() -> StandardParkingApiClient.parse("{\"header\":{\"resultCode\":\"30\"}}", mapper)).hasMessageContaining("resultCode=30");
    }
    @Test void usesStandardContractAndPaginates() {
        var requests = new ArrayList<URI>();
        var api = new StandardParkingApiClient("a%2Bb%2Fc%3D", mapper) {
            protected String request(URI uri) {
                requests.add(uri);
                return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":2,\"items\":[{\"prkplceNo\":\""
                        + requests.size() + "\"}]}}}";
            }
        };
        assertThat(api.fetch("경상북도 포항시")).hasSize(2);
        assertThat(requests.get(1).toString()).contains("type=json", "pageNo=2", "serviceKey=a%2Bb%2Fc%3D", "institutionNm=", "prkplceType=")
                .doesNotContain("format=", "sigunguCd=");
        assertThat(requests.get(0).getHost()).isEqualTo("api.data.go.kr");
    }
    @Test void treatsNoDataAsEmptyAndNeverExposesUpstreamErrors() {
        assertThat(StandardParkingApiClient.parse("{\"response\":{\"header\":{\"resultCode\":\"03\"}}}", mapper)).isNull();
        for (String body : List.of("<error>secret-key</error>", "not-json-secret-key",
                "{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"secret-key\"}}}")) {
            assertThatThrownBy(() -> StandardParkingApiClient.parse(body, mapper))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret-key");
        }
        assertThatThrownBy(() -> new StandardParkingApiClient("", mapper).fetch("포항시"))
                .hasMessageContaining("PARKING_STANDARD_API_SERVICE_KEY");
    }
    @Test void refusesDuplicatePages() {
        var api = new StandardParkingApiClient("test", mapper) {
            protected String request(URI uri) { return """
                {"response":{"header":{"resultCode":"00"},"body":{"totalCount":2,"items":[{"prkplceNo":"a"}]}}}
                """; }
        };
        assertThatThrownBy(() -> api.fetch("포항시")).hasMessageContaining("중복");
    }
    @Test void reportsNumericCauseAndSeparatesSchemaErrorsFromAuthorization() {
        for (String code : List.of("20", "22", "30", "10")) {
            String body = "{\"response\":{\"header\":{\"resultCode\":\"" + code + "\",\"resultMsg\":\"secret-key\"}}}";
            assertThatThrownBy(() -> StandardParkingApiClient.parse(body, mapper))
                    .hasMessageContaining("resultCode=" + code).hasMessageNotContaining("secret-key");
        }
        assertThatThrownBy(() -> StandardParkingApiClient.parse("{\"unexpected\":\"secret-key\"}", mapper))
                .hasMessageContaining("응답 형식 오류").hasMessageNotContaining("secret-key");
        assertThatThrownBy(() -> StandardParkingApiClient.parse("<error><returnReasonCode>30</returnReasonCode><msg>secret-key</msg></error>", mapper))
                .hasMessageContaining("resultCode=30").hasMessageNotContaining("secret-key");
    }
}
