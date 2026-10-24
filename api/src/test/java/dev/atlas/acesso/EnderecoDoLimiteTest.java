package dev.atlas.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EnderecoDoLimiteTest {

	private static ContaController controlador(String cabecalho) {
		return new ContaController(null, null, new LimitesProperties(5, 3600, 20, 5, 20, 900, 60, 600, cabecalho));
	}

	private static MockHttpServletRequest requisicao(String xff) {
		var r = new MockHttpServletRequest();
		r.setRemoteAddr("172.18.0.2");
		if (xff != null) r.addHeader("X-Forwarded-For", xff);
		return r;
	}

	@Test
	void semCabecalhoConfiguradoUsaOEnderecoDaConexaoEIgnoraOQueOClienteEnvia() {
		assertThat(controlador("").ip(requisicao("1.2.3.4"))).isEqualTo("172.18.0.2");
	}

	@Test
	void comCabecalhoConfiguradoUsaOUltimoValor_queOProxyConfiavelAcrescentou() {
		assertThat(controlador("X-Forwarded-For").ip(requisicao("9.9.9.9, 203.0.113.7"))).isEqualTo("203.0.113.7");
		assertThat(controlador("X-Forwarded-For").ip(requisicao(null))).isEqualTo("172.18.0.2");
	}
}
