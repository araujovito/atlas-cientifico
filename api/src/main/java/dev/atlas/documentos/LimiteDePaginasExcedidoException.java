package dev.atlas.documentos;

import java.io.IOException;

public class LimiteDePaginasExcedidoException extends IOException {

	public LimiteDePaginasExcedidoException(int paginas, int maximo) {
		super("O PDF tem " + paginas + " páginas; o limite é " + maximo + ".");
	}
}
