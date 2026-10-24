package dev.atlas.dominio.baterias;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** Conversões dimensionais explícitas; nenhuma unidade é adivinhada a partir da métrica. */
public final class NormalizadorUnidades {
    private NormalizadorUnidades() {}
    public record Valor(String valor,String unidade,String metodo) {}
    private record Conversao(String unidade,String fator) {}
    private static final Map<String,Conversao> UNIDADES=Map.ofEntries(
            Map.entry("mAh/g",new Conversao("mAh/g","1")),Map.entry("mAhg-1",new Conversao("mAh/g","1")),
            Map.entry("Ah/kg",new Conversao("mAh/g","1")),Map.entry("Ahkg-1",new Conversao("mAh/g","1")),
            Map.entry("Ah/g",new Conversao("mAh/g","1000")),Map.entry("µAh/g",new Conversao("mAh/g","0.001")),
            Map.entry("V",new Conversao("V","1")),Map.entry("mV",new Conversao("V","0.001")),Map.entry("%",new Conversao("%","1")));
    public static Optional<Valor> normalizar(String original,String unidade) {
        if(original==null || unidade==null || original.length()>100 || !original.strip().matches("-?[0-9]+([.,][0-9]+)?")) return Optional.empty();
        String chave=unidade.replaceAll("(?U)\\s+","").replace("−","-").replace("⁻¹","-1");
        var c=UNIDADES.get(chave); if(c==null) return Optional.empty();
        var valor=new BigDecimal(original.strip().replace(',','.'));
        if(valor.precision()>40 || valor.scale()>20) return Optional.empty();
        return Optional.of(new Valor(valor.multiply(new BigDecimal(c.fator())).stripTrailingZeros().toPlainString(),c.unidade(),"unidades-v1"));
    }
}
