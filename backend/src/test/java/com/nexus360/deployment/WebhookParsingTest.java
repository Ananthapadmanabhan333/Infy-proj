package com.nexus360.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus360.change.Change;
import com.nexus360.change.ChangeRisk;
import com.nexus360.cmdb.BusinessService;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Helpers of the webhook parser and the change risk model. */
class WebhookParsingTest {

    @Test
    void environmentsVersionsAndChangeNumbersAreRecognised() {
        assertThat(DeploymentWebhookService.environment("production")).isEqualTo(Deployment.Environment.PRODUCTION);
        assertThat(DeploymentWebhookService.environment("prod-eu")).isEqualTo(Deployment.Environment.PRODUCTION);
        assertThat(DeploymentWebhookService.environment("uat")).isEqualTo(Deployment.Environment.STAGING);
        assertThat(DeploymentWebhookService.environment("qa")).isEqualTo(Deployment.Environment.TEST);
        assertThat(DeploymentWebhookService.environment("development")).isEqualTo(Deployment.Environment.DEV);
        assertThat(DeploymentWebhookService.environment("review/feature-x")).isNull();
        assertThat(DeploymentWebhookService.version("abcdef1234", "v2.4.1")).isEqualTo("2.4.1");
        assertThat(DeploymentWebhookService.version("abcdef1234", null, "Release 3.0.0-rc.1 notes")).isEqualTo("3.0.0-rc.1");
        assertThat(DeploymentWebhookService.version("abcdef1234", "main")).isEqualTo("abcdef1");
        assertThat(DeploymentWebhookService.changeNumber("Fix (CHG-5012)", null)).isEqualTo("CHG-5012");
        assertThat(DeploymentWebhookService.changeNumber("no change")).isNull();
        // Known HMAC-SHA256 test vector (RFC 4231 style key/data).
        assertThat(DeploymentWebhookService.hmacHex("key", "The quick brown fox jumps over the lazy dog".getBytes()))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void riskModelScoresQuestionnaireAndComputedFactors() {
        BusinessService tier1 = new BusinessService();
        tier1.setTier(1);
        Map<String, String> risky = Map.of("userImpact", "ALL", "tested", "NO", "rollbackTested", "NOT_POSSIBLE", "downtime", "HOURS",
                "complexity", "HIGH", "experience", "NO");
        ChangeRisk.Assessment high = ChangeRisk.assess(Change.Type.NORMAL, risky, List.of(tier1), Instant.parse("2026-11-04T05:30:00Z"),
                ZoneId.of("Asia/Kolkata"), 1);
        assertThat(high.risk()).isEqualTo(Change.Risk.HIGH);
        assertThat(high.factors()).extracting(ChangeRisk.Factor::key).contains("tier", "businessHours", "history");
        ChangeRisk.Assessment standard = ChangeRisk.assess(Change.Type.STANDARD, risky, List.of(tier1), null, ZoneId.of("UTC"), 3);
        assertThat(standard.risk()).isEqualTo(Change.Risk.LOW);
        // Unanswered questions count with their middle option, so an unassessed change is never risk-free.
        ChangeRisk.Assessment unanswered = ChangeRisk.assess(Change.Type.NORMAL, Map.of(), List.of(), null, ZoneId.of("UTC"), 0);
        assertThat(unanswered.score()).isEqualTo(2 + 2 + 2 + 1 + 2 + 2);
        assertThat(ChangeRisk.level(4)).isEqualTo(Change.Risk.LOW);
        assertThat(ChangeRisk.level(5)).isEqualTo(Change.Risk.MEDIUM);
        assertThat(ChangeRisk.level(10)).isEqualTo(Change.Risk.HIGH);
    }
}
