package com.wastesim.readiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.*;

/** Provider-owned specification, not inferred from SES names or supplied by the LLM. */
public record TaskManifest(String version, List<Definition> tasks, List<String> observations, List<String> resources) {
    public record Definition(String id, String label, List<String> dependencies, List<String> subtasks,
                             List<String> outputs, String validator) {}
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> VALIDATORS = Set.of("INPUTS", "CONFIG", "ROUNDTRIP", "SCENARIO");

    public static TaskManifest load(Class<?> owner) {
        return load(owner, VALIDATORS);
    }
    /** Providers may register additional server validators explicitly; clients cannot register them. */
    public static TaskManifest load(Class<?> owner, Set<String> registeredValidators) {
        try (InputStream in = owner.getResourceAsStream("/ses/task-manifest.json")) {
            if (in == null) throw new IllegalArgumentException("Task 명세가 없습니다");
            TaskManifest manifest = JSON.readValue(in, TaskManifest.class);
            manifest.validate(registeredValidators);
            return manifest;
        } catch (java.io.IOException e) { throw new IllegalArgumentException("Task 명세를 읽지 못했습니다", e); }
    }

    public void validate() {
        validate(VALIDATORS);
    }
    public void validate(Set<String> registeredValidators) {
        if (version == null || version.isBlank() || tasks == null || tasks.isEmpty()
                || observations == null || resources == null) throw new IllegalArgumentException("불완전한 Task 명세");
        Set<String> owned = new HashSet<>();
        for (Definition d : tasks) {
            if (d.label() == null || d.label().isBlank() || d.outputs() == null || d.outputs().isEmpty()
                    || d.outputs().stream().anyMatch(x -> x == null || x.isBlank())
                    || d.dependencies() == null || d.subtasks() == null || !registeredValidators.contains(d.validator()))
                throw new IllegalArgumentException("불완전한 Task: " + d.id());
            for (String k : d.subtasks()) if (!owned.add(k)) throw new IllegalArgumentException("중복 Subtask 소속: " + k);
        }
        TaskCompletion.evaluate(tasks.stream().map(d -> new TaskCompletion.Task(d.id(), d.dependencies(),
                d.subtasks(), List::of)).toList(), Map.of());
        for (String required : List.of("CONFIG", "ROUNDTRIP", "SCENARIO"))
            if (tasks.stream().filter(t -> t.validator().equals(required)).count() != 1)
                throw new IllegalArgumentException("완료 검증 단계는 한 번씩 필요합니다: " + required);
        String root = tasks.stream().filter(t -> t.validator().equals("SCENARIO")).findFirst().orElseThrow().id();
        Set<String> reachable = new HashSet<>();
        collect(root, reachable);
        if (reachable.size() != tasks.size()) throw new IllegalArgumentException("최종 시나리오 Task에 연결되지 않은 작업이 있습니다");
    }

    private void collect(String id, Set<String> reachable) {
        if (!reachable.add(id)) return;
        tasks.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow().dependencies().forEach(d -> collect(d, reachable));
    }

    public void verifyCoverage(Set<String> templateKeys) {
        Set<String> declared = new HashSet<>();
        tasks.forEach(d -> declared.addAll(d.subtasks()));
        if (!declared.equals(templateKeys)) throw new IllegalArgumentException("Task 명세와 템플릿의 Subtask 집합이 다릅니다");
    }

    /** Digest real provider resources; changing any source invalidates prior validation. */
    public String resourceFingerprint(Class<?> owner) {
        Map<String, String> hashes = new TreeMap<>();
        List<String> paths = new ArrayList<>(resources);
        paths.add("/ses/task-manifest.json");
        for (String path : paths) {
            try (InputStream in = owner.getResourceAsStream(path)) {
                if (in == null) throw new IllegalArgumentException("검증 대상 리소스 누락: " + path);
                hashes.put(path, ValidationReceipt.digest(in.readAllBytes()));
            } catch (java.io.IOException e) { throw new IllegalArgumentException("리소스 검사 실패: " + path, e); }
        }
        return ValidationReceipt.fingerprint(JSON.valueToTree(hashes));
    }

    public List<TaskCompletion.Task> instantiate(String prefix, Map<String, List<String>> errors) {
        Map<String, java.util.function.Supplier<List<String>>> checks = new HashMap<>();
        errors.forEach((key, value) -> checks.put(key, () -> value));
        return instantiateWithChecks(prefix, checks);
    }
    public List<TaskCompletion.Task> instantiateWithChecks(String prefix,
            Map<String, java.util.function.Supplier<List<String>>> checks) {
        for (Definition d : tasks)
            if (!checks.containsKey(d.validator())) throw new IllegalArgumentException("서버 검증 함수 누락: " + d.validator());
        return tasks.stream().map(d -> new TaskCompletion.Task(prefix + d.id(),
                d.dependencies().stream().map(x -> prefix + x).toList(),
                d.subtasks().stream().map(x -> prefix + x).toList(),
                checks.get(d.validator()))).toList();
    }
}
