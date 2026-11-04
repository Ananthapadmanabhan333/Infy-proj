package com.nexus360.alert;

import com.nexus360.alert.AlertDtos.AlertSummary;
import com.nexus360.mywork.MyWorkContributor;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Alert module contributions: the My Work section {@code alerts} (open and acknowledged alerts routed to my teams, most severe first)
 * and the workflow facts of an alert ({@link #facts}).
 */
@Configuration
@RequiredArgsConstructor
public class AlertExtensions {

    private final AlertService alerts;

    @Bean
    MyWorkContributor alertsSection() {
        return new MyWorkContributor() {
            @Override
            public String key() {
                return "alerts";
            }

            @Override
            public int order() {
                return 130;
            }

            @Override
            public Object contribute(Context ctx) {
                return new Section<AlertSummary>(alerts.workCount(ctx.teamIds()), alerts.workFor(ctx.teamIds(), ctx.limit()));
            }
        };
    }

    /**
     * Facts of an alert for workflow conditions (trigger ALERT_RECEIVED): {@code alertSeverity}, {@code alertSource},
     * {@code alertStatus}, {@code alertName}, {@code serviceCode}, {@code teamCode}, {@code ciName}, {@code ciType}, {@code title},
     * {@code occurrenceCount}, plus {@code priority}/{@code status} mirrors for generic rules.
     */
    public static Map<String, String> facts(Alert a) {
        Map<String, String> f = new HashMap<>();
        f.put("alertSeverity", a.getSeverity().name());
        f.put("alertSource", a.getSourceKind().name());
        f.put("alertStatus", a.getStatus().name());
        f.put("alertName", a.alertName());
        f.put("status", a.getStatus().name());
        f.put("title", a.getTitle());
        f.put("serviceCode", a.getService() == null ? "" : a.getService().getCode());
        f.put("teamCode", a.getTeam() == null ? "" : a.getTeam().getCode());
        f.put("ciName", a.getCi() == null ? "" : a.getCi().getName());
        f.put("ciType", a.getCi() == null ? "" : a.getCi().getCiType().name());
        f.put("occurrenceCount", String.valueOf(a.getOccurrenceCount()));
        f.put("source", a.getSourceKind().name());
        return f;
    }
}
