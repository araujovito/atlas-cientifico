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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import dev.atlas.fontes.openalex.OpenAlexClient;
import dev.atlas.catalogo.ObraEncontrada;

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
	private String emailConta;
	@TempDir Path arquivos;
	@LocalServerPort int porta;
	@MockitoBean OpenAlexClient fonte;

	@DynamicPropertySource
	static void propriedades(DynamicPropertyRegistry registry) {
		registry.add("atlas.documentos.diretorio", () -> DIRETORIO_UPLOAD.toString());
		// O WebKit (Safari) não guarda cookie Secure em http://127.0.0.1, ao contrário de Chromium e Firefox.
		registry.add("atlas.contas.cookie-seguro", () -> !"webkit".equals(System.getProperty("atlas.navegador")));
	}

	@BeforeAll
	static void abrirNavegador() {
		com.microsoft.playwright.assertions.PlaywrightAssertions.setDefaultAssertionTimeout(15000);
		playwright = Playwright.create();
		// -Datlas.navegador=firefox|webkit repete a classe em outro motor; o padrão é Chromium.
		navegador = switch (System.getProperty("atlas.navegador", "chromium")) {
			case "firefox" -> playwright.firefox().launch();
			case "webkit" -> playwright.webkit().launch();
			default -> playwright.chromium().launch();
		};
		System.out.println("Motor de navegador do teste: " + navegador.browserType().name() + " " + navegador.version());
	}

	@BeforeEach
	void abrirPagina() {
		contexto = navegador.newContext();
		pagina = contexto.newPage();
		pagina.navigate("http://127.0.0.1:" + porta + "/");
		emailConta="navegador-"+UUID.randomUUID()+"@example.com";
		assertThat(pagina.locator("#auth-register")).isEnabled();
		pagina.locator("#auth-email").fill(emailConta);
		pagina.locator("#auth-password").fill(ContasTeste.SENHA);
		pagina.locator("#auth-register").click();
		assertThat(pagina.locator("#workspace")).isVisible();
		assertThat(pagina.locator("#account-email")).hasText(emailConta);
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
		pagina.reload();
		pagina.locator("#resume-processing").click();
		assertThat(pagina.locator("#status")).hasText("Texto extraído de 1 página(s).");
		assertThat(pagina.locator("#measure-list article")).containsText("LiFePO4: capacidade específica");
		pagina.locator("#operation-refresh").click();
		assertThat(pagina.locator("#operation-status")).containsText("CONCLUIDO:");
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
	void permiteEntrarNovamenteDepoisDeSairSemManterConteudoDaContaNoDom() throws Exception {
		enviarPdf("Documento para testar acesso.");
		assertThat(pagina.locator("#page-text")).containsText("Documento para testar acesso.");
		org.assertj.core.api.Assertions.assertThat((String)pagina.evaluate("document.cookie")).doesNotContain("__Host-atlas-sessao");
		pagina.locator("#auth-logout").click();
		assertThat(pagina.locator("#auth-form")).isVisible();
		assertThat(pagina.locator("#workspace")).isHidden();
		assertThat(pagina.locator("#page-text")).isEmpty();
		assertThat(pagina.locator("#auth-login")).isEnabled();
		pagina.locator("#auth-email").fill(emailConta);
		pagina.locator("#auth-password").fill("Uma senha errada longa");
		pagina.locator("#auth-login").click();
		assertThat(pagina.locator("#auth-state")).hasText("E-mail ou senha incorretos.");
		pagina.locator("#auth-password").fill(ContasTeste.SENHA);
		pagina.locator("#auth-login").click();
		assertThat(pagina.locator("#workspace")).isVisible();
		enviarPdf("Documento para testar acesso.");
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
	void comparaMedidasERevisaSemApagarOriginal() throws Exception {
		enviarPdf("Capacidade 160 mAh/g. Segundo valor 161 mAh/g.");
		assertThat(pagina.locator("#candidate")).isVisible();
		for (String valor : java.util.List.of("160","161")) {
			pagina.locator("#object").fill("NCM622"); pagina.locator("#metric").fill("capacidade");
			pagina.locator("#value").fill(valor); pagina.locator("#unit").fill("mAh/g"); pagina.locator("#excerpt").fill(valor+" mAh/g");
			pagina.locator("#save-button").click();
			assertThat(pagina.locator("#measure-list article")).hasCount(valor.equals("160")?1:2);
		}
		pagina.locator("#measure-list input[type=checkbox]").nth(0).check();
		pagina.locator("#measure-list input[type=checkbox]").nth(1).check();
		pagina.locator("#compare-measures").click();
		assertThat(pagina.locator("#comparison-result tr")).hasCount(3);
		assertThat(pagina.locator("#comparison-status")).containsText("Condições incompletas");
		pagina.locator("#measure-list article").first().getByText("Ver atual / revisar").click();
		assertThat(pagina.locator("#review-fields")).isVisible();
		pagina.locator("#review-reason").fill("Falta conferir o contexto.");
		pagina.locator("#state").selectOption("PENDENTE_REVISAO"); pagina.locator("#save-button").click();
		assertThat(pagina.locator("#status")).hasText("Revisão registrada. O original foi preservado no histórico.");
		assertThat(pagina.locator("#measure-list article").first()).containsText("REPORTADO");
		pagina.locator("#compare-measures").click();
		assertThat(pagina.locator("#comparison-result")).containsText("PENDENTE_REVISAO / v1");
		// O histórico fica dentro de <details>; o texto está no DOM mesmo recolhido.
		pagina.locator("#measure-list article").first().getByText("Ver atual / revisar").click();
		assertThat(pagina.locator("#review-history")).containsText((String)pagina.evaluate("atlasUsuario.id"));
		assertThat(pagina.locator("#review-history")).containsText("Falta conferir o contexto.");
		assertThat(pagina.locator("#review-history")).containsText("160");
	}

	@Test
	void buscaDoiMostraTextoExternoSemHtmlELimpaResultadoAoFalhar() {
		org.mockito.Mockito.when(fonte.buscarPorDoi("10.1234/artigo")).thenReturn(new ObraEncontrada(
				"OPENALEX","https://openalex.org/W123","10.1234/artigo","Título conferido no servidor",2022,java.util.List.of("Uma autora")));
		org.mockito.Mockito.when(fonte.referencias("https://openalex.org/W123")).thenReturn(java.util.List.of("https://openalex.org/W456"));
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
		pagina.locator("#save-work").click();
		assertThat(pagina.locator("#library-list h3")).hasText("Título conferido no servidor");
		pagina.locator("#library-list").getByText("Consultar referências").click();
		assertThat(pagina.locator("#citation-graph svg")).isVisible();
		assertThat(pagina.locator("#citation-graph line")).hasCount(1);
		assertThat(pagina.locator("#citation-timeline")).containsText("Ano desconhecido");
		pagina.locator("#collection-name").fill("Minha coleção de teste");
		pagina.locator("#collection-form button").click();
		assertThat(pagina.locator("#library-status")).hasText("Coleção criada.");
		pagina.locator("#library-list").getByText("Adicionar à coleção").click();
		assertThat(pagina.locator("#library-status")).hasText("Obra adicionada à coleção.");
		pagina.reload();
		pagina.locator("#library-refresh").click();
		assertThat(pagina.locator("#collection-select option")).hasText("Minha coleção de teste");
		pagina.locator("#collection-show").click();
		assertThat(pagina.locator("#library-list h3")).hasText("Título conferido no servidor");

		pagina.unroute(consulta);
		pagina.route(consulta, route -> route.fulfill(new Route.FulfillOptions().setStatus(404)
				.setContentType("application/problem+json").setBody("{\"detail\":\"Nenhuma obra encontrada no OpenAlex para este DOI.\"}")));
		pagina.locator("#doi").fill("10.1234/inexistente");
		pagina.locator("#doi-button").click();
		assertThat(pagina.locator("#catalog-status")).hasText("Nenhuma obra encontrada no OpenAlex para este DOI.");
		assertThat(pagina.locator("#catalog-result")).isHidden();
		assertThat(pagina.locator("#doi-button")).isEnabled();
	}

	@Test
	void buscaPorAutorTemaEAnoPaginaEDescartaResultadoAntigoAoMudarDePagina() {
		org.mockito.Mockito.when(fonte.buscarAutores("Silva")).thenReturn(java.util.List.of(
				new OpenAlexClient.AutorEncontrado("https://openalex.org/A1", "Ana Silva"),
				new OpenAlexClient.AutorEncontrado("https://openalex.org/A2", "Ana Silva")));
		org.mockito.Mockito.when(fonte.buscar("lithium", "https://openalex.org/A2", 2022, 1, 10))
				.thenReturn(new OpenAlexClient.PaginaObras(12, 1, 10, obrasDaPagina("p1", 10)));
		org.mockito.Mockito.when(fonte.buscar("lithium", "https://openalex.org/A2", 2022, 2, 10))
				.thenReturn(new OpenAlexClient.PaginaObras(12, 2, 10, obrasDaPagina("p2", 2)));

		pagina.locator("#author-name").fill("Silva");
		pagina.locator("#author-form button").click();
		assertThat(pagina.locator("#author-filter option")).hasCount(3);
		pagina.locator("#author-filter").selectOption("https://openalex.org/A2");
		pagina.locator("#theme").fill("lithium");
		pagina.locator("#search-year").fill("2022");
		pagina.locator("#discovery-form button").click();

		assertThat(pagina.locator("#discovery-page")).hasText("Página 1 · 12 resultado(s)");
		assertThat(pagina.locator("#discovery-results article")).hasCount(10);
		assertThat(pagina.locator("#search-previous")).isDisabled();
		assertThat(pagina.locator("#search-next")).isEnabled();

		pagina.locator("#search-next").click();
		assertThat(pagina.locator("#discovery-page")).hasText("Página 2 · 12 resultado(s)");
		assertThat(pagina.locator("#discovery-results article")).hasCount(2);
		assertThat(pagina.locator("#discovery-results")).containsText("Obra p2-0");
		assertThat(pagina.locator("#discovery-results")).not().containsText("Obra p1-0");
		assertThat(pagina.locator("#search-next")).isDisabled();

		pagina.locator("#search-previous").click();
		assertThat(pagina.locator("#discovery-page")).hasText("Página 1 · 12 resultado(s)");
		assertThat(pagina.locator("#discovery-results article")).hasCount(10);
	}

	private static java.util.List<ObraEncontrada> obrasDaPagina(String prefixo, int quantidade) {
		var obras = new java.util.ArrayList<ObraEncontrada>();
		for (int i = 0; i < quantidade; i++) {
			obras.add(new ObraEncontrada("OPENALEX", "https://openalex.org/W" + (prefixo.equals("p1") ? 100 : 200) + i,
					"10.1234/" + prefixo + "-" + i, "Obra " + prefixo + "-" + i, 2022, java.util.List.of("Ana Silva")));
		}
		return obras;
	}

	@Test
	void donoTornaOPdfPublicoEVoltaAPrivadoEAListaPublicaMostraSoEsseDocumentoQuandoPublico() throws Exception {
		enviarPdf("Capacidade 160 mAh/g.");
		assertThat(pagina.locator("#share-state")).hasText("Este PDF é privado: só você o baixa.");
		String href = pagina.locator("#share-download").getAttribute("href");
		assertThat(pagina.locator("#share-download")).hasAttribute("href", java.util.regex.Pattern.compile("/api/documentos/[0-9a-f-]{36}/original"));
		String id = href.replace("/api/documentos/", "").replace("/original", "");
		pagina.locator("#share-toggle").click();
		assertThat(pagina.locator("#share-state")).hasText("Este PDF é público: outras contas podem baixá-lo.");
		pagina.locator("#public-refresh").click();
		// O banco é compartilhado entre as classes de teste: confere só este documento, não a contagem total.
		assertThat(pagina.locator("#public-list a[href*='" + id + "']")).hasCount(1);
		pagina.locator("#share-toggle").click();
		assertThat(pagina.locator("#share-state")).hasText("Este PDF é privado: só você o baixa.");
		pagina.locator("#public-refresh").click();
		assertThat(pagina.locator("#public-status")).not().hasText("");
		assertThat(pagina.locator("#public-list a[href*='" + id + "']")).hasCount(0);
	}

	@Test
	void criarContaMostraOCodigoUmaVezEARecuperacaoTrocaASenhaPelaTela() {
		String email = "tela-" + UUID.randomUUID() + "@example.com";
		// Contexto próprio: o da classe já está com a sessão de outra conta.
		var outroContexto = navegador.newContext();
		var nova = outroContexto.newPage();
		nova.navigate("http://127.0.0.1:" + porta + "/");
		nova.locator("#auth-email").fill(email);
		nova.locator("#auth-password").fill("Senha inicial bem longa 123");
		nova.locator("#auth-register").click();
		assertThat(nova.locator("#recovery-panel")).isVisible();
		String codigo = nova.locator("#recovery-code-value").textContent();
		org.junit.jupiter.api.Assertions.assertTrue(codigo.matches("([A-Z2-9]{4}-){4}[A-Z2-9]{4}"), codigo);
		nova.locator("#recovery-ack").click();
		assertThat(nova.locator("#recovery-panel")).isHidden();
		assertThat(nova.locator("#recovery-code-value")).isEmpty();
		nova.locator("#auth-logout").click();
		// O logout recarrega a página: espera o formulário de entrada voltar antes de seguir.
		assertThat(nova.locator("#auth-form")).isVisible();
		nova.locator("#auth-forgot").click();
		nova.locator("#recover-email").fill(email);
		nova.locator("#recover-code").fill(codigo.toLowerCase());
		nova.locator("#recover-password").fill("Senha redefinida pela tela 789");
		nova.locator("#recover-submit").click();
		assertThat(nova.locator("#account-email")).hasText(email);
		assertThat(nova.locator("#recovery-panel")).isVisible();
		org.junit.jupiter.api.Assertions.assertNotEquals(codigo, nova.locator("#recovery-code-value").textContent());
		outroContexto.close();
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
