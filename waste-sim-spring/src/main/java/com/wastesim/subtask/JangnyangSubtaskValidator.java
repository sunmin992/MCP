package com.wastesim.subtask;

import com.wastesim.tool.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 서브태스크별 규칙(자료형·허용 범위·검증 규칙)으로 답변을 검사한다(FR-126, SDD 2.18.5).
 *
 * <p>설계 규약 셋을 그대로 코드로 옮긴 것이다.
 * <ul>
 *   <li><b>오류를 모아서 반환한다</b> — 첫 오류에서 던지지 않는다(같은
 *       이유: 여러 개를 한 번에 알려주는 편이 시행착오가 적다).</li>
 *   <li><b>재질문 문장을 만들지 않는다</b> — 카탈로그의 {@code retryQuestion}을 오류에
 *       그대로 실어 보낸다(FR-127, D-47).</li>
 *   <li><b>조용히 보정하지 않는다</b> — 범위 밖 값을 클램프하지 않고 돌려준다(D-26).</li>
 * </ul>
 *
 * <p><b>왜 LLM이 이 자리에 없는가</b>: 정규화(자연어 → 값)와 검증(값 → 통과 여부)을 한
 * 단계로 합치면 "LLM이 통과라고 했으니 통과"가 되어 fail-closed가 무너진다(D-46). 이
 * 클래스는 이미 구조화된 값만 받고, 그 값이 어디서 왔는지는 보지 않는다 — LLM이 뽑았든
 * 사용자가 직접 골랐든 같은 규칙을 통과해야 한다.
 */
@Component
public class JangnyangSubtaskValidator {

    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");
    private static final Pattern NODE_ID = Pattern.compile("^Node_[A-Z]$");

    /**
     * "이번 실험과 무관하다"는 답으로 인정하는 표현.
     *
     * <p>고정 세트는 관련 없는 항목도 생략하지 않고 묻는다. 그러면 사용자는 답할 수 없는
     * 질문을 만나게 되는데, 그때 빠져나갈 문을 두지 않으면 수집이 그 자리에서 멈춘다.
     * 그래서 "해당 없음"을 <b>정식 답변</b>으로 받는다 — 건너뛴 것이 아니라 답한 것이고,
     * 그 사실이 답변 기록에 남는다(NFR-20).
     */
    public static final String NOT_APPLICABLE = "해당 없음";

    private static final java.util.Set<String> NOT_APPLICABLE_WORDS = java.util.Set.of(
            "해당 없음", "해당없음", "없음", "n/a", "na", "안 함", "안함",
            "기본값 사용", "기본값사용", "기본값", "제공 데이터 없음", "제공데이터없음");

    /**
     * 이 답이 "해당 없음"류인가.
     *
     * <p>목록으로 감싸인 것도 인정한다 — 목록형 질문에 "해당 없음"이라고 답하면 정규화
     * LLM이 {@code ["해당 없음"]}으로 감싸 돌려주기 때문이다. 문자열만 보면 그 답이
     * 형식 오류로 떨어지고 사용자는 빠져나갈 수 없는 질문에 갇힌다(실측 확인).
     */
    public static boolean isNotApplicable(Object rawValue) {
        if (rawValue instanceof String text) {
            return NOT_APPLICABLE_WORDS.contains(text.trim().toLowerCase());
        }
        if (rawValue instanceof java.util.List<?> l) {
            return l.size() == 1 && isNotApplicable(l.get(0));
        }
        return false;
    }

    /**
     * 답변 묶음을 세트 규칙으로 검사한다.
     *
     * @param def      기준이 되는 세트
     * @param answers  서브태스크 ID → 제출된 원시 값(문자열·숫자·불리언·목록)
     * @param existing 이미 세션에 누적돼 통과한 답변들. 누락 판정에 함께 반영한다 —
     *                 이번 턴에 안 낸 항목이 지난 턴에 이미 통과했다면 누락이 아니다
     */
    public SubtaskValidationResult validate(JangnyangSubtaskDefinition def,
                                            Map<String, Object> answers,
                                            Map<String, JangnyangSubtaskAnswer> existing) {
        List<SubtaskError> errors = new ArrayList<>();
        Map<String, JangnyangSubtaskAnswer> accepted = new LinkedHashMap<>();
        if (existing != null) accepted.putAll(existing);

        if (answers != null) {
            for (Map.Entry<String, Object> e : answers.entrySet()) {
                String key = e.getKey();
                JangnyangSubtask st = resolve(def, key);
                if (st == null) {
                    // 세트에 없는 ID·필드명은 무시하지 않고 거부한다(FR-138·UT-310).
                    // 조용히 버리면 사용자는 자기 답이 반영됐다고 믿은 채 진행한다.
                    errors.add(new SubtaskError(key, ErrorCode.UNKNOWN_TOOL,
                            "세트 " + def.subtaskSetId() + " v" + def.version()
                                    + "에 없는 서브태스크 ID·답변 필드다: " + key,
                            "세트에 있는 서브태스크에만 답할 수 있습니다."));
                    continue;
                }
                JangnyangSubtaskAnswer a = coerce(st, e.getValue());
                if (a.valid()) {
                    // 다시 제출한 답이 이전 값을 덮어쓴다(UT-309).
                    accepted.put(st.id(), a);
                } else {
                    // 실패한 값은 누적하지 않는다 — 남겨 두면 조립 단계가 그 값을 쓴다.
                    accepted.remove(st.id());
                    errors.add(a.error());
                }
            }
        }

        // 누락은 <b>수집 단계</b>의 필수 항목만 센다. 확인 단계(ST-048~050)는 사용자가
        // 타이핑으로 답하는 항목이 아니라 미리보기 화면이 채우는 것이라, 여기 넣으면
        // 수집이 아무리 완벽해도 complete가 영원히 false가 된다.
        List<String> missing = new ArrayList<>();
        for (JangnyangSubtask st : def.collectSubtasks()) {
            if (st.required() && !accepted.containsKey(st.id())) missing.add(st.id());
        }

        boolean complete = missing.isEmpty() && errors.isEmpty();
        return new SubtaskValidationResult(errors.isEmpty(), missing, errors, complete, accepted);
    }

    /** 서브태스크 ID로도, 답변 필드명으로도 찾을 수 있게 한다(외부 MCP 클라이언트 편의). */
    private static JangnyangSubtask resolve(JangnyangSubtaskDefinition def, String key) {
        JangnyangSubtask byId = def.byId(key);
        return byId != null ? byId : def.byAnswerField(key);
    }

    /**
     * 원시 값 하나를 서브태스크의 자료형·범위로 검사해 구조화 값으로 바꾼다.
     * 실패하면 {@code retryQuestion}이 붙은 거부 답변을 돌려준다.
     */
    public JangnyangSubtaskAnswer coerce(JangnyangSubtask st, Object rawValue) {
        String raw = rawValue == null ? null : String.valueOf(rawValue);
        if (rawValue == null || (rawValue instanceof String s && s.isBlank())) {
            return reject(st, raw, ErrorCode.MISSING_FIELD, "값이 비어 있다.");
        }
        if (isNotApplicable(rawValue)) {
            if (!st.allowsNotApplicable()) {
                // 목적·수거 시각처럼 없으면 실험이 성립하지 않는 항목이다. 여기서 받아
                // 주면 조립 단계가 빈 값을 들고 진행한다.
                return reject(st, raw, ErrorCode.MISSING_FIELD,
                        "이 항목은 '해당 없음'으로 넘어갈 수 없다. 값이 없으면 실험이 성립하지 않는다.");
            }
            return JangnyangSubtaskAnswer.accepted(st.id(), raw, NOT_APPLICABLE,
                    SubtaskAnswerSource.USER_DIRECT);
        }
        return switch (st.answerType()) {
            case STRING -> coerceString(st, raw);
            case INTEGER -> coerceInteger(st, rawValue, raw);
            case NUMBER -> coerceNumber(st, rawValue, raw);
            case BOOLEAN -> coerceBoolean(st, rawValue, raw);
            case TIME -> coerceTime(st, raw);
            case ENUM -> coerceEnum(st, raw);
            case ENUM_LIST -> coerceStringList(st, rawValue, raw);
            case STRING_LIST -> coerceStringList(st, rawValue, raw);
            case TIME_LIST -> coerceTimeList(st, rawValue, raw);
            case INTEGER_MAP -> coerceMap(st, rawValue, raw, true);
            case NUMBER_MAP -> coerceMap(st, rawValue, raw, false);
        };
    }

    // ── 자료형별 강제 ──────────────────────────────────────────────────────

    private JangnyangSubtaskAnswer coerceString(JangnyangSubtask st, String raw) {
        String v = raw.trim();
        AllowedRange r = st.allowedRange();
        if (r.minLength() != null && v.length() < r.minLength()) {
            return reject(st, raw, ErrorCode.OUT_OF_RANGE,
                    "최소 " + r.minLength() + "자 이상이어야 한다(받은 길이: " + v.length() + ").");
        }
        if (r.maxLength() != null && v.length() > r.maxLength()) {
            return reject(st, raw, ErrorCode.OUT_OF_RANGE,
                    "최대 " + r.maxLength() + "자 이하여야 한다(받은 길이: " + v.length() + ").");
        }
        return JangnyangSubtaskAnswer.accepted(st.id(), raw, v, SubtaskAnswerSource.USER_DIRECT);
    }

    private JangnyangSubtaskAnswer coerceInteger(JangnyangSubtask st, Object rawValue, String raw) {
        Long parsed = asLong(rawValue);
        if (parsed == null) {
            // 소수를 조용히 절삭하면(10.9 → 10) 사용자가 보낸 값과 계산에 쓰인 값이
            // 달라진다(E-03과 같은 이유, UT-305).
            return reject(st, raw, ErrorCode.INVALID_ARGUMENTS,
                    "정수여야 한다(받은 값: " + raw + "). 소수는 반올림·절삭하지 않는다.");
        }
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            return reject(st, raw, ErrorCode.OUT_OF_RANGE, "정수 범위를 벗어났다(받은 값: " + raw + ").");
        }
        int v = parsed.intValue();
        AllowedRange r = st.allowedRange();
        if (r.min() != null && v < r.min()) return outOfRange(st, raw, r, v);
        if (r.max() != null && v > r.max()) return outOfRange(st, raw, r, v);
        return JangnyangSubtaskAnswer.accepted(st.id(), raw, v, SubtaskAnswerSource.USER_DIRECT);
    }

    private JangnyangSubtaskAnswer coerceNumber(JangnyangSubtask st, Object rawValue, String raw) {
        Double v = asDouble(rawValue);
        if (v == null || !Double.isFinite(v)) {
            return reject(st, raw, ErrorCode.INVALID_ARGUMENTS,
                    "유한한 수여야 한다(받은 값: " + raw + "). NaN·Infinity는 받지 않는다.");
        }
        AllowedRange r = st.allowedRange();
        // threshold를 "80"처럼 퍼센트로 답하는 것은 자연스러운 표현이므로 규칙으로
        // 인정한다 — 다만 "조용히 보정"이 아니라 카탈로그의 허용 범위 설명에 명시된
        // 변환이고(ST-10), 원문은 그대로 남는다.
        if (r.max() != null && r.max() <= 1.0 && v > 1.0 && v <= 100.0) {
            v = v / 100.0;
        }
        if (r.min() != null && v < r.min()) return outOfRange(st, raw, r, v);
        if (r.max() != null && v > r.max()) return outOfRange(st, raw, r, v);
        return JangnyangSubtaskAnswer.accepted(st.id(), raw, v, SubtaskAnswerSource.USER_DIRECT);
    }

    private JangnyangSubtaskAnswer coerceBoolean(JangnyangSubtask st, Object rawValue, String raw) {
        if (rawValue instanceof Boolean b) {
            return JangnyangSubtaskAnswer.accepted(st.id(), raw, b, SubtaskAnswerSource.USER_DIRECT);
        }
        String v = raw.trim().toLowerCase();
        if (v.equals("true") || v.equals("예") || v.equals("네") || v.equals("yes") || v.equals("y") || v.equals("반영")) {
            return JangnyangSubtaskAnswer.accepted(st.id(), raw, Boolean.TRUE, SubtaskAnswerSource.USER_DIRECT);
        }
        if (v.equals("false") || v.equals("아니오") || v.equals("아니요") || v.equals("no") || v.equals("n") || v.equals("미반영")) {
            return JangnyangSubtaskAnswer.accepted(st.id(), raw, Boolean.FALSE, SubtaskAnswerSource.USER_DIRECT);
        }
        // 애매한 답을 임의로 false로 두지 않는다 — 사용자는 교통 레이어가 켜졌다고
        // 믿은 채 꺼진 결과를 읽게 된다.
        return reject(st, raw, ErrorCode.INVALID_ARGUMENTS, "예/아니오로 답해야 한다(받은 값: " + raw + ").");
    }

    private JangnyangSubtaskAnswer coerceTime(JangnyangSubtask st, String raw) {
        String v = raw.trim();
        if (!HHMM.matcher(v).matches()) {
            // 12:99를 13:39로 정상화하지 않는다(W-04).
            return reject(st, raw, ErrorCode.INVALID_ARGUMENTS,
                    "24시간 HH:MM 형식이어야 한다(받은 값: " + raw + "). 00:00~23:59 범위 밖의 값을 보정하지 않는다.");
        }
        int minutes = Integer.parseInt(v.substring(0, 2)) * 60 + Integer.parseInt(v.substring(3, 5));
        return JangnyangSubtaskAnswer.accepted(st.id(), raw, minutes, SubtaskAnswerSource.USER_DIRECT);
    }

    private JangnyangSubtaskAnswer coerceEnum(JangnyangSubtask st, String raw) {
        String v = raw.trim();
        for (String allowed : st.allowedRange().valuesOrEmpty()) {
            if (allowed.equalsIgnoreCase(v)) {
                return JangnyangSubtaskAnswer.accepted(st.id(), raw, allowed, SubtaskAnswerSource.USER_DIRECT);
            }
        }
        return reject(st, raw, ErrorCode.INVALID_ENUM,
                "허용되지 않은 값 '" + v + "'. 허용 값: " + String.join(", ", st.allowedRange().valuesOrEmpty()));
    }

    private JangnyangSubtaskAnswer coerceStringList(JangnyangSubtask st, Object rawValue, String raw) {
        List<String> items = asStringList(rawValue);
        if (items == null) {
            return reject(st, raw, ErrorCode.INVALID_ARGUMENTS, "목록이어야 한다(받은 값: " + raw + ").");
        }
        AllowedRange r = st.allowedRange();
        List<String> allowed = r.valuesOrEmpty();
        List<String> normalized = new ArrayList<>();
        for (String item : items) {
            String t = item.trim();
            if (t.isEmpty()) continue;
            if (!allowed.isEmpty()) {
                String match = allowed.stream().filter(a -> a.equalsIgnoreCase(t)).findFirst().orElse(null);
                if (match == null) {
                    return reject(st, raw, ErrorCode.INVALID_ENUM,
                            "허용되지 않은 값 '" + t + "'. 허용 값: " + String.join(", ", allowed));
                }
                normalized.add(match);
            } else {
                // 허용 목록이 없는 목록형(routeSequence)은 형식 규칙으로 본다.
                if ("routeSequence".equals(st.answerField()) && !NODE_ID.matcher(t).matches()) {
                    return reject(st, raw, ErrorCode.INVALID_ARGUMENTS,
                            "노드 id는 Node_A~Node_Z 형식이어야 한다(받은 값: " + t + ").");
                }
                normalized.add(t);
            }
        }
        if (normalized.stream().distinct().count() != normalized.size()) {
            return reject(st, raw, ErrorCode.INVALID_ARGUMENTS, "같은 값이 두 번 들어 있다.");
        }
        SubtaskError sizeError = checkSize(st, raw, normalized.size());
        if (sizeError != null) return JangnyangSubtaskAnswer.rejected(st.id(), raw, SubtaskAnswerSource.USER_DIRECT, sizeError);
        return JangnyangSubtaskAnswer.accepted(st.id(), raw, List.copyOf(normalized), SubtaskAnswerSource.USER_DIRECT);
    }

    /** "09:00, 18:00"처럼 시각이 여러 개인 항목. 각 원소를 분으로 바꾸고 중복을 막는다. */
    private JangnyangSubtaskAnswer coerceTimeList(JangnyangSubtask st, Object rawValue, String raw) {
        List<String> items = asStringList(rawValue);
        if (items == null) {
            return reject(st, raw, ErrorCode.INVALID_ARGUMENTS, "시각 목록이어야 한다(받은 값: " + raw + ").");
        }
        List<Integer> minutes = new ArrayList<>();
        for (String item : items) {
            String t = item.trim();
            if (t.isEmpty()) continue;
            if (!HHMM.matcher(t).matches()) {
                return reject(st, raw, ErrorCode.INVALID_ARGUMENTS,
                        "24시간 HH:MM 형식이어야 한다(받은 값: " + t + ").");
            }
            minutes.add(Integer.parseInt(t.substring(0, 2)) * 60 + Integer.parseInt(t.substring(3, 5)));
        }
        if (minutes.stream().distinct().count() != minutes.size()) {
            return reject(st, raw, ErrorCode.INVALID_ARGUMENTS, "같은 시각이 두 번 들어 있다.");
        }
        SubtaskError sizeError = checkSize(st, raw, minutes.size());
        if (sizeError != null) {
            return JangnyangSubtaskAnswer.rejected(st.id(), raw, SubtaskAnswerSource.USER_DIRECT, sizeError);
        }
        // 정렬해 둔다 — 수거 시각의 순서는 의미가 있고, 입력 순서에 좌우되면 안 된다.
        return JangnyangSubtaskAnswer.accepted(st.id(), raw,
                minutes.stream().sorted().toList(), SubtaskAnswerSource.USER_DIRECT);
    }

    /**
     * 키에서 값으로 가는 맵(건물별 인원·직업 구성비·쓰레기 종류 비율).
     *
     * <p>{@code sumTo}가 있으면 합계까지 본다. 비율의 합이 1이 아닌 것은 원소 하나하나로는
     * 잡히지 않고 <b>모아 놓아야</b> 드러나는 오류라, 항목별 검증만으로는 통과해 버린다.
     * 합을 서버가 임의로 정규화하지 않는다 — 그러면 사용자가 준 비율과 계산에 쓰인 비율이
     * 달라진다(D-26).
     */
    private JangnyangSubtaskAnswer coerceMap(JangnyangSubtask st, Object rawValue, String raw,
                                             boolean integral) {
        Map<String, String> entries = asStringMap(rawValue);
        if (entries == null || entries.isEmpty()) {
            return reject(st, raw, ErrorCode.INVALID_ARGUMENTS,
                    "항목=값 형식의 목록이어야 한다(받은 값: " + raw + ").");
        }
        AllowedRange r = st.allowedRange();
        List<String> allowed = r.valuesOrEmpty();
        Map<String, Object> out = new LinkedHashMap<>();
        double sum = 0;
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey().trim();
            if (!allowed.isEmpty()) {
                final String probe = key;
                String match = allowed.stream().filter(a -> a.equalsIgnoreCase(probe)).findFirst().orElse(null);
                if (match == null) {
                    return reject(st, raw, ErrorCode.INVALID_ENUM,
                            "허용되지 않은 항목 '" + key + "'. 허용 값: " + String.join(", ", allowed));
                }
                key = match;
            } else if (st.answerType() == AnswerType.INTEGER_MAP && !NODE_ID.matcher(key).matches()) {
                return reject(st, raw, ErrorCode.INVALID_ARGUMENTS,
                        "노드 id는 Node_A~Node_Z 형식이어야 한다(받은 값: " + key + ").");
            }
            Double v = asDouble(e.getValue());
            if (v == null || !Double.isFinite(v)) {
                return reject(st, raw, ErrorCode.INVALID_ARGUMENTS,
                        key + "의 값이 유한한 수가 아니다(받은 값: " + e.getValue() + ").");
            }
            if (integral && v != Math.rint(v)) {
                return reject(st, raw, ErrorCode.INVALID_ARGUMENTS,
                        key + "의 값은 정수여야 한다(받은 값: " + e.getValue() + ").");
            }
            if ((r.min() != null && v < r.min()) || (r.max() != null && v > r.max())) {
                return reject(st, raw, ErrorCode.OUT_OF_RANGE,
                        key + "의 값이 허용 범위 " + r.description() + "를 벗어났다(받은 값: " + v + ").");
            }
            sum += v;
            out.put(key, integral ? (Object) Integer.valueOf((int) (double) v) : (Object) v);
        }
        SubtaskError sizeError = checkSize(st, raw, out.size());
        if (sizeError != null) {
            return JangnyangSubtaskAnswer.rejected(st.id(), raw, SubtaskAnswerSource.USER_DIRECT, sizeError);
        }
        if (r.sumTo() != null && Math.abs(sum - r.sumTo()) > 1e-6) {
            return reject(st, raw, ErrorCode.OUT_OF_RANGE,
                    "값의 합이 " + r.sumTo() + "이어야 하는데 " + sum + "이다. 합을 서버가 임의로 맞추지 않는다.");
        }
        return JangnyangSubtaskAnswer.accepted(st.id(), raw,
                java.util.Collections.unmodifiableMap(out), SubtaskAnswerSource.USER_DIRECT);
    }

    /** 항목=값 형태의 문자열이나 JSON 객체를 키에서 값으로 가는 문자열 맵으로. 아니면 null. */
    private static Map<String, String> asStringMap(Object v) {
        Map<String, String> out = new LinkedHashMap<>();
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
            return out;
        }
        if (v instanceof String text) {
            String t = text.trim();
            if (t.startsWith("{")) t = t.substring(1);
            if (t.endsWith("}")) t = t.substring(0, t.length() - 1);
            if (t.isBlank()) return out;
            for (String pair : t.split("\\s*[,·]\\s*")) {
                String[] kv = pair.split("\\s*[=:]\\s*", 2);
                if (kv.length != 2) return null;
                out.put(unquote(kv[0]), unquote(kv[1]));
            }
            return out;
        }
        return null;
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '\"' && t.charAt(t.length() - 1) == '\"') {
            t = t.substring(1, t.length() - 1);
        }
        return t.trim();
    }

    private SubtaskError checkSize(JangnyangSubtask st, String raw, int size) {
        AllowedRange r = st.allowedRange();
        if (r.minItems() != null && size < r.minItems()) {
            return error(st, ErrorCode.OUT_OF_RANGE,
                    "원소가 최소 " + r.minItems() + "개 필요하다(받은 개수: " + size + ").");
        }
        if (r.maxItems() != null && size > r.maxItems()) {
            return error(st, ErrorCode.OUT_OF_RANGE,
                    "원소가 최대 " + r.maxItems() + "개까지다(받은 개수: " + size + ").");
        }
        return null;
    }

    // ── 파싱 도우미 ────────────────────────────────────────────────────────

    private static Long asLong(Object v) {
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Long l) return l;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Double.isFinite(d) ? (long) d : null;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    /** 목록 또는 "a, b, c" 문자열을 원소 목록으로. 목록으로 볼 수 없으면 null. */
    private static List<String> asStringList(Object v) {
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) out.add(String.valueOf(o));
            return out;
        }
        if (v instanceof String s) {
            String t = s.trim().replaceAll("^\\[|]$", "");
            if (t.isEmpty()) return List.of();
            return List.of(t.split("\\s*[,·]\\s*"));
        }
        return null;
    }

    // ── 거부 생성 ──────────────────────────────────────────────────────────

    private JangnyangSubtaskAnswer outOfRange(JangnyangSubtask st, String raw, AllowedRange r, Number v) {
        return reject(st, raw, ErrorCode.OUT_OF_RANGE,
                "허용 범위 " + r.description() + "를 벗어났다(받은 값: " + v + "). 값을 잘라 맞추지 않는다.");
    }

    private JangnyangSubtaskAnswer reject(JangnyangSubtask st, String raw, ErrorCode code, String reason) {
        return JangnyangSubtaskAnswer.rejected(st.id(), raw, SubtaskAnswerSource.USER_DIRECT,
                error(st, code, reason));
    }

    private SubtaskError error(JangnyangSubtask st, ErrorCode code, String reason) {
        // 재질문 문장은 여기서 짓지 않고 카탈로그의 것을 그대로 붙인다(D-47).
        return new SubtaskError(st.id(), code, reason, st.retryQuestion());
    }
}
