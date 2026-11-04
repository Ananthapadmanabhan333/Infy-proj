package com.nexus360.ai;

import com.nexus360.config.AppProperties;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class LlmProviderConfig {

    /** Provider used when AI_PROVIDER is "none" or unknown: always unconfigured, so the fallback engine answers. */
    static final class DisabledProvider implements LlmProvider {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public String model() {
            return null;
        }

        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public <T> T generate(String system, String user, Class<T> type) {
            throw new LlmException(LlmException.Reason.NOT_CONFIGURED, "No AI provider configured");
        }
    }

    @Bean
    LlmProvider llmProvider(AppProperties properties) {
        String provider = properties.ai().provider().trim().toLowerCase(Locale.ROOT);
        if ("anthropic".equals(provider)) {
            AnthropicLlmProvider anthropic = new AnthropicLlmProvider(properties.ai());
            if (!anthropic.configured()) {
                log.warn("AI_PROVIDER=anthropic but AI_API_KEY is not set; SA AI will use the deterministic fallback engine");
            }
            return anthropic;
        }
        if (!"none".equals(provider)) {
            log.warn("Unknown AI_PROVIDER '{}'; SA AI will use the deterministic fallback engine", provider);
        }
        return new DisabledProvider();
    }
}
