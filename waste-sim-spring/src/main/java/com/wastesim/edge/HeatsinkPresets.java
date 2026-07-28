package com.wastesim.edge;

/**
 * 채팅에서 "방열판 배치 비교해줘"라고만 해도 바로 실행되도록 준비한 표준 후보 묶음.
 *
 * <p>방열판 배치 비교는 후보마다 치수·핀 개수·기류·TIM을 다 받아야 해서, 자연어로
 * 전부 받기엔 무리가 있다(그리고 LLM이 치수를 지어내면 실험 결과가 오염된다). 그래서
 * 채팅 경로에서는 <b>서버가 들고 있는 고정 후보</b>로 비교한다 — 값이 코드에 박혀 있으니
 * 언제 돌려도 같은 표가 나오고, 학생은 "어느 배치가 유리한가"라는 결론에만 집중할 수 있다.
 *
 * <p>후보는 임의로 고른 게 아니라 실험 설계 문서(RE_엣지_발열실험_설계.md §7.2)의 가설
 * H1~H5와 1:1로 대응한다 — A(기준)를 축으로 한 번에 <b>한 가지 요인만</b> 바꿔서,
 * 결과 표의 온도 차이가 곧 그 요인의 효과가 되게 했다.
 *
 * <ul>
 *   <li>A 기준 — 40×40, 핀 10개, 자연대류, 서멀패드 0.5mm, 중앙 정렬</li>
 *   <li>B ← H1 오프셋(15mm 어긋남)만 다름</li>
 *   <li>C ← H2 핀 방향(기류 가로막음)만 다름</li>
 *   <li>D ← H4 TIM(얇은 그리스)만 다름</li>
 *   <li>E ← H3 기류(팬 8mm)만 다름</li>
 *   <li>F ← H5 크기(소형 20×20)만 다름</li>
 * </ul>
 *
 * <p>치수를 직접 지정하고 싶으면 채팅이 아니라 MCP/REST로 {@code simulate_heatsink_layout}을
 * 직접 호출하면 된다 — 그 경로는 후보를 자유롭게 넣을 수 있다.
 */
public final class HeatsinkPresets {

    private HeatsinkPresets() {}

    /** {@code simulate_heatsink_layout}의 {@code layouts} 인자에 그대로 넣는 JSON 배열. */
    public static final String LAYOUTS_JSON = """
            [
              {"name":"A 중앙 정렬 (기준)",
               "heatsink":{"baseLengthMm":40,"baseWidthMm":40,"baseThicknessMm":3,
                           "finCount":10,"finHeightMm":12,"finThicknessMm":1.2,"material":"aluminum"},
               "placement":{"offsetXMm":0,"offsetYMm":0,"finAlignment":"aligned"},
               "airflow":{"type":"natural"},
               "tim":{"type":"pad","thicknessMm":0.5}},

              {"name":"B 15mm 어긋나게 부착",
               "heatsink":{"baseLengthMm":40,"baseWidthMm":40,"baseThicknessMm":3,
                           "finCount":10,"finHeightMm":12,"finThicknessMm":1.2,"material":"aluminum"},
               "placement":{"offsetXMm":15,"offsetYMm":0,"finAlignment":"aligned"},
               "airflow":{"type":"natural"},
               "tim":{"type":"pad","thicknessMm":0.5}},

              {"name":"C 핀이 기류를 가로막음",
               "heatsink":{"baseLengthMm":40,"baseWidthMm":40,"baseThicknessMm":3,
                           "finCount":10,"finHeightMm":12,"finThicknessMm":1.2,"material":"aluminum"},
               "placement":{"offsetXMm":0,"offsetYMm":0,"finAlignment":"cross"},
               "airflow":{"type":"natural"},
               "tim":{"type":"pad","thicknessMm":0.5}},

              {"name":"D 얇은 서멀 그리스",
               "heatsink":{"baseLengthMm":40,"baseWidthMm":40,"baseThicknessMm":3,
                           "finCount":10,"finHeightMm":12,"finThicknessMm":1.2,"material":"aluminum"},
               "placement":{"offsetXMm":0,"offsetYMm":0,"finAlignment":"aligned"},
               "airflow":{"type":"natural"},
               "tim":{"type":"paste","thicknessMm":0.05}},

              {"name":"E 팬 추가 (8mm 거리)",
               "heatsink":{"baseLengthMm":40,"baseWidthMm":40,"baseThicknessMm":3,
                           "finCount":10,"finHeightMm":12,"finThicknessMm":1.2,"material":"aluminum"},
               "placement":{"offsetXMm":0,"offsetYMm":0,"finAlignment":"aligned"},
               "airflow":{"type":"forced","fanRpm":4000,"fanDistanceMm":8},
               "tim":{"type":"pad","thicknessMm":0.5}},

              {"name":"F 소형 방열판 (20×20)",
               "heatsink":{"baseLengthMm":20,"baseWidthMm":20,"baseThicknessMm":2,
                           "finCount":6,"finHeightMm":8,"finThicknessMm":1.0,"material":"aluminum"},
               "placement":{"offsetXMm":0,"offsetYMm":0,"finAlignment":"aligned"},
               "airflow":{"type":"natural"},
               "tim":{"type":"pad","thicknessMm":0.5}}
            ]
            """;

    /** 결과 아래에 함께 보여줄 안내 — 이 표가 무엇을 비교한 것인지 학생이 오해하지 않게. */
    public static final String NOTICE =
            "※ 채팅에서는 서버에 고정된 표준 후보 6종(A 기준 + 한 번에 한 요인만 바꾼 B~F)으로 비교했습니다. "
          + "직접 잰 치수로 비교하려면 simulate_heatsink_layout 도구를 MCP로 호출하세요.";
}
