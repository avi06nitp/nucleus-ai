package com.visa.nucleus.plugins.tracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JiraTrackerPluginTest {

    private JiraTrackerPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new JiraTrackerPlugin("https://visa.atlassian.net", "service@visa.com", "dummy-token");
    }

    // -------------------------------------------------------------------------
    // parseIssueContext
    // -------------------------------------------------------------------------

    @Test
    void parseIssueContext_extractsSummaryAndLabels() {
        String json = "{"
                + "\"fields\":{"
                + "\"summary\":\"Fix login bug\","
                + "\"description\":null,"
                + "\"labels\":[\"backend\",\"urgent\"]"
                + "}"
                + "}";

        String result = plugin.parseIssueContext(json, "PROJ-42");

        assertTrue(result.contains("PROJ-42"), "should contain ticket id");
        assertTrue(result.contains("Fix login bug"), "should contain summary");
        assertTrue(result.contains("backend"), "should contain labels");
        assertTrue(result.contains("urgent"), "should contain all labels");
    }

    @Test
    void parseIssueContext_extractsDescriptionFromAdf() {
        // Minimal ADF with a text node
        String adfDescription = "{"
                + "\"type\":\"doc\",\"version\":1,"
                + "\"content\":[{"
                + "\"type\":\"paragraph\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Implement the feature.\"}]"
                + "}]"
                + "}";

        String json = "{\"fields\":{"
                + "\"summary\":\"My ticket\","
                + "\"description\":" + adfDescription + ","
                + "\"labels\":[]"
                + "}}";

        String result = plugin.parseIssueContext(json, "PROJ-1");

        assertTrue(result.contains("Implement the feature."), "should contain description text");
    }

    @Test
    void parseIssueContext_separatesAcceptanceCriteria() {
        String adfDescription = "{"
                + "\"type\":\"doc\",\"version\":1,"
                + "\"content\":["
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Background info.\"}]},"
                + "{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"Acceptance Criteria: must pass tests.\"}]}"
                + "]"
                + "}";

        String json = "{\"fields\":{"
                + "\"summary\":\"AC ticket\","
                + "\"description\":" + adfDescription + ","
                + "\"labels\":[]"
                + "}}";

        String result = plugin.parseIssueContext(json, "PROJ-99");

        assertTrue(result.contains("Acceptance Criteria"), "should include acceptance criteria section");
        assertTrue(result.contains("Background info"), "should include description section");
    }

    @Test
    void parseIssueContext_noLabels_omitsLabelsLine() {
        String json = "{\"fields\":{"
                + "\"summary\":\"No labels\","
                + "\"description\":null,"
                + "\"labels\":[]"
                + "}}";

        String result = plugin.parseIssueContext(json, "PROJ-5");

        assertFalse(result.contains("Labels:"), "should not include Labels line when empty");
    }

    // -------------------------------------------------------------------------
    // adfToPlainText
    // -------------------------------------------------------------------------

    @Test
    void adfToPlainText_extractsAllTextNodes() {
        String adf = "{\"type\":\"doc\",\"content\":["
                + "{\"type\":\"paragraph\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"Hello \"},"
                + "{\"type\":\"text\",\"text\":\"world\"}"
                + "]}"
                + "]}";

        String result = plugin.adfToPlainText(adf);

        assertTrue(result.contains("Hello"), "should contain first text node");
        assertTrue(result.contains("world"), "should contain second text node");
    }

    @Test
    void adfToPlainText_handlesEscapedQuotes() {
        String adf = "{\"type\":\"text\",\"text\":\"say \\\"hello\\\"\"}";
        String result = plugin.adfToPlainText(adf);
        assertTrue(result.contains("say \"hello\""), "should unescape JSON strings");
    }

    @Test
    void adfToPlainText_returnsEmptyForNullInput() {
        assertEquals("", plugin.adfToPlainText(null));
        assertEquals("", plugin.adfToPlainText(""));
    }

    // -------------------------------------------------------------------------
    // extractTransitionId
    // -------------------------------------------------------------------------

    @Test
    void extractTransitionId_findsMatchingTransition() {
        String json = "{\"transitions\":["
                + "{\"id\":\"11\",\"name\":\"To Do\"},"
                + "{\"id\":\"21\",\"name\":\"In Progress\"},"
                + "{\"id\":\"31\",\"name\":\"Done\"}"
                + "]}";

        String id = plugin.extractTransitionId(json, "In Progress", "PROJ-1");
        assertEquals("21", id);
    }

    @Test
    void extractTransitionId_caseInsensitiveMatch() {
        String json = "{\"transitions\":["
                + "{\"id\":\"31\",\"name\":\"Done\"}"
                + "]}";

        String id = plugin.extractTransitionId(json, "done", "PROJ-1");
        assertEquals("31", id);
    }

    @Test
    void extractTransitionId_throwsWhenNotFound() {
        String json = "{\"transitions\":["
                + "{\"id\":\"11\",\"name\":\"To Do\"}"
                + "]}";

        assertThrows(IllegalArgumentException.class,
                () -> plugin.extractTransitionId(json, "NonExistent", "PROJ-1"));
    }
}
