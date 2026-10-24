package dev.atlas;

import static org.assertj.core.api.Assertions.*;
import dev.atlas.documentos.*;
import dev.atlas.extracao.RevisaoService;
import dev.atlas.dominio.baterias.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties="atlas.fila.ativa=false")
class RevisaoComparacaoTest {
    @Autowired JdbcClient jdbc;
    @Autowired ProcessadorDocumentoService processador;
    @Autowired RevisaoService revisoes;
    @Autowired ComparacaoController comparacoes;
    private static final Map<String,Object> CONDICOES=Map.of("taxa_corrente","190 mA/g","carga_catodo","2.4 mg/cm2", "eletrolito","AFE","temperatura","25 C","janela_tensao","2.8-4.3 V");

    @Test void correcaoAcrescentaHistoricoSemAlterarOriginal() {
        UUID medida=registrar("98.2","mAh/g",CONDICOES);
        var atual=revisoes.obter(medida);
        var correcao=new MedidaCandidata("NCM622","capacidade","99.3","mAh/g","REPORTADO",1,"Outro resultado 99.3 mAh/g.",CONDICOES);
        var corrigida=revisoes.revisar(medida,new RevisaoService.Pedido("Pessoa revisora","Associação corrigida no parágrafo",0,correcao));
        assertThat(corrigida.versao()).isEqualTo(1);
        assertThat(corrigida.original().medida().valorOriginal()).isEqualTo("98.2");
        assertThat(corrigida.atual().medida().valorOriginal()).isEqualTo("99.3");
        assertThat(jdbc.sql("SELECT valor_original FROM medida WHERE id=:id").param("id",medida).query(String.class).single()).isEqualTo("98.2");
        assertThat(revisoes.historico(medida).getFirst().anterior()).isEqualTo(atual.atual());
        assertThatThrownBy(() -> revisoes.revisar(medida,new RevisaoService.Pedido("Pessoa","Concorrente",0,correcao)))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> jdbc.sql("UPDATE revisao_medida SET motivo='apagado' WHERE medida_id=:id").param("id",medida).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM revisao_medida WHERE medida_id=:id").param("id",medida).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void revisaoTambemExigeValorPresenteEmEvidencia() {
        UUID medida=registrar("160","mAh/g",CONDICOES);
        var invalida=new MedidaCandidata("NCM622","capacidade","999","mAh/g","REPORTADO",1,"Outro resultado 99.3 mAh/g.",CONDICOES);
        assertThatThrownBy(() -> revisoes.revisar(medida,new RevisaoService.Pedido("Pessoa","Revisão inválida",0,invalida)))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
        assertThat(revisoes.historico(medida)).isEmpty();
    }

    @Test void alinhaUnidadesEquivalentesSomenteComCondicoesCompletasIguais() {
        UUID a=registrar("0.160","Ah/g",CONDICOES),b=registrar("160","mAh/g",CONDICOES);
        var resultado=comparacoes.comparar(List.of(a,b));
        assertThat(resultado.podeAlinharNumericamente()).isTrue();
        assertThat(resultado.linhas()).allSatisfy(l -> assertThat(l.normalizado().valor()).isEqualTo("160"));
        UUID semContexto=registrar("160","mAh/g",Map.of());
        var restrita=comparacoes.comparar(List.of(a,semContexto));
        assertThat(restrita.podeAlinharNumericamente()).isFalse(); assertThat(restrita.linhas()).hasSize(2);
        assertThat(restrita.motivos()).anyMatch(m -> m.contains("incompletas"));
        UUID tensao=registrar("3.4","V",CONDICOES);
        assertThat(comparacoes.comparar(List.of(a,tensao)).podeAlinharNumericamente()).isFalse();
    }

    @Test void comparacaoUsaRevisaoAtualESinalizaEstadoPendente() {
        UUID a=registrar("160","mAh/g",CONDICOES),b=registrar("161","mAh/g",CONDICOES);
        var valor=revisoes.obter(a).atual().medida();
        revisoes.revisar(a,new RevisaoService.Pedido("Pessoa","Falta conferir associação",0,
                new MedidaCandidata(valor.objeto(),valor.metrica(),valor.valorOriginal(),valor.unidadeOriginal(),"PENDENTE_REVISAO",valor.pagina(),valor.trecho(),valor.condicoes())));
        var resultado=comparacoes.comparar(List.of(a,b));
        assertThat(resultado.podeAlinharNumericamente()).isFalse();
        assertThat(resultado.linhas().getFirst().registro().versao()).isEqualTo(1);
    }

    @Test void segundaRevisaoEncadeiaComAPrimeiraEPreservaOffsets() {
        UUID medida=registrar("98.2","mAh/g",CONDICOES);
        var v1=new MedidaCandidata("NCM622","capacidade","99.3","mAh/g","REPORTADO",1,"Outro resultado 99.3 mAh/g.",CONDICOES);
        var primeira=revisoes.revisar(medida,new RevisaoService.Pedido("Pessoa","Primeira",0,v1));
        var v2=new MedidaCandidata("NCM622","capacidade","98.2","mAh/g","REPORTADO",1,"Capacidade 98.2 mAh/g.",CONDICOES);
        var segunda=revisoes.revisar(medida,new RevisaoService.Pedido("Outra pessoa","Voltou ao original",1,v2));
        assertThat(segunda.versao()).isEqualTo(2);
        var historico=revisoes.historico(medida);
        assertThat(historico).extracting(RevisaoService.Revisao::versao).containsExactly(1,2);
        assertThat(historico.get(1).anterior()).isEqualTo(historico.get(0).correcao());
        var evidencia=historico.get(1).correcao().evidencias().getFirst();
        assertThat(evidencia.inicioCaractere()).isEqualTo(0);
        assertThat(evidencia.fimCaractere()).isEqualTo("Capacidade 98.2 mAh/g.".length());
        assertThat(primeira.atual().medida().valorOriginal()).isEqualTo("99.3");
    }

    @Test void correcaoParaAusenteNaoCarregaEvidencia() {
        UUID medida=registrar("98.2","mAh/g",CONDICOES);
        var ausente=new MedidaCandidata("NCM622","capacidade",null,null,"AUSENTE",null,null,CONDICOES);
        var resultado=revisoes.revisar(medida,new RevisaoService.Pedido("Pessoa","Valor não consta no artigo",0,ausente));
        assertThat(resultado.atual().evidencias()).isEmpty();
        assertThat(resultado.atual().medida().estado()).isEqualTo("AUSENTE");
        assertThat(resultado.original().evidencias()).isNotEmpty();
    }

    @Test void medidaInexistenteDevolve404() {
        var qualquer=new MedidaCandidata("NCM622","capacidade","1","mAh/g","REPORTADO",1,"Capacidade 1 mAh/g.",CONDICOES);
        assertThatThrownBy(() -> revisoes.revisar(UUID.randomUUID(),new RevisaoService.Pedido("Pessoa","Inexistente",0,qualquer)))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        assertThatThrownBy(() -> revisoes.obter(UUID.randomUUID())).isInstanceOf(ResponseStatusException.class);
    }

    @Test void revisoesConcorrentesDaMesmaVersaoGravamExatamenteUma() throws Exception {
        UUID medida=registrar("98.2","mAh/g",CONDICOES);
        var correcao=new MedidaCandidata("NCM622","capacidade","99.3","mAh/g","REPORTADO",1,"Outro resultado 99.3 mAh/g.",CONDICOES);
        var inicio=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tarefas=List.of("A","B").stream().map(autor -> pool.submit(() -> {
                inicio.await();
                try { revisoes.revisar(medida,new RevisaoService.Pedido(autor,"Concorrente",0,correcao)); return 200; }
                catch(ResponseStatusException e) { return e.getStatusCode().value(); }
            })).toList();
            inicio.countDown();
            var codigos=new java.util.ArrayList<Integer>();
            for(var t:tarefas) codigos.add(t.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(codigos).containsExactlyInAnyOrder(200,409);
        }
        assertThat(revisoes.historico(medida)).hasSize(1);
    }

    private UUID registrar(String valor,String unidade,Map<String,Object> condicoes) {
        UUID doc=jdbc.sql("""
                INSERT INTO versao_documento(origem,sha256,tamanho_bytes,chave_armazenamento,autorizacao_declarada,autorizacao_confirmada_em)
                VALUES ('UPLOAD',:h,10,'fixture.pdf',true,now()) RETURNING id
                """).param("h",(UUID.randomUUID().toString()+UUID.randomUUID()).replace("-","")).query(UUID.class).single();
        UUID exec=jdbc.sql("""
                INSERT INTO execucao_processamento(versao_documento_id,versao_processador,estado,chave_idempotencia,finalizado_em)
                VALUES (:d,'fixture','CONCLUIDO',:c,now()) RETURNING id
                """).param("d",doc).param("c",UUID.randomUUID().toString()).query(UUID.class).single();
        String trecho="Capacidade "+valor+" "+unidade+".";
        jdbc.sql("INSERT INTO pagina_texto(execucao_id,numero,texto) VALUES (:e,1,:t)").param("e",exec)
                .param("t",trecho+" Outro resultado 99.3 mAh/g.").update();
        return processador.registrarMedida(doc,exec,new MedidaCandidata("NCM622","capacidade",valor,unidade,"REPORTADO",1,trecho,condicoes)).medidaId();
    }
}
