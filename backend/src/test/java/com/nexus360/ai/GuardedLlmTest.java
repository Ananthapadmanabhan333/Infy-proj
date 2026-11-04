package com.nexus360.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.admin.SettingsService;
import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.AiDtos.Source;
import com.nexus360.ai.OpsAiDtos.CmdbImpact;
import com.nexus360.ai.OpsAiDtos.CmdbImpactOut;
import com.nexus360.analytics.AnalyticsService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.cmdb.CmdbDtos.CiRef;
import com.nexus360.cmdb.CmdbDtos.ImpactAnalysis;
import com.nexus360.cmdb.CmdbDtos.ImpactNode;
import com.nexus360.cmdb.CmdbDtos.ImpactedService;
import com.nexus360.cmdb.CmdbDtos.RelationshipType;
import com.nexus360.cmdb.CmdbGraphService;
import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.incident.IncidentCommentRepository;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.knowledge.KnowledgeService;
import com.nexus360.security.Actor;
import com.nexus360.sla.IncidentSlaRepository;
import com.nexus360.sla.SlaAdminService;
import com.nexus360.user.Role;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Guarded structured output for the Release 3 SA AI features: validation, one retry for transient failures (never for refusals), a hard
 * timeout, and the content guard that rejects SQL and commands; and the labelling of LLM vs deterministic results (CMDB impact).
 */
class GuardedLlmTest {

    private final LlmProvider llm = mock(LlmProvider.class);
    private final SettingsService settings = mock(SettingsService.class);
    private GuardedLlm guarded;

    @BeforeEach
    void setUp() {
        guarded = new GuardedLlm(llm, new ObjectMapper(), 1);
        when(llm.name()).thenReturn("anthropic");
        when(llm.model()).thenReturn("claude-opus-5-5");
        when(settings.getBoolean(eq(SettingsService.AI_LLM_ENABLED), anyBoolean())).thenReturn(true);
    }

    private static CmdbImpactOut out(String summary, String... recommendations) {
        CmdbImpactOut o = new CmdbImpactOut();
        o.summary = summary;
        o.recommendations = List.of(recommendations);
        return o;
    }

    private static void requireRecommendation(CmdbImpactOut o) {
        if (o.recommendations == null || o.recommendations.isEmpty()) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "No recommendations");
        }
    }

    @Test
    void validOutputIsReturnedAndInvalidOutputIsRetriedOnce() {
        when(llm.generate(anyString(), anyString(), eq(CmdbImpactOut.class))).thenReturn(out("ok"), out("ok", "Inform the service owners"));
        CmdbImpactOut result = guarded.generate("task", "data", CmdbImpactOut.class, GuardedLlmTest::requireRecommendation);
        assertThat(result.recommendations).containsExactly("Inform the service owners");
        verify(llm, times(2)).generate(anyString(), anyString(), eq(CmdbImpactOut.class));
    }

    @Test
    void persistentInvalidOutputFailsAfterTwoAttemptsAndRefusalsAreNotRetried() {
        when(llm.generate(anyString(), anyString(), eq(CmdbImpactOut.class))).thenReturn(out("ok"));
        assertThatThrownBy(() -> guarded.generate("task", "data", CmdbImpactOut.class, GuardedLlmTest::requireRecommendation))
                .isInstanceOf(LlmException.class).extracting("reason").isEqualTo(LlmException.Reason.INVALID_OUTPUT);
        verify(llm, times(2)).generate(anyString(), anyString(), eq(CmdbImpactOut.class));

        LlmProvider refusing = mock(LlmProvider.class);
        when(refusing.generate(anyString(), anyString(), eq(CmdbImpactOut.class))).thenThrow(new LlmException(LlmException.Reason.REFUSAL, "no"));
        GuardedLlm g = new GuardedLlm(refusing, new ObjectMapper(), 1);
        assertThatThrownBy(() -> g.generate("task", "data", CmdbImpactOut.class, o -> { })).extracting("reason").isEqualTo(LlmException.Reason.REFUSAL);
        verify(refusing, times(1)).generate(anyString(), anyString(), eq(CmdbImpactOut.class));
    }

    @Test
    void slowProvidersTimeOut() {
        AtomicInteger calls = new AtomicInteger();
        when(llm.generate(anyString(), anyString(), eq(CmdbImpactOut.class))).thenAnswer(inv -> {
            calls.incrementAndGet();
            Thread.sleep(5_000);
            return out("late", "x");
        });
        long started = System.nanoTime();
        assertThatThrownBy(() -> guarded.generate("task", "data", CmdbImpactOut.class, o -> { }))
                .isInstanceOf(LlmException.class).extracting("reason").isEqualTo(LlmException.Reason.UPSTREAM_ERROR);
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(4_500); // two attempts of 1 s each, not 10 s
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void outputWithSqlOrCommandsIsRejected() {
        assertThat(GuardedLlm.containsCommand("Run SELECT id FROM users WHERE active")).isTrue();
        assertThat(GuardedLlm.containsCommand("DROP TABLE incidents")).isTrue();
        assertThat(GuardedLlm.containsCommand("update alerts set status = 'RESOLVED'")).isTrue();
        assertThat(GuardedLlm.containsCommand("Execute kubectl rollout restart deploy/payroll")).isTrue();
        assertThat(GuardedLlm.containsCommand("sudo systemctl restart payroll")).isTrue();
        assertThat(GuardedLlm.containsCommand("```bash\nrestart\n```")).isTrue();
        assertThat(GuardedLlm.containsCommand("aws ec2 reboot-instances --instance-ids i-1")).isTrue();
        assertThat(GuardedLlm.containsCommand("Restart the payroll application nodes one at a time during the approved window")).isFalse();
        assertThat(GuardedLlm.containsCommand("Select the most recent change and update the stakeholders from the bridge")).isFalse();

        when(llm.generate(anyString(), anyString(), eq(CmdbImpactOut.class))).thenReturn(out("Impact", "Run DELETE FROM sessions to clear them"));
        assertThatThrownBy(() -> guarded.generate("task", "data", CmdbImpactOut.class, GuardedLlmTest::requireRecommendation))
                .isInstanceOf(LlmException.class).hasMessageContaining("SQL or a command");
    }

    @Test
    void cmdbImpactIsLabelledByEngineAndKeepsTheComputedRisk() {
        AiService ai = new AiService(llm, mock(FallbackEngine.class), settings, mock(IncidentRepository.class), mock(IncidentQueryService.class),
                mock(IncidentCommentRepository.class), mock(IncidentSlaRepository.class), mock(IncidentMapper.class), mock(BusinessServiceRepository.class),
                mock(KnowledgeService.class), mock(AnalyticsService.class), mock(SlaAdminService.class), mock(AiInteractionRepository.class),
                mock(ResolutionAdvisor.class), mock(WorkflowSuggester.class), new ObjectMapper(), Clock.systemUTC());
        CmdbGraphService cmdb = mock(CmdbGraphService.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CiRef db = new CiRef(5L, "payroll-db-primary", ConfigurationItem.CiType.DATABASE, ConfigurationItem.Status.ACTIVE,
                ConfigurationItem.Environment.PRODUCTION);
        CiRef san = new CiRef(6L, "san-array-01", ConfigurationItem.CiType.INFRASTRUCTURE, ConfigurationItem.Status.ACTIVE,
                ConfigurationItem.Environment.PRODUCTION);
        when(cmdb.impact(5L, "both", 4)).thenReturn(new ImpactAnalysis(db, "both", 4,
                List.of(new ImpactNode(san, ConfigurationItem.Criticality.CRITICAL, 1, RelationshipType.RUNS_ON, 5L)), List.of(),
                List.of(new ImpactedService(9L, 1L, "PAYROLL", "Payroll", 1, "DEGRADED")), List.of(), List.of(), false));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(String.class))).thenReturn("HIGH");
        OpsAiAdvisor advisor = new OpsAiAdvisor(ai, guarded, jdbc, mock(TransactionTemplate.class), cmdb, mock(IncidentSlaRepository.class),
                mock(IncidentMapper.class), settings, Clock.systemUTC());
        Actor actor = new Actor(3L, "a@x.io", "Agent", Role.SUPPORT_AGENT, 0);

        when(llm.configured()).thenReturn(false);
        AiResult<CmdbImpact> fallback = advisor.cmdbImpact(5L, actor);
        assertThat(fallback.source()).isEqualTo(Source.FALLBACK);
        assertThat(fallback.fallbackReason()).isEqualTo("No AI provider configured");
        assertThat(fallback.notice()).contains("No LLM was used");
        assertThat(fallback.data().riskLevel()).isEqualTo("HIGH"); // tier-1 Payroll depends on it
        assertThat(fallback.data().criticalUpstream()).containsExactly("san-array-01 (INFRASTRUCTURE)");
        assertThat(fallback.data().basis()).startsWith("Deterministic");

        when(llm.configured()).thenReturn(true);
        when(llm.generate(anyString(), anyString(), eq(CmdbImpactOut.class))).thenReturn(out("Payroll stops when the database fails.",
                "Fail over to the replica before maintenance"));
        AiResult<CmdbImpact> viaLlm = advisor.cmdbImpact(5L, actor);
        assertThat(viaLlm.source()).isEqualTo(Source.LLM);
        assertThat(viaLlm.data().summary()).isEqualTo("Payroll stops when the database fails.");
        assertThat(viaLlm.data().riskLevel()).isEqualTo("HIGH");
        assertThat(viaLlm.data().impactedServices()).hasSize(1);

        // A model answer with a command is rejected (twice) and the deterministic result is returned, labelled.
        when(llm.generate(anyString(), anyString(), eq(CmdbImpactOut.class))).thenReturn(out("x", "Run sudo systemctl stop postgresql"));
        AiResult<CmdbImpact> guardedResult = advisor.cmdbImpact(5L, actor);
        assertThat(guardedResult.source()).isEqualTo(Source.FALLBACK);
        assertThat(guardedResult.fallbackReason()).isEqualTo("The LLM response failed validation");
        assertThat(guardedResult.data().recommendations()).noneMatch(GuardedLlm::containsCommand);
    }
}
