package com.nexus360.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.admin.SettingsService;
import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.AiDtos.Source;
import com.nexus360.ai.AiInteractionRepository;
import com.nexus360.ai.AiService;
import com.nexus360.ai.FallbackEngine;
import com.nexus360.ai.LlmException;
import com.nexus360.ai.LlmProvider;
import com.nexus360.ai.ResolutionAdvisor;
import com.nexus360.ai.WorkflowSuggester;
import com.nexus360.analytics.AnalyticsService;
import com.nexus360.change.ChangeDtos.RiskAnalysis;
import com.nexus360.cmdb.BusinessServiceRepository;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** Change-risk analysis: validated LLM output is labelled LLM; invalid output or provider errors fall back, labelled FALLBACK. */
class ChangeAiAdvisorTest {

    private final LlmProvider llm = mock(LlmProvider.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final ChangeService changes = mock(ChangeService.class);
    private final Actor manager = new Actor(7L, "cm@x.io", "Change Manager", Role.CHANGE_MANAGER, 0);
    private ChangeAiAdvisor advisor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AiService ai = new AiService(llm, mock(FallbackEngine.class), settings, mock(IncidentRepository.class), mock(IncidentQueryService.class),
                mock(IncidentCommentRepository.class), mock(IncidentSlaRepository.class), mock(IncidentMapper.class), mock(BusinessServiceRepository.class),
                mock(KnowledgeService.class), mock(AnalyticsService.class), mock(SlaAdminService.class), mock(AiInteractionRepository.class),
                mock(ResolutionAdvisor.class), mock(WorkflowSuggester.class), new ObjectMapper(), Clock.systemUTC());
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        advisor = new ChangeAiAdvisor(ai, llm, changes, mock(NamedParameterJdbcTemplate.class), tx);
        Change c = new Change();
        c.setId(12L);
        c.setNumber("CHG-5100");
        c.setTitle("Upgrade the payroll database");
        c.setType(Change.Type.NORMAL);
        c.setRisk(Change.Risk.MEDIUM);
        c.setRiskScore(7);
        c.setRiskAnswers(Map.of("tested", "NO", "rollbackTested", "YES"));
        c.setRiskFactors(List.of(new ChangeRisk.Factor("tested", "Not tested", 4, "ANSWER")));
        when(changes.load(12L)).thenReturn(c);
        when(changes.conflicts(any(), any(), any())).thenReturn(List.of());
        when(settings.getBoolean(eq(SettingsService.AI_LLM_ENABLED), anyBoolean())).thenReturn(true);
        when(llm.name()).thenReturn("anthropic");
        when(llm.model()).thenReturn("claude-opus-5-5");
    }

    @Test
    void withoutProviderTheDeterministicModelAnswers() {
        when(llm.configured()).thenReturn(false);
        AiResult<RiskAnalysis> r = advisor.analyse(12L, manager);
        assertThat(r.source()).isEqualTo(Source.FALLBACK);
        assertThat(r.fallbackReason()).isEqualTo("No AI provider configured");
        assertThat(r.data().riskLevel()).isEqualTo(Change.Risk.MEDIUM);
        assertThat(r.data().score()).isEqualTo(7);
        assertThat(r.data().recommendations()).anyMatch(s -> s.contains("non-production"));
    }

    @Test
    void invalidLlmOutputFallsBackAndIsLabelled() {
        when(llm.configured()).thenReturn(true);
        ChangeAiAdvisor.ChangeRiskOut out = new ChangeAiAdvisor.ChangeRiskOut();
        out.riskLevel = "CATASTROPHIC";
        out.recommendations = List.of("Pray");
        when(llm.generate(anyString(), anyString(), eq(ChangeAiAdvisor.ChangeRiskOut.class))).thenReturn(out);
        AiResult<RiskAnalysis> r = advisor.analyse(12L, manager);
        assertThat(r.source()).isEqualTo(Source.FALLBACK);
        assertThat(r.fallbackReason()).isEqualTo("The LLM response failed validation");
        assertThat(r.notice()).contains("No LLM was used");
    }

    @Test
    void providerErrorsFallBack() {
        when(llm.configured()).thenReturn(true);
        when(llm.generate(anyString(), anyString(), eq(ChangeAiAdvisor.ChangeRiskOut.class)))
                .thenThrow(new LlmException(LlmException.Reason.UPSTREAM_ERROR, "timeout"));
        assertThat(advisor.analyse(12L, manager).fallbackReason()).isEqualTo("The LLM provider was unavailable");
    }

    @Test
    void validLlmOutputIsLabelledAndKeepsTheComputedScore() {
        when(llm.configured()).thenReturn(true);
        ChangeAiAdvisor.ChangeRiskOut out = new ChangeAiAdvisor.ChangeRiskOut();
        out.riskLevel = "high";
        out.concerns = List.of("Database upgrade without a tested migration");
        out.recommendations = List.of("Rehearse the migration on a copy of production");
        when(llm.generate(anyString(), anyString(), eq(ChangeAiAdvisor.ChangeRiskOut.class))).thenReturn(out);
        AiResult<RiskAnalysis> r = advisor.analyse(12L, manager);
        assertThat(r.source()).isEqualTo(Source.LLM);
        assertThat(r.notice()).contains("anthropic");
        assertThat(r.data().riskLevel()).isEqualTo(Change.Risk.HIGH);
        assertThat(r.data().score()).isEqualTo(7);
        assertThat(r.data().factors()).anyMatch(f -> "AI".equals(f.source()));
    }
}
