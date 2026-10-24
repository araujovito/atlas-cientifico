package dev.atlas.documentos;

/** Medida estruturada para validar o fluxo sem depender de chamada externa de IA. */
public record MedidaCandidata(
		String objeto,
		String metrica,
		String valorOriginal,
		String unidadeOriginal,
		String estado,
		Integer pagina,
		String trecho) {
}
