package com.parkingsim.mcp.ses;

import com.fasterxml.jackson.databind.*;
import com.parkingsim.data.StandardParkingDataService;
import com.parkingsim.mcp.*;
import com.parkingsim.tool.ToolResult;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class FetchStandardParkingTool implements McpToolProvider {
    private final StandardParkingDataService data;
    private final ObjectMapper mapper;
    public FetchStandardParkingTool(StandardParkingDataService data, ObjectMapper mapper) { this.data = data; this.mapper = mapper; }
    public String toolName() { return "fetch_standard_parking"; }
    public String description() { return "전국주차장정보표준데이터에서 관리기관별 공영·노외 주차장 목록·면수·요금·운영시간·기준일을 조회한다. lotIds 선택 시 각 관리번호의 initialAvailableSpaces와 그 상태의 시각 initialStateTime을 사용자 가정으로 받아 시설면수 스냅샷을 만든다. 실시간 잔여면수는 없으며 요금·운영시간은 참고 정보다. Parking_v1과 관리번호를 자동 결합하지 않는다."; }
    public String inputSchemaJson() { return """
        {"type":"object","properties":{
          "institutionName":{"type":"string","minLength":1,"maxLength":100,"description":"관리기관명(institutionNm), 예: 포항시시설관리공단 또는 경상북도 포항시청. 제공기관명(insttNm)과 다름"},
          "lotIds":{"type":"array","maxItems":12,"uniqueItems":true,"items":{"type":"string"}},
          "initialStateTime":{"type":"string","pattern":"^[0-2][0-9]:[0-5][0-9]$","description":"초기 빈자리를 가정한 한국 시각 HH:mm. lotIds 선택 시 필수이며 시뮬레이션 시작 시각이 된다. 사용자에게 묻는다"},
          "origins":{"type":"object","description":"선택할 때 필수. lotIds·initialAvailableSpaces·initialStateTime 각각의 출처. 기본값이 없어 모두 USER(사용자가 직접 말함)여야 스냅샷을 만든다","properties":{"lotIds":{"enum":["USER","MODEL_DEFAULT"]},"initialAvailableSpaces":{"enum":["USER","MODEL_DEFAULT"]},"initialStateTime":{"enum":["USER","MODEL_DEFAULT"]}}},
          "initialAvailableSpaces":{"type":"object","additionalProperties":{"type":"integer","minimum":0},"description":"선택한 관리번호별 초기 빈자리 수. 사용자 가정이며 실측값으로 표시하지 않는다"}},
          "required":["institutionName"],"additionalProperties":false}
        """; }
    public ToolResult call(JsonNode args) {
        try {
            var ids = new ArrayList<String>();
            if (args.has("lotIds") && !args.path("lotIds").isArray()) throw new IllegalArgumentException("lotIds는 목록이어야 합니다.");
            for (var id : args.path("lotIds")) {
                if (!id.isTextual() || id.asText().isBlank()) throw new IllegalArgumentException("관리번호 형식이 잘못됐습니다.");
                ids.add(id.asText());
            }
            var available = new LinkedHashMap<String, Integer>();
            if (args.has("initialAvailableSpaces") && !args.path("initialAvailableSpaces").isObject()) throw new IllegalArgumentException("initialAvailableSpaces는 관리번호별 객체여야 합니다.");
            args.path("initialAvailableSpaces").fields().forEachRemaining(e -> {
                if (!e.getValue().isIntegralNumber() || !e.getValue().canConvertToInt()) throw new IllegalArgumentException("초기 빈자리 수는 정수여야 합니다.");
                available.put(e.getKey(), e.getValue().intValue());
            });
            // 주차장 선택 · 초기 빈자리 · 그 시각은 기본값이 없다. 사용자가 "기본값으로" 라고 하자 LLM 이 7곳을 골라
            // 00:00 전부 빈자리로 넣었다(2026-10-27 ST-S5). 사용자가 말한 값일 때만 스냅샷을 만든다.
            if (!ids.isEmpty()) {
                var notUser = new ArrayList<String>();
                for (String k : List.of("lotIds", "initialAvailableSpaces", "initialStateTime"))
                    if (!"USER".equals(args.path("origins").path(k).asText())) notUser.add(k);
                if (!notUser.isEmpty())
                    throw new IllegalArgumentException(notUser + " 는 기본값이 없는 사용자 가정입니다. 사용자에게 물어 받은 값이면 origins 에 USER 로 적으십시오. "
                            + "사용자가 '기본값으로'라고 했다면 기본값이 없다고 알리고 다시 물으십시오. 정할 수 없으면 가정값 모드(parkingSnapshotId 없이)를 제안하십시오.");
            }
            return ToolResult.ok(mapper.writeValueAsString(data.fetch(args.path("institutionName").asText(), ids, available,
                    args.hasNonNull("initialStateTime") ? args.path("initialStateTime").asText() : null)));
        } catch (IllegalArgumentException e) { return ToolFailure.of("standardParking", e.getMessage()); }
        catch (Exception e) { return ToolFailure.of("standardParking", "표준 주차정보 조회에 실패했습니다."); }
    }
}
