package com.metaform.cxve.verification.application;

import com.metaform.cxve.verification.domain.model.ChecklistItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Judges a participant's eventlog rollup against the expected-events map (subject → minimum
 * count). A pure function of its inputs — the assumptions about WHICH events a successful run
 * must leave behind live entirely in configuration ({@code verification.expected-events}).
 */
@Component
public class ChecklistEvaluator {

    /** {@code rollup} is the hub's participant_eventlog record; null while none exists yet. */
    public List<ChecklistItem> evaluate(JsonNode rollup, Map<String, Integer> expected) {
        var counts = new HashMap<String, Integer>();
        if (rollup != null) {
            for (var event : rollup.path("events")) {
                counts.merge(event.path("subject").asText(), 1, Integer::sum);
            }
        }
        return expected.entrySet().stream()
                .map(entry -> {
                    var actual = counts.getOrDefault(entry.getKey(), 0);
                    return new ChecklistItem(entry.getKey(), entry.getValue(), actual, actual >= entry.getValue());
                })
                .toList();
    }

    public static boolean satisfied(List<ChecklistItem> checklist) {
        return checklist.stream().allMatch(ChecklistItem::satisfied);
    }

    public static List<String> missing(List<ChecklistItem> checklist) {
        return checklist.stream()
                .filter(item -> !item.satisfied())
                .map(item -> "%s (%d/%d)".formatted(item.subject(), item.actualCount(), item.minCount()))
                .toList();
    }
}
