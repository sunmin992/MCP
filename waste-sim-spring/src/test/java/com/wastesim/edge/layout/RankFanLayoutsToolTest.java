package com.wastesim.edge.layout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wastesim.mcp.McpDomain;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RankFanLayoutsToolTest {
    private final ObjectMapper om=new ObjectMapper();private final RankFanLayoutsTool tool=new RankFanLayoutsTool();
    @SuppressWarnings("unchecked") private Map<String,Object> ok(String s)throws Exception{ToolResult r=tool.call(om.readTree(s));assertTrue(r.ready(),()->""+r.errors());return(Map<String,Object>)r.result();}
    private ToolResult bad(String s)throws Exception{ToolResult r=tool.call(om.readTree(s));assertFalse(r.ready());return r;}
    @Test void contract()throws Exception{assertEquals("rank_fan_layouts",tool.toolName());assertEquals(McpDomain.EDGE,tool.domain());assertEquals("object",om.readTree(tool.inputSchemaJson()).path("type").asText());}
    @Test @SuppressWarnings("unchecked") void defaultRankingAndAdvisory()throws Exception{var o=ok("{}");assertEquals(60,o.get("evaluatedCount"));var rows=(List<Map<String,Object>>)o.get("ranking");assertEquals(10,rows.size());var first=rows.get(0);assertEquals("P02",first.get("id"));assertFalse(first.containsKey("peakTempC"));var a=(Map<String,Object>)first.get("advisory");assertEquals(false,a.get("comparableWithSimulator"));assertEquals(52.975,(Double)a.get("peakTempC"),1e-9);assertEquals(FanLayoutRanking.WARNINGS,o.get("warnings"));}
    @Test @SuppressWarnings("unchecked") void allAndSubset()throws Exception{assertEquals(60,((List<?>)ok("{\"includeAllCombinations\":true}").get("ranking")).size());assertEquals(4,ok("{\"positions\":[\"bottom\",\"top\"]}").get("evaluatedCount"));}
    @Test void explicitCandidates()throws Exception{assertEquals(2,ok("{\"candidates\":[{\"fan1\":{\"position\":\"하단\",\"flow\":\"흡기\"},\"fan2\":{\"position\":\"상단\",\"flow\":\"배기\"}},{\"fan1\":{\"position\":\"bottom\",\"flow\":\"exhaust\"},\"fan2\":{\"position\":\"top\",\"flow\":\"intake\"}}]}").get("evaluatedCount"));}
    @Test void rejectsBadInput()throws Exception{assertEquals(ErrorCode.INVALID_ARGUMENTS,bad("{\"positions\":[\"bottom\",\"top\"],\"candidates\":[]}").errors().get(0).code());assertEquals(ErrorCode.OUT_OF_RANGE,bad("{\"positions\":[\"bottom\"]}").errors().get(0).code());assertEquals(ErrorCode.INVALID_ARGUMENTS,bad("{\"positions\":[\"bottom\",\"bottom\"]}").errors().get(0).code());assertEquals(ErrorCode.INVALID_ENUM,bad("{\"positions\":[\"back\",\"top\"]}").errors().get(0).code());assertEquals(ErrorCode.OUT_OF_RANGE,bad("{\"topK\":0}").errors().get(0).code());assertEquals(ErrorCode.OUT_OF_RANGE,bad("{\"candidates\":[]}").errors().get(0).code());}
}
