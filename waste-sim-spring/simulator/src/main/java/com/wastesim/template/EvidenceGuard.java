package com.wastesim.template;

import com.wastesim.evidence.EvidenceField;
import java.util.List;
import java.util.Map;

/**
 * 근거 검사는 공통 모듈({@link com.wastesim.evidence.EvidenceGuard})이 한다. 여기서는 이 시뮬레이터의 템플릿을
 * 공통 형식({@link EvidenceField})으로 넘길 뿐이다 — 단위 · 선택지 표현 · 단위말은 템플릿이 선언한다.
 */
public final class EvidenceGuard {
    private EvidenceGuard() {}

    public static com.wastesim.evidence.EvidenceGuard.Result check(Map<String, List<Object>> submitted, Map<String, String> origins,
            Map<String, String> evidence, List<String> utterances, TemplateCatalog catalog) {
        return com.wastesim.evidence.EvidenceGuard.check(submitted, origins, evidence, utterances,
                key -> catalog.byAnswerKey(key).map(EvidenceGuard::field));
    }

    /** 이 값을 guard 가 실제로 따져 보는가. */
    static boolean examined(String origin, SubtaskTemplate t) {
        return com.wastesim.evidence.EvidenceGuard.examined(origin, field(t));
    }

    static EvidenceField field(SubtaskTemplate t) {
        return new EvidenceField(t.answerKey(), t.valueType(), t.unit(), t.defaultValue() != null && !t.generateWhen().always(),
                t.valueTerms(), t.unitWords());
    }
}
