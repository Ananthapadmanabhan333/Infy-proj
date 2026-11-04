package com.nexus360.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus360.common.ApiException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AttachmentValidationTest {

    @Test
    void fileNamesAreSanitised() {
        assertThat(AttachmentService.sanitizeFileName("..\\..\\windows\\system32\\evil.log")).isEqualTo("evil.log");
        assertThat(AttachmentService.sanitizeFileName("/etc/passwd")).isEqualTo("passwd");
        assertThat(AttachmentService.sanitizeFileName(".hidden")).isEqualTo("hidden");
        assertThat(AttachmentService.sanitizeFileName("a<b>:c?.txt")).isEqualTo("a_b__c_.txt");
        assertThat(AttachmentService.sanitizeFileName(null)).isEqualTo("attachment");
    }

    @Test
    void contentTypeComesFromSignatureNotClient() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A};
        assertThat(AttachmentService.detectContentType("screen.png", png)).isEqualTo("image/png");
        assertThat(AttachmentService.detectContentType("server.log", "ERROR 503 at 10:02".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("text/plain");
    }

    @Test
    void mismatchedOrDangerousFilesAreRejected() {
        byte[] exe = {'M', 'Z', 0, 0};
        assertThatThrownBy(() -> AttachmentService.detectContentType("tool.exe", exe)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AttachmentService.detectContentType("fake.png", exe)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AttachmentService.detectContentType("page.html", "<script>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AttachmentService.detectContentType("binary.txt", new byte[]{'a', 0, 'b'})).isInstanceOf(ApiException.class);
    }

    @Test
    void stateMachineRulesForEmployees() {
        Incident incident = new Incident();
        incident.setStatus(IncidentStatus.NEW);
        com.nexus360.user.User requester = new com.nexus360.user.User();
        requester.setId(10L);
        incident.setRequester(requester);
        var employee = new com.nexus360.security.Actor(10L, "e@x.io", "E", com.nexus360.user.Role.EMPLOYEE, 0);
        var stranger = new com.nexus360.security.Actor(11L, "s@x.io", "S", com.nexus360.user.Role.EMPLOYEE, 0);
        var agent = new com.nexus360.security.Actor(12L, "a@x.io", "A", com.nexus360.user.Role.SUPPORT_AGENT, 0);
        assertThat(IncidentStateMachine.allowedFor(incident, employee)).containsExactly(IncidentStatus.CLOSED); // cancel
        assertThat(IncidentStateMachine.allowedFor(incident, stranger)).isEmpty();
        assertThat(IncidentStateMachine.allowedFor(incident, agent))
                .containsExactly(IncidentStatus.IN_PROGRESS, IncidentStatus.PENDING, IncidentStatus.RESOLVED, IncidentStatus.CLOSED);
        incident.setStatus(IncidentStatus.RESOLVED);
        assertThat(IncidentStateMachine.allowedFor(incident, employee)).containsExactly(IncidentStatus.CLOSED, IncidentStatus.REOPENED);
        assertThat(IncidentStateMachine.isAllowed(IncidentStatus.CLOSED, IncidentStatus.IN_PROGRESS)).isFalse();
    }
}
