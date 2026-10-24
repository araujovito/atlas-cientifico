package dev.atlas.fontes.openalex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpenAlexPropertiesTest {

	private static OpenAlexProperties com(String url, String chave) {
		return new OpenAlexProperties(URI.create(url), chave, Duration.ofSeconds(5));
	}

	@Test
	void chaveSoViajaPorHttpsOuParaLoopback() {
		assertThat(com("https://api.openalex.org", "segredo")).isNotNull();
		assertThat(com("http://127.0.0.1:9", "segredo")).isNotNull();
		assertThat(com("http://openalex.example", "")).isNotNull();
		assertThatThrownBy(() -> com("http://openalex.example", "segredo")).isInstanceOf(IllegalArgumentException.class);
	}
}
