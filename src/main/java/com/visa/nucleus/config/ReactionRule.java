package com.visa.nucleus.config;

/**
 * Configuration for a single reaction rule in agent-orchestrator.yaml.
 * Example rules: ci-failed, changes-requested, approved-and-green.
 */
public class ReactionRule {

    private boolean auto = false;
    private String action;
    private int retries = 0;
    /** Escalation timeout expressed as a string (e.g. "30m", "1h"). */
    private String escalateAfter;

    public boolean isAuto() {
        return auto;
    }

    public void setAuto(boolean auto) {
        this.auto = auto;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public String getEscalateAfter() {
        return escalateAfter;
    }

    public void setEscalateAfter(String escalateAfter) {
        this.escalateAfter = escalateAfter;
    }
}
