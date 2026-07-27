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

        sb.append(String.format("%s · %s · %s%n",
                out.get("board"), coolingKo(str(out, "cooling")), modeKo(str(out, "workloadMode"))));

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

    /** {@code simulate_heatsink_layout} 결과 요약 — 순위표. */
    @SuppressWarnings("unchecked")
    public static String heatsink(Map<String, Object> out) {
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) out.get("ranking");
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s · 주변 %s℃ · 방열판 배치 비교%n",
                out.get("board"), fmt(num(out, "ambientTempC"))));

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
