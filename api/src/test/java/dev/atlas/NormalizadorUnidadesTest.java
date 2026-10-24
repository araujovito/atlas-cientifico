package dev.atlas;
import static org.assertj.core.api.Assertions.*;
import dev.atlas.dominio.baterias.NormalizadorUnidades;
import org.junit.jupiter.api.Test;

class NormalizadorUnidadesTest {
    @Test void converteDimensoesSemPerderPrecisaoDecimal() {
        assertThat(NormalizadorUnidades.normalizar("0,160","Ah/g").orElseThrow().valor()).isEqualTo("160");
        assertThat(NormalizadorUnidades.normalizar("98.2","mAh g−1").orElseThrow().valor()).isEqualTo("98.2");
        assertThat(NormalizadorUnidades.normalizar("98.2","Ah/kg").orElseThrow().valor()).isEqualTo("98.2");
        assertThat(NormalizadorUnidades.normalizar("3400","mV").orElseThrow().valor()).isEqualTo("3.4");
        assertThat(NormalizadorUnidades.normalizar("12345678901234567890.12345","mAh/g").orElseThrow().valor()).isEqualTo("12345678901234567890.12345");
    }
    @Test void naoAdivinhaValorOuUnidadeAmbigua() {
        assertThat(NormalizadorUnidades.normalizar("1,234.5","mAh/g")).isEmpty();
        assertThat(NormalizadorUnidades.normalizar("160 mAh/g","mAh/g")).isEmpty();
        assertThat(NormalizadorUnidades.normalizar("160","unidade desconhecida")).isEmpty();
        assertThat(NormalizadorUnidades.normalizar(null,"mAh/g")).isEmpty();
    }
}
