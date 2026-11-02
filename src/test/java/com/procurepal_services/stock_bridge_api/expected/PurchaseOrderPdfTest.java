package com.procurepal_services.stock_bridge_api.expected;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * PURCHASE_ORDER_PLAN.md phase 2: the PDF is drawn from the frozen snapshot alone. Set
 * {@code PO_PDF_OUT} to a folder to keep the files for looking at.
 */
class PurchaseOrderPdfTest {

    private final PurchaseOrderPdf pdf = new PurchaseOrderPdf();

    @Test
    void aShortOrderSaysWhatIsWantedWhereAndWhen() throws Exception {
        byte[] bytes = pdf.render(order(2, "PO-0007", 1, "Dami's Kitchen", true), null);
        keep("short", bytes);

        String text = text(bytes);
        assertThat(bytes).startsWith("%PDF".getBytes());
        assertThat(pages(bytes)).isEqualTo(1);
        assertThat(text).contains("PURCHASE ORDER", "PO-0007", "Dami's Kitchen", "Tony Stores", "Lekki branch",
                "12 Nov 2026", "Rice (Mama Gold)", "Bag · 50 kg", "20", "Please reply with your price");
    }

    @Test
    void thereAreNoPricesTotalsOrTaxAnywhere() throws Exception {
        String text = text(pdf.render(order(3, "PO-0001", 1, "Co", true), null));

        assertThat(text).doesNotContain("₦").doesNotContain("Total").doesNotContain("VAT").doesNotContain("Price:");
    }

    @Test
    void aRevisionIsMarkedAsOne() throws Exception {
        String text = text(pdf.render(order(2, "PO-0007", 2, "Co", true), null));

        assertThat(text).contains("Revised (2)");
        assertThat(text(pdf.render(order(2, "PO-0007", 1, "Co", true), null))).doesNotContain("Revised");
    }

    @Test
    void aLongOrderRunsOverPagesAndKeepsItsHeaderRow() throws Exception {
        byte[] bytes = pdf.render(order(60, "PO-0042", 1, "Co", true), null);
        keep("long", bytes);

        assertThat(pages(bytes)).isGreaterThan(1);
        assertThat(text(bytes)).contains("Product 60", "Page 2");
    }

    @Test
    void namesWithAccentsAndDotsBelowDrawInsteadOfBecomingBoxes() throws Exception {
        PurchaseOrderSnapshot order = new PurchaseOrderSnapshot(
                "PO-0003", 1, LocalDate.of(2026, 11, 2),
                new PurchaseOrderSnapshot.Party("Ọ̀ṣun Café", "08030000000", null, "12 Ẹ̀gbá Rd, Ìkeja", null),
                new PurchaseOrderSnapshot.Party("Ẹlẹ́ran Ìbàdàn Ltd", null, null, null, null),
                null, null, null,
                List.of(new PurchaseOrderSnapshot.Line("Ẹran màlúù", "EM-1", "Kilogram", new BigDecimal("12.5"))));
        byte[] bytes = pdf.render(order, null);
        keep("yoruba", bytes);

        assertThat(text(bytes)).contains("Ìkeja").contains("12.5");
        assertThat(text(bytes)).contains("As soon as you can");
    }

    @Test
    void aLogoIsDrawnWhenThereIsOneAndOmittedWithoutAnEmptyBox() throws Exception {
        byte[] withLogo = pdf.render(order(2, "PO-0008", 1, "Dami's Kitchen", true), logo());
        byte[] without = pdf.render(order(2, "PO-0008", 1, "Dami's Kitchen", true), null);
        keep("with-logo", withLogo);
        keep("no-logo", without);

        assertThat(withLogo.length).isGreaterThan(without.length);
        assertThat(text(withLogo)).contains("Dami's Kitchen");
        assertThat(text(without)).contains("Dami's Kitchen");
    }

    @Test
    void aLogoThatWillNotDrawIsLeftOutRatherThanFailingTheOrder() throws Exception {
        byte[] bytes = pdf.render(order(2, "PO-0009", 1, "Co", true), new byte[] {1, 2, 3, 4});

        assertThat(text(bytes)).contains("PO-0009");
    }

    @Test
    void quantitiesReadAsPeopleWriteThem() {
        assertThat(PurchaseOrderPdf.quantity(new BigDecimal("20.0000"))).isEqualTo("20");
        assertThat(PurchaseOrderPdf.quantity(new BigDecimal("1E+2"))).isEqualTo("100");
        assertThat(PurchaseOrderPdf.quantity(new BigDecimal("2.50"))).isEqualTo("2.5");
    }

    // ----------------------------------------------------------------------------------- helpers ---

    private static PurchaseOrderSnapshot order(int lines, String number, int revision, String buyer, boolean note) {
        List<PurchaseOrderSnapshot.Line> items = new ArrayList<>();
        for (int i = 1; i <= lines; i++) {
            items.add(new PurchaseOrderSnapshot.Line(
                    i == 1 ? "Rice (Mama Gold)" : "Product " + i, "SKU-" + i, "Bag · 50 kg", new BigDecimal("20.0000")));
        }
        return new PurchaseOrderSnapshot(
                number, revision, LocalDate.of(2026, 11, 2),
                new PurchaseOrderSnapshot.Party(buyer, "08031112222", "owner@damis.example", "5 Admiralty Way, Lekki, Lagos", null),
                new PurchaseOrderSnapshot.Party("Tony Stores", "08030000000", "tony@stores.example", "Mile 12 Market, Lagos", null),
                new PurchaseOrderSnapshot.Delivery("Lekki branch", "5 Admiralty Way, Lekki", "08031112222"),
                LocalDate.of(2026, 11, 12), note ? "Please call before you set out." : null, items);
    }

    private static byte[] logo() throws IOException {
        BufferedImage image = new BufferedImage(400, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x0E, 0x7C, 0x5A));
        g.fillRect(0, 0, 400, 160);
        g.setColor(Color.WHITE);
        g.fillOval(30, 30, 100, 100);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String text(byte[] bytes) throws IOException {
        PdfReader reader = new PdfReader(bytes);
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder all = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            all.append(extractor.getTextFromPage(page)).append('\n');
        }
        return all.toString();
    }

    private static int pages(byte[] bytes) throws IOException {
        return new PdfReader(bytes).getNumberOfPages();
    }

    private static void keep(String name, byte[] bytes) throws IOException {
        String dir = System.getenv("PO_PDF_OUT");
        if (dir != null) {
            Files.createDirectories(Path.of(dir));
            Files.write(Path.of(dir, name + ".pdf"), bytes);
        }
    }
}
