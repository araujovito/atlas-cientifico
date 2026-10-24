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
	@com.fasterxml.jackson.annotation.JsonAnySetter
	public void rejeitarCampo(String campo,Object valor){throw new IllegalArgumentException("Campo não aceito na medida candidata.");}
}
