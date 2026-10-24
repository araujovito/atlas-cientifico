package dev.atlas.documentos;

import java.util.Map;

/** Medida estruturada para validar o fluxo sem depender de chamada externa de IA. */
public record MedidaCandidata(
		String objeto,
		String metrica,
		String valorOriginal,
		String unidadeOriginal,
		String estado,
		Integer pagina,
		String trecho,
		Map<String, Object> condicoes) {
}
