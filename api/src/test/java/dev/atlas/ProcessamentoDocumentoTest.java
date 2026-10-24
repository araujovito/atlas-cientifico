package dev.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "atlas.documentos.diretorio=${java.io.tmpdir}/atlas-processamento-test")
@AutoConfigureMockMvc
class ProcessamentoDocumentoTest {

	private static final Path DIRETORIO = Path.of(System.getProperty("java.io.tmpdir"), "atlas-processamento-test");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper objectMapper;

	@Test
	void processaPdfArmazenadoEPersisteTextoPorPagina() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Metrica experimental 125 mAh por grama", "Condicao: 25 graus"));

		MvcResult resposta = mvc.perform(post("/api/documentos/{id}/extracoes", documento))
				.andExpect(status().isOk()).andReturn();
		JsonNode resultado = objectMapper.readTree(resposta.getResponse().getContentAsString());
		UUID execucao = UUID.fromString(resultado.get("execucaoId").asText());

		assertThat(resultado.get("estado").asText()).isEqualTo("CONCLUIDO");
		assertThat(resultado.get("paginas").asInt()).isEqualTo(2);
		assertThat(resultado.get("reutilizada").asBoolean()).isFalse();
		assertThat(jdbc.sql("SELECT texto FROM pagina_texto WHERE execucao_id=:id AND numero=1")
				.param("id", execucao).query(String.class).single()).contains("125 mAh por grama");
		assertThat(jdbc.sql("SELECT texto FROM pagina_texto WHERE execucao_id=:id AND numero=2")
				.param("id", execucao).query(String.class).single()).contains("25 graus");
		assertThat(jdbc.sql("SELECT estado FROM execucao_processamento WHERE id=:id")
				.param("id", execucao).query(String.class).single()).isEqualTo("CONCLUIDO");
	}

	@Test
	void pdfSemTextoCriaExecucaoPrecisaOcr() throws Exception {
		UUID documento = enviarPdf(pdfSemTexto());

		MvcResult resposta = mvc.perform(post("/api/documentos/{id}/extracoes", documento))
				.andExpect(status().isOk()).andReturn();
		JsonNode resultado = objectMapper.readTree(resposta.getResponse().getContentAsString());
		UUID execucao = UUID.fromString(resultado.get("execucaoId").asText());

		assertThat(resultado.get("estado").asText()).isEqualTo("PRECISA_OCR");
		assertThat(resultado.get("paginas").asInt()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT texto FROM pagina_texto WHERE execucao_id=:id AND numero=1")
				.param("id", execucao).query(String.class).single()).isBlank();

		var medidaAusente = new dev.atlas.documentos.MedidaCandidata(
				"LiFePO4", "capacidade", null, null, "AUSENTE", null, null, java.util.Map.of());
		MvcResult conflito = mvc.perform(post("/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas", documento, execucao)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(medidaAusente)))
			.andExpect(status().isConflict()).andReturn();
		assertThat(((org.springframework.web.server.ResponseStatusException) conflito.getResolvedException()).getReason())
				.isEqualTo("Esta extração precisa de OCR; não é possível registrar medidas sem texto pesquisável.");
		assertThat(medidasDaExecucao(execucao)).isZero();
	}

	@Test
	void guardaApenasONomeDoArquivoEAindaProcessaChaveAbsolutaAntiga() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Documento com chave relativa"));
		String chave = jdbc.sql("SELECT chave_armazenamento FROM versao_documento WHERE id=:id")
				.param("id", documento).query(String.class).single();
		String hash = jdbc.sql("SELECT sha256 FROM versao_documento WHERE id=:id")
				.param("id", documento).query(String.class).single();
		assertThat(chave).isEqualTo(hash + ".pdf");

		// Linhas gravadas antes da mudança têm o caminho absoluto.
		jdbc.sql("UPDATE versao_documento SET chave_armazenamento=:c WHERE id=:id")
				.param("c", DIRETORIO.toAbsolutePath().resolve(chave).toString()).param("id", documento).update();
		assertThat(processar(documento).get("estado").asText()).isEqualTo("CONCLUIDO");
	}

	@Test
	void reprocessarMesmoPdfNaoCriaOutraExecucaoNemDuplicaPaginas() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Resultado idempotente"));
		JsonNode primeira = processar(documento);
		Path arquivo = DIRETORIO.resolve(jdbc.sql("SELECT chave_armazenamento FROM versao_documento WHERE id=:id")
				.param("id", documento).query(String.class).single());
		Files.delete(arquivo);
		JsonNode segunda = processar(documento);

		assertThat(segunda.get("execucaoId").asText()).isEqualTo(primeira.get("execucaoId").asText());
		assertThat(segunda.get("reutilizada").asBoolean()).isTrue();
		assertThat(jdbc.sql("SELECT count(*) FROM execucao_processamento WHERE versao_documento_id=:id")
				.param("id", documento).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM pagina_texto p
				JOIN execucao_processamento e ON e.id=p.execucao_id
				WHERE e.versao_documento_id=:id
				""").param("id", documento).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void documentoInexistenteRetornaNaoEncontrado() throws Exception {
		mvc.perform(post("/api/documentos/{id}/extracoes", UUID.randomUUID()))
				.andExpect(status().isNotFound());
	}

	@Test
	void paginaInicialApresentaInterfaceMinimaDaDemonstracao() throws Exception {
		mvc.perform(get("/index.html"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
						.string(org.hamcrest.Matchers.containsString("Registrar uma medida candidata")))
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
						.string(org.hamcrest.Matchers.containsString("Trecho literal")));
	}

	@Test
	void consultaTextoPorPaginaNaOrdemOriginal() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Trecho da pagina inicial", "Trecho da segunda pagina"));
		JsonNode processamento = processar(documento);
		UUID execucao = UUID.fromString(processamento.get("execucaoId").asText());

		mvc.perform(get("/api/documentos/{documentoId}/extracoes/{execucaoId}/paginas", documento, execucao))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].numero").value(1))
				.andExpect(jsonPath("$[0].texto").value(org.hamcrest.Matchers.containsString("Trecho da pagina inicial")))
				.andExpect(jsonPath("$[1].numero").value(2))
				.andExpect(jsonPath("$[1].texto").value(org.hamcrest.Matchers.containsString("Trecho da segunda pagina")));
	}

	@Test
	void naoConsultaExecucaoDeOutroDocumento() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Texto do documento"));
		JsonNode processamento = processar(documento);
		UUID execucao = UUID.fromString(processamento.get("execucaoId").asText());

		mvc.perform(get("/api/documentos/{documentoId}/extracoes/{execucaoId}/paginas", UUID.randomUUID(), execucao))
				.andExpect(status().isNotFound());
	}

	@Test
	void registraMedidaSomenteComTrechoLiteralDaPaginaEPreservaUnidade() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Capacidade específica: 125 mAh/g medida no ciclo inicial"));
		JsonNode processamento = processar(documento);
		UUID execucao = UUID.fromString(processamento.get("execucaoId").asText());
		var candidata = new dev.atlas.documentos.MedidaCandidata(
				"LiFePO4", "capacidade específica", "125", "mAh/g", "REPORTADO", 1,
				"Capacidade específica: 125 mAh/g", java.util.Map.of("taxa", "0,1 C"));

		MvcResult resposta = mvc.perform(post("/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas", documento, execucao)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(candidata)))
			.andExpect(status().isOk()).andReturn();
		JsonNode registrada = objectMapper.readTree(resposta.getResponse().getContentAsString());

		assertThat(registrada.get("estado").asText()).isEqualTo("REPORTADO");
		assertThat(jdbc.sql("SELECT valor_original || ' ' || unidade_original FROM medida WHERE id=:id")
				.param("id", UUID.fromString(registrada.get("medidaId").asText())).query(String.class).single())
				.isEqualTo("125 mAh/g");
		assertThat(jdbc.sql("""
				SELECT x.condicoes ->> 'taxa' FROM experimento x
				JOIN medida m ON m.experimento_id=x.id WHERE m.id=:id
				""").param("id", UUID.fromString(registrada.get("medidaId").asText()))
				.query(String.class).single()).isEqualTo("0,1 C");
		assertThat(jdbc.sql("SELECT pagina || ':' || trecho FROM evidencia WHERE id=:id")
				.param("id", UUID.fromString(registrada.get("evidenciaId").asText())).query(String.class).single())
				.isEqualTo("1:Capacidade específica: 125 mAh/g");
		assertThat(jdbc.sql("SELECT inicio_caractere FROM evidencia WHERE id=:id")
				.param("id", UUID.fromString(registrada.get("evidenciaId").asText())).query(Integer.class).single())
				.isEqualTo("Capacidade específica: 125 mAh/g".indexOf("Capacidade"));
		assertThat(jdbc.sql("SELECT fim_caractere - inicio_caractere FROM evidencia WHERE id=:id")
				.param("id", UUID.fromString(registrada.get("evidenciaId").asText())).query(Integer.class).single())
				.isEqualTo("Capacidade específica: 125 mAh/g".codePointCount(0, "Capacidade específica: 125 mAh/g".length()));

		mvc.perform(get("/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas", documento, execucao))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].valorOriginal").value("125"))
				.andExpect(jsonPath("$[0].unidadeOriginal").value("mAh/g"))
				.andExpect(jsonPath("$[0].condicoes.taxa").value("0,1 C"))
				.andExpect(jsonPath("$[0].pagina").value(1))
				.andExpect(jsonPath("$[0].trecho").value("Capacidade específica: 125 mAh/g"))
				.andExpect(jsonPath("$[0].inicioCaractere").value("Capacidade específica: 125 mAh/g".indexOf("Capacidade")))
				.andExpect(jsonPath("$[0].fimCaractere").value("Capacidade específica: 125 mAh/g".codePointCount(0, "Capacidade específica: 125 mAh/g".length())));
	}

	@Test
	void rejeitaMedidaQuandoTrechoNaoEstaNaPagina() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Texto original da página"));
		UUID execucao = UUID.fromString(processar(documento).get("execucaoId").asText());
		var candidata = new dev.atlas.documentos.MedidaCandidata(
				"LiFePO4", "capacidade", "999", "mAh/g", "REPORTADO", 1, "999 mAh/g", java.util.Map.of());

		mvc.perform(post("/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas", documento, execucao)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(candidata)))
			.andExpect(status().isUnprocessableEntity());
		assertThat(medidasDaExecucao(execucao)).isZero();
	}

	@Test
	void medidasDaMesmaExecucaoCompartilhamEstudoEExperimentoPorObjeto() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Capacidade 125 mAh/g e tensao 3,4 V"));
		UUID execucao = UUID.fromString(processar(documento).get("execucaoId").asText());

		registrar(documento, execucao, "LiFePO4", "capacidade", "125", "mAh/g", "Capacidade 125 mAh/g");
		registrar(documento, execucao, "LiFePO4", "tensao", "3,4", "V", "tensao 3,4 V");
		registrar(documento, execucao, "LiCoO2", "tensao", "3,4", "V", "tensao 3,4 V");

		assertThat(jdbc.sql("SELECT count(*) FROM estudo WHERE execucao_id = :e")
				.param("e", execucao).query(Long.class).single()).isEqualTo(1);
		assertThat(medidasDaExecucao(execucao)).isEqualTo(3);
		assertThat(jdbc.sql("""
				SELECT count(*) FROM experimento x JOIN estudo s ON s.id = x.estudo_id
				WHERE s.execucao_id = :e
				""").param("e", execucao).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void mesmoObjetoComCondicoesDiferentesGeraExperimentosSeparados() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Capacidade 125 mAh/g e capacidade 98 mAh/g"));
		UUID execucao = UUID.fromString(processar(documento).get("execucaoId").asText());

		registrar(documento, execucao, "LiFePO4", "capacidade", "125", "mAh/g", "Capacidade 125 mAh/g");
		registrar(documento, execucao, "LiFePO4", "capacidade", "98", "mAh/g", "capacidade 98 mAh/g",
				java.util.Map.of("taxa", "1 C"));

		assertThat(jdbc.sql("""
				SELECT count(*) FROM experimento x JOIN estudo s ON s.id=x.estudo_id
				WHERE s.execucao_id=:execucao AND x.objeto='LiFePO4'
				""").param("execucao", execucao).query(Long.class).single()).isEqualTo(2);
	}

	private long medidasDaExecucao(UUID execucao) {
		return jdbc.sql("""
				SELECT count(*) FROM medida m
				JOIN experimento x ON x.id = m.experimento_id
				JOIN estudo s ON s.id = x.estudo_id
				WHERE s.execucao_id = :e
				""").param("e", execucao).query(Long.class).single();
	}

	private void registrar(UUID documento, UUID execucao, String objeto, String metrica, String valor,
			String unidade, String trecho) throws Exception {
		registrar(documento, execucao, objeto, metrica, valor, unidade, trecho, java.util.Map.of());
	}

	private void registrar(UUID documento, UUID execucao, String objeto, String metrica, String valor,
			String unidade, String trecho, java.util.Map<String, Object> condicoes) throws Exception {
		var candidata = new dev.atlas.documentos.MedidaCandidata(
				objeto, metrica, valor, unidade, "REPORTADO", 1, trecho, condicoes);
		mvc.perform(post("/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas", documento, execucao)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(candidata)))
			.andExpect(status().isOk());
	}

	private UUID enviarPdf(byte[] conteudo) throws Exception {
		var arquivo = new MockMultipartFile("arquivo", "artigo.pdf", "application/pdf", conteudo);
		MvcResult resposta = mvc.perform(multipart("/api/documentos").file(arquivo)
				.param("autorizacaoConfirmada", "true"))
			.andExpect(status().isOk()).andReturn();
		return UUID.fromString(objectMapper.readTree(resposta.getResponse().getContentAsString()).get("id").asText());
	}

	private JsonNode processar(UUID documento) throws Exception {
		MvcResult resposta = mvc.perform(post("/api/documentos/{id}/extracoes", documento))
				.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(resposta.getResponse().getContentAsString());
	}

	private static byte[] pdfComPaginas(String... textos) throws Exception {
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

	private static byte[] pdfSemTexto() throws Exception {
		try (PDDocument documento = new PDDocument(); ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
			documento.addPage(new PDPage());
			documento.save(saida);
			return saida.toByteArray();
		}
	}
}
