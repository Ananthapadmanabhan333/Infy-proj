package com.nexus360.report;

/**
 * Report types of scheduled reports (DB constraint {@code ck_report_type}). A type is available once a
 * {@link ReportGenerator} bean for it exists: Release 3 foundations provide ANALYTICS_OVERVIEW, SLA and INCIDENTS; the
 * change, problem, asset and on-call modules add CHANGES, PROBLEMS, ASSETS and ONCALL.
 */
public enum ReportType {
    ANALYTICS_OVERVIEW("Analytics overview"),
    SLA("SLA compliance"),
    INCIDENTS("Incident log"),
    CHANGES("Changes"),
    PROBLEMS("Problems"),
    ASSETS("Assets"),
    ONCALL("On-call");

    private final String label;

    ReportType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
