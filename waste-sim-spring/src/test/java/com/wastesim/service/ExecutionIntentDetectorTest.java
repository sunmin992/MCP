package com.wastesim.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionIntentDetectorTest {

    @Test
    void plainCollectionTimeRequestIsExecution() {
        assertTrue(ExecutionIntentDetector.isExecutionRequest("12시에 수거하는 걸로 실행해줘"));
    }

    @Test
    void instantValueQueryIsNotExecution() {
        assertFalse(ExecutionIntentDetector.isExecutionRequest("12시 시점 배출량 알려줘"));
    }

    @Test
    void explicitSkipExecutionPhraseIsNotExecution() {
        assertFalse(ExecutionIntentDetector.isExecutionRequest("실행하지 말고 상상해서 표로 그려줘"));
    }
}
