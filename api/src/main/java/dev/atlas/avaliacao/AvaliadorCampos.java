package dev.atlas.avaliacao;

import java.util.*;

/** Comparação exata contra anotações externas. Não gera nem revisa o conjunto de referência. */
public final class AvaliadorCampos {
    public record Chave(String sha256, String experimento, String metrica) {
        public Chave {
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}") || experimento == null || experimento.isBlank()
                    || metrica == null || metrica.isBlank()) throw new IllegalArgumentException("Chave de avaliação inválida.");
        }
    }
    public record Campo(Chave chave, String estado, String valor, String unidade, Integer pagina, String trecho,
                        Map<String,String> condicoes) {
        public Campo {
            Objects.requireNonNull(chave); Objects.requireNonNull(estado);
            condicoes=condicoes==null ? Map.of() : Map.copyOf(condicoes);
            if (!Set.of("REPORTADO","INFERIDO","AUSENTE","PENDENTE_REVISAO").contains(estado)) throw new IllegalArgumentException("Estado inválido.");
            if (estado.equals("AUSENTE") && (valor!=null || unidade!=null || pagina!=null || trecho!=null))
                throw new IllegalArgumentException("Ausência não pode conter valor ou evidência.");
        }
    }
    public record Relatorio(int referencia, int candidatos, int associados, int completosCorretos,
            int valoresCorretos, int unidadesCorretas, int evidenciasCorretas, int estadosCorretos,
            int condicoesCorretas, int ausenciasCorretas, int ausenciasEsperadas, int extras, int omitidos,
            Double precisaoExata, Double coberturaExata) {}

    public Relatorio avaliar(List<Campo> referencia,List<Campo> candidatos) {
        var gold=indexar(referencia); var output=indexar(candidatos);
        int associados=0,completos=0,valores=0,unidades=0,evidencias=0,estados=0,condicoes=0,ausencias=0;
        int esperadas=(int)referencia.stream().filter(c->c.estado().equals("AUSENTE")).count();
        for(var entrada:gold.entrySet()) {
            var esperado=entrada.getValue(); var obtido=output.get(entrada.getKey());
            if(obtido==null) continue;
            associados++;
            boolean v=Objects.equals(esperado.valor(),obtido.valor()),u=Objects.equals(esperado.unidade(),obtido.unidade());
            boolean e=Objects.equals(esperado.pagina(),obtido.pagina()) && Objects.equals(esperado.trecho(),obtido.trecho());
            boolean s=esperado.estado().equals(obtido.estado()),c=esperado.condicoes().equals(obtido.condicoes());
            if(v) valores++; if(u) unidades++; if(e) evidencias++; if(s) estados++; if(c) condicoes++;
            if(v&&u&&e&&s&&c) { completos++; if(esperado.estado().equals("AUSENTE")) ausencias++; }
        }
        return new Relatorio(gold.size(),output.size(),associados,completos,valores,unidades,evidencias,estados,condicoes,
                ausencias,esperadas,output.size()-associados,gold.size()-associados,
                output.isEmpty()?null:(double)completos/output.size(), gold.isEmpty()?null:(double)completos/gold.size());
    }
    private Map<Chave,Campo> indexar(List<Campo> campos) {
        if(campos==null || campos.size()>10000) throw new IllegalArgumentException("Avaliação limitada a dez mil campos.");
        var index=new LinkedHashMap<Chave,Campo>();
        for(var campo:campos) if(index.putIfAbsent(campo.chave(),campo)!=null)
            throw new IllegalArgumentException("Chave duplicada: não é permitido mascarar saída repetida.");
        return index;
    }
}
