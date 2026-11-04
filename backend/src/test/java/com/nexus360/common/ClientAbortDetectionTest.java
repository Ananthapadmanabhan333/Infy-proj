package com.nexus360.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class ClientAbortDetectionTest {

    @Test
    void recognisesDisconnectedClientsAnywhereInTheCauseChain() {
        assertThat(GlobalExceptionHandler.isClientAbort(new AsyncRequestNotUsableException("Response not usable"))).isTrue();
        assertThat(GlobalExceptionHandler.isClientAbort(new IOException("Broken pipe"))).isTrue();
        assertThat(GlobalExceptionHandler.isClientAbort(
                new IllegalStateException("send failed", new IOException("An established connection was aborted by the software in your host machine"))))
                .isTrue();
        assertThat(GlobalExceptionHandler.isClientAbort(new IOException("Connection reset by peer"))).isTrue();
    }

    @Test
    void realFailuresAreStillReportedAsErrors() {
        assertThat(GlobalExceptionHandler.isClientAbort(new IllegalStateException("Boom"))).isFalse();
        assertThat(GlobalExceptionHandler.isClientAbort(new IOException("Disk full"))).isFalse();
        assertThat(GlobalExceptionHandler.isClientAbort(new RuntimeException((String) null))).isFalse();
    }
}
