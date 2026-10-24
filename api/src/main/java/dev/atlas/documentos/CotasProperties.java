package dev.atlas.documentos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cota por conta. É um valor ajustável, não uma proibição: os padrões são generosos e foram escolhidos pelo revisor,
 * sem medição de uso nem de custo de armazenamento.
 */
@ConfigurationProperties("atlas.cotas")
public record CotasProperties(@DefaultValue("5368709120") long bytesPorUsuario, @DefaultValue("5000") int documentosPorUsuario) {
	public CotasProperties {
		if (bytesPorUsuario < 1 || documentosPorUsuario < 1) throw new IllegalArgumentException("Cotas devem ser positivas.");
	}
}
