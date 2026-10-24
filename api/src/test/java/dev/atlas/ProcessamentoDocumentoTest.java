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
	}

	@Test
	void reprocessarMesmoPdfNaoCriaOutraExecucaoNemDuplicaPaginas() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Resultado idempotente"));
		JsonNode primeira = processar(documento);
		Path arquivo = Path.of(jdbc.sql("SELECT chave_armazenamento FROM versao_documento WHERE id=:id")
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
				"Capacidade específica: 125 mAh/g");

		MvcResult resposta = mvc.perform(post("/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas", documento, execucao)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(candidata)))
			.andExpect(status().isOk()).andReturn();
		JsonNode registrada = objectMapper.readTree(resposta.getResponse().getContentAsString());

		assertThat(registrada.get("estado").asText()).isEqualTo("REPORTADO");
		assertThat(jdbc.sql("SELECT valor_original || ' ' || unidade_original FROM medida WHERE id=:id")
				.param("id", UUID.fromString(registrada.get("medidaId").asText())).query(String.class).single())
				.isEqualTo("125 mAh/g");
		assertThat(jdbc.sql("SELECT pagina || ':' || trecho FROM evidencia WHERE id=:id")
				.param("id", UUID.fromString(registrada.get("evidenciaId").asText())).query(String.class).single())
				.isEqualTo("1:Capacidade específica: 125 mAh/g");

		mvc.perform(get("/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas", documento, execucao))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].valorOriginal").value("125"))
				.andExpect(jsonPath("$[0].unidadeOriginal").value("mAh/g"))
				.andExpect(jsonPath("$[0].pagina").value(1))
				.andExpect(jsonPath("$[0].trecho").value("Capacidade específica: 125 mAh/g"));
	}

	@Test
	void rejeitaMedidaQuandoTrechoNaoEstaNaPagina() throws Exception {
		UUID documento = enviarPdf(pdfComPaginas("Texto original da página"));
		UUID execucao = UUID.fromString(processar(documento).get("execucaoId").asText());
		var candidata = new dev.atlas.documentos.MedidaCandidata(
				"LiFePO4", "capacidade", "999", "mAh/g", "REPORTADO", 1, "999 mAh/g");

		mvc.perform(post("/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas", documento, execucao)
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsBytes(candidata)))
			.andExpect(status().isUnprocessableEntity());
		assertThat(jdbc.sql("SELECT count(*) FROM medida").query(Long.class).single()).isZero();
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
