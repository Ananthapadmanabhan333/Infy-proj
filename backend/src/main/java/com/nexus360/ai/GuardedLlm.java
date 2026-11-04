package com.nexus360.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus360.config.AppProperties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Guarded structured-output calls for the Release 3 SA AI features: a hard deadline per attempt ({@code AI_TIMEOUT_SECONDS}), one
 * retry for transient failures (provider error, truncated or invalid output — never for refusals), the feature's own validation of
 * the parsed output, and a content guard that rejects any output containing SQL statements, shell or cloud/infrastructure commands
 * or code blocks (SA AI describes actions in words; it never generates SQL or commands). Every rejection is an
 * {@link LlmException}, so {@link AiService#runGrounded} answers with the labelled deterministic fallback.
 */
@Slf4j
@Component
public class GuardedLlm {

    public static final String NO_COMMANDS = "Never write SQL, database queries, scripts, shell, PowerShell, cloud-CLI, kubectl, Terraform or any "
            + "other commands or code; describe recommended actions in plain language for a human operator.";

    static final int ATTEMPTS = 2;

    private static final Pattern SQL = Pattern.compile("(?is)\\b(select\\s+(\\*|[\\w.]+(\\s*,\\s*[\\w.]+)*)\\s+from\\s+\\w|insert\\s+into\\s+\\w|update\\s+\\w+\\s+set\\s+\\w|"
            + "delete\\s+from\\s+\\w|drop\\s+(table|database|schema)\\b|alter\\s+table\\b|truncate\\s+table\\b|create\\s+(table|index|user)\\b|grant\\s+\\w+\\s+on\\b)");
    private static final Pattern COMMAND = Pattern.compile("(?i)(```|\\bsudo\\s|\\brm\\s+-[rf]|\\bkubectl\\s|\\bsystemctl\\s|\\bterraform\\s+(apply|destroy|plan)"
            + "|\\baws\\s+[a-z0-9-]+\\s+[a-z0-9-]+|\\baz\\s+[a-z]+\\s+[a-z]+|\\bgcloud\\s|\\bdocker\\s+(run|exec|rm|stop)|\\bchmod\\s|\\bchown\\s"
            + "|\\bssh\\s+\\S+@|\\bscp\\s|\\bcurl\\s+-|\\bwget\\s+http|\\bpsql\\s|\\bInvoke-|\\bGet-Service\\b|\\bStop-Service\\b|\\bRestart-Service\\b"
            + "|\\$\\(|&&|\\|\\s*(bash|sh)\\b)");

    private final LlmProvider llm;
    private final ObjectMapper json;
    private final int timeoutSeconds;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @org.springframework.beans.factory.annotation.Autowired
    public GuardedLlm(LlmProvider llm, ObjectMapper json, AppProperties properties) {
        this(llm, json, properties.ai() == null ? 30 : properties.ai().timeoutSeconds());
    }

    GuardedLlm(LlmProvider llm, ObjectMapper json, int timeoutSeconds) {
        this.llm = llm;
        this.json = json;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
    }

    /**
     * Calls the model with {@code system} (the SA AI prompt plus {@link #NO_COMMANDS} are added) and validates the parsed output with
     * {@code validator} (throw {@link LlmException} INVALID_OUTPUT to reject it).
     */
    public <T> T generate(String task, String user, Class<T> type, Consumer<T> validator) {
        String system = AiService.systemPrompt(task + " " + NO_COMMANDS);
        LlmException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                T out = call(system, user, type);
                if (out == null) {
                    throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "Empty model output");
                }
                validator.accept(out);
                guard(out);
                return out;
            } catch (LlmException e) {
                last = e;
                if (e.getReason() == LlmException.Reason.REFUSAL || e.getReason() == LlmException.Reason.NOT_CONFIGURED) {
                    throw e;
                }
                log.debug("SA AI attempt {} failed: {}", attempt, e.getMessage());
            }
        }
        throw last;
    }

    private <T> T call(String system, String user, Class<T> type) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> llm.generate(system, user, type), executor);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmException(LlmException.Reason.UPSTREAM_ERROR, "The model did not answer within " + timeoutSeconds + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Reason.UPSTREAM_ERROR, "Interrupted while waiting for the model");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof LlmException le) {
                throw le;
            }
            throw new LlmException(LlmException.Reason.UPSTREAM_ERROR, "Model call failed: " + e.getCause().getClass().getSimpleName(), e.getCause());
        }
    }

    /** Rejects output with SQL or commands anywhere in its text fields. */
    void guard(Object out) {
        JsonNode tree = json.valueToTree(out);
        check(tree);
    }

    private void check(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            if (containsCommand(node.asText())) {
                throw new LlmException(LlmException.Reason.INVALID_OUTPUT, "Output contained SQL or a command; SA AI does not generate them");
            }
        } else if (node.isContainerNode()) {
            node.forEach(this::check);
        }
    }

    /** True when the text looks like SQL or a shell/infrastructure command. */
    public static boolean containsCommand(String text) {
        return text != null && (SQL.matcher(text).find() || COMMAND.matcher(text).find());
    }
}
