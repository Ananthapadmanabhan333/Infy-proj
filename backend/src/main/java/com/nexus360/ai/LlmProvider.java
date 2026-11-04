package com.nexus360.ai;

/**
 * Abstraction over an external large language model. Implementations return output parsed into {@code type}
 * (a structured-output schema class) or throw {@link LlmException}; callers then fall back to the
 * deterministic engine and label the result accordingly.
 */
public interface LlmProvider {

    /** Provider id shown in the UI, e.g. "anthropic". */
    String name();

    String model();

    /** True when credentials are present and the provider can be called. */
    boolean configured();

    <T> T generate(String system, String user, Class<T> type);
}
