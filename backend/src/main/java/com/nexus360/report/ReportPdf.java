package com.nexus360.report;

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
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Generic report layout on OpenPDF (same look as the analytics export): an SA heading, intro lines and titled tables.
 * Values are written as plain text cells, so nothing in the data is interpreted.
 */
public final class ReportPdf {

    private static final Color HEADER = new Color(0x25, 0x21, 0x1D);
    private static final Color GOLD = new Color(0x96, 0x76, 0x4B);
    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, GOLD);
    private static final Font SECTION = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, HEADER);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.DARK_GRAY);
    private static final Font HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

    private ReportPdf() {}

    /** {@code widths}: relative column widths (null = equal). Columns after the first are right-aligned when {@code numeric}. */
    public record Table(String heading, List<String> headers, float[] widths, List<List<String>> rows, boolean numeric) {}

    public static byte[] render(String title, List<String> intro, List<Table> tables) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 42, 36);
        try {
            PdfWriter.getInstance(document, out);
            document.addTitle("SA — " + title);
            document.addCreator("SA");
            document.open();
            document.add(new Paragraph("SA — " + title, TITLE));
            for (String line : intro) {
                document.add(new Paragraph(line, BODY));
            }
            for (Table t : tables) {
                Paragraph p = new Paragraph(t.heading(), SECTION);
                p.setSpacingBefore(12);
                p.setSpacingAfter(4);
                document.add(p);
                PdfPTable table = t.widths() == null ? new PdfPTable(t.headers().size()) : new PdfPTable(t.widths());
                table.setWidthPercentage(100);
                for (String h : t.headers()) {
                    PdfPCell c = new PdfPCell(new Phrase(h, HEAD));
                    c.setBackgroundColor(HEADER);
                    c.setPadding(4);
                    table.addCell(c);
                }
                table.setHeaderRows(1);
                for (List<String> row : t.rows()) {
                    for (int i = 0; i < row.size(); i++) {
                        PdfPCell c = new PdfPCell(new Phrase(row.get(i) == null ? "-" : row.get(i), BODY));
                        c.setPadding(3);
                        if (t.numeric() && i > 0) {
                            c.setHorizontalAlignment(Element.ALIGN_RIGHT);
                        }
                        table.addCell(c);
                    }
                }
                if (t.rows().isEmpty()) {
                    PdfPCell c = new PdfPCell(new Phrase("No data in this period", BODY));
                    c.setColspan(t.headers().size());
                    c.setPadding(3);
                    table.addCell(c);
                }
                document.add(table);
            }
            document.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the report PDF", e);
        }
        return out.toByteArray();
    }
}
