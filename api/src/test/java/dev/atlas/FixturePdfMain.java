package dev.atlas;

import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** Fixture inteiramente sintética para testar o artefato empacotado, sem artigo de terceiros. */
public class FixturePdfMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Informe o caminho do PDF de teste.");
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf,page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(50,700); content.showText("Synthetic capacity 125 mAh/g at 25 C."); content.endText();
            }
            pdf.save(Path.of(args[0]).toFile());
        }
    }
}
