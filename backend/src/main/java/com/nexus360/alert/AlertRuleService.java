package com.nexus360.alert;

import com.nexus360.alert.AlertDtos.DeliveryDto;
import com.nexus360.alert.AlertDtos.RoutingRuleDto;
import com.nexus360.alert.AlertDtos.RoutingRuleRequest;
import com.nexus360.alert.AlertDtos.SourceDto;
import com.nexus360.alert.AlertDtos.SourceRequest;
import com.nexus360.alert.AlertDtos.SourceSaveResult;
import com.nexus360.alert.AlertDtos.SuppressionRuleDto;
import com.nexus360.alert.AlertDtos.SuppressionRuleRequest;
import com.nexus360.audit.AuditService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.cmdb.ConfigurationItemRepository;
import com.nexus360.common.ApiException;
import com.nexus360.integration.SecretCipher;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.team.TeamRouting;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Alert configuration: webhook sources (secrets shown once, stored encrypted), suppression rules and routing rules. All changes are audited. */
@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private final AlertSourceRepository sources;
    private final AlertSuppressionRuleRepository suppressionRules;
    private final AlertRoutingRuleRepository routingRules;
    private final BusinessServiceRepository services;
    private final ConfigurationItemRepository cis;
    private final TeamRouting teamRouting;
    private final AlertIngestService ingest;
    private final com.nexus360.user.UserRepository users;
    private final SecretCipher cipher;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ sources

    @Transactional(readOnly = true)
    public List<SourceDto> sources() {
        return sources.findAllByOrderByNameAsc().stream().map(AlertRuleService::sourceDto).toList();
    }

    @Transactional(readOnly = true)
    public SourceDto source(Long id) {
        return sourceDto(sources.findWithDefaultsById(id).orElseThrow(() -> ApiException.notFound("Alert source", id)));
    }

    @Transactional
    public SourceSaveResult saveSource(Long id, SourceRequest r, Actor actor) {
        AlertSource s = id == null ? new AlertSource() : sources.findWithDefaultsById(id).orElseThrow(() -> ApiException.notFound("Alert source", id));
        String name = r.name().trim();
        sources.findByNameIgnoreCase(name).filter(other -> !other.getId().equals(s.getId())).ifPresent(other -> {
            throw ApiException.conflict("An alert source named '" + other.getName() + "' already exists");
        });
        if (id != null && r.provider() != s.getProvider()) {
            throw ApiException.invalidField("provider", "The provider of a source cannot change; create a new source");
        }
        s.setName(name);
        s.setProvider(r.provider());
        if (r.provider() == AlertSource.Provider.CLOUDWATCH) {
            if (r.topicArn() == null || !SnsGateway.validTopicArn(r.topicArn())) {
                throw ApiException.invalidField("topicArn", "CloudWatch sources need the SNS topic ARN (arn:aws:sns:<region>:<account>:<topic>)");
            }
            if (s.getSnsTopicArn() != null && !s.getSnsTopicArn().equals(r.topicArn().trim())) {
                s.setSubscriptionStatus(AlertSource.SubscriptionStatus.NONE);
                s.setSubscriptionArn(null);
                s.setSubscriptionConfirmedAt(null);
            }
            s.setSnsTopicArn(r.topicArn().trim());
        } else if (r.topicArn() != null && !r.topicArn().isBlank()) {
            throw ApiException.invalidField("topicArn", "Only CloudWatch (SNS) sources have a topic ARN");
        }
        s.setDefaultService(r.defaultServiceId() == null || r.defaultServiceId() == 0L ? null : services.findById(r.defaultServiceId())
                .orElseThrow(() -> ApiException.invalidField("defaultServiceId", "Service does not exist")));
        s.setDefaultCi(r.defaultCiId() == null || r.defaultCiId() == 0L ? null : cis.findById(r.defaultCiId())
                .orElseThrow(() -> ApiException.invalidField("defaultCiId", "Configuration item does not exist")));
        if (r.defaultSeverity() != null) {
            s.setDefaultSeverity(r.defaultSeverity());
        }
        if (r.active() != null) {
            s.setActive(r.active());
        }
        String secret = null;
        if (id == null) {
            s.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
            if (r.provider() != AlertSource.Provider.CLOUDWATCH) {
                secret = newSecret();
                s.setSecretEncrypted(cipher.encrypt(secret));
            }
        }
        sources.saveAndFlush(s);
        audit.record(id == null ? "ALERT_SOURCE_CREATED" : "ALERT_SOURCE_UPDATED", "ALERT_SOURCE", s.getId(), Map.of("name", s.getName(),
                "provider", s.getProvider(), "active", s.isActive()));
        return new SourceSaveResult(sourceDto(s), secret);
    }

    @Transactional
    public SourceSaveResult rotateSecret(Long id) {
        AlertSource s = sources.findWithDefaultsById(id).orElseThrow(() -> ApiException.notFound("Alert source", id));
        if (s.getProvider() == AlertSource.Provider.CLOUDWATCH) {
            throw ApiException.conflict("CloudWatch sources are authenticated by AWS SNS signatures and have no secret");
        }
        String secret = newSecret();
        s.setSecretEncrypted(cipher.encrypt(secret));
        audit.record("ALERT_SOURCE_SECRET_ROTATED", "ALERT_SOURCE", s.getId(), Map.of("name", s.getName()));
        return new SourceSaveResult(sourceDto(s), secret);
    }

    @Transactional(readOnly = true)
    public List<DeliveryDto> deliveries(Long id, Integer size) {
        sources.findById(id).orElseThrow(() -> ApiException.notFound("Alert source", id));
        int n = size == null ? 50 : Math.max(1, Math.min(size, 200));
        return jdbc.query("select id, delivery_id, event_type, outcome, message, alert_count, received_at from alert_webhook_deliveries where source_id = ? "
                + "order by received_at desc, id desc limit ?", (rs, i) -> new DeliveryDto(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), rs.getTimestamp(7).toInstant()), id, n);
    }

    static SourceDto sourceDto(AlertSource s) {
        String auth = switch (s.getProvider()) {
            case PROMETHEUS -> "Authorization: Bearer <secret> (or HTTP Basic auth with the secret as password)";
            case GRAFANA -> "Authorization: Bearer <secret> (or HTTP Basic auth with the secret as password), or the contact point's HMAC signature with "
                    + "the secret: X-Grafana-Alerting-Signature = hex HMAC-SHA256(secret, body), signed as timestamp + \":\" + body when "
                    + "X-Grafana-Alerting-Timestamp is sent (unix seconds, within 5 minutes)";
            case GENERIC -> "X-Nexus-Timestamp, X-Nexus-Delivery and X-Nexus-Signature: sha256=HMAC-SHA256(secret, timestamp + \".\" + body)";
            case CLOUDWATCH -> "AWS SNS message signature; HTTPS subscription to topic " + s.getSnsTopicArn();
        };
        return new SourceDto(s.getId(), s.getName(), s.getProvider(), s.getDefaultService() == null ? null : s.getDefaultService().getId(),
                s.getDefaultService() == null ? null : s.getDefaultService().getName(), s.getDefaultCi() == null ? null : s.getDefaultCi().getId(),
                s.getDefaultCi() == null ? null : s.getDefaultCi().getName(), s.getDefaultSeverity(), s.getSnsTopicArn(), s.getSubscriptionStatus(),
                s.getSubscriptionArn(), s.getSubscriptionConfirmedAt(), s.isActive(), "/api/alerts/webhooks/" + s.getId(), auth, s.getLastReceivedAt(),
                s.isDemo(), s.getCreatedAt());
    }

    private static String newSecret() {
        return "alsec_" + Hashing.randomToken(32);
    }

    // ------------------------------------------------------------------ suppression rules

    @Transactional(readOnly = true)
    public List<SuppressionRuleDto> suppressionRules() {
        return suppressionRules.findAllByOrderByNameAsc().stream().map(this::dto).toList();
    }

    @Transactional
    public SuppressionRuleDto saveSuppression(Long id, SuppressionRuleRequest r, Actor actor) {
        AlertSuppressionRule rule = id == null ? new AlertSuppressionRule() : suppressionRules.findById(id)
                .orElseThrow(() -> ApiException.notFound("Suppression rule", id));
        MatchCriteria match = MatchCriteria.validated(r.match());
        if (r.startsAt() != null && r.endsAt() != null && !r.endsAt().isAfter(r.startsAt())) {
            throw ApiException.invalidField("endsAt", "The end must be after the start");
        }
        if ((r.dailyStart() == null) != (r.dailyEnd() == null)) {
            throw ApiException.invalidField("dailyEnd", "A daily window needs both dailyStart and dailyEnd");
        }
        if (r.dailyStart() != null && r.dailyStart().equals(r.dailyEnd())) {
            throw ApiException.invalidField("dailyEnd", "The daily window cannot be empty");
        }
        if (r.daysOfWeek() != null && !r.daysOfWeek().isEmpty() && r.dailyStart() == null && r.startsAt() == null && r.endsAt() == null
                && match.isEmpty()) {
            throw ApiException.invalidField("match", "A rule that suppresses every alert on whole days needs a matcher");
        }
        if (match.isEmpty() && r.startsAt() == null && r.endsAt() == null && r.dailyStart() == null) {
            throw ApiException.invalidField("match", "A rule without a time window needs at least one match criterion (it would suppress every alert)");
        }
        rule.setName(r.name().trim());
        rule.setDescription(blank(r.description()));
        if (r.enabled() != null) {
            rule.setEnabled(r.enabled());
        }
        rule.setMatchCriteria(match);
        rule.setStartsAt(r.startsAt());
        rule.setEndsAt(r.endsAt());
        rule.setDailyStart(r.dailyStart() == null ? null : LocalTime.parse(r.dailyStart()));
        rule.setDailyEnd(r.dailyEnd() == null ? null : LocalTime.parse(r.dailyEnd()));
        rule.setDaysOfWeek(r.daysOfWeek() == null ? new ArrayList<>() : new ArrayList<>(new TreeSet<>(r.daysOfWeek())));
        if (id == null) {
            rule.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        }
        rule.setUpdatedAt(clock.instant());
        suppressionRules.saveAndFlush(rule);
        audit.record(id == null ? "ALERT_SUPPRESSION_RULE_CREATED" : "ALERT_SUPPRESSION_RULE_UPDATED", "ALERT_SUPPRESSION_RULE", rule.getId(),
                Map.of("name", rule.getName(), "enabled", rule.isEnabled()));
        return dto(rule);
    }

    @Transactional
    public void deleteSuppression(Long id) {
        AlertSuppressionRule rule = suppressionRules.findById(id).orElseThrow(() -> ApiException.notFound("Suppression rule", id));
        suppressionRules.delete(rule);
        audit.record("ALERT_SUPPRESSION_RULE_DELETED", "ALERT_SUPPRESSION_RULE", id, Map.of("name", rule.getName()));
    }

    private SuppressionRuleDto dto(AlertSuppressionRule r) {
        return new SuppressionRuleDto(r.getId(), r.getName(), r.getDescription(), r.isEnabled(), r.getMatchCriteria(), r.getStartsAt(), r.getEndsAt(),
                r.getDailyStart() == null ? null : r.getDailyStart().toString(), r.getDailyEnd() == null ? null : r.getDailyEnd().toString(),
                r.getDaysOfWeek(), r.isEnabled() && r.windowActive(clock.instant(), ingest.zone()), r.expired(clock.instant()), r.getMatchCount(),
                r.getLastMatchedAt(), r.getCreatedBy() == null ? null : r.getCreatedBy().getFullName(), r.isDemo(), r.getCreatedAt(), r.getUpdatedAt());
    }

    // ------------------------------------------------------------------ routing rules

    @Transactional(readOnly = true)
    public List<RoutingRuleDto> routingRules() {
        return routingRules.findAllByOrderBySortOrderAscIdAsc().stream().map(AlertRuleService::dto).toList();
    }

    @Transactional
    public RoutingRuleDto saveRouting(Long id, RoutingRuleRequest r, Actor actor) {
        AlertRoutingRule rule = id == null ? new AlertRoutingRule() : routingRules.findById(id).orElseThrow(() -> ApiException.notFound("Routing rule", id));
        MatchCriteria match = MatchCriteria.validated(r.match());
        rule.setName(r.name().trim());
        rule.setDescription(blank(r.description()));
        if (r.enabled() != null) {
            rule.setEnabled(r.enabled());
        }
        if (r.sortOrder() != null) {
            rule.setSortOrder(r.sortOrder());
        }
        rule.setMatchCriteria(match);
        rule.setTeam(r.teamId() == null || r.teamId() == 0L ? null : teamRouting.activeById(r.teamId())
                .orElseThrow(() -> ApiException.invalidField("teamId", "Team does not exist or is inactive")));
        rule.setService(r.serviceId() == null || r.serviceId() == 0L ? null : services.findById(r.serviceId())
                .orElseThrow(() -> ApiException.invalidField("serviceId", "Service does not exist")));
        rule.setCi(r.ciId() == null || r.ciId() == 0L ? null : cis.findById(r.ciId())
                .orElseThrow(() -> ApiException.invalidField("ciId", "Configuration item does not exist")));
        rule.setCreateIncident(Boolean.TRUE.equals(r.createIncident()));
        if (r.incidentMinSeverity() != null) {
            rule.setIncidentMinSeverity(r.incidentMinSeverity());
        }
        if (rule.getTeam() == null && rule.getService() == null && rule.getCi() == null && !rule.isCreateIncident()) {
            throw ApiException.invalidField("teamId", "A routing rule needs a team, a service, a CI or createIncident");
        }
        if (id == null) {
            rule.setCreatedBy(actor.isSystem() ? null : users.getReferenceById(actor.id()));
        }
        rule.setUpdatedAt(clock.instant());
        routingRules.saveAndFlush(rule);
        audit.record(id == null ? "ALERT_ROUTING_RULE_CREATED" : "ALERT_ROUTING_RULE_UPDATED", "ALERT_ROUTING_RULE", rule.getId(),
                Map.of("name", rule.getName(), "enabled", rule.isEnabled(), "createIncident", rule.isCreateIncident()));
        return dto(rule);
    }

    @Transactional
    public void deleteRouting(Long id) {
        AlertRoutingRule rule = routingRules.findById(id).orElseThrow(() -> ApiException.notFound("Routing rule", id));
        routingRules.delete(rule);
        audit.record("ALERT_ROUTING_RULE_DELETED", "ALERT_ROUTING_RULE", id, Map.of("name", rule.getName()));
    }

    static RoutingRuleDto dto(AlertRoutingRule r) {
        return new RoutingRuleDto(r.getId(), r.getName(), r.getDescription(), r.isEnabled(), r.getSortOrder(), r.getMatchCriteria(),
                r.getTeam() == null ? null : r.getTeam().getId(), r.getTeam() == null ? null : r.getTeam().getName(),
                r.getService() == null ? null : r.getService().getId(), r.getService() == null ? null : r.getService().getName(),
                r.getCi() == null ? null : r.getCi().getId(), r.getCi() == null ? null : r.getCi().getName(), r.isCreateIncident(), r.getIncidentMinSeverity(),
                r.getMatchCount(), r.getLastMatchedAt(), r.getCreatedBy() == null ? null : r.getCreatedBy().getFullName(), r.isDemo(), r.getCreatedAt(),
                r.getUpdatedAt());
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
