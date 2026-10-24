package dev.atlas.seguranca;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Exige o cabeçalho X-Atlas-Chave em /api/** quando atlas.seguranca.chave-api está definida.
 *
 * É uma chave compartilhada, não autenticação de usuários: quem tem a chave acessa todos os
 * documentos. Serve para não deixar a API aberta enquanto não há contas. Sem a propriedade o
 * filtro não existe (desenvolvimento local).
 */
@Component
@ConditionalOnExpression("!'${atlas.seguranca.chave-api:}'.isBlank()")
public class FiltroChaveApi extends OncePerRequestFilter {

	static final String CABECALHO = "X-Atlas-Chave";
	private final byte[] esperada;

	public FiltroChaveApi(@org.springframework.beans.factory.annotation.Value("${atlas.seguranca.chave-api}") String chave) {
		this.esperada = sha256(chave);
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String enviada = request.getHeader(CABECALHO);
		// Compara os hashes em tempo constante: o tamanho e o prefixo da chave não vazam pelo tempo.
		if (enviada == null || !MessageDigest.isEqual(esperada, sha256(enviada))) {
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
