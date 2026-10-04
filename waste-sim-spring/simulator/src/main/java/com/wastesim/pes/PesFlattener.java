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
        // 리플렉션은 Integer 를 기본형 double 로는 넓혀 주지만 Double 로는 바꿔 주지 않는다.
        // JSON 의 150 은 Integer 로 오므로 여기서 맞추지 않으면 Double 세터가 거절한다.
        Class<?> p = setter.getParameterTypes()[0];
        if (p == Double.class && converted instanceof Number n) {
            converted = n.doubleValue();
        }
        // 열거값 목록(직업 구성)은 List<String> 이다. 원소가 허용값이 아니면 거절한다.
        if ("ENUM_LIST".equals(t.valueType()) && converted instanceof java.util.List<?> list) {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (Object e : list) {
                if (!(e instanceof String s) || !t.allowed().contains(s)) {
                    throw new IllegalArgumentException(
                            field + " 의 값은 " + t.allowed() + " 중에서 골라야 합니다. 받은 값: " + list);
                }
                names.add(s);
            }
            converted = names;
        }
        // 목록 세터(하루 수거 시각)는 List<Integer> 다. 원소가 Long 이나 문자열로 들어오면
        // 엔진이 분을 읽다가 ClassCastException 으로 멈추므로 여기서 정수로 맞춘다. 맞출 수
        // 없으면 세터를 부르지 않고 거절한다 — 고쳐 주지 않는다.
        else if (p == java.util.List.class && converted instanceof java.util.List<?> list) {
            java.util.List<Integer> ints = new java.util.ArrayList<>();
            for (Object e : list) {
                if (e instanceof Number num && num.doubleValue() == Math.rint(num.doubleValue())) {
                    ints.add(num.intValue());
                } else {
                    throw new IllegalArgumentException(
                            field + " 의 값은 정수 목록이어야 합니다. 받은 값: " + list);
                }
            }
            converted = ints;
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
            if (p == java.util.List.class && value instanceof java.util.List) return m;
        }
        return null;
    }
}
