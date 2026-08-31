package com.wastesim.subtask;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 사용자에게 보이는 단계 하나(8개 중 하나).
 *
 * <p><b>ST 번호는 시스템용, 단계 이름은 사용자용이다.</b> 50개를 한 줄로 세워 "ST-021,
 * 21/50"이라고 보여주면 사용자는 자기가 무엇을 하는 중인지 알 수 없다. 화면에는 "3/8 —
 * 쓰레기 배출량과 수거장 조건 설정"과 그 단계 안에서의 "질문 2"만 보이고, ST 번호는
 * 서버가 답변을 추적하고 재질문을 걸 때만 쓴다.
 *
 * @param order       1~8
 * @param name        단계 이름
 * @param description 이 단계에서 무엇을 입력하는지 한 줄 설명
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SubtaskGroup(int order, String name, String description) {

    public boolean isFullySpecified() {
        return order > 0 && name != null && !name.isBlank()
                && description != null && !description.isBlank();
    }
}
