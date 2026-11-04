package com.nexus360.demo;

import com.nexus360.incident.Category;
import com.nexus360.incident.Level;
import com.nexus360.incident.ResolutionCode;
import java.util.List;

/** Static demo content. Everything seeded from here is stored with is_demo = true. */
final class DemoContent {

    private DemoContent() {}

    static final String EMAIL_DOMAIN = "@demo.nexus360.io";

    record Dept(String code, String name, String description) {}

    record Person(String email, String name, String role, String dept, String title) {}

    record Svc(String code, String name, String dept, int tier, Category category, String keywords, String description) {}

    /** Incident template: one of {@code titles} is used; resolution text and root cause feed history and AI fallback. */
    record Template(String service, Category category, Level impact, Level urgency, List<String> titles, String description,
                    ResolutionCode code, String resolution, String rootCause, String kbNumber, int weight) {}

    record Article(String type, Category category, String service, String title, String summary, String tags, String content) {}

    static final List<Dept> DEPARTMENTS = List.of(
            new Dept("IT-SD", "Service Desk", "First-line support for all employees"),
            new Dept("IT-APPS", "Enterprise Applications", "Payroll, HR, ERP and collaboration platforms"),
            new Dept("IT-NET", "Network & Infrastructure", "Corporate network, VPN and data-centre infrastructure"),
            new Dept("IT-DB", "Database Operations", "Database platforms and data services"),
            new Dept("IT-SEC", "Information Security", "Identity, access and security operations"),
            new Dept("IT-EUC", "End User Computing", "Laptops, desktops, printers and peripherals"),
            new Dept("FAC", "Workplace Facilities", "Buildings, meeting rooms and workplace services"),
            new Dept("FIN", "Finance", "Finance and payroll operations (business department)"),
            new Dept("HR", "Human Resources", "People operations (business department)"));

    static final List<Person> PEOPLE = List.of(
            new Person("admin", "Aarav Mehta", "SYSTEM_ADMIN", "IT-SD", "IT Operations Director"),
            new Person("rajesh.iyer", "Rajesh Iyer", "IT_ADMIN", "IT-SD", "IT Platform Administrator"),
            new Person("priya.raman", "Priya Raman", "IT_MANAGER", "IT-APPS", "Applications Manager"),
            new Person("kiran.rao", "Kiran Rao", "IT_MANAGER", "IT-NET", "Infrastructure Manager"),
            new Person("sunita.joshi", "Sunita Joshi", "INCIDENT_MANAGER", "IT-SD", "Service Desk Lead"),
            new Person("thomas.mathew", "Thomas Mathew", "IT_MANAGER", "IT-SEC", "Security Manager"),
            new Person("anil.kumar", "Anil Kumar", "CHANGE_MANAGER", "IT-APPS", "Change & Release Manager"),
            new Person("deepa.nair", "Deepa Nair", "ASSET_MANAGER", "IT-EUC", "IT Asset Manager"),
            new Person("riya.varma", "Riya Varma", "SUPPORT_AGENT", "IT-APPS", "Senior Application Engineer"),
            new Person("dev.nair", "Dev Nair", "SUPPORT_AGENT", "IT-APPS", "Application Support Engineer"),
            new Person("meera.sharma", "Meera Sharma", "SUPPORT_AGENT", "IT-APPS", "Application Support Engineer"),
            new Person("arjun.kapoor", "Arjun Kapoor", "SUPPORT_AGENT", "IT-NET", "Network Engineer"),
            new Person("vikram.singh", "Vikram Singh", "SUPPORT_AGENT", "IT-NET", "Network Engineer"),
            new Person("neha.gupta", "Neha Gupta", "SUPPORT_AGENT", "IT-SD", "Service Desk Analyst"),
            new Person("rahul.das", "Rahul Das", "SUPPORT_AGENT", "IT-SD", "Service Desk Analyst"),
            new Person("farhan.ali", "Farhan Ali", "SUPPORT_AGENT", "IT-DB", "Database Administrator"),
            new Person("lakshmi.menon", "Lakshmi Menon", "SUPPORT_AGENT", "IT-SEC", "Identity Engineer"),
            new Person("vishal.kumar", "Vishal Kumar", "SUPPORT_AGENT", "IT-EUC", "Desktop Engineer"),
            new Person("pooja.iyer", "Pooja Iyer", "SUPPORT_AGENT", "IT-EUC", "Desktop Engineer"),
            new Person("suresh.babu", "Suresh Babu", "SUPPORT_AGENT", "FAC", "Facilities Coordinator"),
            new Person("ananya.bose", "Ananya Bose", "AUDITOR", "IT-SEC", "IT Compliance Auditor"),
            // Release 4 (docs/RELEASE4_SPEC.md §2): security operations and cloud cost management (readers of operational data).
            new Person("vikram.sec", "Vikram Sen", "SECURITY_ANALYST", "IT-SEC", "Security Operations Analyst"),
            new Person("neha.finops", "Neha Kapoor", "FINOPS_ANALYST", "FIN", "FinOps Analyst"),
            new Person("rohan.kulkarni", "Rohan Kulkarni", "EMPLOYEE", "FIN", "Payroll Analyst"),
            new Person("sneha.patil", "Sneha Patil", "EMPLOYEE", "HR", "HR Business Partner"),
            new Person("amit.shah", "Amit Shah", "EMPLOYEE", "FIN", "Finance Controller"),
            new Person("kavya.reddy", "Kavya Reddy", "EMPLOYEE", "HR", "Talent Specialist"),
            new Person("ishaan.malhotra", "Ishaan Malhotra", "EMPLOYEE", "FIN", "Accounts Payable Lead"),
            new Person("divya.pillai", "Divya Pillai", "EMPLOYEE", "FIN", "Treasury Analyst"),
            new Person("manoj.verma", "Manoj Verma", "EMPLOYEE", "HR", "Learning Coordinator"),
            new Person("fatima.khan", "Fatima Khan", "EMPLOYEE", "HR", "Recruiter"),
            new Person("sanjay.menon", "Sanjay Menon", "EMPLOYEE", "FIN", "Financial Analyst"),
            new Person("tanvi.desai", "Tanvi Desai", "EMPLOYEE", "HR", "Compensation Analyst"));

    /** Release 3 demo teams: code, name, department, lead, members (e-mail local parts), department default. */
    record DemoTeam(String code, String name, String dept, String lead, List<String> members, boolean departmentDefault, String description) {}

    static final List<DemoTeam> TEAMS = List.of(
            new DemoTeam("PAYROLL-HR", "Payroll & HR Apps", "IT-APPS", "riya.varma", List.of("riya.varma", "dev.nair"), true,
                    "Payroll, HR portal and ERP finance applications"),
            new DemoTeam("COLLAB-APPS", "Collaboration Apps", "IT-APPS", "meera.sharma", List.of("meera.sharma", "dev.nair", "anil.kumar"), false,
                    "E-mail, calendars and collaboration tools"),
            new DemoTeam("NET-OPS", "Network Operations", "IT-NET", "arjun.kapoor", List.of("arjun.kapoor", "vikram.singh"), true,
                    "Corporate network, Wi-Fi and VPN"),
            new DemoTeam("SD-L1", "Service Desk L1", "IT-SD", "neha.gupta", List.of("neha.gupta", "rahul.das"), true,
                    "First-line support for every employee"),
            new DemoTeam("DB-OPS", "Database Operations", "IT-DB", "farhan.ali", List.of("farhan.ali"), true,
                    "Database platforms and replication"),
            new DemoTeam("ID-SEC", "Identity & Security", "IT-SEC", "lakshmi.menon", List.of("lakshmi.menon", "thomas.mathew"), true,
                    "Single sign-on, MFA and security operations"),
            new DemoTeam("DESKTOP", "Desktop Support", "IT-EUC", "vishal.kumar", List.of("vishal.kumar", "pooja.iyer", "deepa.nair"), true,
                    "Laptops, desktops, printers and peripherals"),
            new DemoTeam("FACILITIES", "Facilities", "FAC", "suresh.babu", List.of("suresh.babu"), true,
                    "Buildings, meeting rooms and workplace services"));

    /** Service code → default team code. */
    static final List<String[]> SERVICE_TEAMS = List.of(new String[]{"PAYROLL", "PAYROLL-HR"}, new String[]{"HR-PORTAL", "PAYROLL-HR"},
            new String[]{"ERP", "PAYROLL-HR"}, new String[]{"EMAIL", "COLLAB-APPS"}, new String[]{"VPN", "NET-OPS"},
            new String[]{"NETWORK", "NET-OPS"}, new String[]{"DATABASE", "DB-OPS"}, new String[]{"IAM", "ID-SEC"},
            new String[]{"EUC", "DESKTOP"}, new String[]{"FACILITIES", "FACILITIES"});

    /** Department manager by department code. */
    static final List<String[]> MANAGERS = List.of(new String[]{"IT-APPS", "priya.raman"}, new String[]{"IT-NET", "kiran.rao"},
            new String[]{"IT-SD", "sunita.joshi"}, new String[]{"IT-SEC", "thomas.mathew"}, new String[]{"IT-DB", "kiran.rao"},
            new String[]{"IT-EUC", "sunita.joshi"}, new String[]{"FAC", "sunita.joshi"});

    static final List<Svc> SERVICES = List.of(
            new Svc("PAYROLL", "Payroll", "IT-APPS", 1, Category.APPLICATION, "payroll,salary,payslip,pay slip,reimbursement,compensation",
                    "Payroll processing, payslips and reimbursements"),
            new Svc("HR-PORTAL", "HR Portal", "IT-APPS", 2, Category.APPLICATION, "hr portal,leave,attendance,appraisal,onboarding",
                    "Leave, attendance and appraisal self-service"),
            new Svc("ERP", "ERP Finance", "IT-APPS", 1, Category.APPLICATION, "erp,invoice,purchase order,ledger,vendor",
                    "General ledger, purchasing and invoicing"),
            new Svc("EMAIL", "Email & Collaboration", "IT-APPS", 2, Category.EMAIL, "email,outlook,mailbox,calendar,teams meeting",
                    "Email, calendars and collaboration"),
            new Svc("VPN", "VPN & Remote Access", "IT-NET", 1, Category.NETWORK, "vpn,remote access,anyconnect,tunnel,work from home",
                    "Remote access for employees and partners"),
            new Svc("NETWORK", "Corporate Network", "IT-NET", 1, Category.NETWORK, "wifi,wi-fi,network,lan,internet,switch",
                    "Office LAN, Wi-Fi and internet connectivity"),
            new Svc("DATABASE", "Database Platform", "IT-DB", 1, Category.DATABASE, "database,db,sql,postgres,oracle,replication",
                    "Shared PostgreSQL and Oracle database clusters"),
            new Svc("IAM", "Identity & Access (SSO)", "IT-SEC", 1, Category.ACCESS, "sso,login,password,mfa,single sign-on,active directory",
                    "Single sign-on, MFA and directory services"),
            new Svc("EUC", "End-User Devices", "IT-EUC", 3, Category.HARDWARE, "laptop,desktop,monitor,printer,keyboard,docking",
                    "Laptops, desktops, printers and peripherals"),
            new Svc("FACILITIES", "Workplace Facilities", "FAC", 3, Category.FACILITIES, "air conditioning,desk,meeting room,badge,parking",
                    "Buildings, meeting rooms and workplace services"));

    static final List<Template> TEMPLATES = List.of(
            new Template("PAYROLL", Category.APPLICATION, Level.MEDIUM, Level.HIGH,
                    List.of("Payroll portal not loading", "Payslip download fails with error", "Payroll page shows 503 error"),
                    "The payroll portal does not load and shows a 503 error after a long wait.", ResolutionCode.FIXED,
                    "Restarted payroll application nodes and raised the database connection pool limit.",
                    "Database connection pool exhausted during the morning payroll batch", "KB-1001", 7),
            new Template("PAYROLL", Category.APPLICATION, Level.LOW, Level.MEDIUM,
                    List.of("Reimbursement claim stuck in pending", "Payroll report shows wrong tax deduction"),
                    "A payroll record looks incorrect and needs to be checked.", ResolutionCode.USER_ERROR,
                    "Corrected the declaration in the payroll system and re-ran the calculation.", null, null, 3),
            new Template("HR-PORTAL", Category.APPLICATION, Level.LOW, Level.MEDIUM,
                    List.of("HR portal intermittently failing", "Leave application not submitting", "Attendance not updated in HR portal"),
                    "The HR portal intermittently fails when submitting forms.", ResolutionCode.FIXED,
                    "Cleared the stuck session cache on the HR portal application server.", "Session cache filled up on one application node", "KB-1003", 6),
            new Template("ERP", Category.APPLICATION, Level.MEDIUM, Level.MEDIUM,
                    List.of("ERP invoice posting slow", "Purchase order approval screen freezes"),
                    "ERP screens are very slow to respond while posting invoices.", ResolutionCode.WORKAROUND,
                    "Rebuilt the invoice index and scheduled heavy reports outside business hours.", "Missing index after the last ERP patch", null, 4),
            new Template("EMAIL", Category.EMAIL, Level.LOW, Level.MEDIUM,
                    List.of("Email synchronization delay", "Outlook not receiving emails", "Shared mailbox not visible"),
                    "Emails arrive late or do not synchronise on Outlook and mobile.", ResolutionCode.FIXED,
                    "Recreated the Outlook profile and cleared the mail transport queue.", "Mail transport queue backlog", "KB-1004", 6),
            new Template("VPN", Category.NETWORK, Level.MEDIUM, Level.HIGH,
                    List.of("High latency in VPN connection (APAC)", "VPN disconnects every few minutes", "Unable to connect to VPN from home"),
                    "VPN connection is very slow and drops repeatedly for remote users.", ResolutionCode.FIXED,
                    "Moved APAC users to the secondary VPN concentrator and increased tunnel capacity.", "VPN concentrator CPU saturation", "KB-1002", 7),
            new Template("NETWORK", Category.NETWORK, Level.MEDIUM, Level.MEDIUM,
                    List.of("Wi-Fi drops on floor 3", "Internet very slow in Block B"),
                    "Wireless connectivity drops frequently on one floor.", ResolutionCode.FIXED,
                    "Replaced a failing access point and rebalanced channels.", "Faulty wireless access point", "KB-1009", 4),
            new Template("DATABASE", Category.DATABASE, Level.HIGH, Level.MEDIUM,
                    List.of("Database replication lag on reporting cluster", "Too many connections error on shared DB"),
                    "Reporting queries return stale data and replication lag is increasing.", ResolutionCode.FIXED,
                    "Killed long-running queries and tuned the replication slot.", "Long-running analytical query blocking replication", "KB-1005", 3),
            new Template("IAM", Category.ACCESS, Level.LOW, Level.HIGH,
                    List.of("Account locked out after password change", "MFA prompt not received", "SSO login loop for finance apps"),
                    "User cannot sign in to corporate applications.", ResolutionCode.FIXED,
                    "Unlocked the account and re-registered MFA.", null, "KB-1006", 6),
            new Template("EUC", Category.HARDWARE, Level.LOW, Level.LOW,
                    List.of("Printer not working in Block A", "Laptop battery not charging", "Second monitor not detected"),
                    "Hardware device is not working as expected.", ResolutionCode.FIXED,
                    "Replaced the faulty part and updated drivers.", null, "KB-1008", 6),
            new Template("FACILITIES", Category.FACILITIES, Level.LOW, Level.LOW,
                    List.of("Meeting room display not working", "Air conditioning too cold in east wing"),
                    "A workplace facility needs attention.", ResolutionCode.FIXED, "Facilities team fixed the issue on site.", null, null, 3),
            new Template("IAM", Category.SECURITY, Level.MEDIUM, Level.HIGH,
                    List.of("Suspicious phishing email received", "Possible malware alert on laptop"),
                    "User reported a suspicious email asking for credentials.", ResolutionCode.FIXED,
                    "Blocked the sender domain, purged the message from mailboxes and reset affected credentials.", "Targeted phishing campaign", "KB-1007", 3));

    static final List<Article> ARTICLES = List.of(
            new Article("SOLUTION", Category.APPLICATION, "PAYROLL", "Payroll application troubleshooting guide",
                    "Diagnose and fix payroll portal outages, 503 errors and slow payslip downloads.", "payroll,503,outage,connection pool",
                    """
                    ## Symptoms
                    - Payroll portal spins and then returns **HTTP 503 Service Unavailable**
                    - Payslip downloads time out
                    - Several employees report the problem at the same time (usually 10:00–12:00 during payroll runs)

                    ## Diagnosis
                    1. Check the payroll application health endpoint on every node.
                    2. Open the database dashboard and compare *active* vs *maximum* pool connections.
                    3. Look for the morning payroll batch job holding connections.

                    ## Resolution
                    1. Pause the batch job if it is still running.
                    2. Restart payroll application nodes one at a time (requires manager approval — use the runbook).
                    3. Raise the connection pool limit from 50 to 80 if exhaustion is confirmed.
                    4. Verify payslip download for a test user and post an update to affected users.
                    """),
            new Article("SOLUTION", Category.NETWORK, "VPN", "VPN connection troubleshooting",
                    "Fix slow or dropping VPN connections for remote employees.", "vpn,latency,remote access",
                    """
                    ## Quick checks
                    1. Confirm the user's internet speed (> 10 Mbps recommended).
                    2. Ask the user to reconnect to the nearest gateway.

                    ## Gateway saturation
                    If many APAC users are affected, check concentrator CPU. Above 85% move users to the secondary concentrator.
                    """),
            new Article("GUIDE", Category.APPLICATION, "HR-PORTAL", "HR portal access and common issues",
                    "Resolve HR portal sign-in failures, stuck leave requests and attendance sync delays.", "hr portal,leave,attendance",
                    """
                    ## Leave request not submitting
                    Clear the browser cache and retry. If the problem persists for several users, clear the application session cache.

                    ## Attendance not updated
                    Attendance sync runs every 30 minutes; check the integration job log before escalating.
                    """),
            new Article("SOLUTION", Category.EMAIL, "EMAIL", "Email synchronization and known issues",
                    "Steps to fix delayed email delivery and Outlook synchronisation problems.", "email,outlook,sync",
                    """
                    1. Check the service health page for mail transport delays.
                    2. Recreate the Outlook profile for the user.
                    3. If the queue is above 5,000 messages, escalate to Enterprise Applications.
                    """),
            new Article("SOLUTION", Category.DATABASE, "DATABASE", "Database connection pool recovery",
                    "Recover from connection pool exhaustion and 'too many connections' errors.", "database,connection pool,postgres",
                    """
                    ## Identify
                    `select count(*), state from pg_stat_activity group by state;`

                    ## Recover
                    1. Terminate idle-in-transaction sessions older than 10 minutes.
                    2. Restart the offending application's pool.
                    3. Review pool sizing against `max_connections`.
                    """),
            new Article("FAQ", Category.ACCESS, "IAM", "Password reset and account unlock",
                    "How employees can reset passwords and unlock accounts, and how agents verify identity.", "password,unlock,mfa",
                    """
                    Employees can reset passwords at the self-service portal. Agents must verify identity with the employee ID and a
                    callback to the registered phone number before unlocking an account.
                    """),
            new Article("GUIDE", Category.SECURITY, "IAM", "Reporting phishing emails",
                    "What to do when you receive a suspicious email.", "phishing,security,email",
                    """
                    Do not click links or open attachments. Use the *Report phishing* button in Outlook or forward the email to
                    security operations. If you entered your password, reset it immediately and inform the Service Desk.
                    """),
            new Article("SOLUTION", Category.HARDWARE, "EUC", "Printer offline troubleshooting",
                    "Bring network printers back online.", "printer,hardware",
                    """
                    1. Power-cycle the printer and check the network cable.
                    2. Remove and re-add the printer from the print server.
                    3. Replace toner or clear paper jams shown on the panel.
                    """),
            new Article("SOLUTION", Category.NETWORK, "NETWORK", "Wi-Fi drops on office floors",
                    "Diagnose frequent wireless disconnects.", "wifi,wireless,access point",
                    """
                    Check the wireless controller for access points with high retry rates and replace or reboot faulty units.
                    """),
            new Article("KNOWN_ERROR", Category.APPLICATION, "PAYROLL", "Known error: payroll batch timeout at month end",
                    "Month-end payroll batch can exhaust database connections between 10:00 and 12:00.", "payroll,known error,month end",
                    """
                    **Workaround:** start the month-end batch at 07:00 and cap its parallelism at 8 workers.
                    **Permanent fix:** tracked as a problem record with Enterprise Applications.
                    """),
            new Article("GUIDE", Category.OTHER, null, "Critical incident response runbook",
                    "How SA handles P1 incidents from detection to post-incident review.", "major incident,p1,process",
                    """
                    1. Declare the major incident and open a bridge.
                    2. Assign an incident commander from the owning department.
                    3. Post public updates every 30 minutes.
                    4. Resolve, then record the root cause within 2 business days.
                    """),
            new Article("FAQ", Category.ACCESS, "VPN", "Requesting VPN access",
                    "How to request VPN access for new joiners and contractors.", "vpn,access request",
                    """
                    Request VPN access from the Service Catalogue (*Access Management → VPN access*). Requests need manager approval and
                    are fulfilled within 4 hours.
                    """),
            new Article("GUIDE", Category.FACILITIES, "FACILITIES", "Meeting room AV checklist",
                    "Fix common meeting room display and audio problems.", "meeting room,display,av",
                    """
                    Check the HDMI input selection, restart the room controller, and report persistent issues to Workplace Facilities.
                    """));
}
