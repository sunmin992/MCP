package com.wastesim.ledger.mcp;

import com.wastesim.ledger.ValueSource;

import java.time.Instant;

/**
 * MCP가 돌려준 값. <b>아직 확정값이 아니다.</b>
 *
 * @param purposeField 이 값을 어디에 쓰기로 하고 불렀는가. 호출 시점에 고정된다
 */
public record ToolCandidate(String purposeField, Object value, String unit, String semanticType,
                            String timeWindow, Instant observedAt, ValueSource source) { }
