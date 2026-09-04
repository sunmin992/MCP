package com.wastesim.llm;

/** 추출에 실패했다. 조용히 기본값으로 채우지 않고 문항 흐름으로 넘기기 위한 신호다. */
public class InterpreterException extends Exception {
    public InterpreterException(String message) { super(message); }
    public InterpreterException(String message, Throwable cause) { super(message, cause); }
}
