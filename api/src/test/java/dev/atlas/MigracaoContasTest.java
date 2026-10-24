package dev.atlas;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class MigracaoContasTest {
    @Test void atualizaBancoV15ComDocumentosMedidasRevisaoEColecaoSemPerderHistorico() throws Exception {
        try(var pg=new PostgreSQLContainer("postgres:18")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).target("15").load().migrate();
            try(var c=DriverManager.getConnection(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()); var s=c.createStatement()) {
                UUID doc=UUID.randomUUID(),exec=UUID.randomUUID(),estudo=UUID.randomUUID(),exp=UUID.randomUUID(),medida=UUID.randomUUID();
                s.execute("INSERT INTO versao_documento(id,origem,sha256,tamanho_bytes,chave_armazenamento,autorizacao_declarada,autorizacao_confirmada_em) VALUES ('"+doc+"','UPLOAD','"+"a".repeat(64)+"',10,'antigo.pdf',true,now())");
                s.execute("INSERT INTO execucao_processamento(id,versao_documento_id,versao_processador,estado,chave_idempotencia,finalizado_em) VALUES ('"+exec+"','"+doc+"','v1','CONCLUIDO','antiga',now())");
                s.execute("INSERT INTO estudo(id,versao_documento_id,execucao_id) VALUES ('"+estudo+"','"+doc+"','"+exec+"')");
                s.execute("INSERT INTO experimento(id,estudo_id,objeto) VALUES ('"+exp+"','"+estudo+"','NCM')");
                s.execute("INSERT INTO medida(id,experimento_id,metrica,estado) VALUES ('"+medida+"','"+exp+"','capacidade','AUSENTE')");
                s.execute("INSERT INTO revisao_medida(medida_id,versao,autor_declarado,motivo,anterior,correcao) VALUES ('"+medida+"',1,'Nome histórico','Motivo histórico','{}','{}')");
                s.execute("INSERT INTO colecao(nome) VALUES ('Coleção antiga')");
                s.execute("INSERT INTO tarefa_extracao(documento_id,chave) VALUES ('"+doc+"','antiga')");
                Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load().migrate();
                for(String tabela: new String[]{"versao_documento","colecao","execucao_processamento","medida","revisao_medida"}) {
                    String coluna=tabela.equals("versao_documento")||tabela.equals("colecao")?"dono_id":"usuario_id";
                    try(var r=s.executeQuery("SELECT count(*) FROM "+tabela+" WHERE "+coluna+"='00000000-0000-0000-0000-000000000001'")) {r.next();assertThat(r.getInt(1)).isEqualTo(1);}
                }
                try(var r=s.executeQuery("SELECT autor_declarado,motivo,anterior::text,correcao::text FROM revisao_medida")) {
                    r.next(); assertThat(r.getString(1)).isEqualTo("Nome histórico"); assertThat(r.getString(2)).isEqualTo("Motivo histórico");
                    assertThat(r.getString(3)).isEqualTo("{}"); assertThat(r.getString(4)).isEqualTo("{}");
                }
                assertThatThrownBy(()->s.execute("UPDATE revisao_medida SET motivo='alterado'"))
                        .hasMessageContaining("imutáveis");
                try(var r=s.executeQuery("SELECT ativo,senha_hash FROM usuario")){r.next();assertThat(r.getBoolean(1)).isFalse();assertThat(r.getString(2)).startsWith("!");}
                assertThatThrownBy(()->s.execute("INSERT INTO colecao(nome) VALUES ('Sem dono')")).hasMessageContaining("dono_id");
                Flyway.configure().dataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword()).load().validate();
            }
        }
    }
}
