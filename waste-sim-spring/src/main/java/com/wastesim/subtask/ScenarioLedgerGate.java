package com.wastesim.subtask;

import com.wastesim.ledger.*;
import com.wastesim.ledger.verify.ConfigBackVerifier;
import com.wastesim.ledger.wiring.JangnyangLedgerWiring;
import java.time.Instant;
import java.util.*;

/** Shared by session builds and the stateless MCP builder. Never repairs a generated config. */
public final class ScenarioLedgerGate {
    private ScenarioLedgerGate() { }

    public static List<String> verify(JangnyangSubtaskDefinition def,
                                      Map<String, JangnyangSubtaskAnswer> answers,
                                      JangnyangScenarioSpec spec) {
        ParameterLedger expected = new ParameterLedger();
        Map<String, String> bindings = new LinkedHashMap<>();
        Map<String, String> candidates = new LinkedHashMap<>(JangnyangLedgerWiring.fieldToParameterId());
        candidates.put("collectionTimeMinutes", JangnyangLedgerWiring.parameterIdOf("collectionTime"));
        candidates.put("numTrucks", JangnyangLedgerWiring.parameterIdOf("truckCount"));
        List<String> blocks = new ArrayList<>();
        for (var entry : candidates.entrySet()) {
            String field = ParameterId.fieldOf(entry.getValue());
            JangnyangSubtask task = def.byAnswerField(field);
            if (task == null) continue; // Legacy sets do not declare all current inputs.
            JangnyangSubtaskAnswer answer = answers.get(task.id());
            if (answer == null || !answer.valid()) continue; // Completeness is checked separately.
            if (JangnyangSubtaskValidator.isNotApplicable(answer.value())) continue;
            if (field.equals("trafficProfileId") && !spec.toSimulationConfig().isTrafficEnabled()) continue;
            Object value = answer.value();
            // The builder explicitly resolves the registered "default" profile alias.
            if (field.equals("trafficProfileId") && "default".equals(value)) {
                var resolved = spec.appliedDefaults().stream().filter(d -> d.field().equals(field)).findFirst();
                if (resolved.isPresent()) value = resolved.get().value();
            }
            var basis = task.basis() == null ? FieldBasis.unknown() : task.basis();
            var decision = AnswerDecisions.fromAnswer(expected.nextDecisionId(entry.getValue()),
                    entry.getValue(), answer.raw(), value, answer.source(), basis.kind(),
                    new ValueSource("validated_answer", task.id(), def.hash(), Instant.now()),
                    answer.source() == SubtaskAnswerSource.LLM_NORMALIZED
                            ? new Transformation("llm_free_text_to_answer_field", List.of(task.id())) : null,
                    Instant.now());
            expected.append(decision);
            bindings.put(entry.getKey(), entry.getValue());
        }
        blocks.addAll(new ConfigBackVerifier().verify(spec.toSimulationConfig(), expected, bindings).blocks());
        var config = spec.toSimulationConfig();
        Object window = answerValue(def, answers, "dischargeWindow");
        if (window instanceof List<?> values && values.size() == 2) {
            check(blocks, "dischargeWindowStartMinutes", values.get(0), config.getDischargeWindowStartMinutes());
            check(blocks, "dischargeWindowEndMinutes", values.get(1), config.getDischargeWindowEndMinutes());
        }
        Object times = answerValue(def, answers, "collectionTimes");
        if (times instanceof List<?> values && values.size() > 1) {
            check(blocks, "collectionTimesMinutes", values, config.getCollectionTimesMinutes());
        }
        Object traffic = answerValue(def, answers, "trafficMode");
        if (traffic != null) check(blocks, "trafficEnabled", !"NONE".equals(traffic), config.isTrafficEnabled());
        Object preset = answerValue(def, answers, "occupationPreset");
        if (preset instanceof String key) {
            check(blocks, "occupationMix", com.wastesim.model.ScenarioPreset.fromKey(key).mix,
                    config.getOccupationMix());
        }
        Object schedule = answerValue(def, answers, "collectionSchedule");
        if (schedule instanceof String key) {
            Integer interval = switch (key) {
                case "EVERY_DAY" -> 1;
                case "EVERY_2_DAYS" -> 2;
                case "EVERY_3_DAYS" -> 3;
                case "EVERY_7_DAYS" -> 7;
                default -> null;
            };
            if (interval != null) check(blocks, "collectionIntervalDays", interval, config.getCollectionIntervalDays());
            List<Integer> days = switch (key) {
                case "WEEKDAYS_MON_FRI" -> List.of(0, 1, 2, 3, 4);
                case "MON_WED_FRI" -> List.of(0, 2, 4);
                case "POHANG_MON_TUE_THU_FRI" -> List.of(0, 1, 3, 4);
                default -> null;
            };
            if (days != null) check(blocks, "collectionDaysOfWeek", days, config.getCollectionDaysOfWeek());
        }
        return List.copyOf(blocks);
    }

    private static Object answerValue(JangnyangSubtaskDefinition def,
                                      Map<String, JangnyangSubtaskAnswer> answers, String field) {
        var task = def.byAnswerField(field);
        var answer = task == null ? null : answers.get(task.id());
        return answer == null || !answer.valid() || JangnyangSubtaskValidator.isNotApplicable(answer.value())
                ? null : answer.value();
    }

    private static void check(List<String> blocks, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) blocks.add(field + ": 답변에서 유도한 값과 실행 설정이 다릅니다.");
    }
}
