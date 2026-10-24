package dev.atlas.fontes.openalex;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "atlas.fontes.openalex")
public record OpenAlexProperties(@DefaultValue("https://api.openalex.org") URI urlBase,
		@DefaultValue("") String chaveApi, @DefaultValue("5s") Duration tempoLimite) {
	public OpenAlexProperties {
		if (urlBase == null || urlBase.getHost() == null
				|| !("https".equals(urlBase.getScheme()) || "http".equals(urlBase.getScheme()))
				|| urlBase.getUserInfo() != null || urlBase.getQuery() != null || urlBase.getFragment() != null) {
			throw new IllegalArgumentException("A URL base do OpenAlex precisa ser uma URL HTTP(S) sem credenciais, query ou fragmento.");
		}
		if (chaveApi != null && !chaveApi.isBlank() && !"https".equals(urlBase.getScheme()) && !destinoLocal(urlBase.getHost())) {
			throw new IllegalArgumentException("A chave do OpenAlex só pode ser enviada por HTTPS (HTTP apenas para loopback).");
		}
		if (tempoLimite == null || tempoLimite.isNegative() || tempoLimite.isZero()
				|| tempoLimite.compareTo(Duration.ofMinutes(1)) > 0) {
			throw new IllegalArgumentException("O prazo de consulta ao OpenAlex precisa ser positivo e de até um minuto.");
		}
	}

	private static boolean destinoLocal(String host) {
		return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
	}
}
