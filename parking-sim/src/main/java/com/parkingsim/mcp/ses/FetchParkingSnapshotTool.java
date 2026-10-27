package com.parkingsim.mcp.ses;

import com.fasterxml.jackson.databind.*;
import com.parkingsim.data.ParkingDataService;
import com.parkingsim.mcp.*;
import com.parkingsim.tool.ToolResult;
import org.springframework.stereotype.Component;
import java.util.ArrayList;

@Component
public class FetchParkingSnapshotTool implements McpToolProvider {
    private final ParkingDataService data;
    private final ObjectMapper mapper;
    public FetchParkingSnapshotTool(ParkingDataService data, ObjectMapper mapper) { this.data = data; this.mapper = mapper; }
    public String toolName() { return "fetch_parking_snapshot"; }
    public String description() {
        return "공공데이터포털 Parking_v1 시설·실시간 잔여면수를 조회한다. lotIds 생략 시 공영 후보를 보여 주고, 선택 시 1~12곳의 스냅샷을 고정한다. 노외 여부는 사용자가 확인해야 한다. 관측 시각 미제공이며 조회 시각을 기록한다. 인증키는 서버 환경변수 전용이다.";
    }
    public String inputSchemaJson() { return """
        {"type":"object","properties":{
          "sigunguCd":{"type":"string","pattern":"^[0-9]{5}$","description":"공식 5자리 시군구 코드"},
          "lotIds":{"type":"array","items":{"type":"string"},"maxItems":12,"uniqueItems":true}},
          "required":["sigunguCd"],"additionalProperties":false}
        """; }
    public ToolResult call(JsonNode args) {
        try {
            var ids = new ArrayList<String>();
            if (args.has("lotIds") && !args.path("lotIds").isArray()) throw new IllegalArgumentException("lotIds 는 목록이어야 합니다.");
            for (JsonNode id : args.path("lotIds")) {
                if (!id.isTextual() || id.asText().isBlank()) throw new IllegalArgumentException("주차장 관리번호가 잘못됐습니다.");
                ids.add(id.asText());
            }
            return ToolResult.ok(mapper.writeValueAsString(data.fetch(args.path("sigunguCd").asText(), ids)));
        } catch (IllegalArgumentException e) { return ToolFailure.of("parkingData", e.getMessage()); }
        catch (Exception e) { return ToolFailure.of("parkingData", "주차 데이터 조회에 실패했습니다."); }
    }
}
