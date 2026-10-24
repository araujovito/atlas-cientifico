package dev.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** A opção existe só para o Safari em http://localhost; o padrão (outros testes) continua __Host- e Secure. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "atlas.fila.ativa=false", "atlas.contas.cookie-seguro=false" })
@AutoConfigureMockMvc
class CookieSemTlsTest {
	@Autowired MockMvc mvc;

	@Test
	void comAOpcaoDesligadaOCookieSaiSemSecureESemPrefixoHostMasContinuaHttpOnlyEStrict() throws Exception {
		String cabecalho = mvc.perform(get("/api/auth/sessao")).andReturn().getResponse().getHeader("Set-Cookie");
		assertThat(cabecalho).startsWith("atlas-sessao=").doesNotContain("Secure").contains("HttpOnly", "SameSite=Strict");
	}

	@Test
	void sessaoEmitidaSemTlsAutenticaAsRequisicoesSeguintes() throws Exception {
		var resposta = mvc.perform(get("/api/auth/sessao")).andReturn().getResponse();
		var cookie = resposta.getCookie("atlas-sessao");
		assertThat(cookie).isNotNull();
		var segunda = mvc.perform(get("/api/auth/sessao").cookie(cookie)).andReturn().getResponse();
		assertThat(segunda.getHeader("Set-Cookie")).as("sessão reconhecida: não emite outra").isNull();
	}
}
