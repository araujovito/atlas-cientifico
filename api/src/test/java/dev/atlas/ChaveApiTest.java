package dev.atlas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "atlas.seguranca.chave-api=chave-de-teste")
@AutoConfigureMockMvc
class ChaveApiTest {

	@Autowired MockMvc mvc;

	private static String rota() {
		return "/api/documentos/" + UUID.randomUUID() + "/extracoes/" + UUID.randomUUID() + "/paginas";
	}

	@Test
	void semAChaveARespostaE401() throws Exception {
		mvc.perform(get(rota()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("Entre na sua conta."));
	}

	@Test
	void chaveErradaTambemE401() throws Exception {
		mvc.perform(get(rota()).header("X-Atlas-Chave", "outra"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void chaveCertaNaoSubstituiSessao() throws Exception {
		mvc.perform(get(rota()).header("X-Atlas-Chave", "chave-de-teste"))
				.andExpect(status().isUnauthorized());
	}

	@Test void metricasGlobaisExigemSessaoEChaveDeOperacao() throws Exception {
		var conta=ContasTeste.cadastrar(mvc,"operacao-"+UUID.randomUUID()+"@example.com");
		mvc.perform(ContasTeste.com(get("/api/operacao/metricas"),conta)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/operacao/metricas").header("X-Atlas-Chave","chave-de-teste")).andExpect(status().isUnauthorized());
		mvc.perform(ContasTeste.com(get("/api/operacao/metricas"),conta).header("X-Atlas-Chave","chave-de-teste")).andExpect(status().isOk());
	}

	@Test
	void parametroDeCaminhoNaoContornaOFiltro() throws Exception {
		mvc.perform(get(rota().replace("/api/", "/api;x=1/"))).andExpect(status().isUnauthorized());
	}

	@Test
	void aTelaEstaticaNaoExigeAChave() throws Exception {
		mvc.perform(get("/index.html")).andExpect(status().isOk());
	}

	@Test
	void saudeNaoExigeAChave() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}
}
