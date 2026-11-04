package com.nexus360.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.beta.messages.StructuredMessage;
import com.anthropic.models.beta.messages.StructuredMessageCreateParams;
import com.nexus360.config.AppProperties;
import java.time.Duration;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

/**
 * Claude via the official Anthropic Java SDK. Uses structured outputs (JSON schema derived from the target class,
 * validated locally) and opts into server-side refusal fallbacks ({@code fallbacks: "default"}), so a request
 * declined by the primary model is retried by the API on Anthropic's recommended fallback model.
 */
@Slf4j
public class AnthropicLlmProvider implements LlmProvider {

    public static final String DEFAULT_MODEL = "claude-opus-5-5";

    private final AppProperties.Ai config;
    private final String model;
    private final BetaOutputConfig.Effort effort;
    private volatile AnthropicClient client;

    public AnthropicLlmProvider(AppProperties.Ai config) {
        this.config = config;
        this.model = config.model() == null || config.model().isBlank() ? DEFAULT_MODEL : config.model().trim();
        this.effort = switch (config.effort() == null ? "low" : config.effort().trim().toLowerCase(Locale.ROOT)) {
            case "medium" -> BetaOutputConfig.Effort.MEDIUM;
            case "high" -> BetaOutputConfig.Effort.HIGH;
            default -> BetaOutputConfig.Effort.LOW;
        };
    }

    @Override
    public String name() {
        return "anthropic";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean configured() {
        return config.hasApiKey();
    }

    @Override
    public <T> T generate(String system, String user, Class<T> type) {
        if (!configured()) {
            throw new LlmException(LlmException.Reason.NOT_CONFIGURED, "No Anthropic API key configured");
        }
        StructuredMessageCreateParams<T> params = buildParams(system, user, type);
        StructuredMessage<T> response;
        try {
            response = client().beta().messages().create(params);
        } catch (AnthropicException e) {
            throw new LlmException(LlmException.Reason.UPSTREAM_ERROR, e.getClass().getSimpleName() + ": " + safeMessage(e), e);
        }
        BetaStopReason stop = response.stopReason().orElse(null);
        if (BetaStopReason.REFUSAL.equals(stop)) {
            throw new LlmException(LlmException.Reason.REFUSAL, "The model declined this request");
        }
        if (BetaStopReason.MAX_TOKENS.equals(stop)) {
            throw new LlmException(LlmException.Reason.TRUNCATED, "The model response was truncated");
        }
        try {
            return response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(text -> text.text())
                    .findFirst()
                    .orElseThrow(() -> new LlmException(LlmException.Reason.INVALID_OUTPUT, "The model returned no structured output"));
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "The model output did not match the expected schema", e);
        }
    }

    /** Builds (and locally validates the schema of) a request; exposed for tests that must not call the network. */
    <T> StructuredMessageCreateParams<T> buildParams(String system, String user, Class<T> type) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(config.maxTokens())
                .system(system)
                .addUserMessage(user)
                .outputConfig(type, effort, JsonSchemaLocalValidation.YES)
                .fallbacksDefault()
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .build();
    }

    private AnthropicClient client() {
        AnthropicClient c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                            .apiKey(config.apiKey())
                            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                            .maxRetries(1);
                    if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
                        builder.baseUrl(config.baseUrl().trim());
                    }
                    client = builder.build();
                    log.info("Anthropic provider initialised with model {}", model);
                }
                c = client;
            }
        }
        return c;
    }

    private static String safeMessage(Exception e) {
        String m = e.getMessage();
        if (m == null) {
            return "request failed";
        }
        return m.length() > 300 ? m.substring(0, 300) : m;
    }
}
