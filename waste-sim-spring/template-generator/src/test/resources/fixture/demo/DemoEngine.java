package demo;

import java.util.List;

/** 생성 조건 추출 시험용 가상 엔진. 규칙마다 필드 하나를 쓴다. */
public class DemoEngine {

    public DemoResult run(DemoConfig cfg) {
        // 검증기가 막는 설정(speed <= 0)에서만 갈리는 조기 탈출 — 생성 조건이 아니다.
        if (cfg.getSpeed() <= 0) {
            throw new IllegalStateException("speed");
        }

        // color 는 결과에 기록하기만 한다 — 동작에 영향이 없다.
        DemoResult result = new DemoResult(cfg.getColor());

        // profileId 는 verbose 일 때만 읽는다(삼항식).
        String profile = cfg.isVerbose() ? cfg.getProfileId() : null;
        if (profile != null) {
            result.setNote(profile);
        }

        // budgetKg 는 FAST 모드에서만 불리는 메서드 안에서 읽는다(메서드 사이).
        Mode mode = cfg.resolveMode();
        if (mode == Mode.FAST) {
            result.add(fast(cfg));
        }

        // slotsMinutes 가 비었을 때만 startMinutes 를 쓴다(설정 클래스의 조기 반환).
        List<Integer> slots = cfg.resolveSlots();

        // stepMinutes 는 반복 변수에 곱해진다 — 두 번째 로봇부터 효과가 있다.
        for (int k = 0; k < cfg.getRobots(); k++) {
            for (Integer s : slots) {
                result.add(s + k * cfg.getStepMinutes());
            }
        }

        // 결과 기록만 하는 if 의 조건은 영향이 아니다.
        if (cfg.getCrew() != null) {
            result.setNote("crew");
        }
        return result;
    }

    private double fast(DemoConfig cfg) {
        Double budget = cfg.getBudgetKg();
        return budget == null ? 0 : budget;
    }
}
