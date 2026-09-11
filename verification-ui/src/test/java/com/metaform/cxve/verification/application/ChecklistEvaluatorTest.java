package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.domain.model.ChecklistItem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecklistEvaluatorTest {

    private final ChecklistEvaluator evaluator = new ChecklistEvaluator();
    private final JsonMapper mapper = new JsonMapper();

    private static Map<String, Integer> expected() {
        var expected = new LinkedHashMap<String, Integer>();
        expected.put("events.onboarding.started", 1);
        expected.put("events.contract.negotiation.finalized", 2);
        return expected;
    }

    private JsonNode rollup(String... subjects) {
        var events = mapper.createArrayNode();
        for (var subject : subjects) {
            events.add(mapper.createObjectNode().put("subject", subject));
        }
        var rollup = mapper.createObjectNode();
        rollup.set("events", events);
        return rollup;
    }

    @Test
    void satisfied_whenEverySubjectReachesItsMinimum() {
        var checklist = evaluator.evaluate(rollup(
                        "events.onboarding.started",
                        "events.contract.negotiation.finalized",
                        "events.contract.negotiation.finalized",
                        "events.something.else"),
                expected());

        assertTrue(ChecklistEvaluator.satisfied(checklist));
        assertEquals(2, checklist.size());
        assertEquals(new ChecklistItem("events.onboarding.started", 1, 1, true), checklist.get(0));
        assertEquals(new ChecklistItem("events.contract.negotiation.finalized", 2, 2, true), checklist.get(1));
    }

    @Test
    void unsatisfied_reportsTheShortfall() {
        var checklist = evaluator.evaluate(rollup(
                        "events.onboarding.started",
                        "events.contract.negotiation.finalized"),
                expected());

        assertFalse(ChecklistEvaluator.satisfied(checklist));
        assertEquals(java.util.List.of("events.contract.negotiation.finalized (1/2)"),
                ChecklistEvaluator.missing(checklist));
    }

    @Test
    void nullRollup_countsEverythingAsZero() {
        var checklist = evaluator.evaluate(null, expected());

        assertFalse(ChecklistEvaluator.satisfied(checklist));
        assertEquals(0, checklist.get(0).actualCount());
        assertEquals(0, checklist.get(1).actualCount());
    }

    @Test
    void checklistPreservesTheConfiguredOrder() {
        var checklist = evaluator.evaluate(rollup(), expected());

        assertEquals("events.onboarding.started", checklist.get(0).subject());
        assertEquals("events.contract.negotiation.finalized", checklist.get(1).subject());
    }
}
