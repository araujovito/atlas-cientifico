package dev.atlas.documentos;

import java.time.Instant;
import java.util.UUID;

public record DocumentoUpload(UUID id, String sha256, long tamanhoBytes, boolean duplicado, Instant recebidoEm) {
}
