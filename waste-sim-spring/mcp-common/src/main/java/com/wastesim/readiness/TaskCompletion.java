package com.wastesim.readiness;

import java.util.*;
import java.util.function.Supplier;

/** Simulator-independent task DAG. Checks execute on the server, never on client-provided completion flags. */
public final class TaskCompletion {
    private TaskCompletion() {}
    public record Task(String id, List<String> dependencies, List<String> subtasks,
                       Supplier<List<String>> validation) {
        public Task {
            dependencies = List.copyOf(dependencies);
            subtasks = List.copyOf(subtasks);
            Objects.requireNonNull(validation);
        }
    }
    public record Result(String taskId, String status, List<String> subtasks, List<String> reasons) {}
    public record Report(boolean ready, List<Result> tasks) {}

    /** subtask status: FILLED/NOT_GENERATED complete; UNFILLED/DEFERRED/missing incomplete. */
    public static Report evaluate(List<Task> tasks, Map<String, String> statuses) {
        Map<String, Task> definitions = new LinkedHashMap<>();
        for (Task t : tasks) {
            if (t.id() == null || t.id().isBlank() || definitions.putIfAbsent(t.id(), t) != null)
                throw new IllegalArgumentException("Invalid or duplicate task id: " + t.id());
        }
        Map<String, Result> results = new LinkedHashMap<>();
        for (Task t : tasks) evaluate(t.id(), definitions, statuses, results, new HashSet<>());
        List<Result> ordered = tasks.stream().map(t -> results.get(t.id())).toList();
        return new Report(!ordered.isEmpty() && ordered.stream().allMatch(r -> r.status().equals("COMPLETE")), ordered);
    }

    private static Result evaluate(String id, Map<String, Task> definitions, Map<String, String> statuses,
                                   Map<String, Result> results, Set<String> visiting) {
        if (results.containsKey(id)) return results.get(id);
        Task t = definitions.get(id);
        if (t == null) throw new IllegalArgumentException("Unknown dependency: " + id);
        if (!visiting.add(id)) throw new IllegalArgumentException("Cyclic task dependency: " + id);
        List<String> reasons = new ArrayList<>();
        for (String dependency : t.dependencies()) {
            if (!evaluate(dependency, definitions, statuses, results, visiting).status().equals("COMPLETE"))
                reasons.add("선행 Task 미완료: " + dependency);
        }
        for (String key : t.subtasks()) {
            String status = statuses.get(key);
            if (!"FILLED".equals(status) && !"NOT_GENERATED".equals(status))
                reasons.add("Subtask 미완료: " + key + " (" + status + ")");
        }
        boolean blocked = !reasons.isEmpty();
        if (!blocked) {
            try { reasons.addAll(t.validation().get()); }
            catch (RuntimeException e) { reasons.add("완료 검증 실패: " + e.getClass().getSimpleName()); }
        }
        Result r = new Result(id, blocked ? "BLOCKED" : reasons.isEmpty() ? "COMPLETE" : "INVALID",
                t.subtasks(), List.copyOf(reasons));
        visiting.remove(id);
        results.put(id, r);
        return r;
    }
}
