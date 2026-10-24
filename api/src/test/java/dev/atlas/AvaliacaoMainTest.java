package dev.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.atlas.avaliacao.AvaliacaoMain;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class AvaliacaoMainTest {
	private static final Path FIXTURE = Path.of("..", "docs", "fixtures", "avaliacao-sintetica.json");

	private static String executar(Path arquivo) throws Exception {
		var anterior = System.out;
		var captura = new ByteArrayOutputStream();
		System.setOut(new PrintStream(captura, true, java.nio.charset.StandardCharsets.UTF_8));
		try {
			AvaliacaoMain.main(new String[] { arquivo.toString() });
		} finally {
			System.setOut(anterior);
		}
		return captura.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	@Test
	void fixtureSinteticaProduzRelatorioComAvisoDeQueNaoMedePrecisaoCientifica() throws Exception {
		var json = new ObjectMapper().readTree(executar(FIXTURE));
		assertThat(json.path("sintetica").asBoolean()).isTrue();
		assertThat(json.path("aviso").asText()).contains("não mede precisão científica");
		assertThat(json.path("resultado").path("referencia").asInt()).isPositive();
	}

	@Test
	void referenciaRealSemRevisorDeclaradoOuSemVersaoERecusada(@TempDir Path dir) throws Exception {
		var base = new ObjectMapper().readTree(Files.readString(FIXTURE));
		var semRevisor = ((tools.jackson.databind.node.ObjectNode) base.deepCopy()).put("sintetica", false).putNull("revisorDeclarado");
		var arquivo = dir.resolve("real.json");
		Files.writeString(arquivo, semRevisor.toString());
		assertThatThrownBy(() -> executar(arquivo)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("revisor");
		var semVersao = ((tools.jackson.databind.node.ObjectNode) base.deepCopy()).put("versaoExtrator", " ");
		Files.writeString(arquivo, semVersao.toString());
		assertThatThrownBy(() -> executar(arquivo)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("versão");
		assertThatThrownBy(() -> AvaliacaoMain.main(new String[0])).isInstanceOf(IllegalArgumentException.class);
	}
}
