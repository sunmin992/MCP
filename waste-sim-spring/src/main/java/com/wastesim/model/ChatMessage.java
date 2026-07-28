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
    /**
     * 이 메시지가 속한 시뮬레이션 도메인 슬러그({@code "waste"} / {@code "edge"}).
     *
     * <p><b>클라이언트→서버</b>: 사용자가 시작화면에서 도메인을 골랐거나 이미
     * {@code /waste}·{@code /edge} 화면에 들어와 있으면 그 값을 실어 보낸다. 서버는
     * 이 값이 있으면 키워드 추측({@code DomainIntentDetector})보다 <b>우선</b>한다 —
     * 사용자가 명시적으로 고른 도메인을 서버가 문장 어휘로 뒤집으면, 엣지 화면에서
     * "트럭 몇 대 굴릴 때 발열" 같은 문장을 쳤을 때 화면과 다른 도메인의 답이 와서
     * 사용자가 방금 한 선택이 무시된 것처럼 보인다.
     *
     * <p><b>서버→클라이언트</b>: 시작화면에서 첫 메시지의 키워드로 도메인이 확정되면
     * 그 결과를 실어 보낸다. 클라이언트는 이 값을 받아 사이드바를 바꾸고 URL을
     * {@code /waste}·{@code /edge}로 전환한다.
     *
     * <p>{@code null}이면 "아직 도메인 미정" — 시작화면의 첫 메시지가 여기 해당한다.
     */
    private String domain;

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

    public String getDomain() { return domain; }
    public void setDomain(String d) { this.domain = d; }
}
