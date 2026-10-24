package dev.atlas.seguranca;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Segredo adicional somente para métricas globais de operação. Não autentica pessoas e não
 * substitui a sessão, inclusive nesta rota. Sem configuração, métricas globais ficam recusadas.
 */
@Component
public class FiltroChaveApi extends OncePerRequestFilter {

	static final String CABECALHO = "X-Atlas-Chave";
	private final byte[] esperada;

	public FiltroChaveApi(@org.springframework.beans.factory.annotation.Value("${atlas.seguranca.chave-api:}") String chave) {
		this.esperada = chave.isBlank() ? null : sha256(chave);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !normalizar(request.getRequestURI()).equals("/api/operacao/metricas");
	}

	/**
	 * Reduz o URI bruto à forma com que o Spring MVC o enxerga: sem parâmetros de caminho
	 * ("/api;x=1/..." chega ao controlador como "/api/...") e sem barras repetidas.
	 */
	public static String normalizar(String uri) {
		String caminho = uri.replaceAll(";[^/]*", "");
		try {
			caminho = java.net.URLDecoder.decode(caminho.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			// Escape inválido: o Tomcat já recusa; mantém o texto bruto, que continua sujeito ao filtro.
		}
		caminho = caminho.replaceAll("/{2,}", "/");
		try {
			// "/x/../api/..." tem de ser visto como "/api/...", como qualquer mapeamento por caminho normalizado o veria.
			return new java.net.URI(null, null, caminho, null).normalize().getPath();
		} catch (java.net.URISyntaxException e) {
			return caminho;
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String enviada = request.getHeader(CABECALHO);
		// Compara os hashes em tempo constante: o tamanho e o prefixo da chave não vazam pelo tempo.
		if (esperada == null || enviada == null || !MessageDigest.isEqual(esperada, sha256(enviada))) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType("application/problem+json");
			response.setCharacterEncoding("UTF-8");
			response.getWriter().write("{\"status\":401,\"title\":\"Não autorizado\",\"detail\":\"Chave de acesso ausente ou incorreta.\"}");
			return;
		}
		chain.doFilter(request, response);
	}

	private static byte[] sha256(String texto) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 indisponível", e);
		}
	}
}
