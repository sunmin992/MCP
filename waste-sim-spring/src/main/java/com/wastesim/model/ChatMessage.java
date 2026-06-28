package com.wastesim.model;

public class ChatMessage {

    public enum MessageType { USER, BOT, SYSTEM, RESULT }

    private MessageType type;
    private String content;
    private SimulationResult simulationResult;
    private SimulationConfig simulationConfig;

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
}
