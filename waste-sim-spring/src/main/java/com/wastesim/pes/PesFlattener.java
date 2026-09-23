package com.wastesim.pes;

import com.wastesim.model.SimulationConfig;
import com.wastesim.template.SubtaskTemplate;
import com.wastesim.template.TemplateCatalog;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * PES 를 실행 설정으로 평탄화한다.
 *
 * <p>대응표는 템플릿의 {@code configField}다 — 이름 규약이 아니라 <b>선언된 대응</b>이다.
 * 템플릿에 없는 키가 들어오면 예외를 던진다. 조용히 무시하면 사용자가 준 값이 실행에
 * 반영되지 않은 채로 넘어가고, 결과만 보고는 알 수 없다.
 */
@Component
public class PesFlattener {

    private final TemplateCatalog catalog;

    public PesFlattener(TemplateCatalog catalog) {
        this.catalog = catalog;
    }

    public SimulationConfig flatten(Pes pes) {
        SimulationConfig cfg = new SimulationConfig();
        for (Map.Entry<String, Object> e : pes.values().entrySet()) {
            String answerKey = e.getKey();
            SubtaskTemplate t = catalog.byAnswerKey(answerKey).orElseThrow(
                    () -> new IllegalArgumentException(
                            "템플릿에 없는 답변키입니다: " + answerKey
                                    + " — 실행 대응이 선언되지 않은 값은 싣지 않습니다"));
            applyOne(cfg, t, e.getValue());
        }
        return cfg;
    }

    private void applyOne(SimulationConfig cfg, SubtaskTemplate t, Object value) {
        String field = t.configField();
        Object converted = convert(t, value);
        Method setter = findSetter(field, converted);
        if (setter == null) {
            throw new IllegalStateException(
                    "설정에 세터가 없습니다: " + field + " (" + t.templateId() + ")");
        }
        try {
            setter.invoke(cfg, converted);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                    field + " 세터 호출이 실패했습니다: " + ex.getMessage(), ex);
        }
    }

    /**
     * 답변값을 설정 필드의 타입으로 바꾼다.
     *
     * <p>{@code trafficMode}만 특별하다 — 답변은 APPLY/IGNORE 인데 설정은 불리언이다.
     * 이 대응이 유일한 예외이므로 표를 만들지 않고 여기 적는다.
     */
    private Object convert(SubtaskTemplate t, Object value) {
        if ("trafficEnabled".equals(t.configField())) {
            return "APPLY".equals(String.valueOf(value));
        }
        return value;
    }

    private Method findSetter(String field, Object value) {
        String name = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (Method m : SimulationConfig.class.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) continue;
            Class<?> p = m.getParameterTypes()[0];
            if (p.isInstance(value)) return m;
            if ((p == int.class || p == Integer.class) && value instanceof Integer) return m;
            if ((p == boolean.class || p == Boolean.class) && value instanceof Boolean) return m;
            if ((p == double.class || p == Double.class) && value instanceof Number) return m;
        }
        return null;
    }
}
