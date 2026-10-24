package dev.atlas.documentos;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record MedidaComEvidencia(
		UUID medidaId,
		String objeto,
		String metrica,
		String valorOriginal,
		String unidadeOriginal,
		String estado,
		JsonNode condicoes,
		Integer pagina,
		String trecho,
		Integer inicioCaractere,
		Integer fimCaractere) {
}
