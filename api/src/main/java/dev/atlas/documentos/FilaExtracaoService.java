package dev.atlas.documentos;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** A linha da tarefa é a outbox de trabalho; estado e eventos são gravados na mesma transação. */
@Service
public class FilaExtracaoService {
    public record Tarefa(UUID id, UUID documentoId, String estado, int tentativas, UUID execucaoId,
            String detalhe, Instant disponivelEm, Instant reservadoAte) {}
    public record Reserva(Tarefa tarefa, UUID token) {}
    public record Evento(long id, String estado, String detalhe, Instant registradoEm) {}
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final int reservaSegundos;
    private final int maxTentativas;

    public FilaExtracaoService(JdbcClient jdbc, TransactionTemplate tx, ProcessamentoProperties pdf,
            @Value("${atlas.fila.reserva-segundos:120}") int reservaSegundos,
            @Value("${atlas.fila.max-tentativas:3}") int maxTentativas) {
        this.jdbc=jdbc; this.tx=tx; this.reservaSegundos=reservaSegundos; this.maxTentativas=maxTentativas;
        if (reservaSegundos < pdf.tempoLimite().toSeconds()+10 || maxTentativas<1 || maxTentativas>10)
            throw new IllegalArgumentException("Reserva deve exceder o prazo do PDF em 10 s; tentativas entre 1 e 10.");
    }

    public Tarefa agendar(UUID documento) {
        return tx.execute(s -> {
            String hash = jdbc.sql("SELECT sha256 FROM versao_documento WHERE id=:id").param("id",documento).query(String.class)
                    .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Documento não encontrado."));
            String chave = hash+":"+ProcessadorDocumentoService.VERSAO_PROCESSADOR;
            var criada = jdbc.sql("INSERT INTO tarefa_extracao(documento_id,chave) VALUES (:d,:c) ON CONFLICT(chave) DO NOTHING RETURNING id")
                    .param("d",documento).param("c",chave).query(UUID.class).optional();
            UUID id = criada.orElseGet(() -> jdbc.sql("SELECT id FROM tarefa_extracao WHERE chave=:c").param("c",chave).query(UUID.class).single());
            if (criada.isPresent()) evento(id,"PENDENTE",null);
            return obter(id);
        });
    }

    public Tarefa obter(UUID id) {
        return jdbc.sql("SELECT * FROM tarefa_extracao WHERE id=:id").param("id",id).query(this::ler)
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Tarefa não encontrada."));
    }

    public Optional<Reserva> reservar() {
        return tx.execute(s -> {
            var esgotadas = jdbc.sql("""
                    UPDATE tarefa_extracao SET estado='FALHOU',token=NULL,reservado_ate=NULL,finalizado_em=now(),detalhe='Tentativas esgotadas após interrupção.'
                    WHERE estado='EM_ANDAMENTO' AND reservado_ate<=now() AND tentativas>=:max RETURNING id
                    """).param("max",maxTentativas).query(UUID.class).list();
            esgotadas.forEach(id -> evento(id,"FALHOU","Tentativas esgotadas após interrupção."));
            return jdbc.sql("""
                    WITH proxima AS (
                        SELECT id FROM tarefa_extracao WHERE tentativas<:max AND
                            ((estado='PENDENTE' AND disponivel_em<=now()) OR (estado='EM_ANDAMENTO' AND reservado_ate<=now()))
                        ORDER BY criado_em,id FOR UPDATE SKIP LOCKED LIMIT 1
                    )
                    UPDATE tarefa_extracao t SET estado='EM_ANDAMENTO',tentativas=tentativas+1,token=gen_random_uuid(),
                        reservado_ate=now()+make_interval(secs=>:prazo),detalhe=NULL
                    FROM proxima p WHERE t.id=p.id RETURNING t.*
                    """).param("max",maxTentativas).param("prazo",reservaSegundos)
                    .query((rs,row) -> new Reserva(ler(rs,row),rs.getObject("token",UUID.class))).optional()
                    .map(reserva -> { evento(reserva.tarefa().id(),"EM_ANDAMENTO","Tentativa "+reserva.tarefa().tentativas()); return reserva; });
        });
    }

    public boolean concluir(Reserva reserva, ResultadoProcessamentoDocumento resultado) {
        if (!resultado.documentoId().equals(reserva.tarefa().documentoId())
                || !List.of("CONCLUIDO","PRECISA_OCR").contains(resultado.estado())) throw new IllegalArgumentException("Resultado incompatível com a tarefa.");
        return tx.execute(s -> {
            int n=jdbc.sql("""
                    UPDATE tarefa_extracao SET estado=:estado,execucao_id=:e,token=NULL,reservado_ate=NULL,finalizado_em=now(),detalhe=NULL
                    WHERE id=:id AND token=:token AND estado='EM_ANDAMENTO' AND reservado_ate>now()
                    """).param("estado",resultado.estado()).param("e",resultado.execucaoId()).param("id",reserva.tarefa().id()).param("token",reserva.token()).update();
            if (n==1) evento(reserva.tarefa().id(),resultado.estado(),null);
            return n==1;
        });
    }

    public boolean falhar(Reserva reserva, String detalhe, boolean permanente) {
        return tx.execute(s -> {
            boolean acabou=permanente || reserva.tarefa().tentativas()>=maxTentativas;
            String estado=acabou ? "FALHOU" : "PENDENTE";
            int n=jdbc.sql("""
                    UPDATE tarefa_extracao SET estado=:estado,detalhe=:detalhe,token=NULL,reservado_ate=NULL,
                        finalizado_em=CASE WHEN :fim THEN now() ELSE NULL END,
                        disponivel_em=now()+make_interval(secs=>:atraso)
                    WHERE id=:id AND token=:token AND estado='EM_ANDAMENTO' AND reservado_ate>now()
                    """).param("estado",estado).param("detalhe",detalhe).param("fim",acabou)
                    .param("atraso",1 << reserva.tarefa().tentativas()).param("id",reserva.tarefa().id()).param("token",reserva.token()).update();
            if(n==1) evento(reserva.tarefa().id(),estado,detalhe);
            return n==1;
        });
    }

    public Tarefa retentar(UUID id) {
        return tx.execute(s -> {
            obter(id);
            int n=jdbc.sql("""
                    UPDATE tarefa_extracao SET estado='PENDENTE',tentativas=0,detalhe=NULL,execucao_id=NULL,
                        finalizado_em=NULL,disponivel_em=now() WHERE id=:id AND estado='FALHOU'
                    """).param("id",id).update();
            if(n!=1) throw new ResponseStatusException(HttpStatus.CONFLICT,"Somente tarefas que falharam podem ser reativadas.");
            evento(id,"REATIVADA","Reativada pelo operador da instância."); return obter(id);
        });
    }

    public List<Tarefa> listar(String estado,int limite) {
        if(limite<1 || limite>100 || !List.of("PENDENTE","EM_ANDAMENTO","CONCLUIDO","PRECISA_OCR","FALHOU").contains(estado))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Estado ou limite inválido.");
        return jdbc.sql("SELECT * FROM tarefa_extracao WHERE estado=:e ORDER BY criado_em,id LIMIT :n")
                .param("e",estado).param("n",limite).query(this::ler).list();
    }

    public List<Evento> eventos(UUID id) {
        obter(id);
        return jdbc.sql("SELECT * FROM tarefa_evento WHERE tarefa_id=:id ORDER BY id LIMIT 1000").param("id",id)
                .query((rs,row) -> new Evento(rs.getLong("id"),rs.getString("estado"),rs.getString("detalhe"),rs.getTimestamp("registrado_em").toInstant())).list();
    }

    private Tarefa ler(ResultSet rs,int row) throws SQLException {
        var reserva=rs.getTimestamp("reservado_ate");
        return new Tarefa(rs.getObject("id",UUID.class),rs.getObject("documento_id",UUID.class),rs.getString("estado"),rs.getInt("tentativas"),
                rs.getObject("execucao_id",UUID.class),rs.getString("detalhe"),rs.getTimestamp("disponivel_em").toInstant(),reserva==null?null:reserva.toInstant());
    }
    private void evento(UUID id,String estado,String detalhe) {
        jdbc.sql("INSERT INTO tarefa_evento(tarefa_id,estado,detalhe) VALUES (:id,:e,:d)").param("id",id).param("e",estado).param("d",detalhe).update();
    }
}
