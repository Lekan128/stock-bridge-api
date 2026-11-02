package com.procurepal_services.stock_bridge_api.expected;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Draws a purchase order (PURCHASE_ORDER_PLAN.md section 4.1) from its frozen
 * {@link PurchaseOrderSnapshot}. Never from live rows, and the result is never stored: every
 * download, email attachment and public-link view builds it again, which costs a few tens of
 * milliseconds for a page or two of text.
 *
 * <p>What it says is "we want this and that" - items and quantities, where to deliver, when, and
 * a line asking the supplier to reply with their price. There are no prices, totals or tax.
 *
 * <p>Set in Noto Sans, embedded, so product and supplier names with accents or dots-below
 * (Yoruba, for one) draw properly instead of as boxes.
 */
@Component
public class PurchaseOrderPdf {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final Color INK = new Color(0x17, 0x17, 0x17);
    private static final Color MUTED = new Color(0x5F, 0x5F, 0x5F);
    private static final Color RULE = new Color(0xD4, 0xD4, 0xD4);
    private static final Color SHADE = new Color(0xF2, 0xF2, 0xF2);

    private final byte[] regularBytes = resource("/fonts/NotoSans-Regular.ttf");
    private final byte[] boldBytes = resource("/fonts/NotoSans-Bold.ttf");

    /**
     * @param logo the buyer's logo as image bytes, or null for none - the company name is then set
     *     in large type on its own, with no empty box.
     */
    public byte[] render(PurchaseOrderSnapshot order, byte[] logo) {
        try {
            BaseFont regular = font(regularBytes, "NotoSans-Regular.ttf");
            BaseFont bold = font(boldBytes, "NotoSans-Bold.ttf");
            Fonts f = new Fonts(
                    new Font(regular, 9, Font.NORMAL, INK),
                    new Font(regular, 8, Font.NORMAL, MUTED),
                    new Font(bold, 9, Font.NORMAL, INK),
                    new Font(bold, 8, Font.NORMAL, MUTED),
                    new Font(bold, 18, Font.NORMAL, INK),
                    new Font(bold, 12, Font.NORMAL, INK));

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Document document = new Document(PageSize.A4, 40, 40, 40, 50);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PageNumbers(regular));
            document.addTitle(order.poNumber() + " - " + order.supplier().name());
            document.open();

            document.add(header(order, logo, f));
            document.add(space(14));
            document.add(parties(order, f));
            document.add(space(12));
            document.add(delivery(order, f));
            document.add(space(14));
            document.add(lines(order.lines(), f));
            if (order.note() != null && !order.note().isBlank()) {
                document.add(space(12));
                document.add(new Paragraph("Note", f.smallBold));
                document.add(new Paragraph(order.note(), f.body));
            }
            document.add(space(18));
            document.add(footer(order, f));

            document.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Could not draw purchase order " + order.poNumber(), e);
        }
    }

    // ------------------------------------------------------------------------------- sections ----

    private PdfPTable header(PurchaseOrderSnapshot order, byte[] logo, Fonts f) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {3, 2});
        table.setWidthPercentage(100);

        PdfPCell left = plain();
        Image image = logoImage(logo);
        if (image != null) {
            image.scaleToFit(130, 56);
            left.addElement(image);
            Paragraph name = new Paragraph(order.buyer().name(), f.heading);
            name.setSpacingBefore(4);
            left.addElement(name);
        } else {
            left.addElement(new Paragraph(order.buyer().name(), f.title));
        }
        table.addCell(left);

        PdfPCell right = plain();
        Paragraph label = new Paragraph("PURCHASE ORDER", f.smallBold);
        label.setAlignment(Element.ALIGN_RIGHT);
        Paragraph number = new Paragraph(order.poNumber(), f.title);
        number.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(label);
        right.addElement(number);
        String date = order.issuedOn() == null ? null : order.issuedOn().format(DATE);
        if (date != null) {
            Paragraph issued = new Paragraph("Date: " + date, f.body);
            issued.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(issued);
        }
        if (order.revision() > 1) {
            Paragraph revised = new Paragraph("Revised (" + order.revision() + ")", f.bold);
            revised.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(revised);
        }
        table.addCell(right);
        return table;
    }

    private PdfPTable parties(PurchaseOrderSnapshot order, Fonts f) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.addCell(party("From", order.buyer(), f));
        table.addCell(party("To", order.supplier(), f));
        return table;
    }

    private PdfPCell party(String heading, PurchaseOrderSnapshot.Party party, Fonts f) {
        PdfPCell cell = plain();
        cell.setPaddingRight(12);
        cell.addElement(new Paragraph(heading, f.smallBold));
        cell.addElement(new Paragraph(party.name(), f.bold));
        for (String line : new String[] {party.address(), party.phone(), party.email()}) {
            if (line != null && !line.isBlank()) {
                cell.addElement(new Paragraph(line, f.body));
            }
        }
        return cell;
    }

    private PdfPTable delivery(PurchaseOrderSnapshot order, Fonts f) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

        PdfPCell where = plain();
        where.setPaddingRight(12);
        where.addElement(new Paragraph("Deliver to", f.smallBold));
        if (order.deliverTo() != null) {
            where.addElement(new Paragraph(order.deliverTo().name(), f.bold));
            if (order.deliverTo().address() != null) {
                where.addElement(new Paragraph(order.deliverTo().address(), f.body));
            }
            if (order.deliverTo().phone() != null) {
                where.addElement(new Paragraph(order.deliverTo().phone(), f.body));
            }
        } else {
            where.addElement(new Paragraph(order.buyer().name(), f.bold));
        }
        table.addCell(where);

        PdfPCell when = plain();
        when.addElement(new Paragraph("Needed by", f.smallBold));
        LocalDate neededBy = order.neededBy();
        when.addElement(new Paragraph(neededBy == null ? "As soon as you can" : neededBy.format(DATE), f.bold));
        table.addCell(when);
        return table;
    }

    private PdfPTable lines(List<PurchaseOrderSnapshot.Line> lines, Fonts f) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[] {0.6f, 5, 2.6f, 1.6f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        // A row never splits across pages: a quantity on one page and its product on the next is
        // exactly the kind of thing that gets an order wrong.
        table.setSplitLate(false);
        for (String heading : new String[] {"#", "Item", "Comes in", "Quantity"}) {
            PdfPCell cell = new PdfPCell(new Phrase(heading, f.bold));
            cell.setBackgroundColor(SHADE);
            cell.setBorderColor(RULE);
            cell.setPadding(6);
            cell.setHorizontalAlignment("Quantity".equals(heading) ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            table.addCell(cell);
        }
        int number = 1;
        for (PurchaseOrderSnapshot.Line line : lines) {
            table.addCell(bodyCell(String.valueOf(number++), f.small, Element.ALIGN_LEFT));
            PdfPCell item = new PdfPCell();
            item.setBorderColor(RULE);
            item.setPadding(6);
            item.addElement(new Paragraph(line.productName(), f.bold));
            if (line.sku() != null && !line.sku().isBlank()) {
                item.addElement(new Paragraph(line.sku(), f.small));
            }
            table.addCell(item);
            table.addCell(bodyCell(line.comesIn(), f.body, Element.ALIGN_LEFT));
            table.addCell(bodyCell(quantity(line.quantity()), f.bold, Element.ALIGN_RIGHT));
        }
        return table;
    }

    private Paragraph footer(PurchaseOrderSnapshot order, Fonts f) {
        String ask = "Please reply with your price and when we can expect delivery.";
        String phone = order.buyer().phone();
        Paragraph paragraph = new Paragraph(phone == null ? ask : ask + " Questions: " + phone + ".", f.body);
        paragraph.setAlignment(Element.ALIGN_LEFT);
        return paragraph;
    }

    // --------------------------------------------------------------------------------- pieces ----

    private static PdfPCell plain() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(0);
        return cell;
    }

    private static PdfPCell bodyCell(String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
        cell.setBorderColor(RULE);
        cell.setPadding(6);
        cell.setHorizontalAlignment(align);
        return cell;
    }

    private static Paragraph space(float points) {
        Paragraph paragraph = new Paragraph(" ");
        paragraph.setLeading(points);
        return paragraph;
    }

    /** 20, not 20.0000 or 2E+1. */
    static String quantity(BigDecimal value) {
        if (value == null) {
            return "";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    /** A logo that will not draw (corrupt, or a format the PDF library cannot read) is left out. */
    private static Image logoImage(byte[] logo) {
        if (logo == null || logo.length == 0) {
            return null;
        }
        try {
            return Image.getInstance(logo);
        } catch (Exception e) {
            return null;
        }
    }

    private static BaseFont font(byte[] bytes, String name) throws DocumentException, IOException {
        return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
    }

    private static byte[] resource(String path) {
        try (InputStream in = PurchaseOrderPdf.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing font " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read font " + path, e);
        }
    }

    private record Fonts(Font body, Font small, Font bold, Font smallBold, Font title, Font heading) {
    }

    /** "Page 2" bottom centre; one or two pages is the normal case. */
    private static final class PageNumbers extends PdfPageEventHelper {
        private final Font font;

        PageNumbers(BaseFont base) {
            this.font = new Font(base, 8, Font.NORMAL, MUTED);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            com.lowagie.text.pdf.ColumnText.showTextAligned(
                    writer.getDirectContent(),
                    Element.ALIGN_CENTER,
                    new Phrase(new Chunk("Page " + writer.getPageNumber(), font)),
                    (document.right() + document.left()) / 2,
                    document.bottom() - 20,
                    0);
        }
    }
}
