package com.nexus360.portal;

import com.nexus360.security.Actor;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assembles {@code GET /api/portal/overview} from every {@link PortalContributor} bean. */
@Service
public class PortalService {

    static final int LIMIT = 5;

    private final List<PortalContributor> contributors;
    private final Clock clock;

    public PortalService(List<PortalContributor> contributors, Clock clock) {
        Set<String> keys = new HashSet<>();
        for (PortalContributor c : contributors) {
            if ("generatedAt".equals(c.key()) || !keys.add(c.key())) {
                throw new IllegalStateException("Duplicate or reserved portal section key '" + c.key() + "' (" + c.getClass().getName() + ")");
            }
        }
        this.contributors = contributors.stream().sorted(Comparator.comparingInt(PortalContributor::order).thenComparing(PortalContributor::key))
                .toList();
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> overview(Actor actor) {
        PortalContributor.Context context = new PortalContributor.Context(actor, LIMIT, clock.instant());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", context.now());
        contributors.forEach(c -> body.put(c.key(), c.contribute(context)));
        return body;
    }
}
