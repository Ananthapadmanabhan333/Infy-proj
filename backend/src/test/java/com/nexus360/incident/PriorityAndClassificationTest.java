package com.nexus360.incident;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus360.cmdb.BusinessService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PriorityAndClassificationTest {

    @ParameterizedTest(name = "impact {0} × urgency {1} = {2}")
    @CsvSource({
            "HIGH,HIGH,CRITICAL", "HIGH,MEDIUM,HIGH", "HIGH,LOW,MEDIUM",
            "MEDIUM,HIGH,HIGH", "MEDIUM,MEDIUM,MEDIUM", "MEDIUM,LOW,LOW",
            "LOW,HIGH,MEDIUM", "LOW,MEDIUM,LOW", "LOW,LOW,LOW"})
    void priorityMatrix(Level impact, Level urgency, Priority expected) {
        assertThat(PriorityCalculator.calculate(impact, urgency)).isEqualTo(expected);
    }

    @Test
    void impactGrowsWithAffectedUsersReportsAndServiceTier() {
        assertThat(PriorityCalculator.deriveImpact(1, 3, 1)).isEqualTo(Level.LOW);
        assertThat(PriorityCalculator.deriveImpact(1, 1, 1)).isEqualTo(Level.MEDIUM);   // tier-1 service raises one level
        assertThat(PriorityCalculator.deriveImpact(10, 2, 1)).isEqualTo(Level.MEDIUM);
        assertThat(PriorityCalculator.deriveImpact(2, 2, 3)).isEqualTo(Level.MEDIUM);   // three linked reports
        assertThat(PriorityCalculator.deriveImpact(28, 1, 3)).isEqualTo(Level.HIGH);
        assertThat(PriorityCalculator.deriveImpact(60, 3, 1)).isEqualTo(Level.HIGH);
    }

    @Test
    void urgencyFromWording() {
        assertThat(PriorityCalculator.deriveUrgency("Payroll portal not loading, error 503")).isEqualTo(Level.HIGH);
        assertThat(PriorityCalculator.deriveUrgency("Question: how to change my signature")).isEqualTo(Level.LOW);
        assertThat(PriorityCalculator.deriveUrgency("Printer prints faded pages")).isEqualTo(Level.MEDIUM);
    }

    @Test
    void classifierDetectsServiceAndCategory() {
        BusinessService payroll = service(1L, "PAYROLL", "Payroll", "payroll,salary,payslip", Category.APPLICATION);
        BusinessService vpn = service(2L, "VPN", "VPN & Remote Access", "vpn,remote access", Category.NETWORK);
        IncidentClassifier classifier = new IncidentClassifier();

        IncidentClassifier.Result r1 = classifier.classify("Cannot download payslip", "Error 503 when opening payroll", List.of(payroll, vpn));
        assertThat(r1.service()).isSameAs(payroll);
        assertThat(r1.category()).isEqualTo(Category.APPLICATION);

        IncidentClassifier.Result r2 = classifier.classify("VPN latency very high", "Packet loss on the tunnel from home", List.of(payroll, vpn));
        assertThat(r2.service()).isSameAs(vpn);
        assertThat(r2.category()).isEqualTo(Category.NETWORK);

        IncidentClassifier.Result r3 = classifier.classify("Account locked out", "I typed my password wrong", List.of(payroll, vpn));
        assertThat(r3.service()).isNull();
        assertThat(r3.category()).isEqualTo(Category.ACCESS);
    }

    @Test
    void keywordsMatchWholeWordsNotFragmentsOfOtherWords() {
        BusinessService database = service(3L, "DATABASE", "Database Platform", "database,db,postgres", Category.DATABASE);
        BusinessService vpn = service(2L, "VPN", "VPN & Remote Access", "vpn,remote access", Category.NETWORK);
        IncidentClassifier classifier = new IncidentClassifier();

        // A reference code containing "db" must not route a VPN problem to the database team.
        IncidentClassifier.Result coded = classifier.classify("VPN client keeps reconnecting 02eedbb5", "The secure link resets", List.of(database, vpn));
        assertThat(coded.service()).isSameAs(vpn);
        assertThat(coded.category()).isEqualTo(Category.NETWORK);

        IncidentClassifier.Result feedback = classifier.classify("Cannot submit the feedback form", "Typing is slow and I want help", List.of(database, vpn));
        assertThat(feedback.service()).isNull();
        assertThat(feedback.category()).isNotIn(Category.DATABASE, Category.NETWORK); // "db" in feedback, "ping" in typing, "wan" in want

        assertThat(IncidentClassifier.mentions(" the db is down ", "db")).isTrue();
        assertThat(IncidentClassifier.mentions(" two new printers ", "printer")).isTrue();   // longer terms keep their endings
        assertThat(IncidentClassifier.mentions(" both apps crash ", "app")).isTrue();         // short terms allow a plural
        assertThat(IncidentClassifier.mentions(" error 1503 seen ", "503")).isFalse();
        assertThat(IncidentClassifier.mentions(" unauthorised access ", "unauthori")).isTrue();
    }

    static BusinessService service(Long id, String code, String name, String keywords, Category category) {
        BusinessService s = new BusinessService();
        s.setId(id);
        s.setCode(code);
        s.setName(name);
        s.setKeywords(keywords);
        s.setDefaultCategory(category);
        s.setTier(1);
        return s;
    }
}
