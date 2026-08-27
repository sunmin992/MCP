package com.wastesim.edge.layout;

import com.fasterxml.jackson.databind.JsonNode;
import com.wastesim.mcp.McpDomain;
import com.wastesim.mcp.McpToolProvider;
import com.wastesim.tool.ErrorCode;
import com.wastesim.tool.ToolResult;
import com.wastesim.tool.ValidationError;
import org.springframework.stereotype.Component;
import java.util.*;

/** 팬 2개의 위치·흡배기 조합을 경험적 점수로 줄 세우는 후보 선별용 MCP 도구. */
@Component
public class RankFanLayoutsTool implements McpToolProvider {
    static final int DEFAULT_TOP_K=10,MAX_COMBINATIONS=60;
    @Override public McpDomain domain(){return McpDomain.EDGE;}
    @Override public String toolName(){return "rank_fan_layouts";}
    @Override public String description(){return "40 mm 팬 2개의 위치 6곳과 흡기·배기 조합을 경험적 냉각점수로 순위 매긴다. 실측 전 후보 선별용이며 advisory 온도는 물리 시뮬레이터와 비교할 수 없다.";}
    @Override public String inputSchemaJson(){return """
        {"type":"object","properties":{
          "positions":{"type":"array","items":{"type":"string","enum":["bottom","top","left_bottom","left_top","right_bottom","right_top"]}},
          "candidates":{"type":"array","items":{"type":"object","properties":{
            "fan1":{"type":"object","properties":{"position":{"type":"string"},"flow":{"type":"string","enum":["intake","exhaust"]}}},
            "fan2":{"type":"object","properties":{"position":{"type":"string"},"flow":{"type":"string","enum":["intake","exhaust"]}}}},"required":["fan1","fan2"]}},
          "topK":{"type":"integer","default":10},"includeAllCombinations":{"type":"boolean","default":false}}}
        """;}
    @Override public ToolResult call(JsonNode root){
        boolean hp=has(root,"positions"),hc=has(root,"candidates");
        if(hp&&hc)return reject(ErrorCode.INVALID_ARGUMENTS,"candidates","positions와 candidates는 함께 쓸 수 없다");
        List<FanLayoutCandidate> candidates;
        if(hc){Object p=parseCandidates(root.get("candidates"));if(p instanceof ValidationError e)return ToolResult.rejected(e);candidates=cast(p);}
        else{Object p=parsePositions(root==null?null:root.get("positions"));if(p instanceof ValidationError e)return ToolResult.rejected(e);candidates=FanLayoutScoreModel.enumerateAll(cast(p));}
        int topK=DEFAULT_TOP_K;
        if(has(root,"topK")){
            JsonNode n=root.get("topK");if(!n.isIntegralNumber())return reject(ErrorCode.INVALID_ARGUMENTS,"topK","topK는 정수여야 한다");
            topK=n.asInt();if(topK<1||topK>MAX_COMBINATIONS)return reject(ErrorCode.OUT_OF_RANGE,"topK","topK는 1~60이어야 한다");
        }
        if(has(root,"includeAllCombinations")&&!root.get("includeAllCombinations").isBoolean())return reject(ErrorCode.INVALID_ARGUMENTS,"includeAllCombinations","boolean이어야 한다");
        boolean all=has(root,"includeAllCombinations")&&root.get("includeAllCombinations").asBoolean();
        List<FanLayoutRanking.Entry> ranked=FanLayoutRanking.rank(candidates);int limit=all?ranked.size():Math.min(topK,ranked.size());
        List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<limit;i++)rows.add(row(ranked.get(i)));
        Map<String,Object> out=new LinkedHashMap<>();out.put("tool",toolName());out.put("status",FanLayoutRanking.STATUS_RANKED);out.put("modelKind",FanLayoutRanking.MODEL_KIND);out.put("evaluatedCount",candidates.size());out.put("ranking",rows);out.put("tieBreak",FanLayoutRanking.TIE_BREAK);out.put("warnings",FanLayoutRanking.WARNINGS);out.put("sourceStatus","PRELIMINARY_ESTIMATE");out.put("recommendedMeasurementSteps",FanLayoutRanking.RECOMMENDED_MEASUREMENT_STEPS);return ToolResult.ok(out);
    }
    private Map<String,Object> row(FanLayoutRanking.Entry e){
        var c=e.candidate();var s=e.score();Map<String,Object> adv=new LinkedHashMap<>();adv.put("peakTempC",round(s.advisoryPeakTempC()));adv.put("meanTempC",round(s.advisoryMeanTempC()));adv.put("spreadC",round(s.advisorySpreadC()));adv.put("anchorBarePeakC",FanLayoutScoreModel.BARE_PEAK_ANCHOR_C);adv.put("comparableWithSimulator",false);
        Map<String,Object> m=new LinkedHashMap<>();m.put("rank",e.rank());m.put("id",c.id());m.put("fan1",fan(c.position1(),c.flow1()));m.put("fan2",fan(c.position2(),c.flow2()));m.put("flowType",s.flowType().wire());m.put("flowTypeKo",s.flowType().koLabel());m.put("coolingScore",round(s.coolingScore()));m.put("pairFactor",round(s.pairFactor()));m.put("flowBonus",round(s.flowBonus()));m.put("stagnationRisk",s.stagnationRisk().wire());m.put("stagnationRiskKo",s.stagnationRisk().koLabel());m.put("interpretation",s.interpretation());m.put("advisory",adv);return m;
    }
    private Map<String,Object> fan(FanMountPosition p,FanFlowRole f){Map<String,Object> m=new LinkedHashMap<>();m.put("position",p.wire());m.put("positionKo",p.koLabel());m.put("flow",f.wire());m.put("flowKo",f.koLabel());return m;}
    private Object parsePositions(JsonNode n){
        if(n==null||n.isNull())return List.of(FanMountPosition.values());if(!n.isArray())return err(ErrorCode.INVALID_ARGUMENTS,"positions","배열이어야 한다");
        LinkedHashSet<FanMountPosition> seen=new LinkedHashSet<>();for(JsonNode x:n){if(!x.isTextual())return err(ErrorCode.INVALID_ENUM,"positions","위치는 문자열이어야 한다");var p=FanMountPosition.parse(x.asText());if(p==null)return err(ErrorCode.INVALID_ENUM,"positions","알 수 없는 위치: "+x.asText());if(!seen.add(p))return err(ErrorCode.INVALID_ARGUMENTS,"positions","중복 위치: "+p.wire());}
        if(seen.size()<2)return err(ErrorCode.OUT_OF_RANGE,"positions","팬 2개에는 위치가 2곳 이상 필요하다");return new ArrayList<>(seen);
    }
    private Object parseCandidates(JsonNode n){
        if(n==null||!n.isArray())return err(ErrorCode.INVALID_ARGUMENTS,"candidates","배열이어야 한다");if(n.isEmpty())return err(ErrorCode.OUT_OF_RANGE,"candidates","후보가 비어 있다");if(n.size()>MAX_COMBINATIONS)return err(ErrorCode.OUT_OF_RANGE,"candidates","후보는 60개 이하여야 한다");
        List<FanLayoutCandidate> out=new ArrayList<>();int i=0;for(JsonNode x:n){i++;var p1=FanMountPosition.parse(x.path("fan1").path("position").asText(null));var f1=FanFlowRole.parse(x.path("fan1").path("flow").asText(null));var p2=FanMountPosition.parse(x.path("fan2").path("position").asText(null));var f2=FanFlowRole.parse(x.path("fan2").path("flow").asText(null));if(p1==null||p2==null)return err(ErrorCode.INVALID_ENUM,"candidates["+(i-1)+"]","알 수 없는 위치");if(f1==null||f2==null)return err(ErrorCode.INVALID_ENUM,"candidates["+(i-1)+"]","알 수 없는 팬 역할");if(p1==p2)return err(ErrorCode.INVALID_ARGUMENTS,"candidates["+(i-1)+"]","같은 자리에 팬 2개를 달 수 없다");out.add(new FanLayoutCandidate(String.format("P%02d",i),p1,f1,p2,f2));}return out;
    }
    @SuppressWarnings("unchecked") private static <T>T cast(Object o){return (T)o;} private static boolean has(JsonNode n,String f){return n!=null&&n.has(f)&&!n.get(f).isNull();}
    private static ValidationError err(ErrorCode c,String f,String m){return new ValidationError(c,f,m);}private static ToolResult reject(ErrorCode c,String f,String m){return ToolResult.rejected(err(c,f,m));}private static double round(double v){return Math.round(v*1e12)/1e12;}
}
