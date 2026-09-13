package com.wastesim.ledger;

import com.wastesim.ses.SesFieldMapping;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 활성 규칙의 정답지가 SES 쪽과 붙어 있는가.
 *
 * <p><b>왜 이 대조가 필요한가</b>: 규칙이 읽는 필드명은 {@code JangnyangRules}에 문자열로
 * 적혀 있지만 그 이름의 정본은 {@code SesFieldMapping}의 커플링 바인딩이다. SES 쪽이
 * 이름을 바꾸면 규칙은 답을 영영 찾지 못해 {@link Activation#UNKNOWN}만 돌려주고, 모든
 * 실행이 {@code activation_unknown}으로 막힌다 — 예외도 컴파일 오류도 없이 조용히.
 * 조용한 고장은 시험으로만 드러난다.
 *
 * <p>{@code com.wastesim.ses}는 여기서 읽기만 한다.
 */
class JangnyangRulesSesAnchorTest {

    private static SesFieldMapping.FieldBinding trafficBinding() {
        return SesFieldMapping.bindings().stream()
                .filter(b -> JangnyangRules.TRAFFIC_MODE_FIELD.equals(b.answerField()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void 규칙이_읽는_필드는_SES_대응표에_실재한다() {
        assertNotNull(trafficBinding(),
                "JangnyangRules가 읽는 답변 필드 '" + JangnyangRules.TRAFFIC_MODE_FIELD
                        + "'가 SesFieldMapping.bindings()에 없습니다 — 규칙은 영영 UNKNOWN만 "
                        + "돌려주고 모든 실행이 activation_unknown으로 막힙니다.");
    }

    @Test
    void 규칙이_기대하는_값은_그_필드의_허용값이다() {
        List<String> allowed = trafficBinding().range().values();
        assertNotNull(allowed, JangnyangRules.TRAFFIC_MODE_FIELD + "의 허용값 선언이 없습니다.");
        assertTrue(allowed.contains(JangnyangRules.TRAFFIC_APPLY_VALUE),
                "규칙이 기대하는 '" + JangnyangRules.TRAFFIC_APPLY_VALUE
                        + "'가 허용값 " + allowed + "에 없습니다.");
    }

    @Test
    void 그_필드에_허용값을_넣으면_규칙이_활성을_돌려준다() {
        assertEquals(Activation.ACTIVE, JangnyangRules.registry().evaluate(
                JangnyangRules.TRAFFIC_APPLY,
                Map.of(JangnyangRules.TRAFFIC_MODE_FIELD, JangnyangRules.TRAFFIC_APPLY_VALUE)));
    }
}
