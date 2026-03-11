package com.visa.nucleus.plugins.tracker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LinearTrackerPluginTest {

    private LinearTrackerPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new LinearTrackerPlugin("dummy-api-key");
    }

    // -------------------------------------------------------------------------
    // parseIssueContext
    // -------------------------------------------------------------------------

    @Test
    void parseIssueContext_extractsTitleAndDescription() {
        String json = "{"
                + "\"data\":{"
                + "\"issue\":{"
                + "\"title\":\"Implement login\","
                + "\"description\":\"Users need to log in.\","
                + "\"state\":{\"name\":\"In Progress\"},"
                + "\"labels\":{\"nodes\":[]}"
                + "}"
                + "}"
                + "}";

        String result = plugin.parseIssueContext(json, "ISSUE-1");

        assertTrue(result.contains("ISSUE-1"), "should contain issue id");
        assertTrue(result.contains("Implement login"), "should contain title");
        assertTrue(result.contains("Users need to log in."), "should contain description");
    }

    @Test
    void parseIssueContext_extractsStateName() {
        String json = "{"
                + "\"data\":{"
                + "\"issue\":{"
                + "\"title\":\"Fix bug\","
                + "\"description\":\"A bug.\","
                + "\"state\":{\"name\":\"Done\"},"
                + "\"labels\":{\"nodes\":[]}"
                + "}"
                + "}"
                + "}";

        String result = plugin.parseIssueContext(json, "ISSUE-2");

        assertTrue(result.contains("Done"), "should contain state name");
        assertTrue(result.contains("State:"), "should include State line");
    }

    @Test
    void parseIssueContext_extractsLabels() {
        String json = "{"
                + "\"data\":{"
                + "\"issue\":{"
                + "\"title\":\"Add feature\","
                + "\"description\":\"Description.\","
                + "\"state\":{\"name\":\"Todo\"},"
                + "\"labels\":{"
                + "\"nodes\":["
                + "{\"name\":\"backend\"},"
                + "{\"name\":\"priority-high\"}"
                + "]"
                + "}"
                + "}"
                + "}"
                + "}";

        String result = plugin.parseIssueContext(json, "ISSUE-3");

        assertTrue(result.contains("backend"), "should contain first label");
        assertTrue(result.contains("priority-high"), "should contain second label");
        assertTrue(result.contains("Labels:"), "should include Labels line");
    }

    @Test
    void parseIssueContext_emptyDescription_omitsDescriptionLine() {
        String json = "{"
                + "\"data\":{"
                + "\"issue\":{"
                + "\"title\":\"Short issue\","
                + "\"description\":\"\","
                + "\"state\":{\"name\":\"Todo\"},"
                + "\"labels\":{\"nodes\":[]}"
                + "}"
                + "}"
                + "}";

        String result = plugin.parseIssueContext(json, "ISSUE-4");

        assertTrue(result.contains("Short issue"), "should contain title");
        assertFalse(result.contains("Description:"), "should not include Description line when empty");
    }

    // -------------------------------------------------------------------------
    // extractStateId
    // -------------------------------------------------------------------------

    @Test
    void extractStateId_findsMatchingState() {
        String json = "{"
                + "\"data\":{"
                + "\"issue\":{"
                + "\"team\":{"
                + "\"states\":{"
                + "\"nodes\":["
                + "{\"id\":\"state-1\",\"name\":\"Todo\"},"
                + "{\"id\":\"state-2\",\"name\":\"In Progress\"},"
                + "{\"id\":\"state-3\",\"name\":\"Done\"}"
                + "]"
                + "}"
                + "}"
                + "}"
                + "}"
                + "}";

        String stateId = plugin.extractStateId(json, "In Progress", "ISSUE-1");
        assertEquals("state-2", stateId);
    }

    @Test
    void extractStateId_caseInsensitiveMatch() {
        String json = "{"
                + "\"data\":{"
                + "\"issue\":{"
                + "\"team\":{"
                + "\"states\":{"
                + "\"nodes\":["
                + "{\"id\":\"state-3\",\"name\":\"Done\"}"
                + "]"
                + "}"
                + "}"
                + "}"
                + "}"
                + "}";

        String stateId = plugin.extractStateId(json, "done", "ISSUE-1");
        assertEquals("state-3", stateId);
    }

    @Test
    void extractStateId_throwsWhenNotFound() {
        String json = "{"
                + "\"data\":{"
                + "\"issue\":{"
                + "\"team\":{"
                + "\"states\":{"
                + "\"nodes\":["
                + "{\"id\":\"state-1\",\"name\":\"Todo\"}"
                + "]"
                + "}"
                + "}"
                + "}"
                + "}"
                + "}";

        assertThrows(IllegalArgumentException.class,
                () -> plugin.extractStateId(json, "NonExistent", "ISSUE-1"));
    }

    // -------------------------------------------------------------------------
    // TrackerPluginFactory
    // -------------------------------------------------------------------------

    @Test
    void factory_throwsForUnknownTrackerType() {
        TrackerPluginFactory factory = new TrackerPluginFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.create("unknown"));
    }

    @Test
    void factory_throwsForNullTrackerType() {
        TrackerPluginFactory factory = new TrackerPluginFactory();
        assertThrows(IllegalArgumentException.class, () -> factory.create(null));
    }
}
