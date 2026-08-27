package com.wastesim.edge.layout;

/** 동일 사양 팬 2개의 위치·역할 조합 하나. */
public record FanLayoutCandidate(String id, FanMountPosition position1, FanFlowRole flow1,
                                 FanMountPosition position2, FanFlowRole flow2) {
    public boolean hasSamePosition(){return position1==position2;}
    public boolean hasSameFlow(){return flow1==flow2;}
}
