package dev.atlas;

import dev.atlas.avaliacao.AvaliadorCampos;
import dev.atlas.avaliacao.AvaliadorCampos.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AvaliadorCamposTest {
    private final AvaliadorCampos avaliador=new AvaliadorCampos();
    private Campo campo(String experimento,String valor) {
        return new Campo(new Chave("a".repeat(64),experimento,"capacidade"),"REPORTADO",valor,"mAh/g",1,"125 mAh/g",Map.of("temperatura","25 C"));
    }
    @Test void associaPorDocumentoExperimentoEMetricaENaoPorPosicao() {
        var a=campo("A","125"); var b=campo("B","126");
        var report=avaliador.avaliar(List.of(a,b),List.of(b,a));
        assertThat(report.completosCorretos()).isEqualTo(2); assertThat(report.precisaoExata()).isEqualTo(1);
        var erro=avaliador.avaliar(List.of(a,b),List.of(campo("B","125"),campo("C","126")));
        assertThat(erro.completosCorretos()).isZero(); assertThat(erro.extras()).isEqualTo(1); assertThat(erro.omitidos()).isEqualTo(1);
    }
    @Test void evidenciaErradaNaoRecebeCreditoMesmoComValorCorreto() {
        var a=campo("A","125");
        var errado=new Campo(a.chave(),a.estado(),a.valor(),a.unidade(),2,a.trecho(),a.condicoes());
        var report=avaliador.avaliar(List.of(a),List.of(errado));
        assertThat(report.valoresCorretos()).isEqualTo(1); assertThat(report.evidenciasCorretas()).isZero();
        assertThat(report.completosCorretos()).isZero();
    }
    @Test void ausenciaExplicitaNaoEquivaleACampoOmitido() {
        var a=new Campo(new Chave("b".repeat(64),"A","retencao"),"AUSENTE",null,null,null,null,Map.of());
        assertThat(avaliador.avaliar(List.of(a),List.of(a)).ausenciasCorretas()).isEqualTo(1);
        var par=avaliador.avaliar(List.of(a),List.of(a));
        assertThat(par.valoresCorretos()).isZero(); assertThat(par.unidadesCorretas()).isZero(); assertThat(par.evidenciasCorretas()).isZero();
        assertThat(par.estadosCorretos()).isEqualTo(1);
        var omitido=avaliador.avaliar(List.of(a),List.of());
        assertThat(omitido.ausenciasCorretas()).isZero(); assertThat(omitido.coberturaExata()).isZero();
        assertThat(omitido.precisaoExata()).isNull();
        assertThatThrownBy(()->new Campo(a.chave(),"AUSENTE","123",null,null,null,Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicataNaoMelhoraMetricasENumerosOriginaisNaoSaoNormalizados() {
        var a=campo("A","125");
        assertThatThrownBy(()->avaliador.avaliar(List.of(a),List.of(a,a))).isInstanceOf(IllegalArgumentException.class);
        assertThat(avaliador.avaliar(List.of(a),List.of(campo("A","125.0"))).valoresCorretos()).isZero();
    }
}
