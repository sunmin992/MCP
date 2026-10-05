package com.wastesim.templategen;

import java.util.List;

/**
 * 코드에서 뽑은 템플릿 초안 하나.
 *
 * <p>칸은 손으로 쓴 템플릿({@code jangnyang-templates.json})과 같다 — 그래야 제공자가 초안을
 * 그대로 고쳐 쓰고, 대조기가 칸별로 맞춰 볼 수 있다. 코드만으로 정할 수 없는 칸
 * (생성 조건 · SES 경로 · 노드 종류)은 비워 두고 {@link #needsReview}에 적는다.
 *
 * @param minExclusive  하한이 {@code > min}인가. 검증기가 {@code x <= 0}을 거절하면 참이다
 * @param evidence      각 값을 뽑아 온 자리("파일:줄 식"). 제공자가 초안을 믿을지 판단하는 근거
 * @param needsReview   코드만으로 정하지 못해 사람이 봐야 하는 칸과 그 이유
 */
public record GeneratedTemplate(
        String templateId,
        String answerKey,
        String valueType,
        String generateWhen,
        List<String> allowed,
        Double min,
        Double max,
        boolean minExclusive,
        boolean maxExclusive,
        String unit,
        Object defaultValue,
        String defaultBasis,
        String question,
        String configField,
        List<String> evidence,
        List<String> needsReview) {
}
