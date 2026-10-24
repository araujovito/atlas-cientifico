package dev.atlas.documentos;

import java.util.UUID;

public record MedidaRegistrada(UUID estudoId, UUID experimentoId, UUID medidaId, UUID evidenciaId, String estado) {
}
