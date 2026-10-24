package dev.atlas.avaliacao;

import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Avaliação offline: nenhuma chamada de IA ou envio de documentos. */
public final class AvaliacaoMain {
    public record Entrada(boolean sintetica, String revisorDeclarado, String versaoExtrator,
            String hashPrompt, List<AvaliadorCampos.Campo> referencia, List<AvaliadorCampos.Campo> candidatos) {}
    public record Saida(boolean sintetica, String aviso, String revisorDeclarado, String versaoExtrator,
            String hashPrompt, AvaliadorCampos.Relatorio resultado) {}
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Informe um JSON de avaliação offline.");
        var mapper=new ObjectMapper(); var arquivo=Path.of(args[0]);
        if(java.nio.file.Files.size(arquivo)>10L*1024*1024) throw new IllegalArgumentException("Entrada limitada a 10 MiB.");
        var entrada=mapper.readValue(arquivo.toFile(),Entrada.class);
        if(entrada.versaoExtrator()==null || entrada.versaoExtrator().isBlank()) throw new IllegalArgumentException("Declare a versão avaliada.");
        if(!entrada.sintetica() && (entrada.revisorDeclarado()==null || entrada.revisorDeclarado().isBlank()))
            throw new IllegalArgumentException("Referência real exige revisor declarado; a ferramenta não revisa artigos.");
        var saida=new Saida(entrada.sintetica(),entrada.sintetica()?"Fixture sintética: não mede precisão científica."
                :"Revisor declarado pelo operador; identidade e referência não são verificadas pela ferramenta.",
                entrada.revisorDeclarado(),entrada.versaoExtrator(),entrada.hashPrompt(),new AvaliadorCampos().avaliar(entrada.referencia(),entrada.candidatos()));
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(saida));
    }
}
