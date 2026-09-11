package com.wastesim.ses;

/**
 * 엔티티 사이로 무엇이 흐르는가.
 *
 * @param activeWhen 이 결합이 살아 있는 조건. {@code null}이면 항상 살아 있다. 교통 결합
 *                   둘은 {@code trafficMode=APPLY}일 때만 산다 — 답변 하나가 값이 아니라
 *                   <b>연결 구조</b>를 바꾸는 자리다
 */
public record Coupling(String from, String to, String mechanism, String activeWhen) { }
