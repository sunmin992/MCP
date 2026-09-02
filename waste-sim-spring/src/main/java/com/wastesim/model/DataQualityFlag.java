package com.wastesim.model;

/**
 * 이 결과가 <b>측정되지 않은 값에 기대고 있다</b>는 표시. 결과와 함께 나간다.
 *
 * <p>{@link CoordinateQuality}가 "좌표가 얼마나 실제인가"를 말한다면, 이쪽은 "계산 중에 어떤
 * 가정을 얹었는가"를 말한다. 둘을 나눠 둔 이유는 축이 다르기 때문이다 — 현장 GPS 좌표로
 * 계산하면서도 정차시간은 가정일 수 있다.
 *
 * <p>표시를 남기는 목적은 하나다. 숫자만 남고 그 숫자가 무엇에 기대고 있었는지가 사라지는
 * 것을 막는 것. 이 프로젝트에서 한 번 겪은 일이다 — 지어낸 좌표로 낸 "구간별 3.8배 편차"가
 * 장량동의 성질처럼 인용됐다.
 */
public enum DataQualityFlag {

    /**
     * 같은 교통 구역 안의 이동시간을 <b>가정한 값</b>으로 계산했다.
     *
     * <p>구역 간 행렬에는 대각 성분이 없고 있을 수도 없다(구역은 점이 아니라 영역이다).
     * 그래서 한 구역 안에서 지점을 옮기는 이동은 측정할 대상이 아예 없고, 누군가 정해 줘야
     * 한다. 0분을 지정했더라도 그것은 "시간이 들지 않는다"는 <b>가정</b>이므로 똑같이 표시된다.
     */
    INTRA_ZONE_TIME_ASSUMED(
            "같은 구역 안의 이동시간을 지정한 값(%s분)으로 계산했습니다. 측정된 값이 아니며, "
                    + "구역 간 행렬에는 이 값에 해당하는 성분이 없습니다. 0분도 "
                    + "\"이동에 시간이 들지 않는다\"는 가정입니다 — 0·5·10분 민감도를 함께 "
                    + "보고하세요.");

    private final String template;

    DataQualityFlag(String template) {
        this.template = template;
    }

    /** 이 표시의 문구. {@code detail}이 문구 안에 들어간다. */
    public String message(Object detail) {
        return template.contains("%s") ? String.format(template, detail) : template;
    }
}
