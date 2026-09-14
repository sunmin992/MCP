package com.wastesim.ses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.wastesim.subtask.FieldBasis;

/** 사람이 쓴 부분. 구조가 모르는 것들만 여기 있다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProseEntry(String question, String retryQuestion, String validationRule,
                         String completionCondition, boolean allowsNotApplicable,
                         FieldBasis basis) { }
