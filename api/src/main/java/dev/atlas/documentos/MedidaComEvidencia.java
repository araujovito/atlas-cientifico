package dev.atlas.documentos;

import java.util.UUID;

public record MedidaComEvidencia(
		UUID medidaId,
		String objeto,
		String metrica,
		String valorOriginal,
		String unidadeOriginal,
		String estado,
		Integer pagina,
		String trecho) {
}
