package com.wangziyang.mes.assistant.agent;

public final class LlmIntentResult {
    private final String failureReason;
    private final boolean attempted;
    private final boolean valid;
    private final String intent;
    private final String materialCode;
    private final boolean clarificationNeeded;

    private LlmIntentResult(String failureReason, boolean attempted, boolean valid,
                            String intent, String materialCode, boolean clarificationNeeded) {
        this.failureReason = failureReason;
        this.attempted = attempted;
        this.valid = valid;
        this.intent = intent;
        this.materialCode = materialCode;
        this.clarificationNeeded = clarificationNeeded;
    }

    public static LlmIntentResult failure(String reason, boolean attempted) {
        return new LlmIntentResult(reason, attempted, false, null, null, false);
    }

    public static LlmIntentResult success(String intent, String materialCode, boolean clarificationNeeded) {
        return new LlmIntentResult(null, true, true, intent, materialCode, clarificationNeeded);
    }

    public boolean isAttempted() {
        return attempted;
    }

    public boolean isValid() {
        return valid;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getIntent() {
        return intent;
    }

    public String getMaterialCode() {
        return materialCode;
    }

    public boolean isClarificationNeeded() {
        return clarificationNeeded;
    }
}
