package dev.atlas;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "atlas.seguranca.chave-api=chave-navegador")
class InterfaceNavegadorTest {

	private static final Path DIRETORIO_UPLOAD = Path.of(
			System.getProperty("java.io.tmpdir"), "atlas-browser-test-" + UUID.randomUUID());
	private static Playwright playwright;
	private static Browser navegador;
	private BrowserContext contexto;
	private Page pagina;
	@TempDir Path arquivos;
	@LocalServerPort int porta;

	@DynamicPropertySource
	static void propriedades(DynamicPropertyRegistry registry) {
		registry.add("atlas.documentos.diretorio", () -> DIRETORIO_UPLOAD.toString());
	}

	@BeforeAll
	static void abrirNavegador() {
		playwright = Playwright.create();
		navegador = playwright.chromium().launch();
	}

	@BeforeEach
	void abrirPagina() {
		contexto = navegador.newContext();
		pagina = contexto.newPage();
		pagina.navigate("http://127.0.0.1:" + porta + "/");
		pagina.locator("#api-key").fill("chave-navegador");
	}

	@AfterEach
	void fecharPagina() {
		if (contexto != null) contexto.close();
	}

	@AfterAll
	static void limpar() throws Exception {
		if (navegador != null) navegador.close();
		if (playwright != null) playwright.close();
		if (Files.exists(DIRETORIO_UPLOAD)) {
			try (var caminhos = Files.walk(DIRETORIO_UPLOAD)) {
				for (Path caminho : caminhos.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(caminho);
			}
		}
	}

	@Test
	void enviaPdfRegistraMedidaEAbreEvidenciaNoTexto() throws Exception {
		enviarPdf("Capacidade específica: 160 mAh/g no ciclo 5.");
		assertThat(pagina.locator("#status")).hasText("Texto extraído de 1 página(s).");
		assertThat(pagina.locator("#page-text")).containsText("Capacidade específica: 160 mAh/g no ciclo 5.");
		pagina.locator("#object").fill("LiFePO4");
		pagina.locator("#metric").fill("capacidade específica");
		pagina.locator("#value").fill("160");
		pagina.locator("#unit").fill("mAh/g");
		pagina.locator("#excerpt").fill("160 mAh/g");
		pagina.locator("#save-button").click();

		assertThat(pagina.locator("#status")).hasText("Medida registrada. Confira a página e o trecho exibidos abaixo.");
		assertThat(pagina.locator("#measure-list article")).containsText("LiFePO4: capacidade específica");
		assertThat(pagina.locator("#measure-list article")).containsText("160 mAh/g");
		assertThat(pagina.locator("#measure-list article")).containsText("Página 1");
		pagina.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON,
				new Page.GetByRoleOptions().setName("Ver no texto extraído")).click();
		assertThat(pagina.locator("#page-text mark")).hasText("160 mAh/g");
	}

	@Test
	void pdfSemTextoExibeAvisoEOcultaRegistroDeMedidas() throws Exception {
		enviarPdf("");
		assertThat(pagina.locator("#ocr-notice")).isVisible();
		assertThat(pagina.locator("#candidate")).isHidden();
		assertThat(pagina.locator("#page-text")).hasText("(Nenhum texto pesquisável nesta página.)");
		assertThat(pagina.locator("#upload-button")).isEnabled();
	}

	@Test
	void permiteCorrigirChaveDepoisDeUmErroDeAcesso() throws Exception {
		pagina.locator("#api-key").fill("incorreta");
		enviarPdf("Documento para testar acesso.");
		assertThat(pagina.locator("#status")).hasText("Chave de acesso ausente ou incorreta.");
		assertThat(pagina.locator("#extraction")).isHidden();
		assertThat(pagina.locator("#upload-button")).isEnabled();

		pagina.locator("#api-key").fill("chave-navegador");
		pagina.locator("#upload-button").click();
		assertThat(pagina.locator("#status")).hasText("Texto extraído de 1 página(s).");
		assertThat(pagina.locator("#page-text")).containsText("Documento para testar acesso.");
	}

	@Test
	void novoEnvioLimpaFormularioEResultadosMesmoQuandoFalha() throws Exception {
		enviarPdf("Texto do primeiro documento.");
		assertThat(pagina.locator("#candidate")).isVisible();
		pagina.locator("#object").fill("Material anterior");
		pagina.locator("#metric").fill("capacidade");
		pagina.locator("#state").selectOption("AUSENTE");
		pagina.locator("#save-button").click();
		assertThat(pagina.locator("#measure-list article")).containsText("Material anterior");

		enviarPdf("Texto do segundo documento.", "Outra página.");
		assertThat(pagina.locator("#status")).hasText("Texto extraído de 2 página(s).");
		assertThat(pagina.locator("#page-text")).containsText("Texto do segundo documento.");
		assertThat(pagina.locator("#object")).hasValue("");
		assertThat(pagina.locator("#state")).hasValue("REPORTADO");
		assertThat(pagina.locator("#value")).isEnabled();
		assertThat(pagina.locator("#excerpt-field")).isVisible();
		assertThat(pagina.locator("#measure-list article")).hasCount(0);
		pagina.locator("#page-select").selectOption("2");
		assertThat(pagina.locator("#page-text")).containsText("Outra página.");
		assertThat(pagina.locator("#evidence-page")).hasValue("2");

		Path invalido = Files.writeString(arquivos.resolve("invalido.pdf"), "arquivo sem assinatura PDF");
		pagina.locator("#pdf").setInputFiles(invalido);
		pagina.locator("#upload-button").click();
		assertThat(pagina.locator("#status")).hasText("O conteúdo enviado não tem assinatura de PDF.");
		assertThat(pagina.locator("#extraction")).isHidden();
		assertThat(pagina.locator("#candidate")).isHidden();
		assertThat(pagina.locator("#results")).isHidden();
	}

	private void enviarPdf(String... textos) throws Exception {
		Path pdf = Files.write(arquivos.resolve("artigo.pdf"), pdfComTexto(textos));
		pagina.locator("#pdf").setInputFiles(pdf);
		pagina.locator("#consent").check();
		pagina.locator("#upload-button").click();
	}

	@Test
	void buscaDoiMostraTextoExternoSemHtmlELimpaResultadoAoFalhar() {
		String consulta = "**/api/catalogo/obras/por-doi?**";
		pagina.route(consulta, route -> route.fulfill(new Route.FulfillOptions()
				.setContentType("application/json").setBody("""
				{"fonte":"OPENALEX","openAlexId":"https://openalex.org/W123","doi":"10.1234/artigo",
				"titulo":"<img src=x onerror=alert(1)>","ano":2022,"autores":["Uma autora","Outro autor"]}
				""")));
		pagina.locator("#doi").fill("https://doi.org/10.1234/artigo");
		pagina.locator("#doi-button").click();
		assertThat(pagina.locator("#catalog-status")).hasText("Artigo encontrado.");
		assertThat(pagina.locator("#work-title")).hasText("<img src=x onerror=alert(1)>");
		assertThat(pagina.locator("#catalog-result img")).hasCount(0);
		assertThat(pagina.locator("#work-authors")).hasText("Uma autora; Outro autor");
		assertThat(pagina.locator("#work-year")).hasText("Ano: 2022");
		assertThat(pagina.locator("#work-openalex")).hasAttribute("href", "https://openalex.org/W123");
		assertThat(pagina.locator("#work-doi")).hasAttribute("href", "https://doi.org/10.1234%2Fartigo");

		pagina.unroute(consulta);
		pagina.route(consulta, route -> route.fulfill(new Route.FulfillOptions().setStatus(404)
				.setContentType("application/problem+json").setBody("{\"detail\":\"Nenhuma obra encontrada no OpenAlex para este DOI.\"}")));
		pagina.locator("#doi").fill("10.1234/inexistente");
		pagina.locator("#doi-button").click();
		assertThat(pagina.locator("#catalog-status")).hasText("Nenhuma obra encontrada no OpenAlex para este DOI.");
		assertThat(pagina.locator("#catalog-result")).isHidden();
		assertThat(pagina.locator("#doi-button")).isEnabled();
	}

	private static byte[] pdfComTexto(String... textos) throws Exception {
		try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
			for (String texto : textos) {
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
			documento.save(saida);
			return saida.toByteArray();
		}
	}
}
