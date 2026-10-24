package dev.atlas.documentos;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import tools.jackson.databind.ObjectMapper;

/** Entrada do subprocesso: recebe caminhos do servidor, sem iniciar Spring ou acessar o banco. */
public final class PdfWorkerMain {
    private PdfWorkerMain() {}
    public record Saida(ResultadoExtracaoPdf resultado, String erro, Integer paginas) {}

    public static void executar(String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("Argumentos do worker inválidos.");
        int limite = Integer.parseInt(args[2]);
        Saida saida;
        try { saida = new Saida(new ExtratorTextoPdf().extrair(Path.of(args[0]),limite),null,null); }
        catch (InvalidPasswordException e) { saida = new Saida(null,"SENHA",null); }
        catch (LimiteDePaginasExcedidoException e) { saida = new Saida(null,"PAGINAS",e.paginas()); }
        catch (IOException e) { saida = new Saida(null,"INVALIDO",null); }
        new ObjectMapper().writeValue(Path.of(args[1]).toFile(),saida);
    }
}
