package dev.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import dev.atlas.documentos.EstadoExtracaoPdf;
import dev.atlas.documentos.ExtratorTextoPdf;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExtratorTextoPdfTest {

	@TempDir Path diretorio;
	private final ExtratorTextoPdf extrator = new ExtratorTextoPdf();

	@Test
	void extraiTextoDeCadaPaginaSemMisturarAsPaginas() throws Exception {
		Path pdf = gravarPdf(documentoComTexto());

		var resultado = extrator.extrair(pdf);

		assertThat(resultado.estado()).isEqualTo(EstadoExtracaoPdf.TEXTO_EXTRAIDO);
		assertThat(resultado.paginas()).hasSize(2);
		assertThat(resultado.paginas().get(0).numero()).isEqualTo(1);
		assertThat(resultado.paginas().get(0).texto()).contains("Evidencia da primeira pagina");
		assertThat(resultado.paginas().get(1).numero()).isEqualTo(2);
		assertThat(resultado.paginas().get(1).texto()).contains("Resultado da segunda pagina");
		assertThat(resultado.paginas().get(0).texto()).doesNotContain("segunda pagina");
	}

	@Test
	void pdfSemCamadaDeTextoFicaMarcadoParaOcr() throws Exception {
		Path pdf = gravarPdf(documentoSemTexto());

		var resultado = extrator.extrair(pdf);

		assertThat(resultado.estado()).isEqualTo(EstadoExtracaoPdf.PRECISA_OCR);
		assertThat(resultado.paginas()).hasSize(1);
		assertThat(resultado.paginas().getFirst().numero()).isEqualTo(1);
		assertThat(resultado.paginas().getFirst().texto()).isBlank();
	}

	private Path gravarPdf(byte[] conteudo) throws Exception {
		Path caminho = diretorio.resolve("artigo.pdf");
		return Files.write(caminho, conteudo);
	}

	private static byte[] documentoComTexto() throws Exception {
		try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
			adicionarPagina(documento, "Evidencia da primeira pagina");
			adicionarPagina(documento, "Resultado da segunda pagina");
			documento.save(saida);
			return saida.toByteArray();
		}
	}

	private static byte[] documentoSemTexto() throws Exception {
		try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
			documento.addPage(new PDPage());
			documento.save(saida);
			return saida.toByteArray();
		}
	}

	private static void adicionarPagina(PDDocument documento, String texto) throws Exception {
		PDPage pagina = new PDPage();
		documento.addPage(pagina);
		try (PDPageContentStream conteudo = new PDPageContentStream(documento, pagina)) {
			conteudo.beginText();
			conteudo.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
			conteudo.newLineAtOffset(72, 720);
			conteudo.showText(texto);
			conteudo.endText();
		}
	}
}
