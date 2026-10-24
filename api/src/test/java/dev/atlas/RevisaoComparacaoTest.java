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
