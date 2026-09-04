package com.wastesim.subtask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 답하지 않은 필드를 <b>근거로</b> 갈라, 채울 것과 물을 것을 나눈다.
 *
 * <p>이 클래스가 "근거 유무로 가른다"를 실제로 수행하는 자리다. 사람이 매번 판단하면
 * 기준이 흔들리므로 {@link FieldBasis} 선언만 보고 기계적으로 정한다.
 *
 * <p>상태를 갖지 않는다 — 같은 입력이 같은 결과를 낸다.
 */
public final class GapResolver {

    private GapResolver() {}

    /**
     * @param autoFilled       묻지 않고 채운 값 (필드 → 값). <b>값이 {@code null}인 항목이
     *                         있을 수 있다</b> — "해당없음"을 허용하는 필드가 그 상태로
     *                         확정된 것이지, 값을 못 채운 것이 아니다. 호출부는 이 맵에서
     *                         {@code null} 값을 정상 상태로 받아들여야 한다
     * @param defaults         채운 값의 근거 기록. 결과에 함께 실린다
     * @param mustAsk          반드시 물어야 하는 필드
     * @param unverifiedFields 채웠지만 출처를 확인하지 않은 필드. 결과에 표시가 붙는다
     */
    public record Resolution(Map<String, Object> autoFilled,
                             List<AppliedDefault> defaults,
                             List<String> mustAsk,
                             List<String> unverifiedFields) {
        public Resolution {
            // Map.copyOf는 null 값을 거부한다. "해당없음"으로 확정된 필드는 value가
            // null인 채로 자동 채움에 들어와야 하므로(그 자체가 결론이다) null을 허용하는
            // 순서 보존 불변 맵으로 감싼다.
            autoFilled = Collections.unmodifiableMap(new LinkedHashMap<>(autoFilled));
            defaults = List.copyOf(defaults);
            mustAsk = List.copyOf(mustAsk);
            unverifiedFields = List.copyOf(unverifiedFields);
        }
    }

    /**
     * @param answeredSubtaskIds 이미 답이 있는 서브태스크 id. 이 필드들은 건드리지 않는다 —
     *                           답한 값을 기본값으로 덮으면 사용자 입력이 사라진다
     */
    public static Resolution resolve(JangnyangSubtaskDefinition def,
                                     Set<String> answeredSubtaskIds) {
        Map<String, Object> filled = new LinkedHashMap<>();
        List<AppliedDefault> defaults = new ArrayList<>();
        List<String> mustAsk = new ArrayList<>();
        List<String> unverified = new ArrayList<>();

        for (JangnyangSubtask s : def.subtasks()) {
            if (answeredSubtaskIds.contains(s.id())) continue;

            // 선언이 없으면 근거를 모르는 것이다. 모르는 값을 채우지 않는다.
            FieldBasis b = s.basis() != null ? s.basis() : FieldBasis.unknown();

            if (!b.kind().canFillWithoutAsking()) {
                mustAsk.add(s.answerField());
                continue;
            }
            filled.put(s.answerField(), b.value());
            defaults.add(new AppliedDefault(s.answerField(), b.value(),
                    b.source() != null ? b.source() : "출처 미확인"));
            if (b.kind().needsUnverifiedWarning()) {
                unverified.add(s.answerField());
            }
        }
        return new Resolution(filled, defaults, mustAsk, unverified);
    }
}
