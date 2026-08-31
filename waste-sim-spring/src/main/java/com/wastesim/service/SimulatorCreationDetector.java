package com.wastesim.service;

import java.util.regex.Pattern;

/**
 * "시뮬레이터를 구성해 달라"는 <b>생성 요청</b>을 결정론적으로 판별한다(FR-119).
 *
 * <p><b>왜 {@link ExecutionIntentDetector}와 합치지 않는가</b>: 두 판정은 묻는 것이 다르다.
 * 즉시 실행 경로(FR-10)는 "값이 이미 이 문장에 있는가"를 보고, 생성 요청은 "값을 모으는
 * 것부터 시작해야 하는가"를 본다. 한 판정기로 합치면 어느 쪽 기준이 이겼는지가 정규식
 * 순서라는 우연에 좌우되고, "8시 30분에 수거하는 시뮬레이터 만들어 줘"처럼 두 신호가 함께
 * 있는 문장에서 그 우연이 그대로 드러난다.
 *
 * <p>기존 게이트를 고치지 않고 <b>새 판정기를 추가</b>하는 것도 같은 이유다(부록 A.4.3) —
 * {@code ExecutionIntentDetector}를 비롯한 기존 게이트는 손대지 않으므로, 이 계층이
 * 없던 때의 대화는 완전히 동일하게 동작한다.
 *
 * <p>판정 기준은 두 신호의 <b>동시</b> 등장이다.
 * <ul>
 *   <li><b>대상</b> — 시뮬레이터·시뮬레이션·모델·실험 등 "만들 것"의 이름</li>
 *   <li><b>생성 동사</b> — 만들다·구성하다·설계하다·세팅하다 등</li>
 * </ul>
 * 하나만으로는 매칭하지 않는다. "시뮬레이션 실행해줘"는 대상만 있고, "표 만들어 줘"는
 * 동사만 있다 — 둘 다 생성 요청이 아니다. {@code ScenarioIntentDetector}가 AND 조건을 쓰는
 * 것과 같은 판단이며, 잘못 걸리면 즉시 실행돼야 할 요청이 열 개 넘는 질문으로 새기 때문에
 * 여기서는 더더욱 좁혀야 한다.
 */
public final class SimulatorCreationDetector {

    private SimulatorCreationDetector() {}

    /** "무엇을" 만드는가. */
    private static final Pattern TARGET = Pattern.compile(
            "시뮬레이터|시뮬레이션|시뮬레이|모델링|실험\\s*환경|실험\\s*설계|시나리오\\s*구성");

    /** "만든다"에 해당하는 동사. "실행"·"돌려"는 여기 없다 — 그건 즉시 실행 경로다. */
    private static final Pattern CREATE_VERB = Pattern.compile(
            "만들|구성해|구성하|설계해|설계하|세팅|셋업|구축|새로\\s*만|처음부터|준비해\\s*줘");

    /**
     * 명시적으로 수집을 시작하겠다는 표현 — 대상 어휘가 없어도 인정한다.
     * "질문해 줘"처럼 사용자가 이 계층의 존재를 이미 알고 부르는 경우다.
     */
    private static final Pattern EXPLICIT_START = Pattern.compile(
            "(필요한|무엇이).{0,12}?(물어|질문)|하나씩\\s*(물어|질문)|서브태스크");

    /** true면 생성 요청(수집을 시작해야 한다). */
    public static boolean isCreationRequest(String text) {
        if (text == null || text.isBlank()) return false;
        if (EXPLICIT_START.matcher(text).find()) return true;
        return TARGET.matcher(text).find() && CREATE_VERB.matcher(text).find();
    }
}
