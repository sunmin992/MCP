package com.wastesim.pes;

import com.wastesim.model.SimulationConfig;

import java.util.List;
import java.util.Map;

/**
 * 검증을 마친 실행 설정 묶음.
 *
 * <p>{@code confirmToken}은 <b>검증을 통과했을 때만</b> 발급된다. 토큰이 없으면 실행하지
 * 않는다 — 사용자가 확인한 설정과 실제 실행 설정이 같음을 묶어 주는 것이 토큰의 일이다.
 *
 * @param blocks                  설정 검증에서 막힌 사유
 * @param backVerificationBlocks  평탄화 결과와 PES 가 어긋난 자리
 * @param branchBlocks            생성 조건이 거짓인(가지친) 결정에 실린 값
 * @param droppedDefaults         가지친 결정에 기본값(MODEL_DEFAULT)으로 실려 실행 설정에서 뺀 답변키 →
 *                                뺀 실행("변수=값") 목록. 보관 PES · 확인 화면에서 지울지 여기서 정한다
 */
public record Scenario(
        String scenarioId,
        List<SimulationConfig> runs,
        List<String> blocks,
        List<String> backVerificationBlocks,
        List<String> branchBlocks,
        String confirmToken,
        Map<String, List<String>> droppedDefaults) {

    public Scenario {
        runs = List.copyOf(runs);
        blocks = List.copyOf(blocks);
        backVerificationBlocks = List.copyOf(backVerificationBlocks);
        branchBlocks = List.copyOf(branchBlocks);
        // 응답에 적히는 순서가 실행마다 같아야 한다 — Map.copyOf 는 순서를 섞는다.
        droppedDefaults = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(droppedDefaults));
    }

    /** 확인 단계가 토큰을 실어 같은 시나리오를 다시 만든다. */
    public Scenario withToken(String token) {
        return new Scenario(scenarioId, runs, blocks, backVerificationBlocks, branchBlocks, token, droppedDefaults);
    }

    public boolean valid() {
        return blocks.isEmpty() && backVerificationBlocks.isEmpty() && branchBlocks.isEmpty();
    }
}
