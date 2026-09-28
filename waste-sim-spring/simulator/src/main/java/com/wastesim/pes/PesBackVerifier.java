package com.wastesim.pes;

import com.wastesim.model.SimulationConfig;
import com.wastesim.template.SubtaskTemplate;
import com.wastesim.template.TemplateCatalog;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 평탄화한 설정을 <b>되읽어</b> PES 와 대조한다.
 *
 * <p>이 대조가 없으면 "사용자가 확인한 설정으로 실행했다"를 주장할 근거가 없다.
 * 읽지 못한 것은 통과가 아니라 <b>불일치</b>로 본다 — 게터가 없거나 호출이 실패하는 것도
 * 대조 실패다. 조용히 건너뛰면 역검증이 스스로의 존재 이유를 배반한다.
 */
@Component
public class PesBackVerifier {

    private final TemplateCatalog catalog;

    public PesBackVerifier(TemplateCatalog catalog) {
        this.catalog = catalog;
    }

    /** @return 어긋난 항목 설명 목록. 비어 있으면 통과 */
    public List<String> verify(Pes pes, SimulationConfig config) {
        List<String> blocks = new ArrayList<>();
        for (Map.Entry<String, Object> e : pes.values().entrySet()) {
            String answerKey = e.getKey();
            SubtaskTemplate t = catalog.byAnswerKey(answerKey).orElse(null);
            if (t == null) {
                blocks.add(answerKey + ": 템플릿에 없는 답변키라 대조할 수 없습니다");
                continue;
            }
            String field = t.configField();
            Object expected = "trafficEnabled".equals(field)
                    ? Boolean.valueOf("APPLY".equals(String.valueOf(e.getValue())))
                    : e.getValue();

            Method getter = findGetter(field);
            if (getter == null) {
                blocks.add(field + ": 게터를 찾을 수 없습니다 (get/is 모두 실패)");
                continue;
            }
            Object actual;
            try {
                actual = getter.invoke(config);
            } catch (ReflectiveOperationException ex) {
                blocks.add(field + ": 게터 호출이 실패했습니다 — " + ex.getMessage());
                continue;
            }
            if (!sameValue(expected, actual)) {
                blocks.add(field + ": PES 는 " + expected + " 인데 실행 설정은 " + actual + " 입니다");
            }
        }
        return List.copyOf(blocks);
    }

    private boolean sameValue(Object expected, Object actual) {
        if (expected == null || actual == null) return expected == actual;
        if (expected instanceof Number a && actual instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        return String.valueOf(expected).equals(String.valueOf(actual));
    }

    private Method findGetter(String field) {
        String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
        for (Method m : SimulationConfig.class.getMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getName().equals("get" + cap) || m.getName().equals("is" + cap)) return m;
        }
        return null;
    }
}
