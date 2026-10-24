package dev.atlas.documentos;

import java.util.List;

public record ResultadoExtracaoPdf(EstadoExtracaoPdf estado, List<PaginaPdf> paginas) {
	public ResultadoExtracaoPdf {
		paginas = List.copyOf(paginas);
	}
}
