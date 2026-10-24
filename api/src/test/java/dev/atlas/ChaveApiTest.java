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
				.andExpect(jsonPath("$.detail").value("Chave de acesso ausente ou incorreta."));
	}

	@Test
	void chaveErradaTambemE401() throws Exception {
		mvc.perform(get(rota()).header("X-Atlas-Chave", "outra"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void comAChaveCertaARequisicaoChegaAoControlador() throws Exception {
		// 404: a execução não existe, o que prova que passou pelo filtro.
		mvc.perform(get(rota()).header("X-Atlas-Chave", "chave-de-teste"))
				.andExpect(status().isNotFound());
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
