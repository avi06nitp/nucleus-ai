package com.visa.nucleus.plugins.tracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GitHubIssuesTrackerPluginTest {

    private GitHubIssuesTrackerPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GitHubIssuesTrackerPlugin("myorg", "myrepo", "dummy-token");
    }

    // -------------------------------------------------------------------------
    // parseIssueContext
    // -------------------------------------------------------------------------

    @Test
    void parseIssueContext_extractsTitleAndBody() {
        String json = "{"
                + "\"number\":123,"
                + "\"title\":\"Fix login bug\","
                + "\"body\":\"Steps to reproduce the issue.\","
                + "\"labels\":[]"
                + "}";

        String result = plugin.parseIssueContext(json, "123");

        assertTrue(result.contains("Issue #123"), "should contain issue number");
        assertTrue(result.contains("Fix login bug"), "should contain title");
        assertTrue(result.contains("Steps to reproduce the issue."), "should contain body");
    }

    @Test
    void parseIssueContext_extractsLabels() {
        String json = "{"
                + "\"number\":42,"
                + "\"title\":\"Add feature\","
                + "\"body\":\"Description here.\","
                + "\"labels\":["
                + "{\"id\":1,\"name\":\"enhancement\",\"color\":\"84b6eb\"},"
                + "{\"id\":2,\"name\":\"backend\",\"color\":\"0075ca\"}"
                + "]"
                + "}";

        String result = plugin.parseIssueContext(json, "42");

        assertTrue(result.contains("enhancement"), "should contain first label");
        assertTrue(result.contains("backend"), "should contain second label");
        assertTrue(result.contains("Labels:"), "should include Labels line");
    }

    @Test
    void parseIssueContext_noLabels_omitsLabelsLine() {
        String json = "{"
                + "\"number\":7,"
                + "\"title\":\"Simple fix\","
                + "\"body\":\"Just a small fix.\","
                + "\"labels\":[]"
                + "}";

        String result = plugin.parseIssueContext(json, "7");

        assertFalse(result.contains("Labels:"), "should not include Labels line when empty");
    }

    @Test
    void parseIssueContext_emptyBody_omitsDescriptionLine() {
        String json = "{"
                + "\"number\":10,"
                + "\"title\":\"No body issue\","
                + "\"body\":\"\","
                + "\"labels\":[]"
                + "}";

        String result = plugin.parseIssueContext(json, "10");

        assertTrue(result.contains("Issue #10"), "should contain issue number");
        assertFalse(result.contains("Description:"), "should not include Description line when body is empty");
    }

    @Test
    void parseIssueContext_handlesEscapedCharactersInBody() {
        String json = "{"
                + "\"number\":5,"
                + "\"title\":\"Issue with \\\"quotes\\\"\","
                + "\"body\":\"Line 1\\nLine 2\","
                + "\"labels\":[]"
                + "}";

        String result = plugin.parseIssueContext(json, "5");

        assertTrue(result.contains("Issue #5"), "should contain issue number");
    }

    // -------------------------------------------------------------------------
    // transitionStatus
    // -------------------------------------------------------------------------

    @Test
    void transitionStatus_throwsForUnsupportedStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> plugin.transitionStatus("123", "Unknown Status"));
    }

    @Test
    void transitionStatus_throwsForNullStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> plugin.transitionStatus("123", null));
    }
}
