package com.wastesim.pes;

import com.wastesim.model.SimulationConfig;

import java.util.List;

/**
 * 검증을 마친 실행 설정 묶음.
 *
 * <p>{@code confirmToken}은 <b>검증을 통과했을 때만</b> 발급된다. 토큰이 없으면 실행하지
 * 않는다 — 사용자가 확인한 설정과 실제 실행 설정이 같음을 묶어 주는 것이 토큰의 일이다.
 *
 * @param blocks                  설정 검증에서 막힌 사유
 * @param backVerificationBlocks  평탄화 결과와 PES 가 어긋난 자리
 */
public record Scenario(
        String scenarioId,
        List<SimulationConfig> runs,
        List<String> blocks,
        List<String> backVerificationBlocks,
        String confirmToken) {

    public Scenario {
        runs = List.copyOf(runs);
        blocks = List.copyOf(blocks);
        backVerificationBlocks = List.copyOf(backVerificationBlocks);
    }

    public boolean valid() {
        return blocks.isEmpty() && backVerificationBlocks.isEmpty();
    }
}
