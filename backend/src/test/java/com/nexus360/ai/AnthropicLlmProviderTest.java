package com.nexus360.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus360.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Builds real SDK requests offline: the SDK validates each structured-output schema locally at build time. */
class AnthropicLlmProviderTest {

    private static AnthropicLlmProvider provider(String apiKey) {
        return new AnthropicLlmProvider(new AppProperties.Ai("anthropic", apiKey, null, null, 30, 8000, "low"));
    }

    @ParameterizedTest
    @ValueSource(classes = {LlmSchemas.Classification.class, LlmSchemas.Summary.class, LlmSchemas.Ranking.class,
            LlmSchemas.RootCause.class, LlmSchemas.Insights.class, LlmSchemas.Answer.class})
    void everySchemaProducesAValidStructuredOutputRequest(Class<?> schema) {
        var params = provider("test-key").buildParams("system", "user", schema);
        assertThat(params).isNotNull();
    }

    @Test
    void defaultsToCurrentOpusModel() {
        assertThat(provider("k").model()).isEqualTo("claude-opus-5-5");
        assertThat(provider("k").configured()).isTrue();
    }

    @Test
    void missingKeyIsReportedAsNotConfigured() {
        AnthropicLlmProvider p = provider("");
        assertThat(p.configured()).isFalse();
        assertThatThrownBy(() -> p.generate("s", "u", LlmSchemas.Summary.class))
                .isInstanceOf(LlmException.class)
                .extracting(e -> ((LlmException) e).getReason()).isEqualTo(LlmException.Reason.NOT_CONFIGURED);
    }
}
