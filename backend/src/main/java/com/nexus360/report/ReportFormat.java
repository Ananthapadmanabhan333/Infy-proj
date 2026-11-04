package com.nexus360.report;

public enum ReportFormat {
    PDF("application/pdf", "pdf"),
    CSV("text/csv;charset=UTF-8", "csv");

    private final String contentType;
    private final String extension;

    ReportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }
}
