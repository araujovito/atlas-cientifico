package dev.atlas;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InterfaceNavegadorTest {

	private static final Path DIRETORIO_UPLOAD = Path.of(
			System.getProperty("java.io.tmpdir"), "atlas-browser-test-" + UUID.randomUUID());

	@LocalServerPort int porta;

	@DynamicPropertySource
	static void propriedades(DynamicPropertyRegistry registry) {
		registry.add("atlas.documentos.diretorio", () -> DIRETORIO_UPLOAD.toString());
	}

	@Test
	void enviaPdfRegistraMedidaEAbreEvidenciaNoTexto() throws Exception {
		Path pdf = Files.createTempFile("atlas-navegador-", ".pdf");
		Files.write(pdf, pdfComTexto("Capacidade específica: 160 mAh/g no ciclo 5."));

		try (Playwright playwright = Playwright.create(); Browser navegador = playwright.chromium().launch()) {
			Page pagina = navegador.newPage();
			pagina.navigate("http://127.0.0.1:" + porta + "/");
			pagina.locator("#pdf").setInputFiles(pdf);
			pagina.locator("#consent").check();
			pagina.locator("#upload-button").click();

			pagina.getByText("Texto extraído de 1 página(s).").waitFor();
			assertThat(pagina.locator("#page-text").textContent())
					.contains("Capacidade específica: 160 mAh/g no ciclo 5.");

			pagina.locator("#object").fill("LiFePO4");
			pagina.locator("#metric").fill("capacidade específica");
			pagina.locator("#value").fill("160 mAh/g");
			pagina.locator("#unit").fill("mAh/g");
			pagina.locator("#excerpt").fill("160 mAh/g");
			pagina.locator("#save-button").click();

			pagina.getByText("Medida registrada. Confira a página e o trecho exibidos abaixo.").waitFor();
			assertThat(pagina.locator("#measure-list article").textContent())
					.contains("LiFePO4: capacidade específica", "160 mAh/g", "Página 1");

			pagina.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
					new Page.GetByRoleOptions().setName("Ver no texto extraído")).click();
			assertThat(pagina.locator("#page-text mark").textContent()).isEqualTo("160 mAh/g");
		} finally {
			Files.deleteIfExists(pdf);
			if (Files.exists(DIRETORIO_UPLOAD)) {
				try (var arquivos = Files.walk(DIRETORIO_UPLOAD)) {
					for (Path caminho : arquivos.sorted(Comparator.reverseOrder()).toList()) {
						Files.deleteIfExists(caminho);
					}
				}
			}
		}
	}

	private static byte[] pdfComTexto(String texto) throws Exception {
		try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
			PDPage pagina = new PDPage();
			documento.addPage(pagina);
			try (PDPageContentStream conteudo = new PDPageContentStream(documento, pagina)) {
				conteudo.beginText();
				conteudo.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				conteudo.newLineAtOffset(72, 720);
				conteudo.showText(texto);
				conteudo.endText();
			}
			documento.save(saida);
			return saida.toByteArray();
		}
	}
}
