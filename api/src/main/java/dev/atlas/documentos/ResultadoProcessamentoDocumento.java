package dev.atlas.documentos;

import java.util.UUID;

public record ResultadoProcessamentoDocumento(
		UUID execucaoId,
		UUID documentoId,
		String versaoProcessador,
		String estado,
		int paginas,
		boolean reutilizada) {
}
