package dev.atlas;

import dev.atlas.catalogo.*;
import dev.atlas.documentos.*;
import dev.atlas.fontes.openalex.OpenAlexClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;
import static dev.atlas.ContasTeste.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties={"atlas.fila.ativa=false","atlas.documentos.diretorio=${java.io.tmpdir}/atlas-idor-test"})
@AutoConfigureMockMvc
class IsolamentoRecursosTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired CatalogoService catalogo;
    @Autowired ObjectMapper mapper;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping rotas;
    @MockitoBean ExtratorPdfIsolado parser;
    @MockitoBean OpenAlexClient fonte;
    Cliente a,b;
    static final String MEDIDA="{\"objeto\":\"NCM\",\"metrica\":\"capacidade\",\"valorOriginal\":\"125\",\"unidadeOriginal\":\"mAh/g\",\"estado\":\"REPORTADO\",\"pagina\":1,\"trecho\":\"125 mAh/g\",\"condicoes\":{}}";
    record Fixture(UUID doc,UUID exec,UUID medida,UUID tarefa,UUID colecao,UUID obra) {}
    record Rota(String metodo,String padrao,int status) {}
    static final List<Rota> PRIVADAS=List.of(
        new Rota("POST","/api/documentos/{documentoId}/extracoes",200),
        new Rota("GET","/api/documentos/{documentoId}/extracoes/{execucaoId}/paginas",200),
        new Rota("POST","/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas",200),
        new Rota("GET","/api/documentos/{documentoId}/extracoes/{execucaoId}/medidas",200),
        new Rota("POST","/api/documentos/{documento}/processamentos",202),
        new Rota("GET","/api/processamentos/{id}",200),
        new Rota("GET","/api/processamentos/{id}/eventos",200),
        new Rota("POST","/api/processamentos/{id}/retentar",200),
        new Rota("GET","/api/medidas/{id}",200),
        new Rota("GET","/api/medidas/{id}/revisoes",200),
        new Rota("POST","/api/medidas/{id}/revisoes",200),
        new Rota("GET","/api/colecoes/{id}/obras",200),
        new Rota("PUT","/api/colecoes/{id}/obras/{obra}",204),
        new Rota("DELETE","/api/colecoes/{id}/obras/{obra}",204),
        new Rota("PUT","/api/catalogo/obras/{id}/documentos/{documento}",204));
    static final Set<String> PUBLICAS=Set.of("GET /api/catalogo/obras/{id}","GET /api/catalogo/obras/{obra}/citacoes","POST /api/catalogo/obras/{obra}/citacoes");

    @BeforeEach void contas() throws Exception {
        a=cadastrar(mvc,"a-"+UUID.randomUUID()+"@example.com");b=cadastrar(mvc,"b-"+UUID.randomUUID()+"@example.com");
        when(parser.extrair(any(),anyInt())).thenReturn(new ResultadoExtracaoPdf(EstadoExtracaoPdf.TEXTO_EXTRAIDO,List.of(new PaginaPdf(1,"Capacity 125 mAh/g."))));
        when(fonte.referencias(anyString())).thenReturn(List.of());
    }
    @Test void todaRotaPrivadaComIdRecusaBCom404EAContinuaLendoEGravando() throws Exception {
        var f=fixture(a);
        for(var rota:PRIVADAS){
            String path=path(rota,f);
            if(rota.padrao().endsWith("/retentar")) jdbc.sql("UPDATE tarefa_extracao SET estado='FALHOU',finalizado_em=now() WHERE id=:id").param("id",f.tarefa()).update();
            String body=rota.padrao().endsWith("/revisoes")?"{\"motivo\":\"Conferência\",\"versaoEsperada\":0,\"corrigida\":"+MEDIDA+"}":MEDIDA;
            mvc.perform(com(request(HttpMethod.valueOf(rota.metodo()),path),b).contentType("application/json").content(body))
                    .andExpect(status().isNotFound());
            mvc.perform(com(request(HttpMethod.valueOf(rota.metodo()),path),a).contentType("application/json").content(body))
                    .andExpect(status().is(rota.status()));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM revisao_medida WHERE medida_id=:m").param("m",f.medida()).query(Long.class).single()).isEqualTo(1);
    }
    @Test void idsMisturadosComparacaoEListagensNaoVazamOutroDono() throws Exception {
        var fa=fixture(a);var fb=fixture(b);
        for(String finalRota:List.of("paginas","medidas")){
            String path="/api/documentos/"+fb.doc()+"/extracoes/"+fa.exec()+"/"+finalRota;
            mvc.perform(com(get(path),b)).andExpect(status().isNotFound());
            if(finalRota.equals("medidas")) mvc.perform(com(post(path),b).contentType("application/json").content(MEDIDA)).andExpect(status().isNotFound());
        }
        mvc.perform(com(get("/api/comparacoes").param("medidas",fa.medida()+","+fb.medida()),b)).andExpect(status().isNotFound());
        String colecoes=mvc.perform(com(get("/api/colecoes"),b)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(colecoes).contains(fb.colecao().toString()).doesNotContain(fa.colecao().toString());
        String tarefas=mvc.perform(com(get("/api/processamentos").param("estado","PENDENTE"),b)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(tarefas).contains(fb.tarefa().toString()).doesNotContain(fa.tarefa().toString(),fa.doc().toString());
        mvc.perform(com(get("/api/operacao"),b)).andExpect(status().isOk()).andExpect(jsonPath("$[0].quantidade").value(1));
        mvc.perform(com(get("/api/operacao/metricas"),b)).andExpect(status().isUnauthorized());
    }
    @Test void mesmoPdfTemRegistrosEExecucoesSeparadosEObrasPublicasContinuamCompartilhadas() throws Exception {
        byte[] bytes=("%PDF-fixture-"+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        UUID docA=upload(a,bytes),docB=upload(b,bytes);
        assertThat(docA).isNotEqualTo(docB);assertThat(upload(a,bytes)).isEqualTo(docA);
        String execA=extrair(a,docA),execB=extrair(b,docB);assertThat(execA).isNotEqualTo(execB);
        var f=fixture(a);
        mvc.perform(com(get("/api/catalogo/obras/"+f.obra()),b)).andExpect(status().isOk());
        mvc.perform(com(post("/api/catalogo/obras/"+f.obra()+"/citacoes"),b)).andExpect(status().isOk());
        mvc.perform(com(get("/api/catalogo/obras/"+f.obra()+"/citacoes"),b)).andExpect(status().isOk());
        // Conhecer uma obra compartilhada não libera o documento que outra pessoa associou.
        mvc.perform(com(put("/api/catalogo/obras/"+f.obra()+"/documentos/"+f.doc()),b)).andExpect(status().isNotFound());
    }
    @Test void inventarioDeRotasExigeCasoParaCadaIdPrivadoOuExcecaoPublica() {
        var esperado=new HashSet<>(PUBLICAS);PRIVADAS.forEach(r->esperado.add(r.metodo()+" "+r.padrao()));
        var encontradas=new HashSet<String>();
        rotas.getHandlerMethods().forEach((mapping,handler)->mapping.getPatternValues().stream().filter(p->p.startsWith("/api/") && p.contains("{"))
            .forEach(p->mapping.getMethodsCondition().getMethods().forEach(m->encontradas.add(m.name()+" "+p))));
        assertThat(encontradas).containsExactlyInAnyOrderElementsOf(esperado);
    }
    @Test void procedenciaVemDaContaENaoDeAutorOuUsuarioForjadoNoJson() throws Exception {
        var f=fixture(a);String path="/api/medidas/"+f.medida()+"/revisoes";
        String pedido="{\"motivo\":\"Conferido\",\"versaoEsperada\":0,\"corrigida\":"+MEDIDA+"}";
        for(String campo:List.of("autorDeclarado","autor_declarado","usuario_id","usuarioId")) {
            String forjado=pedido.substring(0,pedido.length()-1)+",\""+campo+"\":\""+b.id()+"\"}";
            mvc.perform(com(post(path),a).contentType("application/json").content(forjado)).andExpect(status().isBadRequest());
        }
        mvc.perform(com(post(path),a).contentType("application/json").content(pedido)).andExpect(status().isOk());
        mvc.perform(com(get(path),a)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].usuarioId").value(a.id().toString()))
                .andExpect(jsonPath("$[0].autorDeclarado").value(a.id().toString()));
        for(String table:List.of("medida","revisao_medida"))
            assertThat(jdbc.sql("SELECT usuario_id FROM "+table+" WHERE "+(table.equals("medida")?"id":"medida_id")+"=:m")
                    .param("m",f.medida()).query(UUID.class).single()).isEqualTo(a.id());
        assertThat(jdbc.sql("SELECT usuario_id FROM execucao_processamento WHERE id=:e").param("e",f.exec()).query(UUID.class).single()).isEqualTo(a.id());
        String forjada=MEDIDA.substring(0,MEDIDA.length()-1)+",\"usuario_id\":\""+b.id()+"\"}";
        mvc.perform(com(post("/api/documentos/"+f.doc()+"/extracoes/"+f.exec()+"/medidas"),a).contentType("application/json").content(forjada))
                .andExpect(status().isBadRequest());
    }
    private String path(Rota r,Fixture f){
        UUID id=r.padrao().startsWith("/api/colecoes")?f.colecao():r.padrao().startsWith("/api/medidas")?f.medida():r.padrao().startsWith("/api/catalogo")?f.obra():f.tarefa();
        return r.padrao().replace("{documentoId}",f.doc().toString()).replace("{documento}",f.doc().toString())
                .replace("{execucaoId}",f.exec().toString()).replace("{obra}",f.obra().toString()).replace("{id}",id.toString());
    }
    private UUID upload(Cliente c,byte[] bytes) throws Exception {
        var response=mvc.perform(com(multipart("/api/documentos").file(new MockMultipartFile("arquivo","fixture.pdf","application/pdf",bytes)).param("autorizacaoConfirmada","true"),c))
                .andExpect(status().isOk()).andReturn().getResponse();
        return UUID.fromString(mapper.readTree(response.getContentAsString()).get("id").asText());
    }
    private String extrair(Cliente c,UUID doc) throws Exception {
        var response=mvc.perform(com(post("/api/documentos/"+doc+"/extracoes"),c)).andExpect(status().isOk()).andReturn().getResponse();
        return mapper.readTree(response.getContentAsString()).get("execucaoId").asText();
    }
    private Fixture fixture(Cliente c) throws Exception {
        UUID doc=upload(c,("%PDF-fixture-"+UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));UUID exec=UUID.fromString(extrair(c,doc));
        var measure=mvc.perform(com(post("/api/documentos/"+doc+"/extracoes/"+exec+"/medidas"),c).contentType("application/json").content(MEDIDA))
                .andExpect(status().isOk()).andReturn().getResponse();
        UUID medida=UUID.fromString(mapper.readTree(measure.getContentAsString()).get("medidaId").asText());
        var task=mvc.perform(com(post("/api/documentos/"+doc+"/processamentos"),c)).andExpect(status().isAccepted()).andReturn().getResponse();
        UUID tarefa=UUID.fromString(mapper.readTree(task.getContentAsString()).get("id").asText());
        var collection=mvc.perform(com(post("/api/colecoes"),c).contentType("application/json").content("{\"nome\":\"Privada\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        UUID colecao=UUID.fromString(mapper.readTree(collection.getContentAsString()).get("id").asText());
        var obra=catalogo.registrar(new ObraEncontrada("OPENALEX","https://openalex.org/W"+System.nanoTime(),"10.1234/"+UUID.randomUUID(),"Título público",2024,List.of()));
        return new Fixture(doc,exec,medida,tarefa,colecao,obra.id());
    }
}
