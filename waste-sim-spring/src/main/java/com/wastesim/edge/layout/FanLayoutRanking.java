package com.wastesim.edge.layout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 점수 내림차순, 편차 오름차순, ID 오름차순의 재현 가능한 랭킹. */
public final class FanLayoutRanking {
    private FanLayoutRanking(){}
    public static final String STATUS_RANKED="RANKED",MODEL_KIND="EMPIRICAL_SCORE_NOT_PHYSICS";
    public static final double TIE_TOLERANCE=1e-9;
    public static final String TIE_BREAK="coolingScore 동률이면 advisorySpreadC가 작은 쪽, 그래도 같으면 조합 ID 오름차순";
    public static final List<String> WARNINGS=List.of("FAN_SPEC_NOT_VERIFIED","ADVISORY_TEMP_ANCHORED_ESTIMATE","ADVISORY_TEMP_NOT_COMPARABLE_WITH_SIMULATOR");
    public static final List<String> RECOMMENDED_MEASUREMENT_STEPS=List.of("상위 3개 배치 각 3회 반복","무팬 및 팬 1개 기준선과 비교","최고온도·노드별 온도·회복시간·소음·전력 기록");
    public record Entry(int rank,FanLayoutCandidate candidate,FanLayoutScore score){}
    private record Scored(FanLayoutCandidate candidate,FanLayoutScore score){}
    public static List<Entry> rank(List<FanLayoutCandidate> candidates){
        List<Scored> scored=new ArrayList<>();for(var c:candidates)scored.add(new Scored(c,FanLayoutScoreModel.score(c)));
        scored.sort(Comparator.comparingDouble((Scored s)->q(s.score.coolingScore())).reversed().thenComparingDouble(s->q(s.score.advisorySpreadC())).thenComparing(s->s.candidate.id()));
        List<Entry> out=new ArrayList<>();for(int i=0;i<scored.size();i++)out.add(new Entry(i+1,scored.get(i).candidate,scored.get(i).score));return out;
    }
    private static double q(double v){return Math.round(v/TIE_TOLERANCE)*TIE_TOLERANCE;}
}
