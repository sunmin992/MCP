package com.wastesim.service;

import java.util.regex.Pattern;

/**
 * 채팅 메시지가 특정 시뮬레이션 엔진(모델)을 지정하는지 결정론적으로 판정한다
 * (LanguagePurityFilter·TrafficKeywordDetector 등과 같은 C2 원칙 — 실행에
 * 영향을 주는 판단은 LLM에 맡기지 않고 정규식으로 확정한다).
 *
 * <p>기본은 항상 Java 재구현 엔진({@code SimulationModelRegistry.DEFAULT_MODEL_ID})
 * 이고, 이번 메시지에 "파이썬/python/pyevsim/원본 엔진" 등이 언급됐을 때만
 * Python 참조 구현({@link #PYTHON_MODEL_ID})으로 전환한다. DESIGN_DECISIONS.md
 * D-03과 동일하게 이전 대화 이력은 보지 않고 이번 메시지만 판정한다 — 한 번
 * "파이썬으로 해줘"라고 했다고 다음 메시지까지 파이썬 엔진으로 이어받지 않는다.
 */
public final class EngineSelectionDetector {

    private EngineSelectionDetector() {}

    /** {@code com.wastesim.mcp.PythonWasteSimAdapter#modelId()}와 반드시 같은 값이어야 한다. */
    public static final String PYTHON_MODEL_ID = "python-devs";

    private static final Pattern PYTHON_KEYWORDS = Pattern.compile(
            "(파이썬|파이션|python|pyevsim|원본\\s*(논문|paper)?\\s*엔진|레퍼런스\\s*엔진|참조\\s*구현\\s*엔진)",
            Pattern.CASE_INSENSITIVE);

    /** @return 이번 메시지가 특정 엔진을 지정했으면 그 modelId, 아니면 {@code null}(기본 모델 사용). */
    public static String detect(String text) {
        if (text == null || text.isEmpty()) return null;
        return PYTHON_KEYWORDS.matcher(text).find() ? PYTHON_MODEL_ID : null;
    }
}
