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
     * @param modelDefaultFields 시뮬레이터 기본값으로 채운 필드. 결과에 표시가 붙는다
     */
    public record Resolution(Map<String, Object> autoFilled,
                             List<AppliedDefault> defaults,
                             List<String> mustAsk,
                             List<String> modelDefaultFields) {
        public Resolution {
            // Map.copyOf는 null 값을 거부한다. "해당없음"으로 확정된 필드는 value가
            // null인 채로 자동 채움에 들어와야 하므로(그 자체가 결론이다) null을 허용하는
            // 순서 보존 불변 맵으로 감싼다.
            autoFilled = Collections.unmodifiableMap(new LinkedHashMap<>(autoFilled));
            defaults = List.copyOf(defaults);
            mustAsk = List.copyOf(mustAsk);
            modelDefaultFields = List.copyOf(modelDefaultFields);
        }
    }

    /**
     * @param answeredSubtaskIds 이미 답이 있는 서브태스크 id. 이 필드들은 건드리지 않는다 —
     *                           답한 값을 기본값으로 덮으면 사용자 입력이 사라진다
     */
    /**
     * 부르는 쪽이 지정한 필드는 근거가 있어도 채우지 않고 되묻는다.
     *
     * <p>"장량동 26개 동으로 한 달 돌려줘"처럼 실행 동사만 있고 시각이 없는 요청에 필요하다.
     * {@code collectionTime}은 근거가 {@code MODEL_DEFAULT}라 그냥 두면 기본값으로 채워지고
     * 넘어간다 — 사용자가 실행을 요청했는데 정작 자기가 정하려던 시각은 물어보지도 않은 채
     * 도는 상태가 된다.
     *
     * @param alwaysAsk 반드시 물을 {@code answerField} 이름들
     */
    public static Resolution resolve(JangnyangSubtaskDefinition def,
                                     Set<String> answeredSubtaskIds,
                                     Set<String> alwaysAsk) {
        return split(def, answeredSubtaskIds, alwaysAsk);
    }

    public static Resolution resolve(JangnyangSubtaskDefinition def,
                                     Set<String> answeredSubtaskIds) {
        return split(def, answeredSubtaskIds, Set.of());
    }

    private static Resolution split(JangnyangSubtaskDefinition def,
                                    Set<String> answeredSubtaskIds,
                                    Set<String> alwaysAsk) {
        Map<String, Object> filled = new LinkedHashMap<>();
        List<AppliedDefault> defaults = new ArrayList<>();
        List<String> mustAsk = new ArrayList<>();
        List<String> modelDefaults = new ArrayList<>();

        for (JangnyangSubtask s : def.subtasks()) {
            if (answeredSubtaskIds.contains(s.id())) continue;

            // 선언이 없으면 근거를 모르는 것이다. 모르는 값을 채우지 않는다.
            FieldBasis b = s.basis() != null ? s.basis() : FieldBasis.unknown();

            // 부르는 쪽의 지정이 근거보다 앞선다. 근거가 있다는 것은 "채워도 된다"는 뜻이지
            // "채워야 한다"는 뜻이 아니다 — 이 요청에서는 물어야 하는 값일 수 있다.
            if (alwaysAsk.contains(s.answerField()) || !b.kind().canFillWithoutAsking()) {
                mustAsk.add(s.answerField());
                continue;
            }
            filled.put(s.answerField(), b.value());
            defaults.add(new AppliedDefault(s.answerField(), b.value(),
                    b.source() != null ? b.source() : "시뮬레이터 기본값"));
            if (b.kind().needsModelDefaultNotice()) {
                modelDefaults.add(s.answerField());
            }
        }
        return new Resolution(filled, defaults, mustAsk, modelDefaults);
    }
}
