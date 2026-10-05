package demo;

import java.util.ArrayList;
import java.util.List;

public class DemoValidator {

    private static final int MAX_MINUTE = 1439;

    public List<Object> validate(DemoConfig c, Part part, double cap) {
        List<Object> errs = new ArrayList<>();

        if (c.getRobots() < 1)
            errs.add(new ValidationError());

        // 상수가 지역 변수를 거쳐 비교된다.
        double s = c.getSpeed();
        if (s <= 0 || s > DemoConfig.LIMIT) {
            throw new IllegalArgumentException("speed");
        }

        // 오류를 만들지 않는 if 는 범위가 아니다.
        if (c.getRobots() > 99) return errs;

        // 바깥 조건은 "검사할지" 이지 범위가 아니다 — 안쪽 if 만 센다.
        if (c.getRobots() > 5) {
            if (c.getBudgetKg() == null) errs.add(new ValidationError());
        }

        // 상한이 상수가 아니면 비우고 검토로 넘긴다.
        Double budget = c.getBudgetKg();
        if (budget != null && (budget <= 0 || budget > cap)) {
            errs.add(new ValidationError());
        }

        // 목록 원소의 범위.
        if (c.getSlotsMinutes() != null) {
            for (Integer m : c.getSlotsMinutes()) {
                if (m == null || m < 0 || m > MAX_MINUTE) errs.add(new ValidationError());
            }
        }

        // 목록 원소가 enum 으로 해석된다.
        if (c.getCrew() != null) {
            for (String who : c.getCrew()) {
                Role.valueOf(who);
            }
        }

        // 별칭 게터로 검사해도 robots 의 상한이다.
        if (c.getFleetSize() > 50) errs.add(new ValidationError());

        // 다른 객체의 같은 이름 게터는 설정 필드가 아니다.
        if (part.getRobots() > 3) errs.add(new ValidationError());
        return errs;
    }

    static class ValidationError {
    }
}
