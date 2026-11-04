package com.nexus360.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.admin.SettingsService;
import com.nexus360.ai.AiDtos.AiResult;
import com.nexus360.ai.AiDtos.ClassificationSuggestion;
import com.nexus360.ai.AiDtos.Source;
import com.nexus360.analytics.AnalyticsService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.incident.Category;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentCommentRepository;
import com.nexus360.incident.IncidentMapper;
import com.nexus360.incident.IncidentQueryService;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.incident.IncidentStatus;
import com.nexus360.incident.Level;
import com.nexus360.incident.Priority;
import com.nexus360.knowledge.KnowledgeService;
import com.nexus360.security.Actor;
import com.nexus360.sla.IncidentSlaRepository;
import com.nexus360.sla.SlaAdminService;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** SA AI must label its source honestly and fall back on every LLM failure mode. */
class AiServiceFallbackTest {

    private final LlmProvider llm = mock(LlmProvider.class);
    private final FallbackEngine fallback = mock(FallbackEngine.class);
    private final SettingsService settings = mock(SettingsService.class);
    private final IncidentQueryService queries = mock(IncidentQueryService.class);
    private final BusinessServiceRepository services = mock(BusinessServiceRepository.class);
    private AiService ai;
    private final Actor agent = new Actor(5L, "agent@x.io", "Agent", com.nexus360.user.Role.SUPPORT_AGENT, 0);
    private final ClassificationSuggestion ruleResult = new ClassificationSuggestion(Category.APPLICATION, 1L, "Payroll", Level.MEDIUM,
            Level.HIGH, Priority.HIGH, 0.8, "Rule-based");

    @BeforeEach
    void setUp() {
        ai = new AiService(llm, fallback, settings, mock(IncidentRepository.class), queries, mock(IncidentCommentRepository.class),
                mock(IncidentSlaRepository.class), mock(IncidentMapper.class), services, mock(KnowledgeService.class),
                mock(AnalyticsService.class), mock(SlaAdminService.class), mock(AiInteractionRepository.class), mock(ResolutionAdvisor.class),
                mock(WorkflowSuggester.class), new ObjectMapper(), Clock.systemUTC());
        Incident incident = new Incident();
        incident.setId(1L);
        incident.setNumber("INC-1");
        incident.setTitle("Payroll down");
        incident.setCategory(Category.APPLICATION);
        incident.setPriority(Priority.HIGH);
        incident.setImpact(Level.MEDIUM);
        incident.setUrgency(Level.HIGH);
        incident.setStatus(IncidentStatus.NEW);
        when(queries.load(1L, agent)).thenReturn(incident);
        BusinessService payroll = new BusinessService();
        payroll.setId(1L);
        payroll.setCode("PAYROLL");
        payroll.setName("Payroll");
        when(services.findAllByOrderByTierAscNameAsc()).thenReturn(List.of(payroll));
        when(fallback.classify(incident)).thenReturn(ruleResult);
        when(settings.getBoolean(eq(SettingsService.AI_LLM_ENABLED), anyBoolean())).thenReturn(true);
        when(llm.name()).thenReturn("anthropic");
        when(llm.model()).thenReturn("claude-opus-5-5");
    }

    @Test
    void noProviderConfiguredUsesLabelledFallback() {
        when(llm.configured()).thenReturn(false);
        AiResult<ClassificationSuggestion> result = ai.classify(1L, agent);
        assertThat(result.source()).isEqualTo(Source.FALLBACK);
        assertThat(result.notice()).contains("No LLM was used");
        assertThat(result.fallbackReason()).isEqualTo("No AI provider configured");
        assertThat(result.data()).isEqualTo(ruleResult);
        verify(llm, never()).generate(anyString(), anyString(), any());
    }

    @Test
    void refusalFallsBackWithReason() {
        when(llm.configured()).thenReturn(true);
        when(llm.generate(anyString(), anyString(), eq(LlmSchemas.Classification.class)))
                .thenThrow(new LlmException(LlmException.Reason.REFUSAL, "declined"));
        AiResult<ClassificationSuggestion> result = ai.classify(1L, agent);
        assertThat(result.source()).isEqualTo(Source.FALLBACK);
        assertThat(result.fallbackReason()).isEqualTo("The LLM declined this request");
    }

    @Test
    void invalidModelOutputIsRejected() {
        when(llm.configured()).thenReturn(true);
        LlmSchemas.Classification bogus = new LlmSchemas.Classification();
        bogus.category = "TELEPORTATION";
        bogus.serviceCode = "PAYROLL";
        bogus.impact = "HIGH";
        bogus.urgency = "HIGH";
        bogus.rationale = "x";
        when(llm.generate(anyString(), anyString(), eq(LlmSchemas.Classification.class))).thenReturn(bogus);
        AiResult<ClassificationSuggestion> result = ai.classify(1L, agent);
        assertThat(result.source()).isEqualTo(Source.FALLBACK);
        assertThat(result.fallbackReason()).isEqualTo("The LLM response failed validation");
    }

    @Test
    void validModelOutputIsLabelledAsLlm() {
        when(llm.configured()).thenReturn(true);
        LlmSchemas.Classification out = new LlmSchemas.Classification();
        out.category = "application";
        out.serviceCode = "PAYROLL";
        out.impact = "HIGH";
        out.urgency = "HIGH";
        out.confidence = 0.9;
        out.rationale = "Multiple users cannot reach payroll";
        when(llm.generate(anyString(), anyString(), eq(LlmSchemas.Classification.class))).thenReturn(out);
        AiResult<ClassificationSuggestion> result = ai.classify(1L, agent);
        assertThat(result.source()).isEqualTo(Source.LLM);
        assertThat(result.model()).isEqualTo("claude-opus-5-5");
        assertThat(result.notice()).contains("review it before acting");
        assertThat(result.data().priority()).isEqualTo(Priority.CRITICAL);
        assertThat(result.data().serviceName()).isEqualTo("Payroll");
    }

    @Test
    void disabledInSettingsSkipsTheLlm() {
        when(llm.configured()).thenReturn(true);
        when(settings.getBoolean(eq(SettingsService.AI_LLM_ENABLED), anyBoolean())).thenReturn(false);
        AiResult<ClassificationSuggestion> result = ai.classify(1L, agent);
        assertThat(result.source()).isEqualTo(Source.FALLBACK);
        assertThat(result.fallbackReason()).contains("disabled");
        verify(llm, never()).generate(anyString(), anyString(), any());
    }
}
