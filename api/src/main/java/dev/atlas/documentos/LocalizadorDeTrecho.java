package dev.atlas.documentos;

/**
 * Encontra um trecho citado dentro do texto de uma página, ignorando só diferenças de espaço em
 * branco (quebra de linha, tabulação, espaços repetidos). O PDF quebra linhas no meio de frases,
 * então exigir igualdade byte a byte recusava citações corretas.
 *
 * A posição devolvida é no texto original, em pontos de código Unicode (início inclusivo, fim
 * exclusivo), e o trecho devolvido é o pedaço do texto original, não o que a pessoa digitou.
 * Hifenização e diferenças de caracteres não são toleradas.
 */
final class LocalizadorDeTrecho {

	enum Situacao { ENCONTRADO, AUSENTE, AMBIGUO }

	record Resultado(Situacao situacao, int inicio, int fim, String trechoOriginal) {
		static Resultado de(Situacao situacao) {
			return new Resultado(situacao, -1, -1, null);
		}
	}

	private LocalizadorDeTrecho() {
	}

	static Resultado localizar(String pagina, String trecho) {
		String procurado = normalizar(trecho);
		if (procurado.isEmpty()) {
			return Resultado.de(Situacao.AUSENTE);
		}
		Normalizado base = normalizarComMapa(pagina);
		int achado = base.texto().indexOf(procurado);
		if (achado < 0) {
			return Resultado.de(Situacao.AUSENTE);
		}
		if (base.texto().indexOf(procurado, achado + 1) >= 0) {
			return Resultado.de(Situacao.AMBIGUO);
		}
		int inicio = base.origem()[achado];
		int fim = base.origem()[achado + procurado.length() - 1] + 1;
		int deUtf16 = pagina.offsetByCodePoints(0, inicio);
		int ateUtf16 = pagina.offsetByCodePoints(deUtf16, fim - inicio);
		return new Resultado(Situacao.ENCONTRADO, inicio, fim, pagina.substring(deUtf16, ateUtf16));
	}

	/** Troca cada sequência de espaço em branco por um espaço e remove o das pontas. */
	static String normalizar(String texto) {
		return normalizarComMapa(texto).texto().strip();
	}

	private record Normalizado(String texto, int[] origem) {
	}

	/** origem[i] é o índice, em pontos de código, do texto original que gerou o char i do normalizado. */
	private static Normalizado normalizarComMapa(String texto) {
		StringBuilder saida = new StringBuilder(texto.length());
		int[] origem = new int[texto.length()];
		int ponto = 0;
		boolean emEspaco = false;
		for (int i = 0; i < texto.length(); ) {
			int cp = texto.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				if (!emEspaco) {
					origem[saida.length()] = ponto;
					saida.append(' ');
					emEspaco = true;
				}
			} else {
				emEspaco = false;
				for (int c = 0; c < Character.charCount(cp); c++) {
					origem[saida.length()] = ponto;
					saida.append(Character.toChars(cp)[c]);
				}
			}
			ponto++;
		}
		return new Normalizado(saida.toString(), java.util.Arrays.copyOf(origem, saida.length()));
	}
}
