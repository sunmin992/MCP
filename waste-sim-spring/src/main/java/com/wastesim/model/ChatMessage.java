package com.wastesim.model;

public class ChatMessage {

    /**
     * {@code SUBTASK}·{@code PREVIEW}는 v1.13의 구성 계층이 쓴다(SDD 2.18.10).
     * {@code SUBTASK}는 질문·재질문, {@code PREVIEW}는 시나리오 미리보기다.
     */
    public enum MessageType { USER, BOT, SYSTEM, RESULT, CONFIRM, SCENARIO, SUBTASK, PREVIEW }

    private MessageType type;
    private String content;
    private SimulationResult simulationResult;
    private SimulationConfig simulationConfig;
    // SCENARIO 타입 전용 — 사이드바 "시나리오 실험" 버튼과 동일한 ScenarioResponse를
    // 채팅에서도 렌더링하기 위한 필드(ScenarioIntentDetector로 자연어 라우팅).
    private ScenarioResponse scenarioResponse;
    private String scenarioType;
    /**
     * 이 메시지가 속한 도메인 슬러그 — 이 서버에서는 항상 {@code "waste"}다.
     *
     * <p>도메인이 하나뿐이라 판별할 것이 없지만 필드는 남겼다. 프런트엔드가 이 값으로
     * 사이드바를 세우고, 값이 오지 않으면 화면을 세울 근거가 사라지기 때문이다.
     * 라즈베리파이 엣지 도메인이 함께 있던 시절에는 이 값이 서버·클라이언트 양방향
     * 협상 결과였다(클라이언트가 고른 도메인이 서버의 키워드 추측을 이겼다).
     */
    private String domain;

    // ── SUBTASK·PREVIEW 전용 (v1.13, SDD 2.18.10) ────────────────────────────
    //
    // 질문을 그대로 버블에 찍지 않고 구조화 필드로 내려보내는 이유는, 프런트엔드가
    // <b>입력 자료형에 맞는 입력 위젯</b>을 띄워야 하기 때문이다 — 시각은 시간 입력,
    // 차종은 선택지, 경로는 순서 편집이다. 질문을 자유 텍스트로만 주면 클라이언트는
    // 그 판단을 할 수 없고, 결국 문구를 파싱해 추측하게 된다.

    /** 진행 중인 서브태스크 세트 ID. */
    private String subtaskSetId;
    /** 세트 버전 — 답변을 되돌려 보낼 때 이 값이 대조된다(FR-138). */
    private Integer subtaskVersion;
    /** 지금 답해야 할 서브태스크 ID. 미리보기 단계면 null. */
    private String currentSubtaskId;
    /** 이번 실험 계획에서 몇 번째 질문인가(1부터). */
    private Integer subtaskOrder;
    /** 이번 실험에서 실제로 물을 질문 개수 — 세트 전체 개수가 아니다(FR-130). */
    private Integer subtaskTotal;
    /** 카탈로그의 질문 문장 그대로. 서버가 소유하며 LLM이 만들지 않는다(D-44). */
    private String question;
    /** 입력 위젯을 고르기 위한 스키마 — 자료형·허용 범위·필수 여부. */
    private java.util.Map<String, Object> inputSchema;
    /** 직전 검증에서 남은 오류 항목(서브태스크 ID·사유·재질문 문장). */
    private java.util.List<java.util.Map<String, Object>> validationErrors;
    /** 0.0~1.0. */
    private Double progress;

    // 사용자에게는 ST 번호가 아니라 <b>단계</b>가 보인다. "3/8 · 쓰레기 배출량과 수거장
    // 조건 설정"과 그 안에서의 "질문 2"가 화면의 전부이고, currentSubtaskId는 답변을
    // 되돌려 보낼 때만 쓰는 내부 식별자다.

    /** 현재 단계 번호(1~8). */
    private Integer groupOrder;
    /** 전체 단계 수. */
    private Integer groupTotal;
    /** 현재 단계 이름. */
    private String groupName;
    /** 현재 단계가 무엇을 입력하는 단계인지 한 줄 설명. */
    private String groupDescription;
    /** 이 단계 안에서 몇 번째 질문인가(1부터). */
    private Integer questionInGroup;
    /** 이 단계의 질문 수. */
    private Integer questionsInGroup;
    /** PREVIEW 전용 — 시나리오·기본값·가정의 구조화 사본(FR-133·D-53). */
    private java.util.Map<String, Object> scenarioPreview;

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

    public String getSubtaskSetId() { return subtaskSetId; }
    public void setSubtaskSetId(String v) { this.subtaskSetId = v; }

    public Integer getSubtaskVersion() { return subtaskVersion; }
    public void setSubtaskVersion(Integer v) { this.subtaskVersion = v; }

    public String getCurrentSubtaskId() { return currentSubtaskId; }
    public void setCurrentSubtaskId(String v) { this.currentSubtaskId = v; }

    public Integer getSubtaskOrder() { return subtaskOrder; }
    public void setSubtaskOrder(Integer v) { this.subtaskOrder = v; }

    public Integer getSubtaskTotal() { return subtaskTotal; }
    public void setSubtaskTotal(Integer v) { this.subtaskTotal = v; }

    public String getQuestion() { return question; }
    public void setQuestion(String v) { this.question = v; }

    public java.util.Map<String, Object> getInputSchema() { return inputSchema; }
    public void setInputSchema(java.util.Map<String, Object> v) { this.inputSchema = v; }

    public java.util.List<java.util.Map<String, Object>> getValidationErrors() { return validationErrors; }
    public void setValidationErrors(java.util.List<java.util.Map<String, Object>> v) { this.validationErrors = v; }

    public Double getProgress() { return progress; }
    public void setProgress(Double v) { this.progress = v; }

    public Integer getGroupOrder() { return groupOrder; }
    public void setGroupOrder(Integer v) { this.groupOrder = v; }

    public Integer getGroupTotal() { return groupTotal; }
    public void setGroupTotal(Integer v) { this.groupTotal = v; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String v) { this.groupName = v; }

    public String getGroupDescription() { return groupDescription; }
    public void setGroupDescription(String v) { this.groupDescription = v; }

    public Integer getQuestionInGroup() { return questionInGroup; }
    public void setQuestionInGroup(Integer v) { this.questionInGroup = v; }

    public Integer getQuestionsInGroup() { return questionsInGroup; }
    public void setQuestionsInGroup(Integer v) { this.questionsInGroup = v; }

    public java.util.Map<String, Object> getScenarioPreview() { return scenarioPreview; }
    public void setScenarioPreview(java.util.Map<String, Object> v) { this.scenarioPreview = v; }
}
