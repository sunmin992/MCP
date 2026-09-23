package com.wastesim.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
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
            this.sesId = root.path("sesId").asText();
            this.sesVersion = root.path("sesVersion").asText();
            this.templates = List.of(mapper.treeToValue(
                    root.path("templates"), SubtaskTemplate[].class));
        } catch (java.io.IOException e) {
            throw new UncheckedIOException("템플릿을 읽지 못했습니다: " + resourcePath, e);
        }
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
