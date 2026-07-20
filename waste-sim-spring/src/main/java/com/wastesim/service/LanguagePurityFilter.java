package com.wastesim.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 일반 답변(plain-answer)이 한국어로만 이뤄졌는지 결정론적으로 검사하는
 * 후처리 안전망 — {@link JailbreakFilter}와 같은 위치(ChatController가
 * {@link OpenAiService#answerPlain} 출력에 적용)에서 동작한다.
 *
 * <p>{@code PLAIN_ANSWER_SYSTEM_PROMPT}의 "## 언어 규칙(가장 중요, 반드시
 * 최우선으로 지킬 것) — 반드시 한국어로만 답변하세요"를 명시해도, 로컬
 * 모델이 답변 전체를 중국어(간체)로 내는 경우가 실측(라이브 브라우저
 * 테스트)으로 확인됐다 — 프롬프트 최우선 규칙조차 100%는 못 지켜지므로
 * llm_benchmark.py의 {@code detect_lang()}과 동일한 방식(한글/한자/라틴
 * 문자 비율)으로 언어를 판정해 한국어가 아니면 후처리로 교체한다.
 */
public final class LanguagePurityFilter {

    private LanguagePurityFilter() {}

    private static final Pattern HANGUL = Pattern.compile("[가-힣]");
    private static final Pattern CJK = Pattern.compile("[一-鿿]");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]");

    public static final String SAFE_RETRY_MESSAGE =
            "죄송합니다, 응답을 생성하는 중 다른 언어가 섞여 나왔습니다. 같은 질문을 다시 한번 " +
            "보내주시겠어요?";

    /** @return 한국어 응답이 아니면 대체 안전 답변, 정상이면 {@code null} */
    public static String checkAndReplace(String reply) {
        if (reply == null || reply.isBlank()) return null;
        int hangul = countMatches(HANGUL, reply);
        int cjk = countMatches(CJK, reply);
        int latin = countMatches(LATIN, reply);
        int total = hangul + cjk + latin;
        if (total == 0) return null;   // 판단할 문자 자체가 없으면 통과(숫자만 있는 답 등)
        if (hangul >= total * 0.5) return null;   // 한글이 절반 이상이면 한국어로 간주, 통과
        return SAFE_RETRY_MESSAGE;
    }

    private static int countMatches(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int c = 0;
        while (m.find()) c++;
        return c;
    }
}
