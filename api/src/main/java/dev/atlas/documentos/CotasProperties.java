package dev.atlas.documentos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cota por conta. É um valor ajustável, não uma proibição: os padrões são generosos e foram escolhidos pelo revisor,
 * sem medição de uso nem de custo de armazenamento. {@code bytesTotais} é o teto do servidor inteiro (PDFs únicos por hash):
 * com cadastro aberto, a soma das cotas individuais pode passar do disco, então ajuste-o a cerca de 70% do espaço livre.
 */
@ConfigurationProperties("atlas.cotas")
public record CotasProperties(@DefaultValue("1073741824") long bytesPorUsuario, @DefaultValue("1000") int documentosPorUsuario,
		@DefaultValue("21474836480") long bytesTotais) {
	public CotasProperties {
		if (bytesPorUsuario < 1 || documentosPorUsuario < 1 || bytesTotais < 1) throw new IllegalArgumentException("Cotas devem ser positivas.");
	}
}
