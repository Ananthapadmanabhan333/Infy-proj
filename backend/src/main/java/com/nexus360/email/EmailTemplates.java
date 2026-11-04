package com.nexus360.email;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.web.util.HtmlUtils;

/**
 * Branded e-mail layout (HTML with inline CSS plus a plain-text alternative). Every piece of caller-supplied content —
 * headings, paragraphs, facts, link labels and URLs — is HTML-escaped, so user-entered text (incident titles, names,
 * comments) can never inject markup. Links are only rendered for absolute http(s) URLs.
 */
public final class EmailTemplates {

    private static final String GOLD = "#D6B77A";
    private static final String CHAMPAGNE = "#F1DFBA";
    private static final String OBSIDIAN = "#171618";
    private static final String INK = "#25211D";
    private static final String MUTED = "#6F6860";
    private static final String PAPER = "#F5F1EA";
    private static final String FONT = "font-family:'Segoe UI',Arial,Helvetica,sans-serif;";

    private EmailTemplates() {}

    /** A label/value line shown in a small table (e.g. "Priority" / "P1 Critical"). */
    public record Fact(String label, String value) {}

    /**
     * Message content: {@code paragraphs} are plain text (line breaks kept), {@code actionUrl} must be absolute http(s)
     * and is shown as a button plus a copyable address; {@code footer} explains why the recipient gets the message.
     */
    public record Content(String heading, List<String> paragraphs, List<Fact> facts, String actionLabel, String actionUrl, String footer) {

        public Content {
            paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        public static Content of(String heading, String paragraph, String actionLabel, String actionUrl, String footer) {
            return new Content(heading, paragraph == null ? List.of() : List.of(paragraph), List.of(), actionLabel, actionUrl, footer);
        }
    }

    public record Rendered(String html, String text) {}

    public static Rendered render(String subject, Content c, String organisationName) {
        String org = organisationName == null || organisationName.isBlank() ? "SA" : organisationName.trim();
        String url = safeUrl(c.actionUrl());
        StringBuilder html = new StringBuilder(4096);
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(esc(subject)).append("</title></head>")
                .append("<body style=\"margin:0;padding:0;background:").append(PAPER).append(";\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:")
                .append(PAPER).append(";padding:24px 12px;\"><tr><td align=\"center\">")
                .append("<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:600px;width:100%;")
                .append("background:#FFFFFF;border:1px solid #E4DCCD;border-radius:12px;overflow:hidden;\">")
                // Header band with the SA wordmark as text (no images, so nothing is fetched from the network).
                .append("<tr><td style=\"background:").append(OBSIDIAN).append(";padding:20px 32px;\">")
                .append("<div style=\"").append(FONT).append("font-size:26px;font-weight:700;letter-spacing:6px;color:").append(GOLD)
                .append(";\">SA</div>")
                .append("<div style=\"").append(FONT).append("font-size:11px;letter-spacing:2px;text-transform:uppercase;color:")
                .append(CHAMPAGNE).append(";\">Enterprise Operations Platform</div></td></tr>")
                .append("<tr><td style=\"padding:28px 32px 8px 32px;\">")
                .append("<h1 style=\"").append(FONT).append("margin:0 0 16px 0;font-size:20px;line-height:1.3;color:").append(INK).append(";\">")
                .append(esc(c.heading())).append("</h1>");
        for (String p : c.paragraphs()) {
            html.append("<p style=\"").append(FONT).append("margin:0 0 14px 0;font-size:14px;line-height:1.6;color:").append(INK).append(";\">")
                    .append(escMultiline(p)).append("</p>");
        }
        if (!c.facts().isEmpty()) {
            html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:4px 0 18px 0;border-collapse:collapse;\">");
            for (Fact f : c.facts()) {
                html.append("<tr><td style=\"").append(FONT).append("padding:4px 16px 4px 0;font-size:13px;color:").append(MUTED)
                        .append(";vertical-align:top;\">").append(esc(f.label())).append("</td><td style=\"").append(FONT)
                        .append("padding:4px 0;font-size:13px;color:").append(INK).append(";\">").append(escMultiline(f.value()))
                        .append("</td></tr>");
            }
            html.append("</table>");
        }
        if (url != null) {
            String label = c.actionLabel() == null || c.actionLabel().isBlank() ? "Open SA" : c.actionLabel();
            html.append("<p style=\"margin:8px 0 18px 0;\"><a href=\"").append(esc(url)).append("\" style=\"").append(FONT)
                    .append("display:inline-block;background:").append(GOLD).append(";color:").append(OBSIDIAN)
                    .append(";font-size:14px;font-weight:600;text-decoration:none;padding:10px 20px;border-radius:8px;\">")
                    .append(esc(label)).append("</a></p>")
                    .append("<p style=\"").append(FONT).append("margin:0 0 14px 0;font-size:12px;line-height:1.5;color:").append(MUTED)
                    .append(";\">If the button does not work, copy this address into your browser:<br><span style=\"word-break:break-all;\">")
                    .append(esc(url)).append("</span></p>");
        }
        html.append("</td></tr><tr><td style=\"padding:16px 32px 24px 32px;border-top:1px solid #EFE8DC;\">")
                .append("<p style=\"").append(FONT).append("margin:0;font-size:11px;line-height:1.5;color:").append(MUTED).append(";\">");
        if (c.footer() != null && !c.footer().isBlank()) {
            html.append(escMultiline(c.footer())).append("<br>");
        }
        html.append("Sent by SA for ").append(esc(org)).append(". Intelligent Service Management. Unified Operations.</p>")
                .append("</td></tr></table></td></tr></table></body></html>");

        StringBuilder text = new StringBuilder(1024);
        text.append("SA — Enterprise Operations Platform\n\n").append(nullToEmpty(c.heading())).append("\n\n");
        for (String p : c.paragraphs()) {
            text.append(nullToEmpty(p)).append("\n\n");
        }
        for (Fact f : c.facts()) {
            text.append(nullToEmpty(f.label())).append(": ").append(nullToEmpty(f.value())).append('\n');
        }
        if (!c.facts().isEmpty()) {
            text.append('\n');
        }
        if (url != null) {
            text.append(c.actionLabel() == null || c.actionLabel().isBlank() ? "Open SA" : c.actionLabel()).append(": ").append(url).append("\n\n");
        }
        text.append("-- \n");
        if (c.footer() != null && !c.footer().isBlank()) {
            text.append(c.footer()).append('\n');
        }
        text.append("Sent by SA for ").append(org).append(".\n");
        return new Rendered(html.toString(), text.toString());
    }

    /** HTML-escapes text (null → empty), including quotes so the value is also safe inside attributes. */
    public static String esc(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value, "UTF-8");
    }

    private static String escMultiline(String value) {
        return esc(value).replace("\r\n", "\n").replace("\n", "<br>");
    }

    /** Only absolute http(s) URLs without whitespace are linked; anything else is dropped. */
    static String safeUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.trim();
        String lower = u.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("https://") || lower.startsWith("http://")) || u.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return u;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    /** Short plain-text summary lines, for callers building paragraph lists. */
    public static List<String> lines(String... values) {
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }
}
