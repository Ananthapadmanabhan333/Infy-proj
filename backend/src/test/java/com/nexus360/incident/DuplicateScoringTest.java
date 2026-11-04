package com.nexus360.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus360.cmdb.BusinessService;
import org.junit.jupiter.api.Test;

/** Calibration of duplicate scoring against the 0.48 auto-link and 0.30 suggestion thresholds. */
class DuplicateScoringTest {

    private static final double AUTO_LINK = 0.48;
    private static final double SUGGEST = 0.30;

    private final BusinessService payroll = PriorityAndClassificationTest.service(1L, "PAYROLL", "Payroll", "payroll", Category.APPLICATION);
    private final BusinessService vpn = PriorityAndClassificationTest.service(2L, "VPN", "VPN", "vpn", Category.NETWORK);

    @Test
    void paraphrasedOutageReportsOnSameServiceAreLinked() {
        Incident first = incident("Payroll portal not loading",
                "Since about 10 am the payroll portal keeps spinning and then shows error 503. I need to submit my reimbursement claim today.", payroll);
        double score = DuplicateDetector.score("Cannot access payroll application - error 503",
                "Getting HTTP 503 Service Unavailable when opening payroll. Colleagues in my team have the same problem.",
                payroll, Category.APPLICATION, first);
        assertThat(score).isGreaterThanOrEqualTo(AUTO_LINK);
    }

    @Test
    void unrelatedIssueOnSameServiceIsNotAutoLinked() {
        Incident outage = incident("Payroll portal not loading", "The payroll portal keeps spinning and then shows error 503.", payroll);
        double score = DuplicateDetector.score("Payroll report shows wrong tax deduction",
                "My tax deduction for October is incorrect.", payroll, Category.APPLICATION, outage);
        assertThat(score).isLessThan(AUTO_LINK);
    }

    @Test
    void sameWordingOnDifferentServicesIsAtMostASuggestion() {
        Incident vpnDown = incident("Service down since morning", "Getting a timeout error", vpn);
        double score = DuplicateDetector.score("Service down since morning", "Getting a timeout error", payroll, Category.APPLICATION, vpnDown);
        assertThat(score).isLessThan(AUTO_LINK).isLessThanOrEqualTo(SUGGEST);
    }

    @Test
    void identicalReportsWithoutServiceAreLinked() {
        Incident a = incident("Shared drive mapping lost after reboot", "Drive Z is missing after reboot", null);
        a.setCategory(Category.OTHER);
        double score = DuplicateDetector.score("Shared drive mapping lost after reboot", "Drive Z is missing after reboot", null, Category.OTHER, a);
        assertThat(score).isGreaterThanOrEqualTo(AUTO_LINK);
    }

    @Test
    void symptomsAreDetected() {
        assertThat(DuplicateDetector.symptoms("Portal is down, 503")).contains(DuplicateDetector.Symptom.OUTAGE);
        assertThat(DuplicateDetector.symptoms("VPN is very slow")).containsExactly(DuplicateDetector.Symptom.SLOW);
        assertThat(DuplicateDetector.symptoms("wrong amount on payslip")).containsExactly(DuplicateDetector.Symptom.DATA);
    }

    private static Incident incident(String title, String description, BusinessService service) {
        Incident i = new Incident();
        i.setTitle(title);
        i.setDescription(description);
        i.setService(service);
        i.setCategory(service == null ? Category.OTHER : service.getDefaultCategory());
        return i;
    }
}
