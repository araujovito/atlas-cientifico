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
		return extrair(arquivo, Integer.MAX_VALUE);
	}

	/** Recusa o PDF antes de extrair qualquer texto se ele passar de paginasMaximas. */
	public ResultadoExtracaoPdf extrair(Path arquivo, int paginasMaximas) throws IOException {
		try (PDDocument documento = Loader.loadPDF(arquivo.toFile())) {
			if (documento.getNumberOfPages() > paginasMaximas) {
				throw new LimiteDePaginasExcedidoException(documento.getNumberOfPages(), paginasMaximas);
			}
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
