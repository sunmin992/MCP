package com.wastesim.edge.layout;

import com.wastesim.edge.FanArraySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * 듀얼 팬 배치 경험적 점수 모델. 열저항 기반 물리 모델과 의도적으로 격리한다.
 * 모든 계수의 출처는 2026-08-27 엑셀 가정 시트다.
 */
public final class FanLayoutScoreModel {
    private FanLayoutScoreModel(){}
    private static final FanFlowRole[][] FLOW_PAIRS={{FanFlowRole.INTAKE,FanFlowRole.INTAKE},{FanFlowRole.INTAKE,FanFlowRole.EXHAUST},{FanFlowRole.EXHAUST,FanFlowRole.INTAKE},{FanFlowRole.EXHAUST,FanFlowRole.EXHAUST}};
    public static final double BARE_PEAK_ANCHOR_C=82.0,SCORE_TO_DELTA_C=27.0,MEAN_OFFSET_C=5.2,
            SPREAD_BASE=3.0,SPREAD_SLOPE=10.0,SAME_DIRECTION_SPREAD_PENALTY=2.0,SCORE_MIN=0.25,SCORE_MAX=1.15,
            INTAKE_PAIR_FACTOR=0.78,EXHAUST_PAIR_FACTOR=0.82,THROUGH_FLOW_FACTOR=1.0,
            NATURAL_CONVECTION_BONUS=0.15,AGAINST_CONVECTION_PENALTY=-0.10,SHORT_CIRCUIT_PENALTY=-0.12,
            RISK_LOW_THRESHOLD=0.95,RISK_MEDIUM_THRESHOLD=0.78;
    public static List<FanLayoutCandidate> enumerateAll(List<FanMountPosition> positions){
        List<FanLayoutCandidate> out=new ArrayList<>();
        for(int i=0;i<positions.size();i++)for(int j=i+1;j<positions.size();j++)for(var f:FLOW_PAIRS)
            out.add(new FanLayoutCandidate(String.format("P%02d",out.size()+1),positions.get(i),f[0],positions.get(j),f[1]));
        return out;
    }
    public static FanLayoutScore score(FanLayoutCandidate c){
        double factor=c.hasSameFlow()?(c.flow1()==FanFlowRole.INTAKE?INTAKE_PAIR_FACTOR:EXHAUST_PAIR_FACTOR):THROUGH_FLOW_FACTOR;
        double bonus=0; String note;
        if(!c.hasSameFlow()){
            FanMountPosition in=c.flow1()==FanFlowRole.INTAKE?c.position1():c.position2();
            FanMountPosition out=c.flow1()==FanFlowRole.EXHAUST?c.position1():c.position2();
            if(in.level()<out.level()){bonus+=NATURAL_CONVECTION_BONUS;note="자연대류와 같은 아래→위 흐름";}
            else if(in.level()>out.level()){bonus+=AGAINST_CONVECTION_PENALTY;note="자연대류를 거스르는 위→아래 흐름";}
            else note="같은 높이의 횡류";
            if(in.side()==out.side()&&in.side()!=FanMountPosition.Side.CENTER){bonus+=SHORT_CIRCUIT_PENALTY;note+="; 입출구 단락 가능";}
        }else note=c.flow1()==FanFlowRole.INTAKE?"출구 면적에 따라 내부 양압":"흡기 틈 위치에 따라 내부 음압";
        double score=clampScore((c.position1().efficiency()+c.position2().efficiency())/2*factor+bonus);
        double peak=BARE_PEAK_ANCHOR_C-score*SCORE_TO_DELTA_C;
        double spread=SPREAD_BASE+(1-score)*SPREAD_SLOPE+(c.hasSameFlow()?SAME_DIRECTION_SPREAD_PENALTY:0);
        return new FanLayoutScore(score,flowType(c),factor,bonus,peak,peak-MEAN_OFFSET_C,spread,risk(score),note,FanArraySpec.SourceStatus.PRELIMINARY_ESTIMATE);
    }
    public static double clampScore(double raw){return Math.max(SCORE_MIN,Math.min(SCORE_MAX,raw));}
    private static FanLayoutScore.FlowType flowType(FanLayoutCandidate c){return !c.hasSameFlow()?FanLayoutScore.FlowType.FORCED_THROUGH_FLOW:c.flow1()==FanFlowRole.INTAKE?FanLayoutScore.FlowType.POSITIVE_PRESSURE:FanLayoutScore.FlowType.NEGATIVE_PRESSURE;}
    private static FanLayoutScore.StagnationRisk risk(double s){return s>=RISK_LOW_THRESHOLD?FanLayoutScore.StagnationRisk.LOW:s>=RISK_MEDIUM_THRESHOLD?FanLayoutScore.StagnationRisk.MEDIUM:FanLayoutScore.StagnationRisk.HIGH;}
}
