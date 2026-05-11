package com.example.llmdyn.model;

public class FailureDetail {
    private final FailureLevel level;
    private final ErrorCode errorCode;
    private final String stage;
    private final String message;
    private final String exceptionType;
    private final String rootCauseType;
    private final String rootCauseMessage;
    private final String matchedSignal;

    public FailureDetail(FailureLevel level, String stage, String message, String exceptionType) {
        this(level, ErrorCode.UNCLASSIFIED, stage, message, exceptionType, null, null, null);
    }

    public FailureDetail(FailureLevel level,
                         ErrorCode errorCode,
                         String stage,
                         String message,
                         String exceptionType,
                         String rootCauseType,
                         String rootCauseMessage,
                         String matchedSignal) {
        this.level = level;
        this.errorCode = errorCode;
        this.stage = stage;
        this.message = message;
        this.exceptionType = exceptionType;
        this.rootCauseType = rootCauseType;
        this.rootCauseMessage = rootCauseMessage;
        this.matchedSignal = matchedSignal;
    }

    public FailureLevel getLevel() {
        return level;
    }

    public String getCategory() {
        return level.getLabel();
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public String getStage() {
        return stage;
    }

    public String getMessage() {
        return message;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public String getRootCauseType() {
        return rootCauseType;
    }

    public String getRootCauseMessage() {
        return rootCauseMessage;
    }

    public String getMatchedSignal() {
        return matchedSignal;
    }
}

