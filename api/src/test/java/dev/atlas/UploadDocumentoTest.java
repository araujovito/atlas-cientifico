package dev.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import dev.atlas.documentos.DocumentoUpload;
import dev.atlas.documentos.UploadDocumentoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"atlas.documentos.diretorio=${java.io.tmpdir}/atlas-upload-test",
		"atlas.documentos.tamanho-maximo-bytes=128"
})
@AutoConfigureMockMvc
class UploadDocumentoTest {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired UploadDocumentoService service;

	@Test
	@Transactional
	void uploadPdfComConsentimentoPersisteHashEArquivo() throws Exception {
		var arquivo = pdf("conteudo de teste unico");
		MvcResult result = mvc.perform(multipart("/api/documentos")
				.file(arquivo)
				.param("autorizacaoConfirmada", "true")
				.param("titulo", "Artigo de teste"))
			.andExpect(status().isOk()).andReturn();

		String hash = jdbc.sql("SELECT sha256 FROM versao_documento WHERE origem = 'UPLOAD'")
				.query(String.class).single();
		assertThat(result.getResponse().getContentAsString()).contains(hash, "\"duplicado\":false");
		assertThat(jdbc.sql("SELECT count(*) FROM versao_documento WHERE origem='UPLOAD'")
				.query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT autorizacao_confirmada_em FROM versao_documento WHERE sha256=:h")
				.param("h", hash).query(java.time.Instant.class).single()).isNotNull();
	}

	@Test
	@Transactional
	void reenvioDoMesmoPdfRetornaMesmoRegistroSemDuplicar() throws Exception {
		var arquivo = pdf("mesmo pdf idempotente");
		String primeiro = mvc.perform(multipart("/api/documentos").file(arquivo)
				.param("autorizacaoConfirmada", "true"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String segundo = mvc.perform(multipart("/api/documentos").file(pdf("mesmo pdf idempotente"))
				.param("autorizacaoConfirmada", "true"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(segundo).contains("\"duplicado\":true");
		assertThat(segundo).contains(extrair(primeiro, "id"));
		assertThat(jdbc.sql("SELECT count(*) FROM versao_documento WHERE origem='UPLOAD'")
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void uploadSemConsentimentoEhRecusado() throws Exception {
		mvc.perform(multipart("/api/documentos").file(pdf("sem consentimento")))
				.andExpect(status().isBadRequest());
	}

	@Test
	void conteudoQueNaoEhPdfEhRecusadoMesmoComExtensaoPdf() throws Exception {
		var arquivo = new MockMultipartFile("arquivo", "artigo.pdf", "application/pdf",
				"nao sou pdf".getBytes(StandardCharsets.UTF_8));
		mvc.perform(multipart("/api/documentos").file(arquivo).param("autorizacaoConfirmada", "true"))
				.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void arquivoAcimaDoLimiteConfiguradoEhRecusado() throws Exception {
		var arquivo = new MockMultipartFile("arquivo", "grande.pdf", "application/pdf",
				("%PDF-1.7\n" + "x".repeat(200)).getBytes(StandardCharsets.UTF_8));
		mvc.perform(multipart("/api/documentos").file(arquivo).param("autorizacaoConfirmada", "true"))
				.andExpect(status().isPayloadTooLarge());
	}

	/**
	 * Sem @Transactional de propósito: cada envio precisa da sua própria conexão e do seu commit
	 * para que a violação de unicidade aconteça de verdade. Os testes acima rodam numa transação
	 * só e nunca chegam a esse caminho. Várias rodadas porque a janela da corrida é curta.
	 */
	@Test
	void envioSimultaneoDoMesmoPdfConvergeParaUmUnicoRegistro() throws Exception {
		var pool = Executors.newFixedThreadPool(2);
		String marcador = "concorrencia-" + UUID.randomUUID();
		try {
			for (int rodada = 0; rodada < 25; rodada++) {
				String corpo = "simultaneo " + UUID.randomUUID();
				var largada = new CyclicBarrier(2);
				Callable<DocumentoUpload> envio = () -> {
					largada.await();
					return service.receber(pdf(corpo), true, marcador);
				};
				var primeiro = pool.submit(envio);
				var segundo = pool.submit(envio);
				DocumentoUpload a = primeiro.get(30, TimeUnit.SECONDS);
				DocumentoUpload b = segundo.get(30, TimeUnit.SECONDS);

				assertThat(a.id()).isEqualTo(b.id());
				assertThat(List.of(a.duplicado(), b.duplicado())).containsExactlyInAnyOrder(false, true);
			}
		} finally {
			pool.shutdownNow();
			// Remove pelo marcador do título, inclusive de rodadas que falharam antes de devolver o hash.
			jdbc.sql("DELETE FROM versao_documento WHERE titulo_declarado = :m").param("m", marcador).update();
		}
	}

	private static MockMultipartFile pdf(String corpo) {
		return new MockMultipartFile("arquivo", "artigo.pdf", "application/pdf",
				("%PDF-1.7\n" + corpo).getBytes(StandardCharsets.UTF_8));
	}

	private static String extrair(String json, String campo) {
		String marcador = "\"" + campo + "\":\"";
		int inicio = json.indexOf(marcador) + marcador.length();
		return json.substring(inicio, json.indexOf('"', inicio));
	}
}
