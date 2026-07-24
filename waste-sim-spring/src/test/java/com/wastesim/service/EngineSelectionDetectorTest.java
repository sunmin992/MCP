package com.wastesim.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EngineSelectionDetectorTest {

    @Test
    void detectsPythonEngineMentions() {
        assertEquals("python-devs", EngineSelectionDetector.detect("파이썬 엔진으로 12시에 실행해줘"));
        assertEquals("python-devs", EngineSelectionDetector.detect("python 엔진으로 돌려줘"));
        assertEquals("python-devs", EngineSelectionDetector.detect("pyevsim으로 비교해줘"));
        assertEquals("python-devs", EngineSelectionDetector.detect("원본 논문 엔진으로도 실행해줘"));
    }

    @Test
    void returnsNullWhenNoEngineMentioned() {
        // 기본(모델 미지정)은 항상 null -> 호출측이 기본 모델(Java 엔진)로 처리.
        assertNull(EngineSelectionDetector.detect("12시에 수거해줘"));
        assertNull(EngineSelectionDetector.detect("자바 엔진으로 실행해줘"));   // 기본이 이미 Java라 별도 키워드 불필요
        assertNull(EngineSelectionDetector.detect(null));
        assertNull(EngineSelectionDetector.detect(""));
    }

    @Test
    void isCaseInsensitiveForLatinKeywords() {
        assertEquals("python-devs", EngineSelectionDetector.detect("PYTHON 엔진으로"));
        assertEquals("python-devs", EngineSelectionDetector.detect("PyEvSim 결과 보여줘"));
    }
}
