package com.nexus360.analytics;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.nexus360.analytics.AnalyticsDtos.AgentRow;
import com.nexus360.analytics.AnalyticsDtos.DepartmentRow;
import com.nexus360.analytics.AnalyticsDtos.Kpis;
import com.nexus360.analytics.AnalyticsDtos.Overview;
import com.nexus360.analytics.AnalyticsDtos.PriorityRow;
import com.nexus360.analytics.AnalyticsDtos.ServiceRow;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Renders the analytics overview of a period as a PDF report (OpenPDF). Every figure comes from {@link Overview}. */
@Component
public class AnalyticsPdf {

    private static final Color HEADER = new Color(0x25, 0x21, 0x1D);
    private static final Color GOLD = new Color(0x96, 0x76, 0x4B);
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, GOLD);
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, HEADER);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
    private static final Font HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

    public byte[] render(Overview o, String departmentName, Instant generatedAt, ZoneId zone) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 42, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.addTitle("SA analytics " + o.from() + " to " + o.to());
            document.addCreator("SA");
            document.open();
            document.add(new Paragraph("SA — Operations analytics", TITLE));
            document.add(new Paragraph("Period: " + o.from() + " to " + o.to() + " (" + o.days() + " days, " + zone.getId() + ")", BODY));
            document.add(new Paragraph("Filters: department = " + (departmentName == null ? "all departments" : departmentName), BODY));
            document.add(new Paragraph("Generated " + DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(generatedAt.atZone(zone).withNano(0))
                    + " from live records. Top-level incidents only; duplicate reports count with their parent; cancellations are not resolutions.",
                    BODY));

            section(document, "Key indicators");
            Kpis k = o.kpis();
            PdfPTable kpis = table(new float[]{3, 2}, "Indicator", "Value");
            row(kpis, "Incidents created", String.valueOf(k.createdInPeriod()));
            row(kpis, "Incidents resolved", String.valueOf(k.resolvedInPeriod()));
            row(kpis, "Open now", String.valueOf(k.openIncidents()));
            row(kpis, "Critical open now", String.valueOf(k.criticalOpen()));
            row(kpis, "Resolution rate", percent(k.resolutionRate()));
            row(kpis, "First call resolution", percent(k.firstCallResolution()));
            row(kpis, "SLA compliance", percent(k.slaCompliance()));
            row(kpis, "Mean time to resolve", minutes(k.mttrMinutes()));
            row(kpis, "Mean time to acknowledge", minutes(k.mttaMinutes()));
            document.add(kpis);

            section(document, "By priority");
            PdfPTable priority = table(new float[]{2, 1, 2, 2}, "Priority", "Created", "MTTR", "SLA compliance");
            for (PriorityRow r : o.byPriority()) {
                row(priority, r.priority().label() + " " + r.priority(), String.valueOf(r.count()), minutes(r.mttrMinutes()), percent(r.slaCompliance()));
            }
            document.add(priority);

            section(document, "By service");
            PdfPTable service = table(new float[]{3, 1, 1, 1, 2}, "Service", "Created", "Resolved", "Open", "MTTR");
            for (ServiceRow r : o.byService()) {
                row(service, r.name(), String.valueOf(r.created()), String.valueOf(r.resolved()), String.valueOf(r.open()), minutes(r.mttrMinutes()));
            }
            empty(service, o.byService(), 5);
            document.add(service);

            section(document, "By department");
            PdfPTable department = table(new float[]{3, 1, 1, 2, 2}, "Department", "Open", "Resolved", "MTTR", "SLA compliance");
            for (DepartmentRow r : o.byDepartment()) {
                row(department, r.name(), String.valueOf(r.open()), String.valueOf(r.resolved()), minutes(r.mttrMinutes()), percent(r.slaCompliance()));
            }
            empty(department, o.byDepartment(), 5);
            document.add(department);

            section(document, "Agents");
            PdfPTable agents = table(new float[]{3, 3, 1, 2, 1}, "Agent", "Department", "Resolved", "Avg resolution", "Open");
            for (AgentRow r : o.agentPerformance()) {
                row(agents, r.name(), r.departmentName() == null ? "-" : r.departmentName(), String.valueOf(r.resolved()),
                        minutes(r.avgResolutionMinutes()), String.valueOf(r.open()));
            }
            empty(agents, o.agentPerformance(), 5);
            document.add(agents);
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the analytics PDF", e);
        }
        return out.toByteArray();
    }

    public static ResponseEntity<byte[]> response(String fileName, byte[] document) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(document);
    }

    private static void section(Document document, String title) throws DocumentException {
        Paragraph p = new Paragraph(title, SECTION);
        p.setSpacingBefore(12);
        p.setSpacingAfter(4);
        document.add(p);
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        for (String h : headers) {
            PdfPCell c = new PdfPCell(new Phrase(h, HEAD));
            c.setBackgroundColor(HEADER);
            c.setPadding(4);
            t.addCell(c);
        }
        t.setHeaderRows(1);
        return t;
    }

    private static void row(PdfPTable t, String... values) {
        for (int i = 0; i < values.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(values[i] == null ? "-" : values[i], BODY));
            c.setPadding(3);
            if (i > 0) {
                c.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            t.addCell(c);
        }
    }

    private static void empty(PdfPTable t, List<?> rows, int columns) {
        if (rows.isEmpty()) {
            PdfPCell c = new PdfPCell(new Phrase("No data in this period", BODY));
            c.setColspan(columns);
            c.setPadding(3);
            t.addCell(c);
        }
    }

    private static String percent(Double v) {
        return v == null ? "n/a" : v + " %";
    }

    private static String minutes(Double v) {
        return v == null ? "n/a" : v + " min";
    }
}
