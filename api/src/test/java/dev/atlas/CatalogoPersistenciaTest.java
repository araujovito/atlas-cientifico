package dev.atlas;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.atlas.catalogo.CatalogoService;
import dev.atlas.catalogo.ObraEncontrada;
import dev.atlas.fontes.openalex.OpenAlexClient;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Import({TestcontainersConfiguration.class,OperadorTesteConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class CatalogoPersistenciaTest {
    @Autowired CatalogoService catalogo;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean OpenAlexClient fonte;

    @Test
    void duasFontesCompartilhamObraMasPreservamRegistrosESemSobrescreverTitulo() {
        var recebida = candidata();
        var primeira = catalogo.registrar(recebida);
        var outra = catalogo.registrar(new ObraEncontrada("EUROPEPMC",null,recebida.doi(),"Outro título na fonte",2023,List.of("Outro nome")));
        assertThat(outra.id()).isEqualTo(primeira.id());
        assertThat(outra.titulo()).isEqualTo(recebida.titulo());
        assertThat(outra.metadados()).hasSize(2);
        assertThat(catalogo.registrar(recebida).metadados()).hasSize(2);
        assertThat(catalogo.importar("https://doi.org/" + recebida.doi().toUpperCase()).id()).isEqualTo(primeira.id());
        verifyNoInteractions(fonte);
    }

    @Test
    void identificadoresDivergentesNaoFundemObras() {
        var a = candidata(); var b = candidata();
        var primeira = catalogo.registrar(a); catalogo.registrar(b);
        var divergente = new ObraEncontrada("OPENALEX", b.openAlexId(), a.doi(), "Conflito",2022,List.of());
        assertThatThrownBy(() -> catalogo.registrar(divergente)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(catalogo.obter(primeira.id()).metadados()).hasSize(1);
        assertThatThrownBy(() -> catalogo.registrar(new ObraEncontrada("OPENALEX",a.openAlexId(),b.doi()+"-novo","Conflito",2022,List.of())))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void mesmoDoiComOutroIdentificadorOpenAlexEConflito() {
        var original = candidata();
        var obra = catalogo.registrar(original);
        var outroId = new ObraEncontrada("OPENALEX","https://openalex.org/W9"+System.nanoTime()%1000000000L,original.doi(),original.titulo(),original.ano(),List.of());
        assertThatThrownBy(() -> catalogo.registrar(outroId)).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(catalogo.obter(obra.id()).identificadores().stream().filter(i -> i.tipo().equals("OPENALEX"))).hasSize(1);
    }

    @Test
    void respostaTrazSoOsVinteSnapshotsMaisRecentesEOBancoGuardaTodos() {
        var obra = catalogo.registrar(candidata());
        for (int i = 0; i < 24; i++) {
            jdbc.sql("""
                    INSERT INTO obra_metadados(obra_id,fonte,registro,sha256,coletado_em)
                    VALUES (:o,'OPENALEX',CAST(:r AS jsonb),:h,now() + make_interval(secs => :s))
                    """).param("o", obra.id()).param("r", "{\"n\":" + i + "}")
                    .param("h", String.format("%064x", i)).param("s", i + 1).update();
        }
        var lidos = catalogo.obter(obra.id()).metadados();
        assertThat(lidos).hasSize(20);
        assertThat(lidos.getLast().path("n").asInt()).isEqualTo(23);
        assertThat(lidos.getFirst().path("n").asInt()).isEqualTo(4);
        assertThat(jdbc.sql("SELECT count(*) FROM obra_metadados WHERE obra_id=:o").param("o", obra.id()).query(Long.class).single()).isEqualTo(25);
    }

    @Test
    void importacoesConcorrentesCriamUmaObraEUmSnapshot() throws Exception {
        var recebida = candidata();
        var iniciar = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(() -> { iniciar.await(); return catalogo.registrar(recebida); });
            var segunda = executor.submit(() -> { iniciar.await(); return catalogo.registrar(recebida); });
            iniciar.countDown();
            var obra = primeira.get(10,TimeUnit.SECONDS);
            assertThat(segunda.get(10,TimeUnit.SECONDS).id()).isEqualTo(obra.id());
            assertThat(catalogo.obter(obra.id()).metadados()).hasSize(1);
            assertThat(jdbc.sql("SELECT count(*) FROM identificador WHERE tipo='DOI' AND valor=:v")
                    .param("v",recebida.doi()).query(Long.class).single()).isEqualTo(1);
        }
    }

    @Test
    void apiImportaMetadadosDoServidorEColecaoTemAssociacaoIdempotente() throws Exception {
        var recebida = candidata();
        when(fonte.buscarPorDoi(recebida.doi())).thenReturn(recebida);
        var resposta = mvc.perform(post("/api/catalogo/obras").contentType("application/json")
                .content(mapper.writeValueAsString(java.util.Map.of("doi", recebida.doi()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.titulo").value(recebida.titulo())).andReturn();
        String obra = mapper.readTree(resposta.getResponse().getContentAsString()).path("id").asText();
        var criacao = mvc.perform(post("/api/colecoes").contentType("application/json").content("{\"nome\":\"Baterias\"}"))
                .andExpect(status().isOk()).andReturn();
        String colecao = mapper.readTree(criacao.getResponse().getContentAsString()).path("id").asText();
        for (int i=0; i<2; i++) mvc.perform(put("/api/colecoes/{c}/obras/{o}",colecao,obra)).andExpect(status().isNoContent());
        mvc.perform(get("/api/colecoes/{c}/obras",colecao)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(obra));
        mvc.perform(delete("/api/colecoes/{c}/obras/{o}",colecao,obra)).andExpect(status().isNoContent());
        mvc.perform(get("/api/colecoes/{c}/obras",colecao)).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/catalogo/obras/{o}",obra)).andExpect(status().isOk());
        mvc.perform(post("/api/colecoes").contentType("application/json").content("{\"nome\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/catalogo/obras").param("limite","101")).andExpect(status().isBadRequest());
    }

    private static ObraEncontrada candidata() {
        return new ObraEncontrada("OPENALEX", "https://openalex.org/W"+Long.toUnsignedString(UUID.randomUUID().getMostSignificantBits()),
                "10.1234/"+UUID.randomUUID(), "Título de estudo",2022,List.of("Uma autora"));
    }
}
