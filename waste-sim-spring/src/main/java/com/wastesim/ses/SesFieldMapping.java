package com.wastesim.ses;

import com.wastesim.subtask.AllowedRange;
import com.wastesim.subtask.AnswerType;

import java.util.List;

/**
 * SES 지점과 답변 필드를 잇는다.
 *
 * <p><b>이것만은 유도할 수 없다.</b> SES의 이름은 한글이고 필드는 영문이며, 둘 사이에
 * 기계가 건널 다리가 없다. {@code 구성결정-SES-대응표.md}가 코드 소비 지점을 짚어 확정한
 * 사람의 지식이고, 이 클래스는 그것을 코드로 옮긴 것이다.
 *
 * <p>그래서 이 자리가 가설의 경계다 — SES는 "무엇을 물어야 하는가"를 정하고, 이 대응은
 * "그것을 어떤 이름으로 묻는가"를 정한다. 수가 맞는지는 {@code SesFieldMappingTest}가 지킨다.
 *
 * <p>{@code answerType}과 숫자 범위(min/max)는 {@code jangnyang-simulator-v4.json}의 같은
 * {@code answerField}에서 그대로 옮겼다 — 지어내면 서브태스크 검증과 조용히 갈라진다.
 *
 * <p>{@code spec} 지점의 {@code range.values}는 <b>비워 둔다</b> — 허용값은 트리의 자식
 * 이름이고, 여기 또 적으면 트리를 고쳤을 때 조용히 갈라진다.
 */
public final class SesFieldMapping {

    public record FieldBinding(String pointId, String answerField,
                               AnswerType answerType, AllowedRange range) { }

    public record NonSesField(String answerField, Reason reason, String why) {
        public enum Reason { OUTSIDE_SES, PROCEDURE_CONTROL }
    }

    private SesFieldMapping() { }

    private static AllowedRange desc(String description) {
        return new AllowedRange(description, null, null, null, null, null, null, null, null);
    }

    private static AllowedRange num(String description, double min, double max) {
        return new AllowedRange(description, min, max, null, null, null, null, null, null);
    }

    private static final List<FieldBinding> BINDINGS = List.of(
            // ── spec 축 4개 — 허용값은 트리에서 온다 ────────────────────────────
            new FieldBinding("spec:실험:실험 유형 축", "scenarioType", AnswerType.ENUM,
                    desc("서버가 실제로 구현한 실행 유형 중 하나")),
            new FieldBinding("spec:거주민:배출시각 모델 축", "dischargeTimeMode", AnswerType.ENUM,
                    desc("배출 시각을 무엇으로 정하는가")),
            new FieldBinding("spec:수거차량:차종 축", "truckType", AnswerType.ENUM,
                    desc("수거에 쓰는 차종")),
            new FieldBinding("spec:수거 경로:이동시간 방식 축", "travelTimeMode", AnswerType.ENUM,
                    desc("지점 사이 이동시간을 무엇으로 계산하는가")),

            // ── multi 복제 수 3개 ──────────────────────────────────────────────
            new FieldBinding("multi:수거지점 집합", "numBuildings", AnswerType.INTEGER,
                    num("1 이상 26 이하 — 자동 생성 지점 ID가 Node_A~Node_Z다", 1, 26)),
            new FieldBinding("multi:거주민 집합", "residentsPerBuilding", AnswerType.INTEGER,
                    num("건물당 1명 이상", 1, 500)),
            new FieldBinding("multi:수거차량 집합", "truckCount", AnswerType.INTEGER,
                    num("1대 이상 — python 참조 엔진은 1대만 지원한다", 1, 26)),

            // ── 커플링 활성 1개 ────────────────────────────────────────────────
            new FieldBinding("coupling:교통 구역.혼잡계수->수거 경로.이동시간", "trafficMode",
                    AnswerType.ENUM, desc("교통 혼잡을 반영할 것인가")),

            // ── 속성 21개 ──────────────────────────────────────────────────────
            new FieldBinding("attr:거주민 집합:직업구성", "occupationPreset", AnswerType.ENUM,
                    desc("직업 구성 프리셋")),
            new FieldBinding("attr:실험:기간", "days", AnswerType.INTEGER, num("1일 이상", 1, 365)),
            new FieldBinding("attr:실험:반복횟수", "seeds", AnswerType.INTEGER, num("1회 이상", 1, 100)),
            new FieldBinding("attr:대상 시스템:1인배출량", "wasteMeanKg", AnswerType.NUMBER,
                    num("1인 1일 배출량(kg)", 0.01, 10)),
            new FieldBinding("attr:대상 시스템:배출량변동", "wasteSigma", AnswerType.NUMBER,
                    num("배출량의 표준편차", 0, 5)),
            new FieldBinding("attr:거주민:외출시각변동", "leaveSigma", AnswerType.NUMBER,
                    num("외출 시각의 표준편차(분)", 0, 180)),
            new FieldBinding("attr:포항시 배출시간대 기반:배출허용창", "dischargeWindow",
                    AnswerType.TIME_RANGE, desc("HH:MM~HH:MM 한 구간. 자정을 넘을 수 있다")),
            new FieldBinding("attr:폐기물 유형:용량", "capacity", AnswerType.NUMBER,
                    num("수거함 용량(kg)", 1, 10000)),
            new FieldBinding("attr:폐기물 유형:임계값", "threshold", AnswerType.NUMBER,
                    num("민원이 발생하는 적재 비율(0~1)", 0, 1)),
            new FieldBinding("attr:수거차량:수거시각", "collectionTime", AnswerType.TIME,
                    desc("HH:MM 24시간 표기")),
            new FieldBinding("attr:수거차량:수거시각", "collectionTimes", AnswerType.TIME_LIST,
                    desc("하루 여러 번 수거할 때의 시각 목록")),
            new FieldBinding("attr:수거차량:수거요일", "collectionSchedule", AnswerType.ENUM,
                    desc("수거 주기 또는 요일 집합")),
            new FieldBinding("attr:수거차량:적재용량", "routeAvailableCapacityKg", AnswerType.NUMBER,
                    num("한 운행에 실을 수 있는 양(kg)", 1, 20000)),
            new FieldBinding("attr:수거차량:초기적재량", "initialTruckLoadKg", AnswerType.NUMBER,
                    num("출발 시 이미 실려 있는 양(kg)", 0, 20000)),
            new FieldBinding("attr:수거차량:배차간격", "dispatchIntervalMinutes", AnswerType.INTEGER,
                    num("차량 사이 출발 간격(분)", 0, 720)),
            new FieldBinding("attr:수거차량:지점당수거시간", "serviceMinutesPerSite", AnswerType.INTEGER,
                    num("한 지점에 머무는 시간(분)", 0, 120)),
            new FieldBinding("attr:교통 구역:시간대프로파일", "trafficProfileId", AnswerType.ENUM,
                    desc("교통 프로파일 식별자")),
            new FieldBinding("attr:구간 상수:구간이동시간", "routeTravelMinutes", AnswerType.INTEGER,
                    num("지점 사이 고정 이동시간(분)", 0, 240)),
            new FieldBinding("attr:교통구역 근사:구역내이동시간", "intraZoneTravelMinutes",
                    AnswerType.INTEGER, num("같은 구역 안 이동시간(분)", 0, 120)),
            new FieldBinding("attr:교통구역 근사:구역배정가정", "zoneAssignmentRule", AnswerType.ENUM,
                    desc("건물을 교통 구역에 배정하는 가정")),
            new FieldBinding("attr:수거 경로:방문순서", "routeSequence", AnswerType.STRING_LIST,
                    desc("지점 ID를 방문 순서대로 적은 목록"))
    );

    private static final List<NonSesField> NON_SES = List.of(
            new NonSesField("engine", NonSesField.Reason.OUTSIDE_SES,
                    "java냐 python이냐는 대상 시스템의 성질이 아니라 그것을 돌리는 수단이다"),
            new NonSesField("simulationGoal", NonSesField.Reason.PROCEDURE_CONTROL,
                    "실험의 목적 문장. 계산에 쓰이지 않는다(빌더 :381)"),
            new NonSesField("defaultApproval", NonSesField.Reason.PROCEDURE_CONTROL,
                    "서버가 채운 기본값에 동의했는가 — 구성 절차의 제어"),
            new NonSesField("inputAndScenarioConfirmed", NonSesField.Reason.PROCEDURE_CONTROL,
                    "미리보기를 확인했는가 — 구성 절차의 제어"),
            new NonSesField("executionApproval", NonSesField.Reason.PROCEDURE_CONTROL,
                    "실행을 눌렀는가 — 구성 절차의 제어")
    );

    public static List<FieldBinding> bindings() {
        return BINDINGS;
    }

    public static List<NonSesField> nonSesFields() {
        return NON_SES;
    }

    public static List<FieldBinding> bindingsFor(String pointId) {
        return BINDINGS.stream().filter(b -> b.pointId().equals(pointId)).toList();
    }
}
