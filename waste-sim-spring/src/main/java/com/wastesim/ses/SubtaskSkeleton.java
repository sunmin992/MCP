package com.wastesim.ses;

import com.wastesim.subtask.AllowedRange;
import com.wastesim.subtask.AnswerType;

/**
 * SES가 정해 주는 문항의 뼈대 — 무엇을 어떤 형식으로 묻는가까지.
 * 말투와 근거는 들어 있지 않다. 그것은 구조가 모르는 것이다.
 */
public record SubtaskSkeleton(String pointId, String answerField, AnswerType answerType,
                              AllowedRange allowedRange, boolean required, int group) { }
