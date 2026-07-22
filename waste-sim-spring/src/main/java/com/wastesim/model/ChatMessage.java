package com.wastesim.model;

public class ChatMessage {

    public enum MessageType { USER, BOT, SYSTEM, RESULT, CONFIRM, SCENARIO }

    private MessageType type;
    private String content;
    private SimulationResult simulationResult;
    private SimulationConfig simulationConfig;
    // SCENARIO 타입 전용 — 사이드바 "시나리오 실험" 버튼과 동일한 ScenarioResponse를
    // 채팅에서도 렌더링하기 위한 필드(ScenarioIntentDetector로 자연어 라우팅).
    private ScenarioResponse scenarioResponse;
    private String scenarioType;

    public ChatMessage() {}

    public ChatMessage(MessageType type, String content) {
        this.type = type;
        this.content = content;
    }

    public MessageType getType() { return type; }
    public void setType(MessageType t) { this.type = t; }

    public String getContent() { return content; }
    public void setContent(String c) { this.content = c; }

    public SimulationResult getSimulationResult() { return simulationResult; }
    public void setSimulationResult(SimulationResult r) { this.simulationResult = r; }

    public SimulationConfig getSimulationConfig() { return simulationConfig; }
    public void setSimulationConfig(SimulationConfig c) { this.simulationConfig = c; }

    public ScenarioResponse getScenarioResponse() { return scenarioResponse; }
    public void setScenarioResponse(ScenarioResponse r) { this.scenarioResponse = r; }

    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String t) { this.scenarioType = t; }
}
