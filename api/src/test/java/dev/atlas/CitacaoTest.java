package dev.atlas;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.atlas.catalogo.*;
import dev.atlas.fontes.openalex.OpenAlexClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties="atlas.fila.ativa=false")
class CitacaoTest {
    @Autowired CatalogoService catalogo;
    @Autowired CitacaoService citacoes;
    @Autowired JdbcClient jdbc;
    @MockitoBean OpenAlexClient fonte;
    @Test void guardaProvenienciaEPaginaSemInferirMetadadosDeNosDesconhecidos(){
        String origem="https://openalex.org/W"+Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits());
        var obra=catalogo.registrar(new ObraEncontrada("OPENALEX",origem,"10.1234/"+UUID.randomUUID(),"Estudo origem",2024,List.of()));
        var conhecida=catalogo.registrar(new ObraEncontrada("OPENALEX","https://openalex.org/W101","10.1234/"+UUID.randomUUID(),"Referência conhecida",2020,List.of()));
        when(fonte.referencias(origem)).thenReturn(List.of("https://openalex.org/W101","https://openalex.org/W102",origem,"https://openalex.org/W101"));
        var grafo=citacoes.atualizar(obra.id());
        assertThat(grafo.totalReferencias()).isEqualTo(2);
        assertThat(grafo.ligacoes()).allSatisfy(l -> assertThat(l.fonte()).isEqualTo("OPENALEX"));
        assertThat(grafo.nos()).anySatisfy(n -> {assertThat(n.obraId()).isEqualTo(conhecida.id());assertThat(n.ano()).isEqualTo(2020);});
        var segunda=citacoes.obter(obra.id(),1,1);
        assertThat(segunda.ligacoes()).hasSize(1); assertThat(segunda.nos().getLast().titulo()).isNull(); assertThat(segunda.nos().getLast().ano()).isNull();
        citacoes.atualizar(obra.id());
        assertThat(jdbc.sql("SELECT count(*) FROM citacao_snapshot WHERE obra_id=:id").param("id",obra.id()).query(Long.class).single()).isEqualTo(1);
        when(fonte.referencias(origem)).thenReturn(List.of("https://openalex.org/W103"));
        assertThat(citacoes.atualizar(obra.id()).totalReferencias()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM citacao_snapshot WHERE obra_id=:id").param("id",obra.id()).query(Long.class).single()).isEqualTo(2);
    }
}
