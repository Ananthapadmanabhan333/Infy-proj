package com.nexus360.demo;

import com.nexus360.alert.Alert;
import com.nexus360.alert.AlertHistory;
import com.nexus360.alert.AlertRepository;
import com.nexus360.alert.AlertRoutingRule;
import com.nexus360.alert.AlertRoutingRuleRepository;
import com.nexus360.alert.AlertSeverity;
import com.nexus360.alert.AlertSource;
import com.nexus360.alert.AlertSourceRepository;
import com.nexus360.alert.AlertSuppressionRule;
import com.nexus360.alert.AlertSuppressionRuleRepository;
import com.nexus360.alert.MatchCriteria;
import com.nexus360.asset.Asset;
import com.nexus360.asset.AssetDtos.AssetDetail;
import com.nexus360.asset.AssetDtos.AssetRequest;
import com.nexus360.asset.AssetDtos.AssignRequest;
import com.nexus360.asset.AssetDtos.MaintenanceRequest;
import com.nexus360.asset.AssetDtos.TransitionRequest;
import com.nexus360.asset.AssetRepository;
import com.nexus360.asset.AssetService;
import com.nexus360.cmdb.BusinessService;
import com.nexus360.cmdb.BusinessServiceRepository;
import com.nexus360.cmdb.CiLinkService;
import com.nexus360.cmdb.ConfigurationItem;
import com.nexus360.cmdb.ConfigurationItem.CiType;
import com.nexus360.cmdb.ConfigurationItem.Criticality;
import com.nexus360.cmdb.ConfigurationItem.Environment;
import com.nexus360.cmdb.ConfigurationItemRepository;
import com.nexus360.cmdb.ServiceCiSync;
import com.nexus360.config.AppProperties;
import com.nexus360.incident.Incident;
import com.nexus360.incident.IncidentRepository;
import com.nexus360.integration.SecretCipher;
import com.nexus360.security.Actor;
import com.nexus360.security.Hashing;
import com.nexus360.team.Team;
import com.nexus360.team.TeamRepository;
import com.nexus360.user.Department;
import com.nexus360.user.DepartmentRepository;
import com.nexus360.user.User;
import com.nexus360.user.UserRepository;
import com.nexus360.workflow.WorkflowModel;
import com.nexus360.workflow.WorkflowRepository;
import com.nexus360.workflow.WorkflowService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Release 3 / BE-3 demo data (docs/RELEASE3_SPEC.md §5): a CMDB with a believable payroll dependency chain (Payroll → payroll ALB
 * and Payroll Application → app servers → payroll database → storage array; identity provider; VPN gateways; core network), assets
 * (laptops assigned to demo employees, servers, network gear, phones, licences with seats, one laptop in repair, retired and
 * disposed devices, warranties ending soon), alert sources for Prometheus, Grafana, CloudWatch (SNS) and generic JSON, suppression
 * and routing rules, an ALERT_RECEIVED workflow, and alerts including a Prometheus-style payroll 503 alert group linked to the
 * payroll incident. Every part is created once (each checks its own marker), so the seeder also upgrades databases created by
 * earlier releases. Runs after the other demo seeders; everything is flagged demo.
 */
@Slf4j
@Component
@Order(27)
@RequiredArgsConstructor
public class DemoRelease3AssetSeeder implements ApplicationRunner {

    private final AppProperties properties;
    private final UserRepository users;
    private final DepartmentRepository departments;
    private final TeamRepository teams;
    private final BusinessServiceRepository services;
    private final ConfigurationItemRepository cis;
    private final ServiceCiSync serviceCiSync;
    private final CiLinkService ciLinks;
    private final AssetRepository assets;
    private final AssetService assetService;
    private final AlertSourceRepository alertSources;
    private final AlertSuppressionRuleRepository suppressionRules;
    private final AlertRoutingRuleRepository routingRules;
    private final AlertRepository alerts;
    private final AlertHistory alertHistory;
    private final IncidentRepository incidents;
    private final WorkflowRepository workflows;
    private final WorkflowService workflowService;
    private final SecretCipher cipher;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        AppProperties.Demo demo = properties.demo();
        if (demo == null || !demo.seed() || users.countByDemoTrue() == 0 || users.findByEmailIgnoreCase(email("deepa.nair")).isEmpty()
                || services.findByCodeIgnoreCase("PAYROLL").isEmpty()) {
            return;
        }
        serviceCiSync.syncAll();
        int ciCount = seedCmdb();
        int assetCount = seedAssets();
        int rules = seedAlertConfiguration();
        int alertCount = seedAlerts();
        boolean workflow = seedWorkflow();
        int links = ciLinks.backfillByName();
        if (ciCount + assetCount + rules + alertCount + links > 0 || workflow) {
            log.info("Release 3 (BE-3) demo data: {} CIs, {} assets, {} alert rules/sources, {} alerts, workflow {}, {} problem/change CI links", ciCount,
                    assetCount, rules, alertCount, workflow, links);
        }
    }

    // ------------------------------------------------------------------ CMDB

    private int seedCmdb() {
        if (cis.findByNameIgnoreCase("Payroll Application").isPresent()) {
            return 0;
        }
        Instant created = clock.instant().minus(Duration.ofDays(120));
        ci("Payroll Application", CiType.APPLICATION, Environment.PRODUCTION, Criticality.CRITICAL, "PAYROLL-HR", "riya.varma",
                "Payroll web application and batch (salary runs, payslips, reimbursements).", Map.of("version", "2.4.1", "runtime", "Java 21",
                        "url", "https://payroll.demo.nexus360.io", "batchWindow", "07:00-09:00 IST"), created);
        ci("payroll-alb", CiType.CLOUD_RESOURCE, Environment.PRODUCTION, Criticality.CRITICAL, "PAYROLL-HR", "riya.varma",
                "AWS application load balancer in front of the payroll application servers.", Map.of("provider", "AWS", "region", "ap-south-1",
                        "resource", "app/payroll-alb/50dc6c495c0c9188"), created);
        ci("payroll-app-01", CiType.SERVER, Environment.PRODUCTION, Criticality.HIGH, "PAYROLL-HR", "riya.varma", "Payroll application node 1.",
                Map.of("ip", "10.20.1.11", "os", "RHEL 9.4", "cpu", 8, "memoryGb", 32, "datacenter", "Bengaluru DC1"), created);
        ci("payroll-app-02", CiType.SERVER, Environment.PRODUCTION, Criticality.HIGH, "PAYROLL-HR", "riya.varma", "Payroll application node 2.",
                Map.of("ip", "10.20.1.12", "os", "RHEL 9.4", "cpu", 8, "memoryGb", 32, "datacenter", "Bengaluru DC1"), created);
        ci("payroll-db-primary", CiType.DATABASE, Environment.PRODUCTION, Criticality.CRITICAL, "DB-OPS", "farhan.ali",
                "Primary PostgreSQL database of the payroll application (shared connection pool with the batch).", Map.of("engine", "PostgreSQL 16",
                        "maxConnections", 50, "ip", "10.20.2.21"), created);
        ci("payroll-db-replica", CiType.DATABASE, Environment.PRODUCTION, Criticality.HIGH, "DB-OPS", "farhan.ali",
                "Streaming replica used for payroll reports and backups.", Map.of("engine", "PostgreSQL 16", "ip", "10.20.2.22"), created);
        ci("san-array-01", CiType.INFRASTRUCTURE, Environment.PRODUCTION, Criticality.CRITICAL, "DB-OPS", "farhan.ali",
                "All-flash storage array for the database platform.", Map.of("model", "NetApp AFF A400", "capacityTb", 120, "raid", "RAID-DP"), created);
        ci("Identity Provider (ADFS)", CiType.APPLICATION, Environment.PRODUCTION, Criticality.CRITICAL, "ID-SEC", "lakshmi.menon",
                "Federation service for single sign-on (SAML/OIDC) and VPN authentication.", Map.of("product", "AD FS 2022", "farm", "adfs.demo.nexus360.io"),
                created);
        ci("adfs-01", CiType.SERVER, Environment.PRODUCTION, Criticality.HIGH, "ID-SEC", "lakshmi.menon", "Federation server 1.",
                Map.of("os", "Windows Server 2022", "ip", "10.30.1.11"), created);
        ci("adfs-02", CiType.SERVER, Environment.PRODUCTION, Criticality.HIGH, "ID-SEC", "lakshmi.menon", "Federation server 2 (NTP drift in the past).",
                Map.of("os", "Windows Server 2022", "ip", "10.30.1.12"), created);
        ci("vpn-gw-01", CiType.NETWORK_DEVICE, Environment.PRODUCTION, Criticality.HIGH, "NET-OPS", "arjun.kapoor", "Remote-access VPN gateway (primary).",
                Map.of("model", "Cisco ASA 5516-X", "firmware", "9.16.4", "ip", "203.0.113.10"), created);
        ci("vpn-gw-02", CiType.NETWORK_DEVICE, Environment.PRODUCTION, Criticality.HIGH, "NET-OPS", "arjun.kapoor", "Remote-access VPN gateway (secondary).",
                Map.of("model", "Cisco ASA 5516-X", "firmware", "9.16.4", "ip", "203.0.113.11"), created);
        ci("core-sw-01", CiType.NETWORK_DEVICE, Environment.PRODUCTION, Criticality.CRITICAL, "NET-OPS", "kiran.rao", "Data-centre core switch.",
                Map.of("model", "Cisco Catalyst 9500", "ports", 48), created);
        ci("fw-edge-01", CiType.NETWORK_DEVICE, Environment.PRODUCTION, Criticality.CRITICAL, "NET-OPS", "kiran.rao", "Internet edge firewall.",
                Map.of("model", "Palo Alto PA-3220"), created);
        ci("ERP Finance Application", CiType.APPLICATION, Environment.PRODUCTION, Criticality.CRITICAL, "PAYROLL-HR", "priya.raman",
                "ERP finance suite (ledger, invoices, purchase orders).", Map.of("version", "7.2"), created);
        ci("erp-app-01", CiType.SERVER, Environment.PRODUCTION, Criticality.HIGH, "PAYROLL-HR", "priya.raman", "ERP application server.",
                Map.of("os", "RHEL 9.4", "ip", "10.20.3.11"), created);
        ci("erp-db-01", CiType.DATABASE, Environment.PRODUCTION, Criticality.HIGH, "DB-OPS", "farhan.ali", "ERP database.",
                Map.of("engine", "Oracle 19c"), created);
        ci("HR Portal Application", CiType.APPLICATION, Environment.PRODUCTION, Criticality.HIGH, "PAYROLL-HR", "riya.varma",
                "Employee self-service HR portal.", Map.of("version", "5.2.0"), created);
        ci("hr-portal-web-01", CiType.SERVER, Environment.PRODUCTION, Criticality.MEDIUM, "PAYROLL-HR", "riya.varma", "HR portal web server.",
                Map.of("os", "Ubuntu 24.04", "ip", "10.20.4.11"), created);
        ci("Microsoft 365", CiType.SOFTWARE, Environment.PRODUCTION, Criticality.HIGH, "COLLAB-APPS", "meera.sharma",
                "Exchange Online, Teams and SharePoint tenant.", Map.of("tenant", "demo.nexus360.io", "plan", "E3"), created);
        ci("payroll-app-staging", CiType.SERVER, Environment.STAGING, Criticality.LOW, "PAYROLL-HR", "dev.nair", "Payroll staging server.",
                Map.of("os", "RHEL 9.4"), created);

        String[][] rels = {
                {"Payroll", "DEPENDS_ON", "Payroll Application"}, {"Payroll", "DEPENDS_ON", "payroll-alb"},
                {"payroll-alb", "CONNECTS_TO", "payroll-app-01"}, {"payroll-alb", "CONNECTS_TO", "payroll-app-02"},
                {"Payroll Application", "RUNS_ON", "payroll-app-01"}, {"Payroll Application", "RUNS_ON", "payroll-app-02"},
                {"Payroll Application", "DEPENDS_ON", "payroll-db-primary"}, {"Payroll Application", "DEPENDS_ON", "Identity Provider (ADFS)"},
                {"payroll-db-primary", "DEPENDS_ON", "san-array-01"}, {"payroll-db-replica", "DEPENDS_ON", "san-array-01"},
                {"payroll-db-replica", "CONNECTS_TO", "payroll-db-primary"}, {"payroll-db-primary", "PART_OF", "Database Platform"},
                {"payroll-db-replica", "PART_OF", "Database Platform"}, {"erp-db-01", "PART_OF", "Database Platform"},
                {"payroll-app-01", "CONNECTS_TO", "core-sw-01"}, {"payroll-app-02", "CONNECTS_TO", "core-sw-01"},
                {"Identity & Access (SSO)", "DEPENDS_ON", "Identity Provider (ADFS)"}, {"Identity Provider (ADFS)", "RUNS_ON", "adfs-01"},
                {"Identity Provider (ADFS)", "RUNS_ON", "adfs-02"}, {"VPN & Remote Access", "DEPENDS_ON", "vpn-gw-01"},
                {"VPN & Remote Access", "DEPENDS_ON", "vpn-gw-02"}, {"vpn-gw-01", "DEPENDS_ON", "Identity Provider (ADFS)"},
                {"vpn-gw-02", "DEPENDS_ON", "Identity Provider (ADFS)"}, {"vpn-gw-01", "CONNECTS_TO", "core-sw-01"},
                {"vpn-gw-02", "CONNECTS_TO", "core-sw-01"}, {"Corporate Network", "DEPENDS_ON", "core-sw-01"}, {"Corporate Network", "DEPENDS_ON", "fw-edge-01"},
                {"ERP Finance", "DEPENDS_ON", "ERP Finance Application"}, {"ERP Finance Application", "RUNS_ON", "erp-app-01"},
                {"ERP Finance Application", "DEPENDS_ON", "erp-db-01"}, {"erp-db-01", "DEPENDS_ON", "san-array-01"},
                {"HR Portal", "DEPENDS_ON", "HR Portal Application"}, {"HR Portal Application", "RUNS_ON", "hr-portal-web-01"},
                {"HR Portal Application", "DEPENDS_ON", "Identity Provider (ADFS)"}, {"Email & Collaboration", "DEPENDS_ON", "Microsoft 365"}};
        for (String[] r : rels) {
            Long source = ciId(r[0]);
            Long target = ciId(r[2]);
            if (source != null && target != null) {
                jdbc.update("insert into ci_relationships (source_id, target_id, rel_type, origin, is_demo, created_at) values (?, ?, ?, 'MANUAL', true, ?) "
                        + "on conflict do nothing", source, target, r[1], Timestamp.from(created));
            }
        }
        return 21;
    }

    private void ci(String name, CiType type, Environment env, Criticality criticality, String teamCode, String owner, String description,
                    Map<String, Object> attributes, Instant created) {
        if (cis.findByNameIgnoreCase(name).isPresent()) {
            return;
        }
        ConfigurationItem c = new ConfigurationItem();
        c.setName(name);
        c.setCiType(type);
        c.setEnvironment(env);
        c.setCriticality(criticality);
        c.setDescription(description);
        c.setTeam(teams.findByCodeIgnoreCase(teamCode).orElse(null));
        c.setOwner(user(owner));
        c.setAttributes(new LinkedHashMap<>(attributes));
        c.setDemo(true);
        c.setCreatedAt(created);
        c.setUpdatedAt(created);
        cis.save(c);
        jdbc.update("insert into ci_history (ci_id, actor_name, action, to_value, note, created_at) values (?, 'Deepa Nair', 'CREATED', ?, 'Demo CMDB', ?)",
                c.getId(), type + " " + name, Timestamp.from(created));
    }

    // ------------------------------------------------------------------ assets

    private int seedAssets() {
        if (assets.countByDemoTrue() > 0) {
            return 0;
        }
        Actor deepa = Actor.of(user("deepa.nair"));
        LocalDate today = LocalDate.now(clock);
        Long euc = dept("IT-EUC");
        int n = 0;
        // Laptops of demo employees (and staff), each assigned through the real assignment flow.
        String[][] laptops = {
                {"rohan.kulkarni", "Dell", "Latitude 7440", "DL7440-8F3K2Q1", "FIN", "Bengaluru HQ, 4th floor", "-400", "1060"},
                {"sneha.patil", "Lenovo", "ThinkPad T14 Gen 4", "PF4KX2Z9", "HR", "Bengaluru HQ, 3rd floor", "-380", "1080"},
                {"amit.shah", "Apple", "MacBook Pro 14 M3", "C02XK1Q4MD6T", "FIN", "Bengaluru HQ, 4th floor", "-300", "60"},
                {"kavya.reddy", "Lenovo", "ThinkPad T14 Gen 4", "PF4KX3A1", "HR", "Pune office", "-370", "1090"},
                {"ishaan.malhotra", "Dell", "Latitude 7440", "DL7440-9G2M4R7", "FIN", "Bengaluru HQ, 4th floor", "-350", "1110"},
                {"divya.pillai", "HP", "EliteBook 840 G10", "5CG3301XYZ", "FIN", "Mumbai office", "-1080", "12"},
                {"fatima.khan", "Lenovo", "ThinkPad X1 Carbon Gen 11", "PF4LM7B2", "HR", "Pune office", "-200", "895"},
                {"sanjay.menon", "Dell", "Latitude 5440", "DL5440-3H8N2P5", "FIN", "Bengaluru HQ, 4th floor", "-150", "945"},
                {"tanvi.desai", "Apple", "MacBook Air 13 M2", "C02YH2R8Q6L4", "HR", "Bengaluru HQ, 3rd floor", "-500", "230"},
                {"riya.varma", "Dell", "Precision 5680", "DP5680-7K1L9M3", "IT-APPS", "Bengaluru HQ, 2nd floor", "-260", "835"},
                {"arjun.kapoor", "Lenovo", "ThinkPad P16s", "PF4NP6C8", "IT-NET", "Bengaluru DC1", "-240", "855"},
                {"neha.gupta", "HP", "EliteBook 840 G10", "5CG3302ABC", "IT-SD", "Bengaluru HQ, ground floor", "-220", "875"},
                {"deepa.nair", "Dell", "Latitude 7440", "DL7440-2B7C5D9", "IT-EUC", "Bengaluru HQ, ground floor", "-210", "885"}};
        for (String[] l : laptops) {
            AssetDetail d = assetService.create(new AssetRequest(null, l[1] + " " + l[2], Asset.Type.LAPTOP, l[1], l[2], l[3], dept(l[4]), l[5],
                    l[1] + " India", "PO-2025-" + (4100 + n), new BigDecimal(l[1].equals("Apple") ? "189900.00" : "98500.00"), "INR",
                    today.plusDays(Long.parseLong(l[6])), today.plusDays(Long.parseLong(l[7])), null, null, null, null, user(l[0]).getId()), deepa);
            backdate(d.asset().id(), Long.parseLong(l[6]));
            n++;
        }
        // Spares, a laptop in repair, retired and disposed devices.
        AssetDetail spare1 = assetService.create(new AssetRequest(null, "Dell Latitude 7440 (spare)", Asset.Type.LAPTOP, "Dell", "Latitude 7440",
                "DL7440-5S1P4R2", euc, "Bengaluru HQ, IT store room", "Dell India", "PO-2026-0102", new BigDecimal("98500.00"), "INR",
                today.minusDays(40), today.plusDays(1055), null, null, null, "Pool laptop for new joiners", null), deepa);
        assetService.create(new AssetRequest(null, "Lenovo ThinkPad T14 (spare)", Asset.Type.LAPTOP, "Lenovo", "ThinkPad T14 Gen 4", "PF4KX9D4", euc,
                "Pune office, IT cupboard", "Lenovo India", "PO-2026-0103", new BigDecimal("92000.00"), "INR", today.minusDays(40), today.plusDays(1055), null,
                null, null, null, null), deepa);
        AssetDetail repair = assetService.create(new AssetRequest(null, "Lenovo ThinkPad T14 Gen 3", Asset.Type.LAPTOP, "Lenovo", "ThinkPad T14 Gen 3",
                "PF3HZ1E5", dept("HR"), "Bengaluru HQ, 3rd floor", "Lenovo India", "PO-2024-3311", new BigDecimal("86000.00"), "INR", today.minusDays(700),
                today.plusDays(395), null, null, null, null, user("manoj.verma").getId()), deepa);
        assetService.transition(repair.asset().id(), new TransitionRequest(Asset.Status.IN_REPAIR, "Keyboard keys not responding", null), deepa);
        assetService.addMaintenance(repair.asset().id(), new MaintenanceRequest("REPAIR", "SCHEDULED", "Keyboard replacement under warranty (case 4471-22)",
                "Lenovo Service Centre", BigDecimal.ZERO, today.plusDays(2), null), deepa);
        AssetDetail old = assetService.create(new AssetRequest(null, "Dell Latitude 5490", Asset.Type.LAPTOP, "Dell", "Latitude 5490", "DL5490-1A2B3C4", euc,
                "Bengaluru HQ, IT store room", "Dell India", "PO-2020-0815", new BigDecimal("72000.00"), "INR", today.minusDays(2100), today.minusDays(1000),
                null, null, null, "Replaced by the 2025 refresh", null), deepa);
        assetService.transition(old.asset().id(), new TransitionRequest(Asset.Status.RETIRED, "End of life (battery and performance)", null), deepa);
        AssetDetail gone = assetService.create(new AssetRequest(null, "HP ProBook 450 G6", Asset.Type.LAPTOP, "HP", "ProBook 450 G6", "5CD9123QRS", euc,
                null, "HP India", "PO-2019-0420", new BigDecimal("61000.00"), "INR", today.minusDays(2400), today.minusDays(1300), null, null, null, null, null),
                deepa);
        assetService.transition(gone.asset().id(), new TransitionRequest(Asset.Status.RETIRED, "End of life", null), deepa);
        assetService.transition(gone.asset().id(), new TransitionRequest(Asset.Status.DISPOSED,
                "Disk wiped (NIST 800-88 purge) and handed to a certified e-waste recycler; certificate EW-2026-117", null), deepa);
        n += 5;
        // Desktops, monitors and phones.
        assetService.create(new AssetRequest(null, "Dell OptiPlex 7010 (service desk)", Asset.Type.DESKTOP, "Dell", "OptiPlex 7010", "DO7010-4M2N8P1",
                dept("IT-SD"), "Bengaluru HQ, service desk counter", "Dell India", "PO-2025-2210", new BigDecimal("64000.00"), "INR", today.minusDays(330),
                today.plusDays(765), null, null, null, null, user("rahul.das").getId()), deepa);
        assetService.create(new AssetRequest(null, "Dell UltraSharp U2723QE", Asset.Type.MONITOR, "Dell", "U2723QE", "CN0U27-23QE-0041", dept("FIN"),
                "Bengaluru HQ, 4th floor", "Dell India", "PO-2025-2211", new BigDecimal("42000.00"), "INR", today.minusDays(330), today.plusDays(765), null,
                null, null, null, user("amit.shah").getId()), deepa);
        assetService.create(new AssetRequest(null, "Apple iPhone 15 (on-call phone)", Asset.Type.MOBILE, "Apple", "iPhone 15", "F2LZK9XYPQ",
                dept("IT-SD"), "With the on-call incident manager", "Apple India", "PO-2025-3005", new BigDecimal("79900.00"), "INR", today.minusDays(180),
                today.plusDays(185), null, null, null, "Rotates with the on-call incident manager", user("sunita.joshi").getId()), deepa);
        assetService.create(new AssetRequest(null, "Samsung Galaxy S24", Asset.Type.MOBILE, "Samsung", "Galaxy S24", "R5CW30XYZ9K", dept("IT-NET"),
                "Bengaluru DC1", "Samsung India", "PO-2025-3006", new BigDecimal("74999.00"), "INR", today.minusDays(170), today.plusDays(195), null, null,
                null, null, user("arjun.kapoor").getId()), deepa);
        n += 4;
        // Data-centre hardware linked to its CIs (warranties: one ending soon, one expired).
        server("Dell PowerEdge R750 (payroll-app-01)", Asset.Type.SERVER, "Dell", "PowerEdge R750", "SVR-R750-001", "payroll-app-01", -700, 395, deepa);
        server("Dell PowerEdge R750 (payroll-app-02)", Asset.Type.SERVER, "Dell", "PowerEdge R750", "SVR-R750-002", "payroll-app-02", -700, 395, deepa);
        Long db = server("HPE ProLiant DL380 Gen10 (payroll-db-primary)", Asset.Type.SERVER, "HPE", "ProLiant DL380 Gen10", "SVR-DL380-7731",
                "payroll-db-primary", -1050, 45, deepa);
        assetService.addMaintenance(db, new MaintenanceRequest("PREVENTIVE", "COMPLETED", "Firmware and iLO update during the monthly maintenance window",
                "HPE Pointnext", new BigDecimal("0.00"), today.minusDays(21), today.plusDays(69)), deepa);
        Long san = server("NetApp AFF A400 (san-array-01)", Asset.Type.SERVER, "NetApp", "AFF A400", "SAN-A400-0091", "san-array-01", -900, 560, deepa);
        assetService.addMaintenance(san, new MaintenanceRequest("REPAIR", "COMPLETED", "Replaced failed SSD in shelf 2, bay 7 (RAID rebuilt in 3 hours)",
                "NetApp Support", new BigDecimal("0.00"), today.minusDays(9), null), deepa);
        server("Cisco ASA 5516-X (vpn-gw-01)", Asset.Type.NETWORK, "Cisco", "ASA 5516-X", "JAD2101XYZ1", "vpn-gw-01", -1400, 12, deepa);
        server("Cisco ASA 5516-X (vpn-gw-02)", Asset.Type.NETWORK, "Cisco", "ASA 5516-X", "JAD2101XYZ2", "vpn-gw-02", -1400, 12, deepa);
        server("Cisco Catalyst 9500-48Y4C (core-sw-01)", Asset.Type.NETWORK, "Cisco", "Catalyst 9500-48Y4C", "FCW2233L0AB", "core-sw-01", -1500, -20, deepa);
        server("Dell PowerEdge R650 (erp-app-01)", Asset.Type.SERVER, "Dell", "PowerEdge R650", "SVR-R650-014", "erp-app-01", -500, 595, deepa);
        n += 8;
        // Software licences with seats.
        Long m365 = licence("Microsoft 365 E3", "Microsoft", "M365-E3", 50, 200, "Microsoft 365", "7300.00", deepa);
        for (String u : List.of("rohan.kulkarni", "sneha.patil", "amit.shah", "kavya.reddy", "ishaan.malhotra", "divya.pillai", "manoj.verma",
                "fatima.khan", "sanjay.menon", "tanvi.desai", "riya.varma", "priya.raman")) {
            assetService.assign(m365, new AssignRequest(user(u).getId(), null), deepa);
        }
        Long acrobat = licence("Adobe Acrobat Pro", "Adobe", "ACRO-PRO-TEAM", 10, 120, null, "19500.00", deepa);
        for (String u : List.of("amit.shah", "rohan.kulkarni", "sneha.patil")) {
            assetService.assign(acrobat, new AssignRequest(user(u).getId(), null), deepa);
        }
        Long jetbrains = licence("JetBrains IntelliJ IDEA Ultimate", "JetBrains", "IDEA-ULT", 5, 20, null, "48900.00", deepa);
        for (String u : List.of("riya.varma", "dev.nair", "meera.sharma", "farhan.ali")) {
            assetService.assign(jetbrains, new AssignRequest(user(u).getId(), null), deepa);
        }
        n += 3;
        jdbc.update("update asset_events set created_at = a.created_at from assets a where a.id = asset_events.asset_id and asset_events.event_type in "
                + "('CREATED', 'ASSIGNED') and a.is_demo and asset_events.created_at > a.created_at + interval '1 minute' and asset_events.created_at > now() - interval '1 hour'");
        jdbc.update("update asset_assignments x set assigned_at = a.created_at from assets a where a.id = x.asset_id and a.is_demo and x.returned_at is null "
                + "and x.assigned_at > now() - interval '1 hour'");
        jdbc.update("update assets set assigned_at = created_at where is_demo and assigned_to_id is not null");
        jdbc.update("update asset_maintenance set is_demo = true");
        jdbc.update("update assets set is_demo = true where is_demo = false and created_by_id = ? and created_at < ?", user("deepa.nair").getId(),
                Timestamp.from(clock.instant().plusSeconds(1)));
        return n + (spare1 == null ? 0 : 0);
    }

    private Long server(String name, Asset.Type type, String maker, String model, String serial, String ciName, int purchasedDays, int warrantyDays,
                        Actor actor) {
        LocalDate today = LocalDate.now(clock);
        Long ci = ciId(ciName);
        AssetDetail d = assetService.create(new AssetRequest(null, name, type, maker, model, serial, dept(type == Asset.Type.NETWORK ? "IT-NET" : "IT-DB"),
                "Bengaluru DC1, rack " + (serial.hashCode() & 15), maker + " India", "PO-DC-" + Math.abs(serial.hashCode() % 9000 + 1000),
                new BigDecimal(type == Asset.Type.NETWORK ? "640000.00" : "1150000.00"), "INR", today.plusDays(purchasedDays), today.plusDays(warrantyDays),
                null, null, ci, "Production data-centre hardware", null), actor);
        assetService.transition(d.asset().id(), new TransitionRequest(Asset.Status.IN_USE, "In production", null), actor);
        backdate(d.asset().id(), purchasedDays);
        return d.asset().id();
    }

    private Long licence(String name, String vendor, String sku, int seats, int expiresInDays, String ciName, String cost, Actor actor) {
        LocalDate today = LocalDate.now(clock);
        AssetDetail d = assetService.create(new AssetRequest(null, name, Asset.Type.SOFTWARE_LICENSE, vendor, sku, null, dept("IT-EUC"), null, vendor,
                "PO-SW-" + sku, new BigDecimal(cost).multiply(BigDecimal.valueOf(seats)), "INR", today.minusDays(365 - expiresInDays), null, seats,
                today.plusDays(expiresInDays), ciName == null ? null : ciId(ciName), "Annual subscription", null), actor);
        backdate(d.asset().id(), -(365 - expiresInDays));
        return d.asset().id();
    }

    private void backdate(Long assetId, long daysFromToday) {
        Instant at = clock.instant().plus(Duration.ofDays(Math.min(daysFromToday, -1)));
        jdbc.update("update assets set created_at = ?, updated_at = ?, is_demo = true where id = ?", Timestamp.from(at), Timestamp.from(at), assetId);
    }

    // ------------------------------------------------------------------ alert configuration

    private int seedAlertConfiguration() {
        int n = 0;
        User admin = user("admin");
        if (alertSources.findByNameIgnoreCase("prometheus-prod").isEmpty()) {
            source("prometheus-prod", AlertSource.Provider.PROMETHEUS, null, null, AlertSeverity.WARNING, admin);
            source("grafana-cloud", AlertSource.Provider.GRAFANA, null, null, AlertSeverity.WARNING, admin);
            AlertSource cw = source("aws-cloudwatch-payroll", AlertSource.Provider.CLOUDWATCH, "PAYROLL", "arn:aws:sns:ap-south-1:123456789012:payroll-alarms",
                    AlertSeverity.MAJOR, admin);
            cw.setSubscriptionStatus(AlertSource.SubscriptionStatus.CONFIRMED);
            cw.setSubscriptionArn("arn:aws:sns:ap-south-1:123456789012:payroll-alarms:8a1f6e0c-2d34-4b8e-9f1a-0c5d7e3b2a91");
            cw.setSubscriptionConfirmedAt(clock.instant().minus(Duration.ofDays(30)));
            source("uptime-checks", AlertSource.Provider.GENERIC, null, null, AlertSeverity.MAJOR, admin);
            n += 4;
        }
        if (suppressionRules.findAllByOrderByNameAsc().stream().noneMatch(AlertSuppressionRule::isDemo)) {
            AlertSuppressionRule backup = new AlertSuppressionRule();
            backup.setName("Nightly backup window");
            backup.setDescription("Backup jobs saturate disk I/O between 01:00 and 03:00; their warnings are expected.");
            backup.setMatchCriteria(new MatchCriteria(Map.of("job", "backup"), null, List.of(AlertSeverity.WARNING, AlertSeverity.MINOR, AlertSeverity.INFO),
                    null, null, null, null));
            backup.setDailyStart(LocalTime.of(1, 0));
            backup.setDailyEnd(LocalTime.of(3, 0));
            backup.setDemo(true);
            backup.setCreatedBy(user("farhan.ali"));
            suppressionRules.save(backup);
            AlertSuppressionRule vpn = new AlertSuppressionRule();
            vpn.setName("VPN firmware 9.18 maintenance");
            vpn.setDescription("Planned failover tests on vpn-gw-02 during the firmware change window.");
            Long gw2 = ciId("vpn-gw-02");
            vpn.setMatchCriteria(new MatchCriteria(null, null, null, null, gw2 == null ? null : List.of(gw2), null, null));
            vpn.setStartsAt(clock.instant().minus(Duration.ofDays(6)));
            vpn.setEndsAt(clock.instant().minus(Duration.ofDays(6)).plus(Duration.ofHours(3)));
            vpn.setDemo(true);
            vpn.setCreatedBy(user("arjun.kapoor"));
            suppressionRules.save(vpn);
            n += 2;
        }
        if (routingRules.findAllByOrderBySortOrderAscIdAsc().stream().noneMatch(AlertRoutingRule::isDemo)) {
            BusinessService payroll = services.findByCodeIgnoreCase("PAYROLL").orElseThrow();
            AlertRoutingRule p = new AlertRoutingRule();
            p.setName("Payroll alerts to Payroll & HR Apps");
            p.setDescription("Critical payroll alerts open an incident straight away.");
            p.setSortOrder(10);
            p.setMatchCriteria(new MatchCriteria(null, null, null, List.of(payroll.getId()), null, null, null));
            p.setTeam(team("PAYROLL-HR"));
            p.setCreateIncident(true);
            p.setIncidentMinSeverity(AlertSeverity.CRITICAL);
            p.setDemo(true);
            routingRules.save(p);
            AlertRoutingRule dba = new AlertRoutingRule();
            dba.setName("Database alerts to Database Operations");
            dba.setSortOrder(20);
            List<Long> dbs = new ArrayList<>();
            for (String name : List.of("payroll-db-primary", "payroll-db-replica", "erp-db-01", "san-array-01")) {
                Long id = ciId(name);
                if (id != null) {
                    dbs.add(id);
                }
            }
            dba.setMatchCriteria(new MatchCriteria(null, null, null, null, dbs, null, null));
            dba.setTeam(team("DB-OPS"));
            dba.setDemo(true);
            routingRules.save(dba);
            AlertRoutingRule net = new AlertRoutingRule();
            net.setName("SNMP and network device alerts to Network Operations");
            net.setSortOrder(30);
            net.setMatchCriteria(new MatchCriteria(Map.of("job", "snmp|blackbox-vpn"), null, null, null, null, null, null));
            net.setTeam(team("NET-OPS"));
            net.setDemo(true);
            routingRules.save(net);
            n += 3;
        }
        return n;
    }

    private AlertSource source(String name, AlertSource.Provider provider, String serviceCode, String topic, AlertSeverity severity, User by) {
        AlertSource s = new AlertSource();
        s.setName(name);
        s.setProvider(provider);
        s.setSnsTopicArn(topic);
        if (provider != AlertSource.Provider.CLOUDWATCH) {
            s.setSecretEncrypted(cipher.encrypt("alsec_" + Hashing.randomToken(32))); // random: rotate the secret to connect a real system
        }
        s.setDefaultService(serviceCode == null ? null : services.findByCodeIgnoreCase(serviceCode).orElse(null));
        s.setDefaultSeverity(severity);
        s.setDemo(true);
        s.setCreatedBy(by);
        s.setLastReceivedAt(clock.instant().minus(Duration.ofMinutes(20)));
        return alertSources.save(s);
    }

    // ------------------------------------------------------------------ alerts

    private int seedAlerts() {
        if (alerts.countByDemoTrue() > 0) {
            return 0;
        }
        Instant now = clock.instant();
        AlertSource prom = alertSources.findByNameIgnoreCase("prometheus-prod").orElseThrow();
        AlertSource grafana = alertSources.findByNameIgnoreCase("grafana-cloud").orElseThrow();
        AlertSource cw = alertSources.findByNameIgnoreCase("aws-cloudwatch-payroll").orElseThrow();
        AlertSource uptime = alertSources.findByNameIgnoreCase("uptime-checks").orElseThrow();
        BusinessService payroll = services.findByCodeIgnoreCase("PAYROLL").orElseThrow();
        List<Long> active = jdbc.queryForList("select id from incidents where is_demo and service_id = ? and parent_id is null and status in "
                + com.nexus360.incident.IncidentStatus.activeSqlList() + " order by created_at desc limit 1", Long.class, payroll.getId());
        List<Long> any = active.isEmpty() ? jdbc.queryForList("select id from incidents where is_demo and service_id = ? and parent_id is null "
                + "order by created_at desc limit 1", Long.class, payroll.getId()) : active;
        Incident incident = any.isEmpty() ? null : incidents.findById(any.get(0)).orElse(null);
        Instant start = incident == null ? now.minus(Duration.ofHours(2)) : incident.getCreatedAt().minus(Duration.ofMinutes(4));
        User riya = user("riya.varma");
        Team payrollTeam = team("PAYROLL-HR");
        String group = "{}:{service=\"payroll\"}";
        String gen = "http://prometheus.demo.nexus360.io/graph?g0.expr=sum%28rate%28http_requests_total%7Bservice%3D%22payroll%22%2Ccode%3D~%225..%22%7D%5B5m%5D%29%29";
        int n = 0;
        for (int i = 1; i <= 2; i++) {
            Alert a = alert(prom, Alert.SourceKind.PROMETHEUS, "c4f3b2a1d0e9f8a" + i, group, "Payroll returns HTTP 503 for " + (i == 1 ? "7.4" : "6.1")
                    + "% of requests on payroll-app-0" + i, "More than 5% of payroll requests failed with HTTP 503 for 5 minutes; the connection pool "
                    + "to payroll-db-primary is exhausted while the payroll batch runs.", AlertSeverity.CRITICAL, labels("alertname", "PayrollHigh5xxRate",
                    "severity", "critical", "service", "payroll", "instance", "payroll-app-0" + i + ":8080", "job", "payroll", "team", "payroll-hr"),
                    gen, payroll, "payroll-app-0" + i, payrollTeam, start.plus(Duration.ofMinutes(i)), i == 1 ? 14 : 11);
            ack(a, riya, start.plus(Duration.ofMinutes(9)));
            link(a, incident);
            n++;
        }
        Alert pool = alert(prom, Alert.SourceKind.PROMETHEUS, "9b8a7c6d5e4f3a21", group, "Payroll database connection pool at 98% (49/50)",
                "Active connections on payroll-db-primary are above 90% of max_connections.", AlertSeverity.MAJOR, labels("alertname", "PayrollDbPoolSaturated",
                        "severity", "major", "service", "payroll", "instance", "payroll-db-primary:9187", "job", "postgres-exporter"), gen, payroll,
                "payroll-db-primary", team("DB-OPS"), start.plus(Duration.ofMinutes(3)), 6);
        ack(pool, user("farhan.ali"), start.plus(Duration.ofMinutes(12)));
        link(pool, incident);
        n++;
        Alert alb = alert(cw, Alert.SourceKind.CLOUDWATCH, "arn:aws:cloudwatch:ap-south-1:123456789012:alarm:payroll-alb-5xx",
                "cloudwatch:arn:aws:cloudwatch:ap-south-1:123456789012:alarm:payroll-alb-5xx", "payroll-alb-5xx: Payroll ALB 5XX above 50 per minute",
                "Threshold Crossed: 1 datapoint [212.0 (04/11/26 10:02:00)] was greater than the threshold (50.0).\nMetric: AWS/ApplicationELB "
                        + "HTTPCode_Target_5XX_Count GreaterThanThreshold 50.0", AlertSeverity.MAJOR, labels("alarmName", "payroll-alb-5xx", "region", "ap-south-1",
                        "account", "123456789012", "namespace", "AWS/ApplicationELB", "metricName", "HTTPCode_Target_5XX_Count", "dimension.LoadBalancer",
                        "app/payroll-alb/50dc6c495c0c9188"), "https://console.aws.amazon.com/cloudwatch/home?region=ap-south-1#alarmsV2:alarm/payroll-alb-5xx",
                payroll, "payroll-alb", payrollTeam, start.plus(Duration.ofMinutes(2)), 3);
        ack(alb, riya, start.plus(Duration.ofMinutes(10)));
        link(alb, incident);
        n++;
        Alert vpn = alert(grafana, Alert.SourceKind.GRAFANA, "6d0f1e2a3b4c5d6e", "{}/{alertname=\"VpnTunnelFlapping\"}", "VPN tunnel flapping on vpn-gw-02",
                "IPsec tunnel to the Pune office went down and up 4 times in 15 minutes.", AlertSeverity.WARNING, labels("alertname", "VpnTunnelFlapping",
                        "severity", "warning", "instance", "vpn-gw-02", "job", "blackbox-vpn", "grafana_folder", "Network"),
                "https://grafana.demo.nexus360.io/d/vpn/remote-access?orgId=1&viewPanel=4", services.findByCodeIgnoreCase("VPN").orElse(null), "vpn-gw-02",
                team("NET-OPS"), now.minus(Duration.ofMinutes(35)), 4);
        n++;
        Alert sso = alert(uptime, Alert.SourceKind.GENERIC, "uptime-sso-latency", null, "SSO sign-in latency above 2 s",
                "Synthetic sign-in check against adfs.demo.nexus360.io took 2.8 s (p95 over 10 minutes).", AlertSeverity.MAJOR, labels("check", "sso-login",
                        "region", "bengaluru"), null, services.findByCodeIgnoreCase("IAM").orElse(null), "adfs-02", team("ID-SEC"),
                now.minus(Duration.ofMinutes(25)), 5);
        sso.setEscalationLevel(1);
        sso.setLastEscalatedAt(now.minus(Duration.ofMinutes(10)));
        sso.setNextEscalationAt(now.plus(Duration.ofMinutes(5)));
        alerts.save(sso);
        alertHistory.record(sso.getId(), "ESCALATED", Actor.SYSTEM, "Level 1: notified team lead Lakshmi Menon, manager Thomas Mathew",
                Map.of("level", 1, "policy", "team-lead"));
        n++;
        Alert disk = alert(prom, Alert.SourceKind.PROMETHEUS, "1a2b3c4d5e6f7081", "{}/{alertname=\"DiskSpaceLow\"}", "Disk usage above 85% on erp-app-01",
                "/var/log is 87% full.", AlertSeverity.MINOR, labels("alertname", "DiskSpaceLow", "severity", "minor", "instance", "erp-app-01:9100",
                        "mountpoint", "/var/log", "job", "node"), null, services.findByCodeIgnoreCase("ERP").orElse(null), "erp-app-01", payrollTeam,
                now.minus(Duration.ofHours(30)), 9);
        disk.setStatus(Alert.Status.RESOLVED);
        disk.setFiring(false);
        disk.setResolvedAt(now.minus(Duration.ofHours(27)));
        disk.setEndsAt(disk.getResolvedAt());
        disk.setResolutionNote("Resolved by the source");
        alerts.save(disk);
        alertHistory.record(disk.getId(), "AUTO_RESOLVED", Actor.SYSTEM, "The source reports the alert as resolved", null);
        n++;
        Alert backup = alert(prom, Alert.SourceKind.PROMETHEUS, "7f6e5d4c3b2a1908", "{}/{alertname=\"BackupJobHighIO\"}",
                "Backup job saturates disk I/O on payroll-db-replica", "Disk utilisation above 95% for 10 minutes during pg_basebackup.", AlertSeverity.WARNING,
                labels("alertname", "BackupJobHighIO", "severity", "warning", "instance", "payroll-db-replica:9100", "job", "backup"), null,
                services.findByCodeIgnoreCase("DATABASE").orElse(null), "payroll-db-replica", null, now.minus(Duration.ofHours(8)), 12);
        AlertSuppressionRule nightly = suppressionRules.findAllByOrderByNameAsc().stream().filter(r -> r.getName().equals("Nightly backup window"))
                .findFirst().orElse(null);
        backup.setStatus(Alert.Status.SUPPRESSED);
        backup.setSuppressionRule(nightly);
        backup.setSuppressedAt(backup.getFirstSeenAt());
        alerts.save(backup);
        alertHistory.record(backup.getId(), "SUPPRESSED", Actor.SYSTEM, "Suppressed by rule Nightly backup window", null);
        n++;
        Alert cert = alert(null, Alert.SourceKind.MANUAL, "manual-hr-portal-cert", null, "TLS certificate of hr.demo.nexus360.io expires in 14 days",
                "Renew through the standard change template STD-CERT-RENEW.", AlertSeverity.WARNING, labels("reportedBy", "deepa.nair"), null,
                services.findByCodeIgnoreCase("HR-PORTAL").orElse(null), "hr-portal-web-01", payrollTeam, now.minus(Duration.ofHours(3)), 1);
        alertHistory.record(cert.getId(), "COMMENT", Actor.of(user("deepa.nair")), "Raised manually from the certificate inventory", null);
        n++;
        return n;
    }

    private Alert alert(AlertSource source, Alert.SourceKind kind, String fingerprint, String group, String title, String description, AlertSeverity severity,
                        Map<String, String> labels, String url, BusinessService service, String ciName, Team team, Instant firstSeen, int count) {
        Alert a = new Alert();
        a.setNumber("ALR-" + alerts.nextNumber());
        a.setSourceKind(kind);
        a.setSource(source);
        a.setScopeKey(source == null ? "MANUAL" : "S" + source.getId());
        a.setFingerprint(fingerprint);
        a.setGroupKey(group);
        a.setTitle(title);
        a.setDescription(description);
        a.setSeverity(severity);
        a.setLabels(new LinkedHashMap<>(labels));
        a.setExternalUrl(url);
        a.setService(service);
        a.setCi(ciName == null ? null : cis.findByNameIgnoreCase(ciName).orElse(null));
        a.setTeam(team);
        a.setOccurrenceCount(count);
        a.setFirstSeenAt(firstSeen);
        a.setStartsAt(firstSeen);
        Instant last = firstSeen.plus(Duration.ofMinutes(5L * Math.max(0, count - 1)));
        a.setLastSeenAt(last.isAfter(clock.instant()) ? clock.instant() : last);
        a.setDemo(true);
        a.setCreatedAt(firstSeen);
        alerts.save(a);
        alertHistory.record(a.getId(), "CREATED", Actor.SYSTEM, "Received from " + (source == null ? "Deepa Nair" : source.getName() + " (" + kind + ")"), null);
        if (team != null) {
            alertHistory.record(a.getId(), "ROUTED", Actor.SYSTEM, "Routed to " + team.getName(), null);
        }
        jdbc.update("update alert_events set created_at = ? where alert_id = ?", Timestamp.from(firstSeen), a.getId());
        return a;
    }

    private void ack(Alert a, User by, Instant at) {
        a.setStatus(Alert.Status.ACKNOWLEDGED);
        a.setAcknowledgedAt(at);
        a.setAcknowledgedBy(by);
        alerts.save(a);
        alertHistory.record(a.getId(), "ACKNOWLEDGED", Actor.of(by), "Looking into it", null);
        jdbc.update("update alert_events set created_at = ? where alert_id = ? and event_type = 'ACKNOWLEDGED'", Timestamp.from(at), a.getId());
    }

    private void link(Alert a, Incident incident) {
        if (incident == null) {
            return;
        }
        a.setIncident(incident);
        alerts.save(a);
        alertHistory.record(a.getId(), "LINKED_INCIDENT", Actor.SYSTEM, "Linked to " + incident.getNumber() + " with its alert group", null);
    }

    // ------------------------------------------------------------------ workflow

    private boolean seedWorkflow() {
        String name = "Critical database alert to incident";
        if (workflows.findAll().stream().anyMatch(w -> name.equals(w.getName()))) {
            return false;
        }
        WorkflowModel.Definition def = new WorkflowModel.Definition(new WorkflowModel.Conditions("ALL", List.of(
                new WorkflowModel.ConditionRule("alertSeverity", WorkflowModel.Operator.EQUALS, "CRITICAL"),
                new WorkflowModel.ConditionRule("ciType", WorkflowModel.Operator.EQUALS, "DATABASE"))),
                List.of(new WorkflowModel.ActionDef(WorkflowModel.ActionType.CREATE_INCIDENT, Map.of()),
                        new WorkflowModel.ActionDef(WorkflowModel.ActionType.NOTIFY_ROLE, Map.of("role", "INCIDENT_MANAGER",
                                "message", "Critical database alert {{number}} on {{ci}}: {{title}}"))));
        workflowService.create(new WorkflowService.WorkflowRequest(name, "Open an incident and tell the incident managers when a database CI raises a "
                + "critical alert.", WorkflowModel.Trigger.ALERT_RECEIVED, true, 30, 2, 60, def, "Demo workflow"), Actor.of(user("priya.raman")));
        workflows.findAll().stream().filter(w -> name.equals(w.getName())).forEach(w -> w.setDemo(true));
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> labels(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private Long ciId(String name) {
        return cis.findByNameIgnoreCase(name).map(ConfigurationItem::getId).orElse(null);
    }

    private Long dept(String code) {
        return departments.findByCodeIgnoreCase(code).map(Department::getId).orElse(null);
    }

    private Team team(String code) {
        return teams.findByCodeIgnoreCase(code).orElse(null);
    }

    private User user(String local) {
        return users.findByEmailIgnoreCase(email(local)).orElseThrow(() -> new IllegalStateException("Demo user " + local + " is missing"));
    }

    private static String email(String local) {
        return local + DemoContent.EMAIL_DOMAIN;
    }
}
