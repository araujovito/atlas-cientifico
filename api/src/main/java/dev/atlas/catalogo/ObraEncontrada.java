package dev.atlas.catalogo;

import java.util.List;

/** Metadados da fonte; a consulta não cria uma obra nem concede permissão sobre o PDF. */
public record ObraEncontrada(String fonte, String openAlexId, String doi, String titulo, Integer ano,
		List<String> autores) {
	public ObraEncontrada {
		autores = List.copyOf(autores);
	}
}
