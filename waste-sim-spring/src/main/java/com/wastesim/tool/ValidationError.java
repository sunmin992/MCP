package com.wastesim.tool;

/** 단일 검증 오류 항목. code=오류 종류, field=대상 필드, message=사람용 설명. */
public record ValidationError(ErrorCode code, String field, String message) {}
