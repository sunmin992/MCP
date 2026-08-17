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

    public enum Domain {
        WASTE_SIM,
        EDGE_THERMAL,
        /**
         * 어느 도메인인지 판단할 근거가 없음 — 양쪽 키워드가 모두 0개.
         * 예: "안녕하세요", "뭘 할 수 있어?". {@link #classify}만 반환하며,
         * 하위호환용 {@link #detect}는 이 값을 절대 내지 않는다.
         */
        UNKNOWN
    }

    /** 라즈베리파이 엣지 발열 도메인 어휘. */
    private static final Pattern EDGE = Pattern.compile(
            "(라즈베리\\s*파이|라즈베리파이|raspberry\\s*pi|rpi|\\bpi\\s*[45]\\b|pi4|pi5"
            + "|스로틀|throttl|발열|과열|냉각|방열판|히트\\s*싱크|heatsink|서멀|thermal"
            + "|쿨러|냉각팬|팬\\s*속도|soc\\s*온도|cpu\\s*온도|칩\\s*온도"
            // 팬 회전수 어휘(v1.9) — sweep_fan_rpm(FR-97~103)이 추가될 때
            // EdgeToolSelector에는 들어갔지만 <b>그보다 먼저 도는 이 게이트에는
            // 빠져</b> 있었다. 그래서 "팬 rpm 몇이 가성비가 제일 좋아?"·"최적 회전수
            // 찾아줘" 같은 가장 자연스러운 표현이 양쪽 점수 0으로 UNKNOWN이 되어,
            // 도구 선택기가 그 어휘를 전부 알고 있는데도 호출조차 되지 않았다.
            //
            // "스윕"·"sweep"과 "가성비"는 일부러 넣지 않는다 — 장량동에도
            // 수거시각 sweep 시나리오(collection-sweep)가 있고 "가성비"는 트럭
            // 선택에도 쓰이는 중립 어휘라, 넣으면 장량동 요청이 엣지로 새는
            // 방향의 오탐이 생긴다(현재 누수 0건을 깨뜨린다). 위 넷만으로도
            // 실측된 실패 사례는 전부 복구된다.
            + "|rpm|알피엠|pwm|회전수|운전점"
            // 예측 냉각(PTM, v1.9) — 같은 이유로 도메인 게이트에도 넣는다. "예측 냉각
            // 이득 있어?"는 엣지 어휘가 이것뿐이라, 빠지면 UNKNOWN이 되어 도구
            // 선택기까지 가지 못한다. "예측"만 단독으로는 넣지 않는다 — 장량동에도
            // 배출량 예측이 있어 그쪽이 엣지로 새기 때문이다.
            + "|ptm|예측\\s*(냉각|쿨링)|프리딕티브|predictive\\s*cool"
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
     * <b>기존 2분기 판정</b>(하위호환) — 엣지가 아니면 전부 장량동으로 흘려보낸다.
     * 이미 도메인이 확정된 화면(/waste, /edge) 안에서의 라우팅은 이 동작이 맞다.
     *
     * @return 이번 메시지가 명백히 엣지 도메인이면 {@link Domain#EDGE_THERMAL},
     *         그 외에는 {@code null}(= 기존 장량동 파이프라인이 그대로 처리).
     */
    public static Domain detect(String text) {
        if (text == null || text.isBlank()) return null;
        int edge = count(EDGE, text);
        if (edge == 0) return null;
        return edge > count(WASTE, text) ? Domain.EDGE_THERMAL : null;
    }

    /**
     * <b>3분기 판정</b> — 도메인이 아직 정해지지 않은 <b>루트 시작화면</b>에서 쓴다.
     * {@link #detect}와 달리 {@code null}을 반환하지 않고 세 값 중 하나를 낸다.
     *
     * <p><b>왜 별도 메서드인가</b>: {@link #detect}는 "엣지가 아니면 장량동"이라는
     * 폴백이 내장돼 있는데, 이건 장량동 화면 안에서는 옳지만 도메인 중립 시작화면에서는
     * 틀린다 — "안녕하세요"처럼 아무 단서 없는 첫 메시지가 조용히 장량동 시뮬레이터로
     * 빨려 들어가 사용자가 고르지도 않은 도메인에 갇히기 때문이다. 그렇다고
     * {@link #detect}의 반환 규약을 바꾸면 이미 그 폴백에 의존하는 채팅 파이프라인
     * 전체가 영향을 받으므로(회귀 위험), 기존 메서드는 그대로 두고 시작화면 전용
     * 판정을 따로 둔다.
     *
     * <p>판정 규칙:
     * <ul>
     *   <li>양쪽 키워드 0개 → {@link Domain#UNKNOWN} (되물어야 함)</li>
     *   <li>엣지가 더 많음 → {@link Domain#EDGE_THERMAL}</li>
     *   <li>그 외(장량동이 더 많거나 동점) → {@link Domain#WASTE_SIM}</li>
     * </ul>
     * 동점을 장량동으로 보내는 것은 {@link #detect}와 같은 기준이다 — 두 도메인
     * 어휘가 같은 수로 섞인 문장은 기존 동작과 어긋나지 않게 유지한다.
     */
    public static Domain classify(String text) {
        if (text == null || text.isBlank()) return Domain.UNKNOWN;
        int edge = count(EDGE, text);
        int waste = count(WASTE, text);
        if (edge == 0 && waste == 0) return Domain.UNKNOWN;
        return edge > waste ? Domain.EDGE_THERMAL : Domain.WASTE_SIM;
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
