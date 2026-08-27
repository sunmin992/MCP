package com.wastesim.edge.layout;

import com.wastesim.edge.FanArraySpec;

/** 배치 상대평가 결과. advisory 온도는 물리 시뮬레이터와 비교할 수 없는 임시값이다. */
public record FanLayoutScore(double coolingScore, FlowType flowType, double pairFactor, double flowBonus,
        double advisoryPeakTempC,double advisoryMeanTempC,double advisorySpreadC,
        StagnationRisk stagnationRisk,String interpretation,FanArraySpec.SourceStatus sourceStatus) {
    public enum FlowType {
        FORCED_THROUGH_FLOW("강제 관통류"),POSITIVE_PRESSURE("양압/자연배출"),NEGATIVE_PRESSURE("음압/자연흡기");
        private final String ko; FlowType(String ko){this.ko=ko;} public String koLabel(){return ko;} public String wire(){return name();}
    }
    public enum StagnationRisk {
        LOW("낮음"),MEDIUM("보통"),HIGH("높음");
        private final String ko; StagnationRisk(String ko){this.ko=ko;} public String koLabel(){return ko;} public String wire(){return name();}
    }
}
