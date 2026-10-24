package dev.atlas.catalogo;

import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record ObraCatalogada(UUID id, String titulo, Integer ano, List<Identificador> identificadores,
        List<JsonNode> metadados) {
    public record Identificador(String tipo, String valor) {}
}
