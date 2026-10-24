package dev.atlas.documentos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Extrai texto página a página sem descartar PDFs que precisam de OCR. */
@Component
public class ExtratorTextoPdf {

	public ResultadoExtracaoPdf extrair(Path arquivo) throws IOException {
		try (PDDocument documento = Loader.loadPDF(arquivo.toFile())) {
			PDFTextStripper stripper = new PDFTextStripper();
			List<PaginaPdf> paginas = new ArrayList<>(documento.getNumberOfPages());
			boolean encontrouTexto = false;

			for (int numero = 1; numero <= documento.getNumberOfPages(); numero++) {
				stripper.setStartPage(numero);
				stripper.setEndPage(numero);
				String texto = stripper.getText(documento);
				paginas.add(new PaginaPdf(numero, texto));
				encontrouTexto |= !texto.isBlank();
			}

			EstadoExtracaoPdf estado = encontrouTexto
					? EstadoExtracaoPdf.TEXTO_EXTRAIDO
					: EstadoExtracaoPdf.PRECISA_OCR;
			return new ResultadoExtracaoPdf(estado, paginas);
		}
	}
}
