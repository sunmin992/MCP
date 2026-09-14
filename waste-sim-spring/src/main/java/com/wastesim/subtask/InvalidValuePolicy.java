package com.wastesim.subtask;

/**
 * 요청에서 추출한 값이 템플릿 검증을 통과하지 못했을 때의 공통 처리 정책.
 *
 * @param action 실패 필드를 다시 물을지, 명시적으로 기본값 사용을 허용할지
 * @param allowDefaultReplacement 실패한 사용자 값을 서버 기본값으로 대체할 수 있는지
 */
public record InvalidValuePolicy(
        Action action,
        boolean allowDefaultReplacement) {

    public enum Action { REASK, USE_DEFAULT }

    public InvalidValuePolicy {
        action = action == null ? Action.REASK : action;
        if (action == Action.REASK && allowDefaultReplacement) {
            throw new IllegalArgumentException(
                    "REASK 정책은 검증 실패 값을 기본값으로 대체할 수 없습니다.");
        }
    }

    public static InvalidValuePolicy failClosed() {
        return new InvalidValuePolicy(Action.REASK, false);
    }

    public boolean mustReask() {
        return action == Action.REASK || !allowDefaultReplacement;
    }
}
