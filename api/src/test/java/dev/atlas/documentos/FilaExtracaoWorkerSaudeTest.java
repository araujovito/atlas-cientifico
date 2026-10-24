package dev.atlas.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilaExtracaoWorkerSaudeTest {

	private FilaExtracaoWorker worker(boolean ativa, String arquivo) {
		var fila = mock(FilaExtracaoService.class);
		when(fila.reservar()).thenReturn(Optional.empty());
		return new FilaExtracaoWorker(fila, mock(ProcessadorDocumentoService.class), ativa,
				new SimpleMeterRegistry(), ObservationRegistry.create(), arquivo);
	}

	@Test
	void cadaCicloTocaOArquivoDeSaude(@TempDir Path dir) throws Exception {
		Path arquivo = dir.resolve("vivo");
		worker(true, arquivo.toString()).tick();
		assertThat(arquivo).exists();
		Files.setLastModifiedTime(arquivo, java.nio.file.attribute.FileTime.fromMillis(0));
		worker(true, arquivo.toString()).tick();
		assertThat(Files.getLastModifiedTime(arquivo).toMillis()).isGreaterThan(0);
	}

	@Test
	void semArquivoConfiguradoOuInativoNaoGravaNada(@TempDir Path dir) {
		Path arquivo = dir.resolve("vivo");
		worker(false, arquivo.toString()).tick();
		worker(true, "").tick();
		assertThat(arquivo).doesNotExist();
	}
}
