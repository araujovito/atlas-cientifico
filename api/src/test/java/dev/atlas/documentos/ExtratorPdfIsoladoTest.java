package dev.atlas.documentos;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ExtratorPdfIsoladoTest {
    @TempDir Path pasta;

    @Test
    void prazoEncerraProcessoEmVezDeDeixarThreadRodando() throws Exception {
        Path pid = pasta.resolve("worker.pid");
        var extrator = new ExtratorPdfIsolado(new ProcessamentoProperties(Duration.ofSeconds(2),128,""),new ObjectMapper()) {
            @Override protected List<String> comando(Path arquivo, Path saida, int limite) {
                return List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                        System.getProperty("surefire.test.class.path"),WorkerLento.class.getName(),pid.toString());
            }
        };
        assertThatThrownBy(() -> extrator.extrair(pasta.resolve("qualquer.pdf"),3)).isInstanceOf(PrazoPdfExcedidoException.class);
        assertThat(Files.isRegularFile(pid)).isTrue();
        long identificador = Long.parseLong(Files.readString(pid));
        assertThat(ProcessHandle.of(identificador).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    public static class WorkerLento {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]),Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(30000);
        }
    }
}
