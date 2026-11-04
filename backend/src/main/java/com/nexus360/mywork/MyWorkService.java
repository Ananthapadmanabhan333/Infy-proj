package com.nexus360.mywork;

import com.nexus360.security.Actor;
import com.nexus360.team.TeamRouting;
import com.nexus360.team.TeamService;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assembles {@code GET /api/my-work} from every {@link MyWorkContributor} bean. */
@Slf4j
@Service
public class MyWorkService {

    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;
    private static final Set<String> RESERVED = Set.of("generatedAt", "teams");

    private final List<MyWorkContributor> contributors;
    private final TeamService teams;
    private final TeamRouting teamRouting;
    private final Clock clock;

    public MyWorkService(List<MyWorkContributor> contributors, TeamService teams, TeamRouting teamRouting, Clock clock) {
        Set<String> keys = new HashSet<>();
        for (MyWorkContributor c : contributors) {
            if (RESERVED.contains(c.key()) || !keys.add(c.key())) {
                throw new IllegalStateException("Duplicate or reserved My Work section key '" + c.key() + "' (" + c.getClass().getName() + ")");
            }
        }
        this.contributors = contributors.stream().sorted(Comparator.comparingInt(MyWorkContributor::order).thenComparing(MyWorkContributor::key))
                .toList();
        this.teams = teams;
        this.teamRouting = teamRouting;
        this.clock = clock;
    }

    /** {@code {generatedAt, teams: TeamRef[], <section key>: <section>, ...}}. */
    @Transactional(readOnly = true)
    public Map<String, Object> myWork(Actor actor, Integer limit) {
        int max = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        MyWorkContributor.Context context = new MyWorkContributor.Context(actor, teamRouting.teamIdsOf(actor.id()), max, clock.instant());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", context.now());
        body.put("teams", teams.mine(actor));
        for (MyWorkContributor c : contributors) {
            if (c.appliesTo(actor)) {
                body.put(c.key(), c.contribute(context));
            }
        }
        return body;
    }
}
