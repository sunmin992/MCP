package com.wastesim.ledger.mcp;

import java.time.Duration;

/**
 * 이 매개변수가 외부 값에 대해 기대하는 것.
 *
 * <p>호출 <b>전에</b> 정해 둔다. 결과가 온 뒤에 쓸 곳을 정하면 "평균 통행시간"과
 * "혼잡 시간대 통행시간"이 같은 필드에 들어간다 — 단위가 같아서 검사를 통과한다.
 *
 * @param timeWindow {@code daily_average} · {@code peak_hour} 등. 단위와 별개의 사실이다
 * @param maxAge     이보다 오래된 관측은 받지 않는다
 */
public record ParameterExpectation(String parameterId, String semanticType, String unit,
                                   String timeWindow, Duration maxAge) { }
