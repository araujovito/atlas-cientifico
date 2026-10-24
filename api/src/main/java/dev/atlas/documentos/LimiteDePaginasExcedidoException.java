package dev.atlas.documentos;

import java.io.IOException;

public class LimiteDePaginasExcedidoException extends IOException {
	private final int paginas;

	public LimiteDePaginasExcedidoException(int paginas, int maximo) {
		super("O PDF tem " + paginas + " páginas; o limite é " + maximo + ".");
		this.paginas = paginas;
	}
	public int paginas() { return paginas; }
}
