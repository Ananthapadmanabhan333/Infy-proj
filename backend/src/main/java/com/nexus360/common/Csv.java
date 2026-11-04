package com.nexus360.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Minimal RFC 4180 writer with spreadsheet formula-injection protection. */
public final class Csv {

    private Csv() {}

    public static final String CONTENT_TYPE = "text/csv;charset=UTF-8";

    public static ResponseEntity<byte[]> response(String fileName, List<String> header, List<List<Object>> rows) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(bytes(header, rows));
    }

    /** The CSV document (UTF-8, CRLF line ends, formula-safe cells) for scheduled reports and other non-HTTP uses. */
    public static byte[] bytes(List<String> header, List<List<Object>> rows) {
        StringBuilder out = new StringBuilder();
        appendRow(out, header.stream().map(h -> (Object) h).toList());
        rows.forEach(row -> appendRow(out, row));
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder out, List<Object> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(values.get(i)));
        }
        out.append("\r\n");
    }

    static String escape(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        // Neutralise values a spreadsheet would evaluate as a formula.
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0 && !(value instanceof Number)) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
