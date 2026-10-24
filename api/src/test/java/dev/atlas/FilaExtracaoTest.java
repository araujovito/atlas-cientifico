package dev.atlas;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.atlas.documentos.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties="atlas.fila.ativa=false")
@AutoConfigureMockMvc
class FilaExtracaoTest {
    @Autowired FilaExtracaoService fila;
    @Autowired FilaExtracaoWorker worker;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean ProcessadorDocumentoService processador;

    @BeforeEach void limparFilaDesteBancoDeTeste() { jdbc.sql("DELETE FROM tarefa_extracao").update(); }

    @Test void comandoRepetidoRetornaMesmaTarefaComUmEventoInicial() throws Exception {
        UUID doc=documento(); var a=fila.agendar(doc); var b=fila.agendar(doc);
        assertThat(b.id()).isEqualTo(a.id()); assertThat(fila.eventos(a.id())).hasSize(1);
        mvc.perform(post("/api/documentos/{id}/processamentos",doc)).andExpect(status().isAccepted())
                .andExpect(header().string("Location","/api/processamentos/"+a.id()))
                .andExpect(jsonPath("$.id").value(a.id().toString()));
        mvc.perform(post("/api/documentos/{id}/processamentos",UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test void doisTrabalhadoresNaoReservamAMesmaTarefa() throws Exception {
        fila.agendar(documento()); var inicio=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> {inicio.await();return fila.reservar();});
            var b=pool.submit(() -> {inicio.await();return fila.reservar();}); inicio.countDown();
            int reservas=(a.get(10,TimeUnit.SECONDS).isPresent()?1:0)+(b.get(10,TimeUnit.SECONDS).isPresent()?1:0);
            assertThat(reservas).isEqualTo(1);
        }
    }

    @Test void reservaExpiradaPodeSerRetomadaMasTokenAntigoNaoConfirma() {
        UUID doc=documento(); var tarefa=fila.agendar(doc); var antiga=fila.reservar().orElseThrow();
        expirar(tarefa.id()); var nova=fila.reservar().orElseThrow();
        assertThat(nova.token()).isNotEqualTo(antiga.token()); assertThat(nova.tarefa().tentativas()).isEqualTo(2);
        var resultado=resultado(doc);
        assertThat(fila.concluir(antiga,resultado)).isFalse();
        assertThat(fila.concluir(nova,resultado)).isTrue();
        assertThat(fila.obter(tarefa.id()).estado()).isEqualTo("CONCLUIDO");
    }

    @Test void falhaTemporariaRecuaEAcabaNaFilaDeFalhasComHistorico() {
        UUID doc=documento(); var tarefa=fila.agendar(doc);
        when(processador.processar(doc)).thenThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,"Prazo excedido."));
        for(int tentativa=1;tentativa<=3;tentativa++) {
            assertThat(worker.executarUma()).isTrue(); var atual=fila.obter(tarefa.id());
            assertThat(atual.tentativas()).isEqualTo(tentativa);
            if(tentativa<3) {
                assertThat(atual.estado()).isEqualTo("PENDENTE"); assertThat(atual.disponivelEm()).isAfter(Instant.now());
                assertThat(worker.executarUma()).isFalse();
                jdbc.sql("UPDATE tarefa_extracao SET disponivel_em=now() WHERE id=:id").param("id",tarefa.id()).update();
            }
        }
        assertThat(fila.obter(tarefa.id()).estado()).isEqualTo("FALHOU");
        assertThat(fila.listar("FALHOU",100)).extracting(FilaExtracaoService.Tarefa::id).contains(tarefa.id());
        int eventos=fila.eventos(tarefa.id()).size();
        assertThat(fila.retentar(tarefa.id()).tentativas()).isZero();
        assertThat(fila.eventos(tarefa.id())).hasSize(eventos+1);
        verify(processador,times(3)).processar(doc);
    }

    @Test void erroPermanenteNaoRepeteEInterrupcoesEsgotadasSaoRetidas() {
        UUID doc=documento(); var tarefa=fila.agendar(doc);
        when(processador.processar(doc)).thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"PDF inválido."));
        worker.executarUma(); assertThat(fila.obter(tarefa.id()).estado()).isEqualTo("FALHOU");
        assertThat(worker.executarUma()).isFalse();
        fila.retentar(tarefa.id());
        for(int i=0;i<3;i++){ fila.reservar().orElseThrow(); expirar(tarefa.id()); }
        assertThat(fila.reservar()).isEmpty(); assertThat(fila.obter(tarefa.id()).estado()).isEqualTo("FALHOU");
    }

    @Test void bancoNaoPermiteAssociarExecucaoDeOutroDocumento() {
        var tarefa=fila.agendar(documento()); var reserva=fila.reservar().orElseThrow();
        var outro=resultado(documento());
        assertThatThrownBy(() -> jdbc.sql("UPDATE tarefa_extracao SET execucao_id=:e WHERE id=:id")
                .param("e",outro.execucaoId()).param("id",tarefa.id()).update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> fila.concluir(reserva,outro)).isInstanceOf(IllegalArgumentException.class);
    }

    private void expirar(UUID id) { jdbc.sql("UPDATE tarefa_extracao SET reservado_ate=now()-interval '1 second' WHERE id=:id").param("id",id).update(); }
    private UUID documento() {
        String hash=(UUID.randomUUID().toString()+UUID.randomUUID()).replace("-","");
        return jdbc.sql("""
                INSERT INTO versao_documento(origem,sha256,tamanho_bytes,chave_armazenamento,autorizacao_declarada,autorizacao_confirmada_em)
                VALUES ('UPLOAD',:hash,10,'teste.pdf',true,now()) RETURNING id
                """).param("hash",hash).query(UUID.class).single();
    }
    private ResultadoProcessamentoDocumento resultado(UUID doc) {
        UUID exec=jdbc.sql("""
                INSERT INTO execucao_processamento(versao_documento_id,versao_processador,estado,chave_idempotencia,finalizado_em)
                VALUES (:doc,'teste','CONCLUIDO',:chave,now()) RETURNING id
                """).param("doc",doc).param("chave",UUID.randomUUID().toString()).query(UUID.class).single();
        return new ResultadoProcessamentoDocumento(exec,doc,"teste","CONCLUIDO",1,false);
    }
}
