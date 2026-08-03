package com.wastesim.edge;

import java.util.List;
import java.util.Map;

/**
 * 엣지 도구의 결과(Map)를 채팅에 그대로 띄울 한국어 문장으로 바꾼다.
 *
 * <p><b>이 문장을 LLM에게 쓰게 하지 않는다.</b> 결과 요약은 서버가 계산한 사실이지
 * 생성물이 아니다 — 숫자를 LLM이 다시 옮겨 적게 하면 반올림이 바뀌거나 없는 값이
 * 생겨난다(이 프로젝트가 경고 브리핑을 결정론적으로 만드는 것과 같은 이유).
 *
 * <p>null 처리에 특히 신경 썼다. 엣지 지표는 "값이 없는 것"이 곧 결과인 경우가 많다
 * (스로틀링이 안 걸리면 TTT는 null이고, 그게 정상이다). 그래서 null을 0으로 뭉개지 않고
 * "발생 안 함"처럼 뜻이 드러나는 말로 적는다.
 */
public final class EdgeChatFormatter {

    private EdgeChatFormatter() {}

    /** {@code simulate_edge_throttling} 결과 요약. */
    @SuppressWarnings("unchecked")
    public static String throttling(Map<String, Object> out) {
        Map<String, Object> m = (Map<String, Object>) out.get("metrics");
        StringBuilder sb = new StringBuilder();

        // 부하 패턴은 조건 줄에 함께 적는다 — 같은 보드·냉각이라도 패턴이 다르면 다른
        // 실험이므로, 어느 패턴으로 돌렸는지 보이지 않으면 결과를 잘못 비교하게 된다.
        String loadLabel = aiLoadLabel(out);
        sb.append(String.format("%s · %s · %s%s%n",
                out.get("board"), coolingKo(str(out, "cooling")), modeKo(str(out, "workloadMode")),
                loadLabel == null ? "" : " · " + loadLabel));

        Double ttt = num(m, "tttSec");
        Double soft = num(m, "softLimitEntrySec");
        sb.append("- 스로틀링 진입(TTT): ")
          .append(ttt == null ? "발생 안 함" : fmt(ttt) + "초").append('\n');
        sb.append("- 소프트 제한(80℃) 진입: ")
          .append(soft == null ? "도달 안 함" : fmt(soft) + "초").append('\n');
        sb.append(String.format("- 정상상태 예상 온도: %s℃ (최고 관측 %s℃)%n",
                fmt(num(m, "steadyStateTempC")), fmt(num(m, "peakTempC"))));

        Double ted = num(m, "medianTedSec");
        Integer eps = intVal(m, "episodeCount");
        if (eps != null && eps > 0) {
            sb.append(String.format("- 스로틀링 에피소드 %d회, TED 중앙값 %s%n",
                    eps, ted == null ? "측정 구간 내 미종료" : fmt(ted) + "초"));
        }
        sb.append(String.format("- 부하 중 평균 FPS %s (최대 대비 %s%% 하락)%n",
                fmt(num(m, "meanFpsDuringLoad")), fmt(num(m, "fpsDropPercent"))));

        String policy = str(out, "recoveryPolicy");
        if (policy != null && !"none".equals(policy)) {
            sb.append(String.format("- 회복(%s): 비트 해제 %s / 서비스 복원 %s / 완전 냉각 %s%n",
                    policyKo(policy), sec(num(m, "trtStateSec")), sec(num(m, "trtServiceSec")),
                    sec(num(m, "trtFullSec"))));
        }
        sb.append(String.format("- 가열 시정수 τ = %s초%n", fmt(num(m, "tauHeatingSec"))));

        appendNotes(sb, (List<String>) out.get("notes"));
        return sb.toString().trim();
    }

    /**
     * 보드 비교 — 같은 조건에서 돌린 두 실행 결과를 지표별로 나란히 놓는다.
     *
     * <p>공통 조건을 맨 위에 <b>한 번만</b> 적는 것이 이 포맷의 핵심이다. 각 보드 블록마다
     * 조건을 반복하면 읽는 사람이 "무엇이 통제됐고 무엇이 바뀌었는지"를 매번 대조해야 하는데,
     * 비교 실험에서 알고 싶은 건 정확히 그 하나(보드)만 달랐다는 사실이기 때문이다.
     *
     * <p>맨 아래 해석 한 줄도 서버가 계산한다 — 두 숫자를 나란히 보여주고 "어느 쪽이 더
     * 뜨거운지"는 사용자가 읽어내라고 두면, 정작 이 도구가 답해야 할 질문("어떻게 다른가")이
     * 답해지지 않은 채 끝난다.
     */
    @SuppressWarnings("unchecked")
    public static String boardComparison(List<Map<String, Object>> runs) {
        if (runs == null || runs.size() < 2) return "비교할 결과가 부족합니다.";
        Map<String, Object> a = runs.get(0), b = runs.get(1);
        return comparison(runs,
                String.format("보드 비교 — %s vs %s", a.get("board"), b.get("board")),
                String.format("공통 조건: %s · %s · 부하 %s초",
                        coolingKo(str(a, "cooling")), modeKo(str(a, "workloadMode")),
                        fmt(num(a, "loadSeconds"))),
                String.valueOf(a.get("board")), String.valueOf(b.get("board")));
    }

    /**
     * {@code simulate_edge_throttling}을 재질만 바꿔 두 번 돌린 결과 비교.
     * 질량이 같아도 비열이 달라 열용량이 달라지므로(알루미늄 900 / 구리 385 J/kg·K)
     * 부하가 출렁일 때 피크 온도가 갈린다 — 그 차이가 이 표의 요점이다.
     */
    @SuppressWarnings("unchecked")
    public static String materialComparison(List<Map<String, Object>> runs) {
        if (runs == null || runs.size() < 2) return "비교할 결과가 부족합니다.";
        Map<String, Object> a = runs.get(0);
        return comparison(runs, "방열판 재질 비교 — 알루미늄 vs 구리",
                String.format("공통 조건: %s · %s · %s · 부하 %s초",
                        a.get("board"), coolingKo(str(a, "cooling")), modeKo(str(a, "workloadMode")),
                        fmt(num(a, "loadSeconds"))),
                "알루미늄", "구리");
    }

    /**
     * 팬을 끈 상태와 정격으로 돌린 결과 비교.
     *
     * <p>팬은 열저항을 낮추는 대신 전력을 쓰므로, 온도만 보면 언제나 켜는 것이 이득이다.
     * 그래서 <b>얼마를 더 써서 몇 도를 벌었는지</b>를 함께 보여야 판단할 수 있다.
     */
    @SuppressWarnings("unchecked")
    public static String fanComparison(List<Map<String, Object>> runs) {
        if (runs == null || runs.size() < 2) return "비교할 결과가 부족합니다.";
        Map<String, Object> a = runs.get(0);
        return comparison(runs, "냉각팬 유무 비교 — 팬 없음 vs 팬 가동",
                String.format("공통 조건: %s · %s · 주변 %s℃ · 부하 %s초",
                        a.get("board"), modeKo(str(a, "workloadMode")),
                        fmt(num(a, "ambientTempC")), fmt(num(a, "loadSeconds"))),
                "팬 없음", "팬 가동");
    }

    /** 같은 조건에서 한 요인만 바꿔 두 번 돌린 결과를 나란히 놓는 공통 표. */
    @SuppressWarnings("unchecked")
    private static String comparison(List<Map<String, Object>> runs, String title, String header,
                                     String nameA, String nameB) {
        Map<String, Object> a = runs.get(0), b = runs.get(1);
        Map<String, Object> ma = (Map<String, Object>) a.get("metrics");
        Map<String, Object> mb = (Map<String, Object>) b.get("metrics");

        StringBuilder sb = new StringBuilder();
        sb.append(title).append(System.lineSeparator());
        sb.append(header).append(System.lineSeparator()).append(System.lineSeparator());

        row(sb, "스로틀링 진입(TTT)", secOrNone(num(ma, "tttSec")), secOrNone(num(mb, "tttSec")));
        row(sb, "소프트 제한(80℃) 진입", secOrNone(num(ma, "softLimitEntrySec")), secOrNone(num(mb, "softLimitEntrySec")));
        row(sb, "정상상태 예상 온도", fmt(num(ma, "steadyStateTempC")) + "℃", fmt(num(mb, "steadyStateTempC")) + "℃");
        row(sb, "최고 관측 온도", fmt(num(ma, "peakTempC")) + "℃", fmt(num(mb, "peakTempC")) + "℃");
        row(sb, "부하 중 평균 FPS", fmt(num(ma, "meanFpsDuringLoad")), fmt(num(mb, "meanFpsDuringLoad")));
        row(sb, "FPS 하락", fmt(num(ma, "fpsDropPercent")) + "%", fmt(num(mb, "fpsDropPercent")) + "%");
        row(sb, "가열 시정수 τ", fmt(num(ma, "tauHeatingSec")) + "초", fmt(num(mb, "tauHeatingSec")) + "초");

        // 어느 한쪽이라도 팬을 썼으면 에너지 분해를 함께 보여준다 — 냉각을 산 대가가
        // 얼마인지가 이 표의 요점이 되고, 온도만 보면 언제나 팬이 이기기 때문이다.
        Double fanA = num(ma, "fanEnergyJ"), fanB = num(mb, "fanEnergyJ");
        if (fanA != null || fanB != null) {
            row(sb, "SoC 소비 에너지", fmt(num(ma, "energyJ")) + "J", fmt(num(mb, "energyJ")) + "J");
            row(sb, "팬 소비 에너지", fmt(fanA == null ? 0.0 : fanA) + "J", fmt(fanB == null ? 0.0 : fanB) + "J");
            row(sb, "총 소비 에너지",
                    fmt(totalEnergy(ma)) + "J", fmt(totalEnergy(mb)) + "J");
        }

        sb.append('\n').append(comparisonVerdict(nameA, nameB, ma, mb)).append('\n');
        String cost = coolingCostLine(nameA, nameB, ma, mb);
        if (cost != null) sb.append(cost).append('\n');

        // 두 실행의 notes를 합치되 중복은 제거한다 — 같은 조건에서 돌렸으므로
        // "이 조건에서는 스로틀링이 안 걸린다" 같은 문장이 양쪽에 똑같이 붙는 일이 흔하다.
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        for (Map<String, Object> run : runs) {
            List<String> notes = (List<String>) run.get("notes");
            if (notes != null) merged.addAll(notes);
        }
        appendNotes(sb, new java.util.ArrayList<>(merged));
        return sb.toString().trim();
    }

    /**
     * 앞 단어의 받침에 따라 조사를 고른다 — "팬 없음<b>가</b>"처럼 어색한 문장을 막는다.
     *
     * <p>비교 대상 이름이 보드 라벨("Raspberry Pi 5")만 있을 때는 드러나지 않다가,
     * 재질·팬 비교에서 한글 라벨("팬 없음", "구리")이 들어오면서 문제가 됐다.
     *
     * <p>숫자로 끝나면 읽는 소리의 받침을 따른다 — "Pi 5"는 "오"라서 받침이 없고,
     * "Pi 4"는 "사"라서 역시 없다. 한글도 숫자도 아니면 받침 없음으로 본다.
     */
    static String particle(String word, String afterBatchim, String afterVowel) {
        if (word == null || word.isBlank()) return afterVowel;
        char last = word.charAt(word.length() - 1);
        if (last >= 0xAC00 && last <= 0xD7A3) {
            return (last - 0xAC00) % 28 != 0 ? afterBatchim : afterVowel;
        }
        if (last >= '0' && last <= '9') {
            // 영(ㅇ)·일(ㄹ)·삼(ㅁ)·육(ㄱ)·칠(ㄹ)·팔(ㄹ)은 받침이 있고 이·사·오·구는 없다.
            return "013678".indexOf(last) >= 0 ? afterBatchim : afterVowel;
        }
        return afterVowel;
    }

    /** SoC + 팬. 팬 에너지가 없으면 SoC 에너지가 곧 총합이다. */
    private static double totalEnergy(Map<String, Object> m) {
        Double total = num(m, "totalEnergyJ");
        if (total != null) return total;
        Double soc = num(m, "energyJ");
        return soc == null ? 0.0 : soc;
    }

    /**
     * 냉각을 산 대가를 "1℃당 몇 J"로 환산해 한 줄 덧붙인다 — 온도 이득만 보면 팬은
     * 언제나 이기므로, 비용을 같은 문장에 놓아야 적정 수준을 판단할 수 있다.
     * 팬을 안 쓴 비교에서는 붙이지 않는다(null).
     */
    private static String coolingCostLine(String nameA, String nameB,
                                          Map<String, Object> ma, Map<String, Object> mb) {
        Double fanA = num(ma, "fanEnergyJ"), fanB = num(mb, "fanEnergyJ");
        if (fanA == null && fanB == null) return null;
        Double pA = num(ma, "peakTempC"), pB = num(mb, "peakTempC");
        if (pA == null || pB == null) return null;

        double dTemp = pA - pB;                                   // A 대비 B가 낮춘 온도
        double dEnergy = totalEnergy(mb) - totalEnergy(ma);       // 그러려고 더 쓴 에너지
        if (Math.abs(dTemp) < TEMP_TIE_C) {
            return String.format("→ 다만 %s%s 온도를 거의 낮추지 못하면서 에너지만 %sJ 더 쓴다 — 이 조건에서는 켤 이유가 없다.",
                    nameB, particle(nameB, "은", "는"), fmt(Math.abs(dEnergy)));
        }
        String cooler = dTemp > 0 ? nameB : nameA;
        return String.format("→ 비용: %s%s %s℃ 낮추는 데 에너지를 %sJ 더 쓴다(1℃당 %sJ). "
                + "팬 전력은 회전수의 3승이라 회전수를 낮추면 이 비용이 급격히 줄어든다 — 요구 온도만 맞추는 지점을 찾는 것이 이 실험의 목적이다.",
                cooler, particle(cooler, "이", "가"), fmt(Math.abs(dTemp)), fmt(Math.abs(dEnergy)),
                fmt(Math.abs(dEnergy) / Math.abs(dTemp)));
    }

    /** 표시 자리수(0.1℃) 아래 차이는 같은 값으로 본다 — "0℃ 더 뜨겁다"는 문장을 막는다. */
    private static final double TEMP_TIE_C = 0.05;

    /**
     * 두 실행이 실제로 어떻게 갈렸는지 한 줄로 결론 낸다.
     *
     * <p>기준 지표를 고정하지 않는 이유: 열저항이 다른 비교(보드)는 정상상태 온도로
     * 갈리지만, <b>열저항이 같은 비교(재질·질량만 다름)는 정상상태가 아예 동일하다</b> —
     * 정상상태 식 {@code T_주변 + P·R_ja}에 열용량이 나타나지 않기 때문이다. 그 경우
     * 갈리는 것은 피크 온도뿐이므로 판정 기준을 피크로 옮긴다. 실측 회귀: 재질 비교에서
     * "구리가 0℃ 더 뜨겁게 안정된다(43.2℃ vs 43.2℃)"는 무의미한 문장이 나왔다.
     */
    private static String comparisonVerdict(String nameA, String nameB,
                                            Map<String, Object> ma, Map<String, Object> mb) {
        Double tA = num(ma, "tttSec"), tB = num(mb, "tttSec");
        Double sA = num(ma, "steadyStateTempC"), sB = num(mb, "steadyStateTempC");
        Double pA = num(ma, "peakTempC"), pB = num(mb, "peakTempC");

        // 정상상태 온도는 항상 계산되지만, 없을 때 산술로 NPE를 내느니 비교를 포기한다 —
        // 표는 이미 위에 찍혔으므로 해석 한 줄이 빠지는 게 예외보다 낫다.
        if (sA == null || sB == null) return "→ 두 실행의 정상상태 온도를 비교할 수 없습니다.";
        boolean steadyTied = Math.abs(sA - sB) < TEMP_TIE_C;

        if (tA != null && tB != null) {
            String faster = tA < tB ? nameA : nameB;
            String tail = steadyTied
                    ? String.format("정상상태 온도는 양쪽 %s℃로 같다.", fmt(sA))
                    : String.format("정상상태 온도는 %s℃ 차이.", fmt(Math.abs(sA - sB)));
            return String.format("→ %s%s %s초 먼저 스로틀링에 걸린다(%s초 vs %s초). %s",
                    faster, particle(faster, "이", "가"), fmt(Math.abs(tA - tB)), fmt(tA), fmt(tB), tail);
        }
        if (tA == null && tB == null) {
            if (steadyTied) {
                // 열저항이 같은 비교 — 피크 온도가 유일한 차이다.
                if (pA == null || pB == null || Math.abs(pA - pB) < TEMP_TIE_C) {
                    return String.format("→ 양쪽 다 스로틀링에 걸리지 않고 정상상태(%s℃)도 피크도 사실상 같다 — "
                            + "열용량 차이를 드러내려면 부하 패턴(burst·mixed)을 넣거나 주변 온도를 올려야 한다.",
                            fmt(sA));
                }
                String hotterPeak = pA > pB ? nameA : nameB;
                String coolerPeak = pA > pB ? nameB : nameA;
                return String.format("→ 정상상태 온도는 양쪽 %s℃로 같다(열저항이 같으므로 당연하다 — 정상상태 식에는 열용량이 없다). "
                        + "갈리는 것은 피크 온도로 %s%s %s℃ 더 높다(%s℃ vs %s℃): 부하가 출렁일 때 열용량이 큰 %s 쪽이 피크를 더 눌러 준다.",
                        fmt(sA), hotterPeak, particle(hotterPeak, "이", "가"),
                        fmt(Math.abs(pA - pB)), fmt(pA), fmt(pB), coolerPeak);
            }
            String hotter = sA > sB ? nameA : nameB;
            String peakTail = (pA == null || pB == null || Math.abs(pA - pB) < TEMP_TIE_C) ? ""
                    : String.format(" 피크 온도 차이는 %s℃다.", fmt(Math.abs(pA - pB)));
            return String.format("→ 이 조건에서는 양쪽 다 스로틀링에 걸리지 않는다. 다만 %s%s %s℃ 더 뜨겁게 안정된다(%s℃ vs %s℃) — "
                    + "차이를 더 벌리려면 무냉각·최대 처리량으로 바꾸거나 주변 온도를 올려야 한다.%s",
                    hotter, particle(hotter, "이", "가"), fmt(Math.abs(sA - sB)), fmt(sA), fmt(sB), peakTail);
        }
        String throttled = tA != null ? nameA : nameB;
        String safe = tA != null ? nameB : nameA;
        return String.format("→ 같은 조건인데 %s만 스로틀링에 걸리고 %s%s 걸리지 않는다 — 이 조건이 둘의 경계선이다.",
                throttled, safe, particle(safe, "은", "는"));
    }

    /** 지표 한 줄: 라벨과 두 값. */
    private static void row(StringBuilder sb, String label, String left, String right) {
        sb.append(String.format("· %s: %s / %s%n", label, left, right));
    }

    private static String secOrNone(Double v) { return v == null ? "발생 안 함" : fmt(v) + "초"; }

    /** {@code simulate_heatsink_layout} 결과 요약 — 순위표. */
    @SuppressWarnings("unchecked")
    public static String heatsink(Map<String, Object> out) {
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        StringBuilder sb = new StringBuilder();
        // 부하 패턴에 따라 순위 자체가 뒤집힐 수 있으므로, 어느 패턴으로 비교한 표인지
        // 제목에 반드시 드러낸다 — 안 그러면 다른 조건의 순위표를 나란히 놓고 비교하게 된다.
        String loadLabel = aiLoadLabel(out);
        sb.append(String.format("%s · 주변 %s℃ · 방열판 배치 비교%s%n",
                out.get("board"), fmt(num(out, "ambientTempC")),
                loadLabel == null ? "" : " · " + loadLabel));

        for (Map<String, Object> row : ranking) {
            Double ttt = num(row, "tttSec");
            sb.append(String.format("%d. %s — 정상상태 %s℃, R_ja %s K/W, %s%n",
                    intVal(row, "rank"), row.get("name"),
                    fmt(num(row, "steadyStateTempC")), fmt(num(row, "rJaKPerW")),
                    ttt == null ? "스로틀링 없음" : "TTT " + fmt(ttt) + "초"));
        }

        sb.append('\n').append(out.get("interpretation")).append('\n');

        Map<String, Object> best = ranking.get(0);
        String hint = (String) best.get("improvementHint");
        if (hint != null) {
            sb.append("\n1위 후보를 더 개선하려면 — ").append(hint).append('\n');
        }
        List<String> warns = (List<String>) best.get("warnings");
        if (warns != null && !warns.isEmpty()) {
            sb.append("\n[1위 후보 주의]\n");
            for (String w : warns) sb.append("· ").append(w).append('\n');
        }
        // 패턴이 순위를 실제로 바꿀 수 있는 시간 규모인지 함께 알려준다 — 느린 패턴을
        // 넣고 "패턴을 줬는데 왜 순위가 그대로지?"라고 오해하는 것을 막는다.
        Object loadNote = out.get("aiLoadNote");
        if (loadNote != null) sb.append("\n※ ").append(loadNote).append('\n');
        sb.append('\n').append(HeatsinkPresets.NOTICE);
        return sb.toString().trim();
    }

    /**
     * 캘리브레이션은 실측 시계열(수백~수천 점)이 있어야 한다 — 채팅 메시지로 옮길 수 있는
     * 데이터가 아니다. 그래서 채팅에서는 실행하지 않고 <b>어떻게 보내는지</b>를 안내하고,
     * 이미 저장된 프로파일이 있으면 그 목록을 보여준다.
     */
    public static String calibrationGuide(List<EdgeThermalProfileStore.Profile> profiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("실측 캘리브레이션은 측정 시계열(CSV)이 필요해서 채팅 메시지로는 실행할 수 없습니다.\n\n")
          .append("측정 후 이렇게 보내세요:\n")
          .append("· PowerShell — Import-McpCalibration -CsvPath .\\runs\\<파일>.csv -Board pi5 -AmbientC 26.5\n")
          .append("· Python     — python3 scripts/edge/csv_to_mcp_payload.py runs/<파일>.csv --post\n");

        if (profiles.isEmpty()) {
            sb.append("\n아직 저장된 캘리브레이션 프로파일이 없습니다.");
        } else {
            sb.append("\n저장된 프로파일:\n");
            for (EdgeThermalProfileStore.Profile p : profiles) {
                sb.append(String.format("· %s — %s (%s, R_ja %s K/W, C_th %s J/K)%n",
                        p.profileId(), p.label(), p.board().label(),
                        fmt(p.override().rJaKPerW()), fmt(p.override().cThJPerK())));
            }
            sb.append("\n이 id를 넣어 \"cal-001 프로파일로 주변 35도일 때 시뮬레이션 해줘\"처럼 이어서 물어보실 수 있습니다.");
        }
        return sb.toString().trim();
    }

    private static void appendNotes(StringBuilder sb, List<String> notes) {
        if (notes == null || notes.isEmpty()) return;
        sb.append('\n');
        for (String n : notes) sb.append("※ ").append(n).append('\n');
    }

    private static String sec(Double v) { return v == null ? "해당 없음" : fmt(v) + "초"; }

    /** 실행에 쓰인 AI 부하 패턴 라벨. 패턴을 안 썼으면 null(조건 줄에 아무것도 붙이지 않는다). */
    @SuppressWarnings("unchecked")
    private static String aiLoadLabel(Map<String, Object> out) {
        Object lp = out.get("aiLoadProfile");
        if (lp instanceof Map<?, ?> m) {
            Object label = ((Map<String, Object>) m).get("label");
            if (label != null) return label.toString();
        }
        Object flat = out.get("aiLoadProfileLabel");
        return flat == null ? null : flat.toString();
    }

    private static String coolingKo(String c) {
        if (c == null) return "-";
        return switch (c) {
            case "bare" -> "무냉각";
            case "passive" -> "방열판";
            case "active" -> "팬 냉각";
            default -> c;
        };
    }

    private static String modeKo(String m) {
        if (m == null) return "-";
        return "max_throughput".equals(m) ? "최대 처리량" : "목표 FPS 유지";
    }

    private static String policyKo(String p) {
        if (p == null) return "-";
        return switch (p) {
            case "r1_stop" -> "R1 완전 중지";
            case "r2_low_load" -> "R2 저부하 유지";
            case "r3_active_cooling" -> "R3 능동 냉각";
            default -> p;
        };
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v == null ? null : v.toString();
    }

    private static Double num(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer intVal(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Number n ? n.intValue() : null;
    }

    /** 소수점 이하가 0이면 정수로 — "150.0초"보다 "150초"가 읽기 쉽다. */
    private static String fmt(Double v) {
        if (v == null) return "-";
        return v == Math.rint(v) ? String.valueOf(v.longValue()) : String.format("%.1f", v);
    }
}
