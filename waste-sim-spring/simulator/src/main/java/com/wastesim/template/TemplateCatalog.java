package com.wastesim.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

/**
 * 서브태스크 템플릿 모음. 리소스에서 읽어 메모리에 고정한다.
 *
 * <p>기동 시 한 번만 읽는다 — 같은 요청을 두 번 돌렸을 때 템플릿이 달라지면
 * 재현성이 깨진다.
 */
@Component
public class TemplateCatalog {

    private static final String RESOURCE = "/ses/jangnyang-templates.json";

    private final String sesId;
    private final String sesVersion;
    private final List<SubtaskTemplate> templates;

    public TemplateCatalog() {
        this(RESOURCE);
    }

    /**
     * 리소스 경로를 지정하는 생성자. 패키지 밖에는 열지 않는다 — 운영 코드는 항상
     * 기본 경로({@link #RESOURCE})만 쓰고, 이 생성자는 "리소스가 없으면 예외를
     * 던진다"는 조용한 폴백 금지 제약을 테스트가 직접 확인할 수 있게 하기 위한
     * 최소한의 통로다.
     */
    TemplateCatalog(String resourcePath) {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = TemplateCatalog.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("템플릿 리소스가 없습니다: " + resourcePath);
            }
            JsonNode root = mapper.readTree(in);
            // 없는 필드를 빈 문자열로 넘기지 않는다. 이 둘은 PES 가 "어느 SES 에서 나왔는가"를
            // 밝히는 유일한 식별자이고, 빈 값이 실리면 확인 화면과 실행까지 그대로 흘러간다.
            this.sesId = requireText(root, "sesId", resourcePath);
            this.sesVersion = requireText(root, "sesVersion", resourcePath);
            this.templates = List.of(mapper.treeToValue(
                    root.path("templates"), SubtaskTemplate[].class));
            checkConditions(templates, resourcePath);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("템플릿을 읽지 못했습니다: " + resourcePath, e);
        }
    }

    /**
     * 생성 조건은 템플릿에 적힌 식이라 컴파일러가 봐 주지 않는다. 불러올 때 두 가지를 막는다 —
     * 없는 답변키를 읽는 식(언제나 보류가 되어 대화가 끝나지 않는다)과 서로를 기다리는 조건(둘 다
     * 영영 보류다). 자기 답을 읽는 것은 순환이 아니다: {@code given} 은 답이 없으면 거짓이라 기다리지 않는다.
     */
    static void checkConditions(List<SubtaskTemplate> templates, String where) {
        Map<String, List<String>> reads = new java.util.HashMap<>();
        for (SubtaskTemplate t : templates) reads.put(t.answerKey(), t.generateWhen().inputs());
        reads.forEach((key, inputs) -> {
            for (String k : inputs) {
                if (!reads.containsKey(k))
                    throw new IllegalStateException(key + " 의 생성 조건이 없는 답변키 " + k + " 를 읽습니다: " + where);
            }
        });
        Set<String> done = new java.util.HashSet<>();
        for (String key : reads.keySet()) visit(key, reads, new java.util.LinkedHashSet<>(), done, where);
    }

    private static void visit(String key, Map<String, List<String>> reads, Set<String> path, Set<String> done, String where) {
        if (done.contains(key)) return;
        if (!path.add(key))
            throw new IllegalStateException("생성 조건이 서로를 기다립니다: " + String.join(" → ", path) + " → " + key + ": " + where);
        for (String k : reads.get(key)) if (!k.equals(key)) visit(k, reads, path, done, where);
        path.remove(key);
        done.add(key);
    }

    private static String requireText(JsonNode root, String field, String resourcePath) {
        String value = root.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalStateException(
                    "템플릿 리소스에 " + field + " 가 없습니다: " + resourcePath);
        }
        return value;
    }

    public String sesId() {
        return sesId;
    }

    public String sesVersion() {
        return sesVersion;
    }

    public List<SubtaskTemplate> all() {
        return templates;
    }

    public Optional<SubtaskTemplate> byId(String templateId) {
        return templates.stream().filter(t -> t.templateId().equals(templateId)).findFirst();
    }

    public Optional<SubtaskTemplate> byAnswerKey(String answerKey) {
        return templates.stream().filter(t -> t.answerKey().equals(answerKey)).findFirst();
    }
}
