package dev.atlas.catalogo;

import java.util.Locale;
import java.util.regex.Pattern;

/** DOI é case-insensitive; apenas o prefixo conhecido do resolvedor é removido. */
public record Doi(String valor) {
	private static final Pattern FORMATO = Pattern.compile("^10\\.\\d{4,9}/\\S+$", Pattern.UNICODE_CHARACTER_CLASS);

	public Doi {
		if (valor == null || valor.length() > 300) throw new IllegalArgumentException("Informe um DOI válido, com até 300 caracteres.");
		valor = valor.strip().replaceFirst("(?i)^https?://(?:dx\\.)?doi\\.org/", "")
				.replaceFirst("(?i)^doi:", "").toLowerCase(Locale.ROOT);
		if (!FORMATO.matcher(valor).matches()) throw new IllegalArgumentException("Informe um DOI válido, como 10.1038/s41467-022-28959-5.");
	}
}
