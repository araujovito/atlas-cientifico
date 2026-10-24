package dev.atlas.documentos;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class ExtratorPdfIsolado {
    private final ProcessamentoProperties properties;
    private final ObjectMapper mapper;
    private final Semaphore capacidade = new Semaphore(2);

    public ExtratorPdfIsolado(ProcessamentoProperties properties, ObjectMapper mapper) {
        this.properties = properties; this.mapper = mapper;
    }

    public ResultadoExtracaoPdf extrair(Path arquivo, int limite) throws IOException {
        if (!capacidade.tryAcquire()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Os processadores de PDF estão ocupados. Tente novamente depois.");
        Path diretorio = null; Process processo = null;
        try {
            diretorio = Files.createTempDirectory("atlas-pdf-worker-");
            Path saida = diretorio.resolve("resultado.json");
            var builder = new ProcessBuilder(comando(arquivo.toAbsolutePath(),saida,limite))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().put("LANG","C.UTF-8");
            processo = builder.start();
            if (!processo.waitFor(properties.tempoLimite().toMillis(),TimeUnit.MILLISECONDS)) {
                processo.destroyForcibly(); processo.waitFor(5,TimeUnit.SECONDS);
                throw new PrazoPdfExcedidoException();
            }
            if (processo.exitValue() != 0 || !Files.isRegularFile(saida) || Files.size(saida) > 32L*1024*1024)
                throw new IOException("O parser não produziu um resultado dentro dos limites.");
            PdfWorkerMain.Saida resultado;
            try { resultado = mapper.readValue(saida.toFile(),PdfWorkerMain.Saida.class); }
            catch (JacksonException e) { throw new IOException("Resultado do parser inválido.",e); }
            if (resultado.erro() != null) {
                switch (resultado.erro()) {
                    case "SENHA" -> throw new PdfProtegidoException();
                    case "PAGINAS" -> throw new LimiteDePaginasExcedidoException(resultado.paginas(),limite);
                    default -> throw new IOException("PDF inválido.");
                }
            }
            if (resultado.resultado() == null) throw new IOException("Parser sem resultado.");
            return resultado.resultado();
        } catch (InterruptedException e) {
            if (processo != null) processo.destroyForcibly();
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Processamento interrompido.");
        } finally {
            if (processo != null && processo.isAlive()) processo.destroyForcibly();
            capacidade.release();
            if (diretorio != null) {
                Files.deleteIfExists(diretorio.resolve("resultado.json")); Files.deleteIfExists(diretorio);
            }
        }
    }

    protected List<String> comando(Path arquivo, Path saida, int limite) {
        var args = new ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
        args.add("-Xmx"+properties.memoriaMaximaMb()+"m");
        String classpath = System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        String jar = properties.workerJar();
        if ((jar == null || jar.isBlank()) && !classpath.contains(java.io.File.pathSeparator) && classpath.endsWith(".jar")) jar = classpath;
        if (jar != null && !jar.isBlank()) { args.add("-jar"); args.add(jar); }
        else { args.add("-cp"); args.add(classpath); args.add("dev.atlas.AtlasApplication"); }
        args.add("--atlas-pdf-worker"); args.add(arquivo.toString()); args.add(saida.toString()); args.add(Integer.toString(limite));
        return args;
    }
}
