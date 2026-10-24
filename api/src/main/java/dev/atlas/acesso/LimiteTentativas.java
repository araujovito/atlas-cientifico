package dev.atlas.acesso;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Contadores por janela fixa no PostgreSQL. Cada operação é uma única instrução atômica. */
@Service
public class LimiteTentativas {
    private record Estado(int contagem, long restanteSegundos) {}

    /** 429 com Retry-After, para o cliente e para a tela saberem quando tentar de novo. */
    public static final class LimiteExcedido extends ResponseStatusException {
        private final long segundos;
        LimiteExcedido(long segundos) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas. Tente novamente em " + Math.max(1, (segundos + 59) / 60) + " minuto(s).");
            this.segundos = Math.max(1, segundos);
        }
        @Override public HttpHeaders getHeaders() {
            var h = new HttpHeaders(); h.set(HttpHeaders.RETRY_AFTER, Long.toString(segundos)); return h;
        }
    }

    private final JdbcClient jdbc;
    public LimiteTentativas(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** Conta esta tentativa e recusa quando passa de {@code maximo} na janela. */
    public void consumir(String chave, int maximo, int janelaSegundos) {
        var estado = incrementar(chave, janelaSegundos);
        if (estado.contagem() > maximo) throw new LimiteExcedido(estado.restanteSegundos());
    }

    /** Conta uma falha sem recusar; quem chama consulta {@link #exigirAbaixo} antes da próxima tentativa. */
    public void registrar(String chave, int janelaSegundos) { incrementar(chave, janelaSegundos); }

    /** Recusa quando a janela vigente já tem {@code maximo} ou mais registros. */
    public void exigirAbaixo(String chave, int maximo, int janelaSegundos) {
        var estado = jdbc.sql("""
                SELECT contagem, EXTRACT(EPOCH FROM (janela_inicio + make_interval(secs => :janela) - now()))::bigint AS restante
                FROM limite_tentativa WHERE chave = :chave AND janela_inicio + make_interval(secs => :janela) > now()
                """).param("chave", chave).param("janela", janelaSegundos)
                .query((rs, n) -> new Estado(rs.getInt("contagem"), rs.getLong("restante"))).optional();
        if (estado.isPresent() && estado.get().contagem() >= maximo) throw new LimiteExcedido(estado.get().restanteSegundos());
    }

    public void limpar(String chave) { jdbc.sql("DELETE FROM limite_tentativa WHERE chave = :chave").param("chave", chave).update(); }

    /** Janelas têm no máximo uma hora; o que tem mais de um dia já venceu. */
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    public int limparVencidas() { return jdbc.sql("DELETE FROM limite_tentativa WHERE janela_inicio < now() - interval '1 day'").update(); }

    private Estado incrementar(String chave, int janelaSegundos) {
        return jdbc.sql("""
                INSERT INTO limite_tentativa(chave, janela_inicio, contagem) VALUES (:chave, now(), 1)
                ON CONFLICT (chave) DO UPDATE SET
                    contagem = CASE WHEN limite_tentativa.janela_inicio + make_interval(secs => :janela) <= now() THEN 1 ELSE limite_tentativa.contagem + 1 END,
                    janela_inicio = CASE WHEN limite_tentativa.janela_inicio + make_interval(secs => :janela) <= now() THEN now() ELSE limite_tentativa.janela_inicio END
                RETURNING contagem, EXTRACT(EPOCH FROM (janela_inicio + make_interval(secs => :janela) - now()))::bigint AS restante
                """).param("chave", chave).param("janela", janelaSegundos)
                .query((rs, n) -> new Estado(rs.getInt("contagem"), rs.getLong("restante"))).single();
    }

    /** Dado pessoal (e-mail) não entra em claro na tabela. */
    public static String anonimo(String prefixo, String valor) {
        try {
            return prefixo + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
