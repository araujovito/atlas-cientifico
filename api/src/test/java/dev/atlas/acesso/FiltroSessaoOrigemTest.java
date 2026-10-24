package dev.atlas.acesso;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class FiltroSessaoOrigemTest {

	private static MockHttpServletRequest requisicao(String origin) {
		var r = new MockHttpServletRequest("POST", "/api/auth/login");
		r.setScheme("http");
		r.setServerName("interno");
		r.setServerPort(8080);
		if (origin != null) r.addHeader("Origin", origin);
		return r;
	}

	@Test
	void origemDoProprioServidorEAusenciaDeOriginSaoAceitas() {
		assertThat(FiltroSessao.origemPermitida(requisicao("http://interno:8080"), Set.of())).isTrue();
		assertThat(FiltroSessao.origemPermitida(requisicao(null), Set.of())).isTrue();
	}

	@Test
	void atrasDeProxyTlsOOriginDoDominioExigeSerListado() {
		assertThat(FiltroSessao.origemPermitida(requisicao("https://atlas.example"), Set.of())).isFalse();
		assertThat(FiltroSessao.origemPermitida(requisicao("https://atlas.example"), Set.of("https://atlas.example"))).isTrue();
		assertThat(FiltroSessao.origemPermitida(requisicao("https://outro.example"), Set.of("https://atlas.example"))).isFalse();
	}

	@Test
	void crossSiteContinuaRecusadoMesmoComOrigemListada() {
		var r = requisicao("https://atlas.example");
		r.addHeader("Sec-Fetch-Site", "cross-site");
		assertThat(FiltroSessao.origemPermitida(r, Set.of("https://atlas.example"))).isFalse();
		assertThat(FiltroSessao.origemPermitida(requisicao("null"), Set.of("https://atlas.example"))).isFalse();
	}
}
