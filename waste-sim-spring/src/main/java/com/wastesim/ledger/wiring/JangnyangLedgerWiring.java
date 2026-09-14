package com.wastesim.ledger.wiring;

import com.wastesim.ledger.JangnyangRules;
import com.wastesim.ledger.ParameterId;
import com.wastesim.registry.SimulationConfigFields;
import com.wastesim.ses.SesFieldMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 장량동 시뮬레이터의 원장 배선. 자산 ID·활성 규칙·종속 관계·역검증 맵을 한자리에 둔다.
 *
 * <p><b>왜 유도하고 일부만 선언하는가</b>: 맵을 전부 손으로 적으면 그 맵도 계약이 되고,
 * 계약이 누락하면 그 계약으로 만든 검증기도 같이 누락한다 — {@code checkInputBindingCoverage}가
 * 막으려던 구조다. 이름이 같은 자리는 대응표에서 유도하고, 유도되지 않는 자리만 선언하면
 * 손으로 관리하는 면적이 다섯 줄로 줄고 그 다섯 줄이 낡으면 테스트가 먼저 깨진다.
 */
public final class JangnyangLedgerWiring {

    public static final String ASSET_ID = "jangnyang-simulator";

    /**
     * 빌더가 값을 옮기는 게 아니라 <b>변환하는</b> 답변 필드와, 그래서 역검증이 값을
     * 대조할 수 없는 이유.
     *
     * <p><b>왜 제외를 선언으로 두는가</b>: 빠뜨린 것과 일부러 뺀 것이 코드에서 같아 보이면
     * 다음 사람은 역검증이 대응표 전부를 본다고 믿는다. 실제로 보는 것은 유도된 자리뿐이다.
     *
     * <p>변환된 자리까지 대조하려면 변환 규칙을 등록해 기대값을 다시 계산해야 한다. 지금
     * 필요한 것은 그것이 아니라, 역검증이 <b>무엇을 보지 않는지가 드러나는 것</b>이다.
     */
    public static final Map<String, String> TRANSFORMED_FIELDS = Map.of(
            "scenarioType", "실행 규모·도구 선택으로 갈라진다 — 같은 이름의 설정 필드가 없다",
            "collectionSchedule", "값에 따라 collectionIntervalDays 또는 collectionDaysOfWeek로 갈라진다",
            "collectionTime", "수거 시각 목록(collectionTimesMinutes)으로 합쳐진다",
            "collectionTimes", "수거 시각 목록(collectionTimesMinutes)으로 합쳐진다",
            "occupationPreset", "프리셋 키가 비율 목록(occupationMix)이 된다",
            "dischargeWindow", "두 원소 목록이 dischargeWindowStartMinutes와 dischargeWindowEndMinutes 두 필드로 갈라진다",
            "trafficMode", "NONE 여부만 남아 trafficEnabled 불리언으로 바뀐다 — 같은 이름의 설정 필드가 없다");

    private JangnyangLedgerWiring() { }

    /** {@code jangnyang-simulator::days} 형태. 규약은 {@link ParameterId}가 소유한다. */
    public static String parameterIdOf(String answerField) {
        return ParameterId.of(ASSET_ID, answerField);
    }

    /**
     * 설정 필드명 → 매개변수 ID. <b>이름이 같고 실제 게터가 있는 것만</b> 담는다.
     *
     * <p>{@link SimulationConfigFields#all()}로 걸러 내는 이유는, 대응표에만 있고 실행
     * 설정에는 없는 이름을 넘기면 역검증이 "게터를 찾을 수 없습니다"로 정상 구성을 막기
     * 때문이다.
     */
    public static Map<String, String> fieldToParameterId() {
        Set<String> real = SimulationConfigFields.all();
        Map<String, String> map = new LinkedHashMap<>();
        for (SesFieldMapping.FieldBinding b : SesFieldMapping.bindings()) {
            String field = b.answerField();
            if (TRANSFORMED_FIELDS.containsKey(field)) continue;
            if (!real.contains(field)) continue;
            map.put(field, parameterIdOf(field));
        }
        return Map.copyOf(map);
    }

    /**
     * 매개변수 → 그 가지를 살리는 규칙 ID.
     *
     * <p>교통 프로필은 교통 결합이 살아 있을 때만 물어야 한다. 배선은 대응표가
     * {@code trafficMode}를 커플링 활성 지점에 묶어 둔 것을 읽은 것이지 새로 정한 것이 아니다.
     */
    public static Map<String, String> activeWhenByParameter() {
        return Map.of(parameterIdOf("trafficProfileId"), JangnyangRules.TRAFFIC_APPLY);
    }

    /**
     * 매개변수 → 이 값이 바뀌면 낡는 매개변수들.
     *
     * <p>교통을 껐다 켜면 이전 프로필 답변은 더 믿을 수 없다 — 다른 구조에서 고른 값이다.
     */
    public static Map<String, List<String>> dependents() {
        return Map.of(parameterIdOf(JangnyangRules.TRAFFIC_MODE_FIELD),
                List.of(parameterIdOf("trafficProfileId")));
    }
}
