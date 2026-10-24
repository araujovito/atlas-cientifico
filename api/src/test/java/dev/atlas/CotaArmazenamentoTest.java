package dev.atlas;

import static dev.atlas.ContasTeste.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.atlas.documentos.UploadDocumentoService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "atlas.fila.ativa=false", "atlas.cotas.bytes-por-usuario=2500", "atlas.cotas.documentos-por-usuario=4",
		"atlas.documentos.diretorio=${java.io.tmpdir}/atlas-cota-teste" })
@AutoConfigureMockMvc
class CotaArmazenamentoTest {
	@Autowired UploadDocumentoService upload;
	@Autowired JdbcClient jdbc;
	@Autowired MockMvc mvc;

	private UUID usuario() {
		return jdbc.sql("INSERT INTO usuario(email,senha_hash) VALUES (:e,'sem-login') RETURNING id")
				.param("e", "cota-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
	}

	private static MockMultipartFile pdf(int tamanho) {
		String corpo = "%PDF-" + UUID.randomUUID() + "x".repeat(Math.max(0, tamanho - 41));
		return new MockMultipartFile("arquivo", "a.pdf", "application/pdf", corpo.getBytes());
	}

	private static void esperar413(Runnable acao) {
		assertThatThrownBy(acao::run).isInstanceOfSatisfying(ResponseStatusException.class,
				e -> assertThat(e.getStatusCode().value()).isEqualTo(413));
	}

	@Test
	void cotaDeBytesRecusaOEnvioQueAUltrapassaEOutraContaNaoEAfetada() {
		UUID a = usuario(), b = usuario();
		upload.receber(pdf(1000), true, null, a);
		upload.receber(pdf(1000), true, null, a);
		esperar413(() -> upload.receber(pdf(1000), true, null, a));
		assertThat(upload.uso(a).bytesUsados()).isEqualTo(2000);
		upload.receber(pdf(1000), true, null, b);
	}

	@Test
	void cotaDeDocumentosRecusaOQuintoMasReenvioDoExistenteContinuaPermitido() {
		UUID dono = usuario();
		var conteudo = pdf(100);
		var primeiro = upload.receber(conteudo, true, null, dono);
		for (int i = 0; i < 3; i++) upload.receber(pdf(100), true, null, dono);
		esperar413(() -> upload.receber(pdf(100), true, null, dono));
		// O mesmo conteúdo já enviado não consome cota nova, mesmo com a conta no limite.
		var reenvio = upload.receber(conteudo, true, null, dono);
		assertThat(reenvio.id()).isEqualTo(primeiro.id());
		assertThat(reenvio.duplicado()).isTrue();
		assertThat(upload.uso(dono).documentos()).isEqualTo(4);
	}

	@Test
	void doisEnviosSimultaneosNaoPassamJuntosDaCota() throws Exception {
		UUID dono = usuario();
		var inicio = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var tarefas = List.of(pdf(1500), pdf(1500)).stream().map(arquivo -> pool.submit(() -> {
				inicio.await();
				try { upload.receber(arquivo, true, null, dono); return 201; }
				catch (ResponseStatusException e) { return e.getStatusCode().value(); }
			})).toList();
			inicio.countDown();
			var codigos = new java.util.ArrayList<Integer>();
			for (var t : tarefas) codigos.add(t.get(15, TimeUnit.SECONDS));
			assertThat(codigos).containsExactlyInAnyOrder(201, 413);
		}
		assertThat(upload.uso(dono).bytesUsados()).isLessThanOrEqualTo(2500);
	}

	@Test
	void rotaDeUsoMostraSoOUsoDaPropriaConta() throws Exception {
		var conta = cadastrar(mvc, "uso-" + UUID.randomUUID() + "@example.com");
		upload.receber(pdf(700), true, null, conta.id());
		mvc.perform(get("/api/conta/uso").cookie(conta.cookie())).andExpect(status().isOk())
				.andExpect(jsonPath("$.bytesUsados").value(700)).andExpect(jsonPath("$.documentos").value(1))
				.andExpect(jsonPath("$.bytesLimite").value(2500)).andExpect(jsonPath("$.documentosLimite").value(4));
		mvc.perform(get("/api/conta/uso")).andExpect(status().isUnauthorized());
	}
}
