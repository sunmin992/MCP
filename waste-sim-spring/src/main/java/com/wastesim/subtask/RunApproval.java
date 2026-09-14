package com.wastesim.subtask;

import java.util.List;

/**
 * 실행 승인의 결과. spec 아니면 차단 사유를 들고 나온다.
 *
 * <p><b>왜 {@code null} 대신 이것을 만드는가</b>: 기존 {@code approveRun}은 차단을
 * {@code null}로만 말할 수 있어 사유를 잃는다. 사용자는 왜 실행이 열리지 않는지 모른 채
 * 같은 요청을 반복하게 된다. 반환 계약을 바꾸면 호출부가 조용히 깨지므로, 계약은 두고
 * 사유를 읽을 길만 따로 낸다.
 *
 * @param blocks 막은 이유 전부. 첫 하나에서 멈추면 고치고 다시 시도하기를 반복해야 한다
 */
public record RunApproval(JangnyangScenarioSpec spec, List<String> blocks) {

    public RunApproval {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    static RunApproval approved(JangnyangScenarioSpec spec) {
        return new RunApproval(spec, List.of());
    }

    static RunApproval blocked(List<String> blocks) {
        return new RunApproval(null, blocks);
    }

    public boolean approved() {
        return spec != null;
    }

    /** 사람이 읽는 차단 사유. 승인됐으면 빈 문자열. */
    public String message() {
        if (approved()) return "";
        StringBuilder sb = new StringBuilder("아직 실행할 수 없습니다:\n");
        for (String b : blocks) sb.append("- ").append(b).append('\n');
        return sb.toString();
    }
}
