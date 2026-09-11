package com.wastesim.ses;

import com.wastesim.subtask.AllowedRange;
import com.wastesim.subtask.AnswerType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * 이름이고, 여기 또 적으면 트리를 고쳤을 때 조용히 갈라진다. 반대로 {@code spec}이 아닌
 * ENUM/ENUM_LIST 필드는 트리에서 허용값을 얻을 길이 없다 — 속성에는 자료형도 허용값도
 * 없다(이것이 이 연구가 측정하는 격차다). 그래서 그런 필드는 {@link #enumOf}로 v4의
 * {@code allowedRange.values}를 그대로 옮겨 <b>여기서 선언</b>한다 — 안 그러면 다음 태스크의
 * 유도기가 선택지 없는 선택형 문항을 만든다(유도기는 {@code SpecChoice} 지점에서만 트리의
 * 자식 이름으로 값을 채우고, 속성 지점에는 그 경로가 없다).
 */
public final class SesFieldMapping {

    /**
     * {@code optionCodes}: spec 축에서만 쓴다 — 트리의 자식 이름(키)을 v4가 이미 쓰고
     * 있는 코드값(값)으로 옮긴다(Task 7 측정 1의 결론, Ruling 3). 이름 표기 차이(한글
     * 자식 이름 vs 영문 코드)는 구조의 차이가 아니라서 대응에만 손을 댄다.
     *
     * <p><b>이 맵은 이름만 옮기는 사전이지 순서를 정하지 않는다(Ruling 7).</b> 최종
     * 허용값의 순서는 {@link SesSubtaskDerivation#deriveSkeletons}가
     * {@code spec.options()}(트리의 자식 순회 순서)를 그대로 따라 정한다 — 이 맵의
     * 선언 순서는 읽기 편하라고 트리 순서를 그대로 옮겨 적었을 뿐, 실제로 읽히지 않는다.
     * ({@code LinkedHashMap}을 쓰는 이유는 순서를 정하기 위해서가 아니라 이 클래스의
     * 다른 헬퍼(enumOf 등)와 타입을 맞추기 위해서다.) 트리와 v4가 순서에 대해 실제로
     * 어긋나는 자리(예: {@code travelTimeMode})가 있을 수 있는데, 그건 이 맵으로
     * 조용히 바로잡을 자리가 아니라 측정으로 드러낼 자리다 — {@code 유도본-v4-대조.md}
     * travelTimeMode 항목과 {@code DerivedSetVsV4ReportTest}를 보라.
     *
     * <p>구성원은 <b>트리와 정확히 같아야 한다</b>(키 목록 == 트리의 자식 이름 집합) —
     * 트리가 자식을 늘리거나 줄이면 {@link SesSubtaskDerivation#deriveSkeletons}이
     * 예외를 던져 이 대응이 곧바로 낡았다는 것을 드러낸다. spec이 아닌 지점에는 이 필드가
     * 필요 없어 {@code null}이다.
     */
    public record FieldBinding(String pointId, String answerField,
                               AnswerType answerType, AllowedRange range,
                               Map<String, String> optionCodes) {
        public FieldBinding(String pointId, String answerField,
                             AnswerType answerType, AllowedRange range) {
            this(pointId, answerField, answerType, range, null);
        }
    }

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

    /**
     * {@code spec}이 아닌 ENUM/ENUM_LIST 필드용. values는 v4의 allowedRange.values를
     * 그대로 옮긴 것이지 여기서 새로 정한 것이 아니다 — 새 값을 지어내면 서버 검증
     * (jangnyang-simulator-v4.json)과 조용히 갈라진다.
     */
    private static AllowedRange enumOf(String description, List<String> values) {
        return new AllowedRange(description, null, null, null, null, null, null, null, values);
    }

    /**
     * 트리의 자식 이름 → v4 코드값을 순서 있는 맵으로 만든다(짝수 인덱스가 이름, 홀수
     * 인덱스가 코드). <b>인자 순서는 출력 순서가 아니다</b>(Ruling 7) — 최종 허용값의
     * 순서는 {@link SesSubtaskDerivation}이 트리의 자식 순회 순서로 다시 정한다. 여기서는
     * 읽기 편하도록 트리 순서 그대로 적는다.
     */
    private static Map<String, String> codes(String... nameThenCode) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenCode.length; i += 2) {
            m.put(nameThenCode[i], nameThenCode[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }

    private static final List<FieldBinding> BINDINGS = List.of(
            // ── spec 축 4개 — 허용값은 트리에서 온다. 이름은 한글, v4는 영문 코드라서
            //    optionCodes로 옮긴다(Task 7 측정 1) ─────────────────────────────
            new FieldBinding("spec:실험:실험 유형 축", "scenarioType", AnswerType.ENUM,
                    desc("서버가 실제로 구현한 실행 유형 중 하나"),
                    // 트리 순서 그대로 — v4도 같은 순서라 옮기기만 하면 된다.
                    codes("단일 실행", "single-run",
                            "직업 구성 실험", "occupation-mix",
                            "수거시각 실험", "collection-sweep",
                            "배출행동 실험", "behavior-grid",
                            "용량·임계값 실험", "infra-grid",
                            "밀도 실험", "density",
                            "수거일정 실험", "collection-schedule",
                            "다중차량 실험", "multi-truck",
                            "분리배출 실험", "waste-separation",
                            "확장직업 실험", "new-occupations",
                            "결합변형 실험", "coupling-variants",
                            "월별배출 실험", "monthly-waste",
                            "차종·방문순서 실험", "truck-route")),
            new FieldBinding("spec:거주민:배출시각 모델 축", "dischargeTimeMode", AnswerType.ENUM,
                    desc("배출 시각을 무엇으로 정하는가"),
                    // 트리 순서 그대로 — v4도 같은 순서다.
                    codes("직업별 외출시각 기반", "PAPER_BASELINE",
                            "포항시 배출시간대 기반", "POHANG_ACTUAL")),
            new FieldBinding("spec:수거차량:차종 축", "truckType", AnswerType.ENUM,
                    desc("수거에 쓰는 차종"),
                    // 트리 순서 그대로 — v4도 같은 순서다(5톤·2.5톤·1톤 내림차순).
                    codes("5톤 차량", "LARGE_5TON",
                            "2.5톤 차량", "MEDIUM_2P5T",
                            "1톤 차량", "SMALL_1TON")),
            new FieldBinding("spec:수거 경로:이동시간 방식 축", "travelTimeMode", AnswerType.ENUM,
                    desc("지점 사이 이동시간을 무엇으로 계산하는가"),
                    // 트리 순서 그대로 적는다: [구간 상수, 교통구역 근사, 실제 도로 기반].
                    // 이름만 옮기면 최종 순서도 이 순서를 따른다(Ruling 7). 그런데 v4는
                    // [LEGACY_CONSTANT, OSRM_HYBRID(실제 도로 기반), ZONE_PROXY_HYBRID
                    // (교통구역 근사)] 순으로 뒤 두 개가 뒤바뀌어 있다 — 트리와 v4가 순서에
                    // 대해 실제로 불일치한다는 뜻이고, 이 대응으로 조용히 맞출 자리가 아니라
                    // 측정으로 드러낼 자리다(유도본-v4-대조.md travelTimeMode 항목,
                    // DerivedSetVsV4ReportTest.specChoiceOptionOrderMismatchesAgainstV4AreOnlyTheKnownOnes).
                    codes("구간 상수", "LEGACY_CONSTANT",
                            "교통구역 근사", "ZONE_PROXY_HYBRID",
                            "실제 도로 기반", "OSRM_HYBRID")),

            // ── multi 복제 수 3개 ──────────────────────────────────────────────
            new FieldBinding("multi:수거지점 집합", "numBuildings", AnswerType.INTEGER,
                    num("1 이상 26 이하 — 자동 생성 지점 ID가 Node_A~Node_Z다", 1, 26)),
            new FieldBinding("multi:거주민 집합", "residentsPerBuilding", AnswerType.INTEGER,
                    num("건물당 1명 이상", 1, 500)),
            new FieldBinding("multi:수거차량 집합", "truckCount", AnswerType.INTEGER,
                    num("1대 이상 — python 참조 엔진은 1대만 지원한다", 1, 26)),

            // ── 커플링 활성 1개 ────────────────────────────────────────────────
            new FieldBinding("coupling:교통 구역.혼잡계수->수거 경로.이동시간", "trafficMode",
                    AnswerType.ENUM, enumOf("교통 혼잡을 반영할 것인가", List.of("APPLY", "NONE"))),

            // ── 속성 21개 ──────────────────────────────────────────────────────
            new FieldBinding("attr:거주민 집합:직업구성", "occupationPreset", AnswerType.ENUM,
                    enumOf("직업 구성 프리셋",
                            List.of("BALANCED", "UNIVERSITY", "INDUSTRIAL", "FAMILY"))),
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
                    enumOf("수거 주기 또는 요일 집합", List.of(
                            "EVERY_DAY", "EVERY_2_DAYS", "EVERY_3_DAYS", "EVERY_7_DAYS",
                            "WEEKDAYS_MON_FRI", "MON_WED_FRI", "POHANG_MON_TUE_THU_FRI"))),
            new FieldBinding("attr:수거차량:적재용량", "routeAvailableCapacityKg", AnswerType.NUMBER,
                    num("한 운행에 실을 수 있는 양(kg)", 1, 20000)),
            new FieldBinding("attr:수거차량:초기적재량", "initialTruckLoadKg", AnswerType.NUMBER,
                    num("출발 시 이미 실려 있는 양(kg)", 0, 20000)),
            new FieldBinding("attr:수거차량:배차간격", "dispatchIntervalMinutes", AnswerType.INTEGER,
                    num("차량 사이 출발 간격(분)", 0, 720)),
            new FieldBinding("attr:수거차량:지점당수거시간", "serviceMinutesPerSite", AnswerType.INTEGER,
                    num("한 지점에 머무는 시간(분)", 0, 120)),
            new FieldBinding("attr:교통 구역:시간대프로파일", "trafficProfileId", AnswerType.ENUM,
                    enumOf("교통 프로파일 식별자",
                            List.of("jangryang-weekday", "jangryang-volume-weekday"))),
            new FieldBinding("attr:구간 상수:구간이동시간", "routeTravelMinutes", AnswerType.INTEGER,
                    num("지점 사이 고정 이동시간(분)", 0, 240)),
            new FieldBinding("attr:교통구역 근사:구역내이동시간", "intraZoneTravelMinutes",
                    AnswerType.INTEGER, num("같은 구역 안 이동시간(분)", 0, 120)),
            new FieldBinding("attr:교통구역 근사:구역배정가정", "zoneAssignmentRule", AnswerType.ENUM,
                    enumOf("건물을 교통 구역에 배정하는 가정",
                            List.of("NONE", "CONTIGUOUS", "ROUND_ROBIN"))),
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
