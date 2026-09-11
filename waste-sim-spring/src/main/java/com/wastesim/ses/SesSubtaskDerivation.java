package com.wastesim.ses;

import com.wastesim.subtask.AllowedRange;
import com.wastesim.subtask.AnswerType;
import com.wastesim.subtask.JangnyangSubtask;
import com.wastesim.subtask.JangnyangSubtaskDefinition;
import com.wastesim.subtask.SubtaskGroup;
import com.wastesim.subtask.SubtaskStage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SES 가지치기 지점에서 서브태스크 세트를 만든다.
 *
 * <p><b>여기가 가설이 주장하는 자리다.</b> 지금까지 34문항은 사람이 리소스 파일에 적은
 * 목록이었다. 이 클래스가 하는 일은 그 목록이 트리에서 나오게 하는 것이고, 나온 것과
 * 적힌 것을 대조하는 것이 검증이다(Task 7의 대조 테스트).
 *
 * <p>유도하는 것과 사람이 쓰는 것의 경계: {@code answerType}·{@code allowedRange}·
 * {@code required}·{@code group}은 여기서 나오고, {@code question}·{@code validationRule}·
 * {@code basis}는 문면 파일에서 온다.
 */
public final class SesSubtaskDerivation {

    /**
     * 화면 단계 이름 — 지점이 달린 엔티티가 루트 아래 어느 가지에 있는지로 정한다.
     *
     * <p>루트의 실제 가지는 "대상 시스템"·"실험"·"관측" 셋뿐이다("실행과 절차"는 트리에
     * 없다). 그래서 이 이름은 세 가지에 걸리는 지점의 그룹을 정하는 동시에, 트리 경로로
     * 안 닿는 지점(커플링 활성·SES 밖 필드·절차 제어)을 모아 두는 네 번째 자리를 만든다.
     */
    private static final List<String> GROUP_NAMES = List.of(
            "대상 시스템", "실험", "관측", "실행과 절차");

    private SesSubtaskDerivation() { }

    public static List<SubtaskSkeleton> deriveSkeletons() {
        EntityStructure structure = JangnyangEntityStructure.get();
        Map<String, DecisionPoint> byId = new LinkedHashMap<>();
        for (DecisionPoint p : DecisionPointExtractor.extract(structure)) byId.put(p.id(), p);

        List<SubtaskSkeleton> out = new ArrayList<>();
        for (SesFieldMapping.FieldBinding b : SesFieldMapping.bindings()) {
            DecisionPoint point = byId.get(b.pointId());
            if (point == null) {
                throw new IllegalStateException("SES에 없는 지점을 가리키는 대응: " + b.pointId());
            }
            out.add(new SubtaskSkeleton(b.pointId(), b.answerField(), b.answerType(),
                    rangeFor(point, b), isRequired(point), groupOf(structure, point)));
        }
        return List.copyOf(out);
    }

    /**
     * spec 축이면 허용값을 <b>트리에서</b> 채운다. 그 외에는 선언된 범위를 그대로 쓴다.
     * 이 한 줄이 "구조가 허용값을 정한다"의 실체다.
     */
    private static AllowedRange rangeFor(DecisionPoint point, SesFieldMapping.FieldBinding b) {
        AllowedRange declared = b.range();
        if (point instanceof DecisionPoint.SpecChoice spec) {
            return new AllowedRange(declared.description(), null, null, null, null, null, null, null,
                    optionValues(spec, b));
        }
        return declared;
    }

    /**
     * spec 축의 최종 허용값 목록. {@code optionCodes}가 있으면 트리의 자식 이름을 v4의
     * 코드값으로 옮긴 것을 쓴다 — v4가 이미 영문 코드로 답을 받고 검증하고 있어서, 한글
     * 이름을 그대로 내보내면 서브태스크 검증이 v4와 다른 값을 허용하게 된다(측정 1,
     * {@code 유도본-v4-대조.md}).
     *
     * <p><b>Ruling 7 — {@code optionCodes}는 이름만 옮기는 사전이지 순서를 정하지
     * 않는다.</b> 구성원과 순서는 여전히 트리, 즉 {@code spec.options()}가 순회하는
     * 순서가 정한다. 그래서 여기서는 {@code optionCodes}의 값 컬렉션을 그대로 내지 않고
     * {@code spec.options()}를 하나씩 돌면서 코드로 옮긴다 — 이전 구현은
     * {@code optionCodes.values()}를 그대로 반환해 선언 순서가 순서의 출처가 돼
     * 버렸고, travelTimeMode에서 v4에 맞춰 선언 순서를 손으로 바꿔 둔 탓에 그 사실이
     * 가려져 있었다(코드 리뷰로 드러남). 트리의 자식 이름이 {@code optionCodes}에 없으면
     * (대응이 낡았다는 뜻) 조용히 빠뜨리지 않고 예외를 던지고, 반대로 {@code optionCodes}에
     * 트리에 없는 이름이 남아 있어도(트리가 자식을 줄였는데 대응을 안 지운 경우) 예외를
     * 던진다 — 양쪽 다 트리와의 불일치이기 때문이다.
     *
     * <p>이렇게 고치면 travelTimeMode는 트리 순서([구간 상수, 교통구역 근사, 실제 도로
     * 기반])를 그대로 코드로 옮긴 [LEGACY_CONSTANT, ZONE_PROXY_HYBRID, OSRM_HYBRID]를
     * 내고, 이는 v4의 순서([LEGACY_CONSTANT, OSRM_HYBRID, ZONE_PROXY_HYBRID])와 실제로
     * 다르다 — 이것을 지우는 것이 아니라 있는 그대로 드러내는 것이 측정이다
     * ({@code DerivedSetVsV4ReportTest.specChoiceOptionOrderMismatchesAgainstV4AreOnlyTheKnownOnes},
     * {@code 유도본-v4-대조.md} travelTimeMode 항목).
     */
    private static List<String> optionValues(DecisionPoint.SpecChoice spec, SesFieldMapping.FieldBinding b) {
        Map<String, String> optionCodes = b.optionCodes();
        if (optionCodes == null) {
            return spec.options();
        }
        List<String> values = new ArrayList<>();
        for (String treeName : spec.options()) {
            String code = optionCodes.get(treeName);
            if (code == null) {
                throw new IllegalStateException("트리의 자식이 optionCodes에 없다(대응이 낡았다) — "
                        + b.answerField() + ": 트리의 자식=" + treeName
                        + " 대응의 키=" + optionCodes.keySet());
            }
            values.add(code);
        }
        Set<String> treeNames = Set.copyOf(spec.options());
        if (!optionCodes.keySet().equals(treeNames)) {
            throw new IllegalStateException("optionCodes에 트리에 없는 이름이 남아 있다(대응이 낡았다) — "
                    + b.answerField() + ": 대응의 키=" + optionCodes.keySet()
                    + " 트리의 자식=" + treeNames);
        }
        return List.copyOf(values);
    }

    private static boolean isRequired(DecisionPoint point) {
        return point instanceof DecisionPoint.SpecChoice
                || point instanceof DecisionPoint.MultiCount;
    }

    /** 루트 바로 아래 가지 이름으로 화면 단계를 정한다. 닿지 않으면 마지막 그룹에 둔다. */
    private static int groupOf(EntityStructure structure, DecisionPoint point) {
        try {
            List<String> path = structure.pathTo(point.entity());
            if (path.size() >= 2) {
                int idx = GROUP_NAMES.indexOf(path.get(1));
                if (idx >= 0) return idx + 1;
            }
        } catch (IllegalArgumentException ignored) {
            // 커플링 활성 지점은 엔티티 경로로 닿지 않는다 — 아래 기본값으로 간다.
        }
        return GROUP_NAMES.size();
    }

    public static JangnyangSubtaskDefinition derive(Map<String, ProseEntry> prose) {
        List<JangnyangSubtask> subtasks = new ArrayList<>();
        int order = 1;

        for (SubtaskSkeleton s : deriveSkeletons()) {
            ProseEntry p = require(prose, s.answerField());
            subtasks.add(new JangnyangSubtask("ST-S" + pad(order), order, s.group(),
                    SubtaskStage.COLLECT, p.question(), s.answerField(), s.answerType(),
                    s.required(), p.allowsNotApplicable(), s.allowedRange(), p.validationRule(),
                    p.retryQuestion(), p.completionCondition(), p.basis()));
            order++;
        }

        // SES 밖 결정과 절차 제어. 트리에서 나오지 않지만 물어야 하는 것들이라,
        // 마지막 그룹에 모아 둔다 — 어디서 왔는지가 순서에 드러나야 한다.
        for (SesFieldMapping.NonSesField f : SesFieldMapping.nonSesFields()) {
            ProseEntry p = require(prose, f.answerField());
            subtasks.add(new JangnyangSubtask("ST-S" + pad(order), order, GROUP_NAMES.size(),
                    SubtaskStage.COLLECT, p.question(), f.answerField(), nonSesType(f),
                    true, p.allowsNotApplicable(), nonSesRange(f), p.validationRule(),
                    p.retryQuestion(), p.completionCondition(), p.basis()));
            order++;
        }

        List<SubtaskGroup> groups = new ArrayList<>();
        for (int i = 0; i < GROUP_NAMES.size(); i++) {
            groups.add(new SubtaskGroup(i + 1, GROUP_NAMES.get(i),
                    GROUP_NAMES.get(i) + "에 관한 결정을 받습니다."));
        }

        return new JangnyangSubtaskDefinition("jangnyang-simulator-v5", 5, true,
                groups, List.copyOf(subtasks));
    }

    private static ProseEntry require(Map<String, ProseEntry> prose, String field) {
        ProseEntry p = prose.get(field);
        if (p == null) throw new IllegalStateException("문면이 없는 필드: " + field);
        return p;
    }

    private static String pad(int order) {
        return String.format("%03d", order);
    }

    /**
     * 절차 제어 3개(defaultApproval·inputAndScenarioConfirmed·executionApproval)는
     * BOOLEAN(예/아니오)이 아니라 v4가 이미 쓰는 ENUM이다 — 측정 1에서 드러난 자리다.
     * executionApproval은 RUN·REVISE·CANCEL 세 값이라 애초에 예/아니오로는 표현할 수
     * 없다. 이 필드들은 트리 밖에 있어 유도할 근거가 없으므로, v4의 값을 그대로 옮긴다
     * ({@code 유도본-v4-대조.md} defaultApproval·executionApproval·
     * inputAndScenarioConfirmed 항목).
     */
    private static AnswerType nonSesType(SesFieldMapping.NonSesField f) {
        return switch (f.answerField()) {
            case "engine", "defaultApproval", "inputAndScenarioConfirmed", "executionApproval" ->
                    AnswerType.ENUM;
            case "simulationGoal" -> AnswerType.STRING;
            default -> AnswerType.BOOLEAN;
        };
    }

    private static AllowedRange nonSesRange(SesFieldMapping.NonSesField f) {
        return switch (f.answerField()) {
            case "engine" -> new AllowedRange("java 또는 python", null, null, null, null, null,
                    null, null, List.of("java", "python"));
            case "simulationGoal" -> new AllowedRange("2자 이상 200자 이하의 한 문장", null, null,
                    2, 200, null, null, null, null);
            case "defaultApproval" -> new AllowedRange("ALL 또는 NONE", null, null, null, null,
                    null, null, null, List.of("ALL", "NONE"));
            case "inputAndScenarioConfirmed" -> new AllowedRange("CONFIRMED 또는 NEEDS_CHANGE",
                    null, null, null, null, null, null, null, List.of("CONFIRMED", "NEEDS_CHANGE"));
            case "executionApproval" -> new AllowedRange("RUN · REVISE · CANCEL", null, null,
                    null, null, null, null, null, List.of("RUN", "REVISE", "CANCEL"));
            default -> new AllowedRange("예/아니오", null, null, null, null, null, null, null, null);
        };
    }
}
