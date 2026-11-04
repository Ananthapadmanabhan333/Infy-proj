package com.nexus360.cmdb;

import com.nexus360.cmdb.CmdbDtos.CiRef;
import com.nexus360.common.ApiException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional links from problems and changes to CIs ({@code problem_cis}, {@code change_cis}) next to their free-text
 * {@code affectedCis} names. Rules: explicit {@code ciIds} replace the links and their CI names are added to the text list;
 * without {@code ciIds} the links follow the text list (every name that matches a CI name exactly, ignoring case, is linked).
 * A CI created or renamed later is linked to every problem/change that already names it.
 */
@Service
@RequiredArgsConstructor
public class CiLinkService {

    public static final int MAX_LINKS = 30;

    public enum Subject {
        PROBLEM("problem_cis", "problem_id", "problems"),
        CHANGE("change_cis", "change_id", "changes");

        final String table;
        final String column;
        final String owner;

        Subject(String table, String column, String owner) {
            this.table = table;
            this.column = column;
            this.owner = owner;
        }
    }

    private final JdbcTemplate jdbc;

    /**
     * Updates the links of a problem or change and returns the affected-CI names to store.
     *
     * @param ciIds         explicit CI ids ({@code null} = derive from the names)
     * @param names         the subject's affected-CI names after the request was applied
     * @param namesProvided whether the request carried {@code affectedCis} (or the subject is new)
     */
    @Transactional
    public List<String> sync(Subject subject, Long subjectId, List<Long> ciIds, List<String> names, boolean namesProvided) {
        List<String> current = names == null ? new ArrayList<>() : new ArrayList<>(names);
        if (ciIds != null) {
            Set<Long> wanted = ciIds.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
            if (wanted.size() > MAX_LINKS) {
                throw ApiException.invalidField("ciIds", "At most " + MAX_LINKS + " configuration items");
            }
            Map<Long, String> found = namesOf(wanted);
            List<Long> missing = wanted.stream().filter(id -> !found.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                throw ApiException.invalidField("ciIds", "Configuration item " + missing.get(0) + " does not exist");
            }
            replace(subject, subjectId, wanted);
            Set<String> lower = current.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            for (Long id : wanted) {
                String n = found.get(id);
                if (lower.add(n.toLowerCase(Locale.ROOT))) {
                    current.add(n);
                }
            }
            return current;
        }
        if (namesProvided) {
            replace(subject, subjectId, idsByName(current));
        }
        return current;
    }

    @Transactional(readOnly = true)
    public List<CiRef> refs(Subject subject, Long subjectId) {
        if (subjectId == null) {
            return List.of();
        }
        return jdbc.query("select c.id, c.name, c.ci_type, c.status, c.environment from " + subject.table + " l join configuration_items c "
                + "on c.id = l.ci_id where l." + subject.column + " = ? order by lower(c.name)", (rs, n) -> new CiRef(rs.getLong(1), rs.getString(2),
                ConfigurationItem.CiType.valueOf(rs.getString(3)), ConfigurationItem.Status.valueOf(rs.getString(4)),
                rs.getString(5) == null ? null : ConfigurationItem.Environment.valueOf(rs.getString(5))), subjectId);
    }

    /** Links every problem and change whose affected-CI names match a CI exactly (idempotent). Returns the number of new links. */
    @Transactional
    public int backfillByName() {
        int n = 0;
        for (Subject s : Subject.values()) {
            n += jdbc.update("insert into " + s.table + " (" + s.column + ", ci_id) select distinct x.id, c.id from " + s.owner + " x "
                    + "cross join lateral jsonb_array_elements_text(x.affected_cis) as n(name) join configuration_items c on lower(c.name) = lower(trim(n.name)) "
                    + "on conflict do nothing");
        }
        return n;
    }

    /** Links a (new or renamed) CI to the problems and changes that already name it. */
    @Transactional
    public void linkByName(Long ciId) {
        for (Subject s : Subject.values()) {
            jdbc.update("insert into " + s.table + " (" + s.column + ", ci_id) select distinct x.id, c.id from " + s.owner + " x "
                    + "cross join lateral jsonb_array_elements_text(x.affected_cis) as n(name) join configuration_items c on lower(c.name) = lower(trim(n.name)) "
                    + "where c.id = ? on conflict do nothing", ciId);
        }
    }

    private void replace(Subject subject, Long subjectId, Collection<Long> ciIds) {
        jdbc.update("delete from " + subject.table + " where " + subject.column + " = ?", subjectId);
        for (Long ci : ciIds) {
            jdbc.update("insert into " + subject.table + " (" + subject.column + ", ci_id) values (?, ?) on conflict do nothing", subjectId, ci);
        }
    }

    private Map<Long, String> namesOf(Collection<Long> ids) {
        Map<Long, String> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        jdbc.query("select id, name from configuration_items where id in (" + placeholders(ids.size()) + ")",
                rs -> {
                    out.put(rs.getLong(1), rs.getString(2));
                }, ids.toArray());
        return out;
    }

    private List<Long> idsByName(List<String> names) {
        List<String> lower = names.stream().filter(Objects::nonNull).map(n -> n.trim().toLowerCase(Locale.ROOT)).filter(n -> !n.isEmpty())
                .distinct().toList();
        if (lower.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("select id from configuration_items where lower(name) in (" + placeholders(lower.size()) + ")", Long.class,
                lower.toArray());
    }

    static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
