package dev.atlas;

import static dev.atlas.ContasTeste.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.atlas.acesso.LimiteTentativas;
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
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "atlas.fila.ativa=false", "atlas.limites.cadastro-por-ip=6",
		"atlas.limites.falhas-por-email-e-ip=3", "atlas.limites.falhas-por-email=6" })
@AutoConfigureMockMvc
class LimiteTentativasTest {
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired LimiteTentativas limites;

	private ResultActions entrar(String email, String senha, String ip) throws Exception {
		var pre = pre(mvc);
		return mvc.perform(com(post("/api/auth/login"), pre).contentType("application/json")
				.with(r -> { r.setRemoteAddr(ip); return r; })
				.content(new ObjectMapper().writeValueAsString(Map.of("email", email, "senha", senha))));
	}

	private static String ipUnico() {
		var n = UUID.randomUUID().getMostSignificantBits();
		return "10." + (n & 0xff) + "." + ((n >> 8) & 0xff) + "." + ((n >> 16) & 0xff);
	}

	@Test
	void cadastroPorIpAcimaDoLimiteRecebe429ComRetryAfterSemCriarConta() throws Exception {
		String ip = ipUnico();
		// Os outros testes da classe cadastram do endereço padrão (5 contas); este usa um endereço próprio.
		for (int i = 0; i < 6; i++) {
			var pre = pre(mvc);
			mvc.perform(com(post("/api/auth/cadastro"), pre).contentType("application/json").with(r -> { r.setRemoteAddr(ip); return r; })
					.content("{\"email\":\"lim-" + UUID.randomUUID() + "@example.com\",\"senha\":\"" + SENHA + "\"}")).andExpect(status().isCreated());
		}
		String bloqueado = "lim-bloq-" + UUID.randomUUID() + "@example.com";
		var pre = pre(mvc);
		mvc.perform(com(post("/api/auth/cadastro"), pre).contentType("application/json").with(r -> { r.setRemoteAddr(ip); return r; })
				.content("{\"email\":\"" + bloqueado + "\",\"senha\":\"" + SENHA + "\"}"))
				.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
		assertThat(jdbc.sql("SELECT count(*) FROM usuario WHERE email=:e").param("e", bloqueado).query(Long.class).single()).isZero();
	}

	@Test
	void falhasDoMesmoParEmailIpBloqueiamAteComASenhaCertaMasNaoOutroEmail() throws Exception {
		String ip = ipUnico();
		var conta = cadastrar(mvc, "par-" + UUID.randomUUID() + "@example.com");
		var outra = cadastrar(mvc, "par-outra-" + UUID.randomUUID() + "@example.com");
		for (int i = 0; i < 3; i++) entrar(conta.email(), "senha errada " + i + " xxxxxxxx", ip).andExpect(status().isUnauthorized());
		entrar(conta.email(), SENHA, ip).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
		entrar(outra.email(), SENHA, ip).andExpect(status().isOk());
		// Outro endereço não é trancado pelas falhas deste: quem erra a própria senha de outro lugar entra.
		entrar(conta.email(), SENHA, ipUnico()).andExpect(status().isOk());
	}

	@Test
	void loginBemSucedidoZeraAsFalhasDoPar() throws Exception {
		String ip = ipUnico();
		var conta = cadastrar(mvc, "zera-" + UUID.randomUUID() + "@example.com");
		for (int rodada = 0; rodada < 3; rodada++) {
			for (int i = 0; i < 2; i++) entrar(conta.email(), "errada errada errada " + i, ip).andExpect(status().isUnauthorized());
			entrar(conta.email(), SENHA, ip).andExpect(status().isOk());
		}
	}

	@Test
	void falhasEspalhadasPorVariosEnderecosChegamAoLimiteGlobalDoEmail() throws Exception {
		var conta = cadastrar(mvc, "global-" + UUID.randomUUID() + "@example.com");
		for (int i = 0; i < 6; i++) entrar(conta.email(), "errada errada errada " + i, ipUnico()).andExpect(status().isUnauthorized());
		entrar(conta.email(), SENHA, ipUnico()).andExpect(status().isTooManyRequests());
	}

	@Test
	void emailNaoFicaEmClaroNaTabelaDeLimites() throws Exception {
		String email = "privado-" + UUID.randomUUID() + "@example.com";
		cadastrar(mvc, "ok-" + UUID.randomUUID() + "@example.com");
		entrar(email, "errada errada errada", ipUnico()).andExpect(status().isUnauthorized());
		assertThat(jdbc.sql("SELECT count(*) FROM limite_tentativa WHERE chave LIKE '%' || :e || '%'").param("e", email).query(Long.class).single()).isZero();
	}

	@Test
	void janelaVenceELimpezaApagaRegistrosAntigos() throws Exception {
		String chave = "teste-janela-" + UUID.randomUUID();
		limites.consumir(chave, 2, 1);
		limites.consumir(chave, 2, 1);
		assertThatThrownBy(() -> limites.consumir(chave, 2, 1)).isInstanceOf(LimiteTentativas.LimiteExcedido.class);
		Thread.sleep(1200);
		limites.consumir(chave, 2, 1);
		jdbc.sql("UPDATE limite_tentativa SET janela_inicio = now() - interval '2 days' WHERE chave = :c").param("c", chave).update();
		assertThat(limites.limparVencidas()).isGreaterThanOrEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM limite_tentativa WHERE chave=:c").param("c", chave).query(Long.class).single()).isZero();
	}
}
