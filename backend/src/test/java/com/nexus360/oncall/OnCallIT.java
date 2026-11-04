package com.nexus360.oncall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus360.alert.AlertEscalationService;
import com.nexus360.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** On-call through the API: rotation in a time zone, who is on call now/at a time, overrides offered and taken, availability, escalation. */
class OnCallIT extends IntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate START = LocalDate.of(2026, 1, 5);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AlertEscalationService escalation;

    private long team;
    private long schedule;
    private long meera;
    private long dev;
    private long vishal;
    private long anil;
    private long priya;
    private String tag;

    private long user(String u) {
        return jdbc.queryForObject("select id from users where email = ?", Long.class, email(u));
    }

    @BeforeEach
    void schedule() throws Exception {
        tag = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        meera = user("meera.sharma");
        dev = user("dev.nair");
        vishal = user("vishal.kumar");
        anil = user("anil.kumar");
        priya = user("priya.raman");
        long apps = jdbc.queryForObject("select id from departments where code = 'IT-APPS'", Long.class);
        team = jdbc.queryForObject("insert into teams (code, name, department_id, lead_id) values (?, ?, ?, ?) returning id", Long.class, "OC" + tag,
                "On-call test " + tag, apps, anil);
        for (long u : new long[]{meera, dev, vishal, anil}) {
            jdbc.update("insert into team_members (team_id, user_id, member_role) values (?, ?, ?)", team, u, u == anil ? "LEAD" : "MEMBER");
        }
        JsonNode policy = body(mvc.perform(jsonBody(as("priya.raman", post("/api/oncall/escalation-policies")), Map.of("name", "Test policy " + tag,
                "minSeverity", "MAJOR", "levels", List.of(Map.of("delayMinutes", 5, "target", "PRIMARY"), Map.of("delayMinutes", 10, "target", "SECONDARY"),
                        Map.of("delayMinutes", 15, "target", "MANAGER"))))).andExpect(status().isCreated()).andReturn());
        assertThat(policy.at("/levels/2/minutesFromStart").asInt()).isEqualTo(30);
        Map<String, Object> req = new HashMap<>(Map.of("teamId", team, "name", "Test schedule " + tag, "timeZone", "Asia/Kolkata", "rotationType", "DAILY",
                "handoffTime", "09:00", "rotationStart", START.toString(), "primaryUserIds", List.of(meera, dev, vishal),
                "escalationPolicyId", policy.get("id").asLong()));
        JsonNode created = body(mvc.perform(jsonBody(as("priya.raman", post("/api/oncall/schedules")), req)).andExpect(status().isCreated()).andReturn());
        schedule = created.at("/schedule/id").asLong();
        assertThat(created.get("primary")).hasSize(3);
        assertThat(created.at("/escalationPolicy/name").asText()).isEqualTo("Test policy " + tag);
    }

    /** The rotation participant at local noon IST on {@code day} (slot = days since the start). */
    private long expectedAt(LocalDate day) {
        long[] list = {meera, dev, vishal};
        return list[(int) Math.floorMod(ChronoUnit.DAYS.between(START, day), 3)];
    }

    private JsonNode now(Instant at) throws Exception {
        JsonNode list = getJson("riya.varma", "/api/oncall/now?teamId=" + team + "&at=" + at);
        assertThat(list).hasSize(1);
        return list.get(0);
    }

    @Test
    void rotationHandsOffAtLocalTimeAndAnswersWhoIsOnCall() throws Exception {
        // 09:00 IST = 03:30 UTC. Slot 0 (5 Jan) is Meera; the handoff to Dev is on 6 Jan at 03:30 UTC.
        JsonNode before = now(Instant.parse("2026-01-06T03:29:00Z"));
        assertThat(before.at("/primary/userId").asLong()).isEqualTo(meera);
        assertThat(before.at("/secondary/userId").asLong()).isEqualTo(dev);
        assertThat(before.at("/primary/until").asText()).isEqualTo("2026-01-06T03:30:00Z");
        JsonNode after = now(Instant.parse("2026-01-06T03:30:00Z"));
        assertThat(after.at("/primary/userId").asLong()).isEqualTo(dev);
        assertThat(after.at("/primary/source").asText()).isEqualTo("ROTATION");
        assertThat(after.at("/secondary/userId").asLong()).isEqualTo(vishal);
        assertThat(after.get("timeZone").asText()).isEqualTo("Asia/Kolkata");

        JsonNode shifts = getJson("riya.varma", "/api/oncall/schedules/" + schedule + "/shifts?from=2026-01-05T00:00:00Z&to=2026-01-08T00:00:00Z");
        assertThat(shifts.get("primary").findValuesAsText("userId")).containsExactly(String.valueOf(meera), String.valueOf(dev), String.valueOf(vishal));
        assertThat(shifts.at("/primary/0/start").asText()).isEqualTo("2026-01-05T03:30:00Z");
        assertThat(shifts.at("/primary/2/end").asText()).isEqualTo("2026-01-08T00:00:00Z");

        // Who is on call right now follows the same maths.
        Instant at = clock.instant();
        JsonNode current = getJson("riya.varma", "/api/oncall/now?teamId=" + team).get(0);
        LocalDate day = at.atZone(IST).toLocalTime().isBefore(java.time.LocalTime.of(9, 0)) ? at.atZone(IST).toLocalDate().minusDays(1)
                : at.atZone(IST).toLocalDate();
        assertThat(current.at("/primary/userId").asLong()).isEqualTo(expectedAt(day));

        // Validation and permissions.
        Map<String, Object> bad = new HashMap<>(Map.of("teamId", team, "name", "Bad zone " + tag, "timeZone", "Mars/Olympus", "rotationType", "DAILY",
                "rotationStart", START.toString(), "primaryUserIds", List.of(meera)));
        mvc.perform(jsonBody(as("priya.raman", post("/api/oncall/schedules")), bad)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("timeZone"));
        bad.put("timeZone", "UTC");
        bad.put("rotationType", "CUSTOM");
        mvc.perform(jsonBody(as("priya.raman", post("/api/oncall/schedules")), bad)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("shiftHours"));
        mvc.perform(jsonBody(as("riya.varma", post("/api/oncall/schedules")), bad)).andExpect(status().isForbidden());
        mvc.perform(as("kavya.reddy", get("/api/oncall/now"))).andExpect(status().isForbidden());
        // The demo organisation has a schedule for every IT team.
        assertThat(getJson("ananya.bose", "/api/oncall/now").size()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void overridesAreSetOfferedTakenAndAvailabilityIsHonoured() throws Exception {
        LocalDate tomorrow = clock.instant().atZone(IST).toLocalDate().plusDays(1);
        Instant noon = tomorrow.atTime(12, 0).atZone(IST).toInstant();
        // An editor's override replaces the rotation for its window.
        JsonNode o = body(mvc.perform(jsonBody(as("priya.raman", post("/api/oncall/schedules/" + schedule + "/overrides")), Map.of("userId",
                user("rahul.das"), "startsAt", noon.minus(Duration.ofHours(1)).toString(), "endsAt", noon.plus(Duration.ofHours(1)).toString(),
                "reason", "Cover"))).andExpect(status().isCreated()).andReturn());
        assertThat(o.get("status").asText()).isEqualTo("ACTIVE");
        JsonNode covered = now(noon);
        assertThat(covered.at("/primary/userId").asLong()).isEqualTo(user("rahul.das"));
        assertThat(covered.at("/primary/source").asText()).isEqualTo("OVERRIDE");
        assertThat(covered.at("/primary/scheduledUserId").asLong()).isEqualTo(expectedAt(tomorrow));
        assertThat(now(noon.plus(Duration.ofHours(2))).at("/primary/userId").asLong()).isEqualTo(expectedAt(tomorrow));

        // A participant offers a shift to everybody; another participant takes it; outsiders cannot.
        Instant later = noon.plus(Duration.ofDays(1));
        Map<String, Object> offer = Map.of("offer", true, "startsAt", later.toString(), "endsAt", later.plus(Duration.ofHours(2)).toString(),
                "reason", "Dentist");
        JsonNode offered = body(mvc.perform(jsonBody(as("dev.nair", post("/api/oncall/schedules/" + schedule + "/overrides")), offer))
                .andExpect(status().isCreated()).andReturn());
        assertThat(offered.get("status").asText()).isEqualTo("OFFERED");
        assertThat(offered.get("originalUserId").asLong()).isEqualTo(dev);
        long offerId = offered.get("id").asLong();
        JsonNode offers = getJson("meera.sharma", "/api/oncall/overrides/offers");
        assertThat(offers.findValuesAsText("id")).contains(String.valueOf(offerId));
        mvc.perform(as("rahul.das", post("/api/oncall/overrides/" + offerId + "/take"))).andExpect(status().isForbidden());
        mvc.perform(as("dev.nair", post("/api/oncall/overrides/" + offerId + "/take"))).andExpect(status().isForbidden());
        JsonNode taken = body(mvc.perform(as("meera.sharma", post("/api/oncall/overrides/" + offerId + "/take"))).andExpect(status().isOk()).andReturn());
        assertThat(taken.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(taken.get("userId").asLong()).isEqualTo(meera);
        mvc.perform(as("vishal.kumar", post("/api/oncall/overrides/" + offerId + "/take"))).andExpect(status().isConflict());
        assertThat(now(later.plus(Duration.ofMinutes(30))).at("/primary/userId").asLong()).isEqualTo(meera);
        // Staff cannot set overrides directly; the past is rejected.
        mvc.perform(jsonBody(as("dev.nair", post("/api/oncall/schedules/" + schedule + "/overrides")), Map.of("userId", meera, "startsAt",
                later.toString(), "endsAt", later.plus(Duration.ofHours(1)).toString()))).andExpect(status().isForbidden());
        mvc.perform(jsonBody(as("priya.raman", post("/api/oncall/schedules/" + schedule + "/overrides")), Map.of("userId", meera, "startsAt",
                "2020-01-01T00:00:00Z", "endsAt", "2020-01-01T01:00:00Z"))).andExpect(status().isBadRequest());

        // Unavailability: on a day of Dev's, the rotation passes to the next available participant (Vishal).
        LocalDate devDay = tomorrow.plusDays(9); // after the demo leave of Meera (tomorrow + 5 … + 8)
        while (expectedAt(devDay) != dev) {
            devDay = devDay.plusDays(1);
        }
        Instant devNoon = devDay.atTime(12, 0).atZone(IST).toInstant();
        assertThat(now(devNoon).at("/primary/userId").asLong()).isEqualTo(dev);
        JsonNode away = body(mvc.perform(jsonBody(as("dev.nair", post("/api/oncall/availability")), Map.of("startsAt", devDay.atStartOfDay(IST).toInstant()
                .toString(), "endsAt", devDay.plusDays(1).atStartOfDay(IST).toInstant().toString(), "reason", "Leave"))).andExpect(status().isCreated()).andReturn());
        JsonNode skipped = now(devNoon);
        assertThat(skipped.at("/primary/userId").asLong()).isEqualTo(vishal);
        assertThat(skipped.at("/primary/source").asText()).isEqualTo("AVAILABILITY");
        assertThat(skipped.at("/secondary/userId").asLong()).isEqualTo(meera);
        mvc.perform(jsonBody(as("dev.nair", post("/api/oncall/availability")), Map.of("userId", meera, "startsAt", devNoon.toString(), "endsAt",
                devNoon.plusSeconds(3600).toString()))).andExpect(status().isForbidden());
        mvc.perform(as("dev.nair", org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/oncall/availability/"
                + away.get("id").asLong()))).andExpect(status().isNoContent());
        assertThat(now(devNoon).at("/primary/userId").asLong()).isEqualTo(dev);

        // Handover notes; My Work lists my shifts.
        mvc.perform(jsonBody(as("meera.sharma", post("/api/oncall/schedules/" + schedule + "/handovers")), Map.of("body", "Watch the batch " + tag)))
                .andExpect(status().isCreated());
        assertThat(getJson("riya.varma", "/api/oncall/schedules/" + schedule + "/handovers").findValuesAsText("body")).contains("Watch the batch " + tag);
        mvc.perform(jsonBody(as("rahul.das", post("/api/oncall/schedules/" + schedule + "/handovers")), Map.of("body", "x"))).andExpect(status().isForbidden());
        assertThat(getJson("meera.sharma", "/api/my-work?limit=50").at("/onCallShifts/total").asLong()).isPositive();
        JsonNode detail = getJson("priya.raman", "/api/oncall/schedules/" + schedule);
        assertThat(detail.get("overrides").findValuesAsText("status")).contains("ACTIVE");
        assertThat(detail.get("canEdit").asBoolean()).isTrue();
    }

    @Test
    void unacknowledgedAlertsEscalateThroughTheOnCallPolicyExactlyOncePerLevel() throws Exception {
        JsonNode created = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts")), Map.of("title", "On-call escalation " + tag, "severity",
                "CRITICAL"))).andExpect(status().isCreated()).andReturn());
        long alert = created.at("/alert/id").asLong();
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + alert + "/assign")), Map.of("teamId", team))).andExpect(status().isOk());
        JsonNode detail = getJson("riya.varma", "/api/alerts/" + alert);
        assertThat(detail.get("escalationPolicy").asText()).isEqualTo("on-call");
        // Level 1 is due (as if the alert had been open for 5 minutes).
        jdbc.update("update alerts set escalation_level = 0, next_escalation_at = ? where id = ?", java.sql.Timestamp.from(clock.instant().minusSeconds(1)), alert);

        long primary = now(clock.instant()).at("/primary/userId").asLong();
        escalation.runEscalations();
        escalation.runEscalations(); // a second run in the same minute pages nobody again
        assertThat(pages(alert, 1)).containsExactly(primary);
        assertThat(level(alert)).isEqualTo(1);
        Instant next = jdbc.queryForObject("select next_escalation_at from alerts where id = ?", java.sql.Timestamp.class, alert).toInstant();
        assertThat(Duration.between(clock.instant(), next).toMinutes()).isBetween(9L, 10L);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'ONCALL_PAGE' and link = ?", Long.class, primary,
                "/alerts/" + alert)).isEqualTo(1);

        testClock().advance(Duration.ofMinutes(11));
        long secondary = now(clock.instant()).at("/secondary/userId").asLong();
        escalation.runEscalations();
        escalation.runEscalations();
        assertThat(pages(alert, 2)).containsExactly(secondary);
        assertThat(level(alert)).isEqualTo(2);

        testClock().advance(Duration.ofMinutes(16));
        escalation.runEscalations();
        escalation.runEscalations();
        assertThat(pages(alert, 3)).containsExactlyInAnyOrder(anil, priya); // team lead and the IT-APPS manager
        assertThat(level(alert)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select next_escalation_at from alerts where id = ?", java.sql.Timestamp.class, alert)).isNull();

        testClock().advance(Duration.ofMinutes(60));
        escalation.runEscalations();
        assertThat(level(alert)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from oncall_pages where alert_id = ?", Long.class, alert)).isEqualTo(4);
        JsonNode history = getJson("riya.varma", "/api/alerts/" + alert).get("history");
        long escalations = 0;
        for (JsonNode h : history) {
            if ("ESCALATED".equals(h.get("type").asText())) {
                escalations++;
                assertThat(h.at("/details/policy").asText()).isEqualTo("on-call");
            }
        }
        assertThat(escalations).isEqualTo(3);
        JsonNode pageLog = getJson("ananya.bose", "/api/oncall/pages?alertId=" + alert);
        assertThat(pageLog).hasSize(4);
        assertThat(pageLog.findValuesAsText("target")).contains("PRIMARY", "SECONDARY", "MANAGER");

        // A WARNING alert of the same team stays with the built-in policy (below the policy's minimum severity).
        JsonNode quiet = body(mvc.perform(jsonBody(as("riya.varma", post("/api/alerts")), Map.of("title", "Quiet " + tag, "severity", "WARNING")))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(jsonBody(as("riya.varma", post("/api/alerts/" + quiet.at("/alert/id").asLong() + "/assign")), Map.of("teamId", team)))
                .andExpect(status().isOk());
        assertThat(getJson("riya.varma", "/api/alerts/" + quiet.at("/alert/id").asLong()).get("escalationPolicy").isNull()).isTrue();
    }

    private List<Long> pages(long alert, int level) {
        return jdbc.queryForList("select user_id from oncall_pages where alert_id = ? and level = ? order by user_id", Long.class, alert, level);
    }

    private int level(long alert) {
        return jdbc.queryForObject("select escalation_level from alerts where id = ?", Integer.class, alert);
    }
}
