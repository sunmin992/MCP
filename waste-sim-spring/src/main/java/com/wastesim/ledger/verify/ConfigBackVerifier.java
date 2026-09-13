package com.wastesim.ledger.verify;

import com.wastesim.ledger.ParameterDecision;
import com.wastesim.ledger.ParameterLedger;
import com.wastesim.model.SimulationConfig;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 기존 빌더가 만든 실행 설정을 다시 열어 원장과 한 필드씩 맞춘다.
 *
 * <p><b>왜 새 컴파일러를 만들지 않고 결과만 보는가</b>: 컴파일 경로를 새로 내면
 * {@code DerivedSetVsV4ReportTest}가 고정한 대조 기준이 셋이 되고, 셋이 어긋났을 때 어느
 * 것이 옳은지 판단할 근거가 없다. 기존 경로를 그대로 두고 <b>그 산출물을 의심하는</b>
 * 자리만 두면 기준은 둘로 유지된다.
 *
 * <p>{@code SimulationConfig}를 읽기만 하고 수정하지 않는다.
 */
public final class ConfigBackVerifier {

    /**
     * @param fieldToParameterId 실행 설정의 필드명 → 원장의 매개변수 ID
     */
    public BackVerificationResult verify(SimulationConfig config,
                                         ParameterLedger ledger,
                                         Map<String, String> fieldToParameterId) {
        List<String> blocks = new ArrayList<>();

        for (Map.Entry<String, String> e : fieldToParameterId.entrySet()) {
            String field = e.getKey();
            String parameterId = e.getValue();
            Object configValue = read(config, field);
            if (configValue == null) continue;

            ParameterDecision decision = ledger.current(parameterId);
            if (decision == null) {
                blocks.add(field + "=" + configValue + ": 원장에 결정이 없습니다 ("
                        + parameterId + ")");
                continue;
            }
            if (!decision.state().executable()) {
                blocks.add(parameterId + ": 실행할 수 없는 상태입니다 — " + decision.state()
                        + " (" + decision.blockingReason() + ")");
                continue;
            }
            if (decision.source() == null) {
                blocks.add(parameterId + ": 확정값인데 출처가 없습니다");
                continue;
            }
            if (!configValue.equals(decision.normalizedValue())) {
                blocks.add(parameterId + ": 설정은 " + configValue + "인데 원장은 "
                        + decision.normalizedValue() + "입니다");
            }
        }

        return new BackVerificationResult(blocks);
    }

    /** 게터가 없거나 읽을 수 없으면 {@code null} — 없는 것은 대조 대상이 아니다. */
    private Object read(SimulationConfig config, String field) {
        String capitalized = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (String prefix : List.of("get", "is")) {
            try {
                Method getter = SimulationConfig.class.getMethod(prefix + capitalized);
                return getter.invoke(config);
            } catch (ReflectiveOperationException ignored) {
                // 다음 접두사를 시도한다
            }
        }
        return null;
    }
}
