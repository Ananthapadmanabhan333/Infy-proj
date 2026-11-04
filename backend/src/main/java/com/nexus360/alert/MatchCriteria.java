package com.nexus360.alert;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexus360.common.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Alert matcher of suppression and routing rules (stored as JSON). Every criterion that is set must match (AND); list criteria match
 * any of their values (OR). Labels: {@code {"name": "value"}} matches the label value ignoring case, {@code "a|b"} any of the
 * alternatives, {@code "*"} any value as long as the label exists. {@code titleContains} is a case-insensitive substring.
 * An empty matcher matches every alert.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record MatchCriteria(Map<String, String> labels, List<Alert.SourceKind> sources, List<AlertSeverity> severities, List<Long> serviceIds,
                            List<Long> ciIds, List<Long> sourceIds, String titleContains) {

    public static final MatchCriteria ANY = new MatchCriteria(null, null, null, null, null, null, null);

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return empty(labels) && empty(sources) && empty(severities) && empty(serviceIds) && empty(ciIds) && empty(sourceIds)
                && (titleContains == null || titleContains.isBlank());
    }

    public boolean matches(Alert a) {
        if (!empty(sources) && !sources.contains(a.getSourceKind())) {
            return false;
        }
        if (!empty(severities) && !severities.contains(a.getSeverity())) {
            return false;
        }
        if (!empty(serviceIds) && (a.getService() == null || !serviceIds.contains(a.getService().getId()))) {
            return false;
        }
        if (!empty(ciIds) && (a.getCi() == null || !ciIds.contains(a.getCi().getId()))) {
            return false;
        }
        if (!empty(sourceIds) && (a.getSource() == null || !sourceIds.contains(a.getSource().getId()))) {
            return false;
        }
        if (titleContains != null && !titleContains.isBlank()
                && !a.getTitle().toLowerCase(Locale.ROOT).contains(titleContains.trim().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (!empty(labels)) {
            Map<String, String> have = a.getLabels() == null ? Map.of() : a.getLabels();
            for (Map.Entry<String, String> want : labels.entrySet()) {
                String actual = have.get(want.getKey());
                if (actual == null) {
                    return false;
                }
                String expected = want.getValue() == null ? "*" : want.getValue().trim();
                if (!expected.equals("*")) {
                    boolean any = false;
                    for (String alt : expected.split("\\|")) {
                        if (alt.trim().equalsIgnoreCase(actual.trim())) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Validates sizes and returns a normalised copy ({@code null} input = match everything). */
    public static MatchCriteria validated(MatchCriteria m) {
        if (m == null) {
            return ANY;
        }
        if (m.labels() != null) {
            if (m.labels().size() > 20) {
                throw ApiException.invalidField("match.labels", "At most 20 label matchers");
            }
            for (Map.Entry<String, String> e : m.labels().entrySet()) {
                if (e.getKey() == null || !e.getKey().matches("^[A-Za-z_][A-Za-z0-9_.-]{0,99}$")) {
                    throw ApiException.invalidField("match.labels", "Label names use letters, digits, '_', '.' and '-' (max 100): '" + e.getKey() + "'");
                }
                if (e.getValue() != null && e.getValue().length() > 300) {
                    throw ApiException.invalidField("match.labels", "Label values are at most 300 characters");
                }
            }
        }
        for (List<?> list : java.util.Arrays.asList(m.sources(), m.severities(), m.serviceIds(), m.ciIds(), m.sourceIds())) {
            if (list != null && list.size() > 50) {
                throw ApiException.invalidField("match", "At most 50 values per criterion");
            }
            if (list != null && list.contains(null)) {
                throw ApiException.invalidField("match", "Criteria values cannot be null");
            }
        }
        if (m.titleContains() != null && m.titleContains().length() > 200) {
            throw ApiException.invalidField("match.titleContains", "At most 200 characters");
        }
        Map<String, String> labels = m.labels() == null ? null : new LinkedHashMap<>(m.labels());
        return new MatchCriteria(labels, m.sources(), m.severities(), m.serviceIds(), m.ciIds(), m.sourceIds(),
                m.titleContains() == null || m.titleContains().isBlank() ? null : m.titleContains().trim());
    }

    private static boolean empty(Map<?, ?> m) {
        return m == null || m.isEmpty();
    }

    private static boolean empty(List<?> l) {
        return l == null || l.isEmpty();
    }
}
