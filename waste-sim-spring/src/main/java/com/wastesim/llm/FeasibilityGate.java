package com.wastesim.llm;

import java.util.List;
import java.util.Locale;

/**
 * 만들 수 없는 요청을 거부한다. <b>LLM이 아니라 코드가 판정한다.</b>
 *
 * <p>LLM은 "부산"이라는 단어를 뽑을 뿐이고 "부산은 지원하지 않는다"는 여기서 정한다.
 * 판정을 LLM에 맡기면 거부 동작을 테스트로 고정할 수 없다.
 *
 * <p>거부는 사유와 <b>부족한 것 목록</b>을 함께 낸다. 통째로 막으면 사용자가 우회로가
 * 있다는 것을 모른다 — 지점 단위 경로는 막히지만 구역 단위로는 지금도 돌아간다.
 */
public final class FeasibilityGate {

    private FeasibilityGate() {}

    /** 이 시스템이 다루는 지역. 구역 정의와 주민 모델이 여기에 묶여 있다. */
    private static final List<String> SUPPORTED_REGION = List.of("장량", "포항");

    /** 모델에 없는 변수를 가리키는 말. 있으면 그 결론은 낼 수 없다. */
    private static final List<String> ABSENT_AXES =
            List.of("가격", "요금", "봉투값", "분리율", "재활용률", "보조금", "과태료");

    /**
     * 지점 단위 결론을 가리키는 말. 수거 지점 좌표가 0곳이라 답할 수 없다.
     *
     * <p>반드시 "지점" 한정어를 포함해야 한다 — "최적 경로"·"최단 경로"만으로는 구역
     * 단위 요청("구역 간 최적 경로")까지 걸려 넘어간다. 구역 단위 경로는 오늘도 계산되고,
     * 그것을 안내하는 것이 바로 이 DATA_UNAVAILABLE 분기의 목적이므로 그 자체를 거부해서는
     * 안 된다.
     */
    private static final List<String> SITE_LEVEL =
            List.of("지점 단위", "지점단위", "지점별", "수거 지점 경로", "지점 경로");

    /** 실행이 아니라 조회를 가리키는 말. */
    private static final List<String> LOOKUP =
            List.of("알려줘", "얼마야", "몇이야", "연락처", "조회");

    /**
     * 시뮬레이션을 요청한다는 표시. 조회 동사와 함께 있으면 조회가 아니다 —
     * "시뮬레이션 결과를 알려줘"는 조회 동사를 쓰지만 실행 요청이다.
     */
    private static final List<String> SIMULATION_INTENT =
            List.of("시뮬레이션", "시뮬레이터", "실험", "비교", "돌려", "돌리", "예측");

    public static FeasibilityVerdict judge(RequestExtraction extraction) {
        String region = lower(extraction.targetRegion());
        // 지역이 비어 있으면 거부하지 않는다 — "시뮬레이터 만들어 줘"처럼 생략한 요청이
        // 정상이다. 침묵을 거부 근거로 쓰지 않는다.
        if (!region.isEmpty() && SUPPORTED_REGION.stream().noneMatch(region::contains)) {
            return new FeasibilityVerdict(false, FeasibilityVerdict.Reason.OUT_OF_REGION,
                    "이 시뮬레이터는 포항시 북구 장량동만 다룹니다.", regionNeeds());
        }

        String conclusion = lower(extraction.requestedConclusion());
        if (containsAny(conclusion, ABSENT_AXES)) {
            return new FeasibilityVerdict(false, FeasibilityVerdict.Reason.AXIS_NOT_IN_MODEL,
                    "이 모델에는 가격·분리율 같은 변수가 없습니다.",
                    List.of(new FeasibilityVerdict.Missing(
                            "요청한 변수를 담은 모델", false,
                            "DEVS 모델은 배출량·수거 일정·차량·교통만 다룹니다. 배출량 변화로 "
                                    + "근사할 수는 있지만 그것은 다른 질문입니다.")));
        }
        if (containsAny(conclusion, SITE_LEVEL)) {
            return new FeasibilityVerdict(false, FeasibilityVerdict.Reason.DATA_UNAVAILABLE,
                    "수거 지점 좌표가 0곳이라 지점 단위 결론은 낼 수 없습니다.",
                    List.of(
                        new FeasibilityVerdict.Missing("수거 지점 좌표", false,
                                "현장 GPS 또는 주소 지오코딩이 필요합니다."),
                        new FeasibilityVerdict.Missing("구역 단위 대안", true,
                                "ZONE_PROXY_HYBRID로 교통 구역 사이는 지금도 계산됩니다. "
                                        + "같은 구역 안의 방문 순서는 결과에 반영되지 않습니다.")));
        }
        // 조회 동사가 있어도 시뮬레이션 의도 표현이 함께 있으면 조회가 아니라 실행 요청이다
        // — "시뮬레이션 결과를 알려줘"를 NOT_A_SIMULATION으로 잘못 거부하지 않기 위함.
        if (containsAny(conclusion, LOOKUP) && !containsAny(conclusion, SIMULATION_INTENT)) {
            return new FeasibilityVerdict(false, FeasibilityVerdict.Reason.NOT_A_SIMULATION,
                    "이것은 실행할 시뮬레이션이 아니라 사실 조회입니다.",
                    List.of(new FeasibilityVerdict.Missing("조회 경로", false,
                            "시뮬레이터는 조건을 바꿔 결과를 비교하는 도구입니다. 값 자체는 "
                                    + "공공데이터나 담당 부서에서 확인해야 합니다.")));
        }
        return FeasibilityVerdict.ok();
    }

    /**
     * 다른 지역에 필요한 것. {@code obtainable: true} 넷이 지역 온보딩 작업의 재료 목록이다.
     */
    private static List<FeasibilityVerdict.Missing> regionNeeds() {
        return List.of(
            new FeasibilityVerdict.Missing("교통 구역 정의", false,
                    "장량동 A~D는 교통량 CSV 링크 매핑과 랜드마크로 정했습니다. 대상 지역에 "
                            + "그 자료가 없으면 사람이 구역을 정해야 합니다."),
            new FeasibilityVerdict.Missing("주민 배출 모델", false,
                    "0.9kg/인·일과 직업별 외출·귀가 시각은 원룸촌 논문 모델입니다. 주거 "
                            + "형태가 다르면 맞지 않습니다."),
            new FeasibilityVerdict.Missing("도로 자유주행시간", true,
                    "OSRM — 구역이 정해지면 자동 수집"),
            new FeasibilityVerdict.Missing("시간대 혼잡", true,
                    "TMAP — 프로파일 1개당 288회 호출"),
            new FeasibilityVerdict.Missing("수거 일정·미수거일", true,
                    "생활쓰레기 배출정보 표준데이터(시군구별, 채움 편차 있음)"),
            new FeasibilityVerdict.Missing("인구·세대수", true, "주민등록 통계"));
    }

    private static boolean containsAny(String text, List<String> needles) {
        return !text.isEmpty() && needles.stream().anyMatch(text::contains);
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
