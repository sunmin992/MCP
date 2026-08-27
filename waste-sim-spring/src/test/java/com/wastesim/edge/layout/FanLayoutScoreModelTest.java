package com.wastesim.edge.layout;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FanLayoutScoreModelTest {
    private FanLayoutScore score(String id){return FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values())).stream().filter(c->c.id().equals(id)).findFirst().map(FanLayoutScoreModel::score).orElseThrow();}
    @Test void positionsAndParsing(){assertEquals(6,FanMountPosition.values().length);assertEquals(.95,FanMountPosition.BOTTOM.efficiency(),1e-9);assertEquals(FanMountPosition.LEFT_TOP,FanMountPosition.parse("좌측 상단"));assertEquals(FanFlowRole.INTAKE,FanFlowRole.parse("흡기"));assertNull(FanMountPosition.parse("뒷면"));}
    @Test void enumeratesAll(){var all=FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()));assertEquals(60,all.size());assertEquals("P01",all.get(0).id());assertEquals("P60",all.get(59).id());assertEquals("P02",all.get(1).id());assertEquals(FanFlowRole.EXHAUST,all.get(1).flow2());}
    @Test void subset(){assertEquals(4,FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.BOTTOM,FanMountPosition.TOP)).size());}
    @Test void goldenValues(){double[][] g={{1,.7215,62.5195,7.785},{2,1.075,52.975,2.25},{3,.825,59.725,4.75},{5,.6747,63.7831,8.253},{58,.83,59.59,4.70},{59,.58,66.34,7.20},{60,.656,64.288,8.44}};for(var x:g){var s=score(String.format("P%02d",(int)x[0]));assertEquals(x[1],s.coolingScore(),1e-9);assertEquals(x[2],s.advisoryPeakTempC(),1e-9);assertEquals(x[3],s.advisorySpreadC(),1e-9);}}
    @Test void flowBonuses(){assertEquals(.15,score("P02").flowBonus(),1e-9);assertEquals(-.10,score("P03").flowBonus(),1e-9);assertEquals(.03,score("P58").flowBonus(),1e-9);assertTrue(score("P58").interpretation().contains("단락"));}
    @Test void clamp(){assertEquals(.25,FanLayoutScoreModel.clampScore(-3),1e-9);assertEquals(1.15,FanLayoutScoreModel.clampScore(9),1e-9);for(var c:FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values()))){double s=FanLayoutScoreModel.score(c).coolingScore();assertTrue(s>.25&&s<1.15);}}
    @Test void rankingAndTie(){var r=FanLayoutRanking.rank(FanLayoutScoreModel.enumerateAll(List.of(FanMountPosition.values())));assertEquals("P02",r.get(0).candidate().id());int p10=-1,p18=-1;for(int i=0;i<r.size();i++){if(r.get(i).candidate().id().equals("P10"))p10=i;if(r.get(i).candidate().id().equals("P18"))p18=i;}assertEquals(r.get(p10).score().coolingScore(),r.get(p18).score().coolingScore(),1e-12);assertTrue(p10<p18);assertEquals(2,r.get(p10).rank());}
}
