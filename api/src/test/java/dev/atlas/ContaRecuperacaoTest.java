package dev.atlas;

import static dev.atlas.ContasTeste.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "atlas.fila.ativa=false", "atlas.limites.falhas-por-email-e-ip=3" })
@AutoConfigureMockMvc
class ContaRecuperacaoTest {
	static final String NOVA = "Outra senha bem longa 456!";
	static final ObjectMapper JSON = new ObjectMapper();
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;

	private JsonNode corpo(ResultActions r) throws Exception { return JSON.readTree(r.andReturn().getResponse().getContentAsString()); }

	private ResultActions postar(String caminho, Map<String, String> corpo, String ip) throws Exception {
		var pre = pre(mvc);
		return mvc.perform(com(post(caminho), pre).contentType("application/json").with(r -> { r.setRemoteAddr(ip); return r; })
				.content(JSON.writeValueAsString(corpo)));
	}

	private JsonNode criarConta(String email) throws Exception {
		return corpo(postar("/api/auth/cadastro", Map.of("email", email, "senha", SENHA), "10.1.1.1").andExpect(status().isCreated()));
	}

	private static String ip() { return "10." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250); }

	@Test
	void cadastroMostraOCodigoUmaVezESoOHashFicaNoBanco() throws Exception {
		String email = "rec-" + UUID.randomUUID() + "@example.com";
		var conta = criarConta(email);
		String codigo = conta.get("codigoRecuperacao").asText();
		assertThat(codigo).matches("([A-Z2-9]{4}-){4}[A-Z2-9]{4}");
		String guardado = jdbc.sql("SELECT recuperacao_hash FROM usuario WHERE email=:e").param("e", email).query(String.class).single();
		assertThat(guardado).hasSize(64).doesNotContain(codigo.replace("-", ""));
		// O login comum não devolve código.
		var login = corpo(postar("/api/auth/login", Map.of("email", email, "senha", SENHA), ip()).andExpect(status().isOk()));
		assertThat(login.has("codigoRecuperacao")).isFalse();
	}

	@Test
	void recuperarTrocaASenhaEncerraSessoesGeraOutroCodigoEInvalidaOAntigo() throws Exception {
		String email = "rec2-" + UUID.randomUUID() + "@example.com";
		var conta = criarConta(email);
		String codigo = conta.get("codigoRecuperacao").asText();
		var cadastro = cadastrar(mvc, "outra-" + UUID.randomUUID() + "@example.com");

		// Código com letras minúsculas e sem hífens também vale (é digitado por pessoas).
		var resposta = corpo(postar("/api/auth/recuperar", Map.of("email", email.toUpperCase(), "codigo", codigo.toLowerCase().replace("-", " "), "novaSenha", NOVA), ip())
				.andExpect(status().isOk()));
		String novoCodigo = resposta.get("codigoRecuperacao").asText();
		assertThat(novoCodigo).isNotEqualTo(codigo).matches("([A-Z2-9]{4}-){4}[A-Z2-9]{4}");

		postar("/api/auth/login", Map.of("email", email, "senha", NOVA), ip()).andExpect(status().isOk());
		postar("/api/auth/login", Map.of("email", email, "senha", SENHA), ip()).andExpect(status().isUnauthorized());
		// O código usado não vale mais; o novo vale.
		postar("/api/auth/recuperar", Map.of("email", email, "codigo", codigo, "novaSenha", NOVA), ip()).andExpect(status().isUnauthorized());
		postar("/api/auth/recuperar", Map.of("email", email, "codigo", novoCodigo, "novaSenha", NOVA + "2"), ip()).andExpect(status().isOk());
		// A sessão de outra conta não foi tocada.
		mvc.perform(com(get("/api/conta/uso"), cadastro)).andExpect(status().isOk());
	}

	@Test
	void sessoesAbertasDaContaSaoEncerradasAoRecuperar() throws Exception {
		String email = "rec3-" + UUID.randomUUID() + "@example.com";
		var conta = criarConta(email);
		var aberta = corpo(postar("/api/auth/login", Map.of("email", email, "senha", SENHA), ip()).andExpect(status().isOk()));
		assertThat(aberta.get("csrfToken").asText()).isNotBlank();
		long antes = jdbc.sql("SELECT count(*) FROM sessao s JOIN usuario u ON u.id=s.usuario_id WHERE u.email=:e").param("e", email).query(Long.class).single();
		assertThat(antes).isGreaterThanOrEqualTo(1);
		postar("/api/auth/recuperar", Map.of("email", email, "codigo", conta.get("codigoRecuperacao").asText(), "novaSenha", NOVA), ip()).andExpect(status().isOk());
		long depois = jdbc.sql("SELECT count(*) FROM sessao s JOIN usuario u ON u.id=s.usuario_id WHERE u.email=:e").param("e", email).query(Long.class).single();
		assertThat(depois).isEqualTo(1); // só a sessão criada pela própria recuperação
	}

	@Test
	void codigoErradoEContaInexistenteDaoAMesmaRespostaEErrosRepetidosBloqueiam() throws Exception {
		String email = "rec4-" + UUID.randomUUID() + "@example.com";
		var conta = criarConta(email);
		String ip = ip();
		String a = postar("/api/auth/recuperar", Map.of("email", email, "codigo", "AAAA-AAAA-AAAA-AAAA-AAAA", "novaSenha", NOVA), ip)
				.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
		String b = postar("/api/auth/recuperar", Map.of("email", "nao-existe-" + UUID.randomUUID() + "@example.com", "codigo", "AAAA-AAAA-AAAA-AAAA-AAAA", "novaSenha", NOVA), ip)
				.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
		assertThat(JSON.readTree(a).get("detail")).isEqualTo(JSON.readTree(b).get("detail"));
		for (int i = 0; i < 2; i++) postar("/api/auth/recuperar", Map.of("email", email, "codigo", "BBBB-BBBB-BBBB-BBBB-BBBB", "novaSenha", NOVA), ip).andExpect(status().isUnauthorized());
		// Passou de 3 falhas do par e-mail+IP: nem o código certo funciona mais deste IP.
		postar("/api/auth/recuperar", Map.of("email", email, "codigo", conta.get("codigoRecuperacao").asText(), "novaSenha", NOVA), ip).andExpect(status().isTooManyRequests());
	}

	@Test
	void contaSemCodigoNaoRecuperaEGerarNovoExigeSenhaAtual() throws Exception {
		var cliente = cadastrar(mvc, "rec5-" + UUID.randomUUID() + "@example.com");
		jdbc.sql("UPDATE usuario SET recuperacao_hash=NULL WHERE id=:id").param("id", cliente.id()).update();
		postar("/api/auth/recuperar", Map.of("email", cliente.email(), "codigo", "AAAA-AAAA-AAAA-AAAA-AAAA", "novaSenha", NOVA), ip()).andExpect(status().isUnauthorized());

		mvc.perform(com(post("/api/auth/codigo-recuperacao"), cliente).contentType("application/json").content("{\"senha\":\"errada errada errada\"}"))
				.andExpect(status().isUnauthorized());
		var novo = JSON.readTree(mvc.perform(com(post("/api/auth/codigo-recuperacao"), cliente).contentType("application/json")
				.content(JSON.writeValueAsString(Map.of("senha", SENHA)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		String codigo = novo.get("codigoRecuperacao").asText();
		postar("/api/auth/recuperar", Map.of("email", cliente.email(), "codigo", codigo, "novaSenha", NOVA), ip()).andExpect(status().isOk());
	}
}
