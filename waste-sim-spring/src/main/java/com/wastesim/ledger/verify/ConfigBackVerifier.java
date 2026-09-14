package com.wastesim.ledger.verify;

import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.ParameterLedger;
import com.wastesim.model.SimulationConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 기존 빌더가 만든 실행 설정을 다시 열어 결정기록과 한 필드씩 맞춘다.
 *
 * <p><b>왜 새 컴파일러를 만들지 않고 결과만 보는가</b>: 컴파일 경로를 새로 내면
 * {@code DerivedSetVsV4ReportTest}가 고정한 대조 기준이 셋이 되고, 셋이 어긋났을 때 어느
 * 것이 옳은지 판단할 근거가 없다. 기존 경로를 그대로 두고 <b>그 산출물을 의심하는</b>
 * 자리만 두면 기준은 둘로 유지된다.
 *
 * <p>{@code SimulationConfig}를 읽기만 하고 수정하지 않는다.
 *
 * <p>읽지 못한 것을 조용히 건너뛰면 역검증이 스스로의 존재 이유를 배반한다 — 게터가
 * 없거나 호출이 실패하는 것, 값이 없어야 할 곳에 없는 것은 전부 대조 실패로 취급한다.
 */
public final class ConfigBackVerifier {

    /**
     * @param fieldToParameterId 실행 설정의 필드명 → 결정기록의 매개변수 ID
     */
    public BackVerificationResult verify(SimulationConfig config,
                                         ParameterLedger ledger,
                                         Map<String, String> fieldToParameterId) {
        List<String> blocks = new ArrayList<>();

        for (Map.Entry<String, String> e : fieldToParameterId.entrySet()) {
            String field = e.getKey();
            String parameterId = e.getValue();

            Method getter = findGetter(field);
            if (getter == null) {
                blocks.add(field + ": 게터를 찾을 수 없습니다 (get/is 접두사 모두 실패) — "
                        + "필드명 매핑을 확인하세요");
                continue;
            }

            Object configValue;
            try {
                configValue = getter.invoke(config);
            } catch (ReflectiveOperationException ex) {
                blocks.add(field + ": 게터 호출이 실패했습니다 — " + ex.getMessage());
                continue;
            }

            ParameterDecision decision = ledger.current(parameterId);

            if (configValue == null) {
                // 결정기록이 실행 가능한 확정값을 갖고 있는데 컴파일 결과에는 값이 없다면,
                // 이것이 바로 이 클래스가 잡아야 할 변환 오류다 — 조용히 넘기지 않는다.
                if (decision != null && decision.state().executable()
                        && decision.normalizedValue() != null) {
                    blocks.add(parameterId + ": 설정에는 값이 없는데 결정기록은 "
                            + decision.normalizedValue() + "이어야 합니다");
                }
                // 결정기록에 결정이 없거나 실행 불가 상태라면 애초에 설정이 값을 가질 이유가
                // 없으므로 대조 대상이 아니다.
                continue;
            }

            if (decision == null) {
                blocks.add(field + "=" + configValue + ": 결정기록에 결정이 없습니다 ("
                        + parameterId + ")");
                continue;
            }
            if (!decision.state().executable()) {
                blocks.add(parameterId + ": 실행할 수 없는 상태입니다 — " + decision.state()
                        + " (" + decision.blockingReason() + ")");
                continue;
            }
            // decision.source()는 여기서 null일 수 없다 — executable() 상태의 결정은
            // ParameterDecision의 컴팩트 생성자가 source == null이면 생성 자체를 막는다.
            if (!configValue.equals(decision.normalizedValue())) {
                blocks.add(parameterId + ": 설정은 " + configValue + "인데 결정기록은 "
                        + decision.normalizedValue() + "입니다");
            }
        }

        return new BackVerificationResult(blocks);
    }

    /** get/is 접두사 순서로 게터를 찾는다. 둘 다 없으면 {@code null} — 매핑 오류로 취급한다. */
    private Method findGetter(String field) {
        String capitalized = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (String prefix : List.of("get", "is")) {
            try {
                return SimulationConfig.class.getMethod(prefix + capitalized);
            } catch (NoSuchMethodException ignored) {
                // 다음 접두사를 시도한다
            }
        }
        return null;
    }
}
