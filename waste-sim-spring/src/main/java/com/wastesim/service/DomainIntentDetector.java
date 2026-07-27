package com.wastesim.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이번 메시지가 <b>어느 도메인의 모델</b>을 부르는 요청인지 결정론적으로 판정한다.
 * MCP 서버가 허브 역할을 하며 요청에 따라 두 계열의 모델을 갈라 부르는데, 그 갈림길
 * 판단을 여기서 한다.
 *
 * <ul>
 *   <li>{@link Domain#WASTE_SIM} — 장량동 생활쓰레기 DEVS 시뮬레이션(수거시각·트럭·민원)</li>
 *   <li>{@link Domain#EDGE_THERMAL} — 라즈베리파이 엣지 발열/스로틀링·방열판 배치</li>
 * </ul>
 *
 * <p><b>왜 LLM을 쓰지 않는가</b>: 이 프로젝트의 C2 원칙 — "실행·제어에 영향을 주는
 * 판단은 결정론적이어야 한다"({@link ExecutionIntentDetector}·{@link ScenarioIntentDetector}와
 * 같은 이유). 도메인을 잘못 고르면 엉뚱한 모델이 실행되고, 학생 실험에서는 같은 문장이
 * 실행할 때마다 다른 도구로 가면 결과 재현이 불가능해진다.
 *
 * <p><b>왜 '점수 비교' 방식인가</b>: 두 도메인의 어휘는 거의 겹치지 않지만("수거·민원·트럭"
 * vs "발열·스로틀링·방열판"), 완전히 안 겹치지는 않는다 — 예를 들어 "온도"는 양쪽 다
 * 나올 수 있고, 학생이 한 문장에 두 얘기를 섞을 수도 있다. 한쪽 키워드가 하나라도
 * 있으면 전환하는 방식은 오탐이 크므로, <b>양쪽 키워드 수를 세어 더 많은 쪽</b>을
 * 고른다. 동점이거나 엣지 키워드가 없으면 {@code null}을 반환해 기존 장량동 흐름을
 * 그대로 타게 한다 — 즉 이 클래스는 <b>기존 동작을 절대 바꾸지 않고, 명백히 엣지인
 * 요청만 새 경로로 빼내는</b> 역할이다(하위호환 우선).
 */
public final class DomainIntentDetector {

    private DomainIntentDetector() {}

    public enum Domain { WASTE_SIM, EDGE_THERMAL }

    /** 라즈베리파이 엣지 발열 도메인 어휘. */
    private static final Pattern EDGE = Pattern.compile(
            "(라즈베리\\s*파이|라즈베리파이|raspberry\\s*pi|rpi|\\bpi\\s*[45]\\b|pi4|pi5"
            + "|스로틀|throttl|발열|과열|냉각|방열판|히트\\s*싱크|heatsink|서멀|thermal"
            + "|쿨러|냉각팬|팬\\s*속도|soc\\s*온도|cpu\\s*온도|칩\\s*온도"
            + "|클럭|clock|mhz|추론\\s*성능|fps|프레임\\s*레이트"
            + "|ttt|ted|trt|시정수|열저항|엣지|edge\\s*(ai|디바이스)|보드\\s*온도"
            + "|캘리브|calibrat|열\\s*모델|thermal\\s*model|열화상)",
            Pattern.CASE_INSENSITIVE);

    /** 장량동 쓰레기 시뮬레이션 도메인 어휘. */
    private static final Pattern WASTE = Pattern.compile(
            "(쓰레기|생활\\s*폐기물|수거|민원|배출|적재율|분리\\s*배출|트럭|수거장|수거차"
            + "|장량동|원룸촌|빌라촌|거주민|직업\\s*구성|교통|정체|혼잡|노드|node_[a-d]"
            + "|시나리오|프리셋|용량|임계)",
            Pattern.CASE_INSENSITIVE);

    /**
     * @return 이번 메시지가 명백히 엣지 도메인이면 {@link Domain#EDGE_THERMAL},
     *         그 외에는 {@code null}(= 기존 장량동 파이프라인이 그대로 처리).
     */
    public static Domain detect(String text) {
        if (text == null || text.isBlank()) return null;
        int edge = count(EDGE, text);
        if (edge == 0) return null;
        return edge > count(WASTE, text) ? Domain.EDGE_THERMAL : null;
    }

    /** 진단·테스트용 — 어떤 근거로 그렇게 판정했는지 볼 수 있게 점수를 그대로 노출한다. */
    public static int edgeScore(String text) { return text == null ? 0 : count(EDGE, text); }

    public static int wasteScore(String text) { return text == null ? 0 : count(WASTE, text); }

    private static int count(Pattern p, String text) {
        Matcher m = p.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
