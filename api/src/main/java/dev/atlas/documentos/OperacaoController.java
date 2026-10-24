package dev.atlas.documentos;

import java.util.List;
import java.util.concurrent.TimeUnit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Estado durável no banco; não confundir com contadores reiniciados junto do processo. */
@RestController
public class OperacaoController {
    private final JdbcClient jdbc;
    private final MeterRegistry metrics;
    private final dev.atlas.acesso.UsuarioAtual usuario;
    public OperacaoController(JdbcClient jdbc, MeterRegistry metrics,dev.atlas.acesso.UsuarioAtual usuario) { this.jdbc = jdbc; this.metrics = metrics;this.usuario=usuario; }
    public record Estado(String estado, long quantidade, long reservasExpiradas) {}
    @GetMapping("/api/operacao")
    public List<Estado> resumo() {
        return jdbc.sql("""
                SELECT t.estado, count(*) AS quantidade,
                       count(*) FILTER (WHERE t.estado='EM_ANDAMENTO' AND t.reservado_ate<now()) AS expiradas
                FROM tarefa_extracao t JOIN versao_documento d ON d.id=t.documento_id
                WHERE d.dono_id=:u GROUP BY t.estado ORDER BY t.estado
                """).param("u",usuario.id()).query((rs,n) -> new Estado(rs.getString("estado"), rs.getLong("quantidade"), rs.getLong("expiradas"))).list();
    }
    public record Metrica(String resultado, long tentativas, double segundosTotais, double segundosMaximos) {}
    @GetMapping("/api/operacao/metricas")
    public List<Metrica> metricas() {
        return metrics.find("atlas.pdf.tentativa").timers().stream()
                .map(t -> new Metrica(t.getId().getTag("resultado"),t.count(),t.totalTime(TimeUnit.SECONDS),t.max(TimeUnit.SECONDS)))
                .sorted(java.util.Comparator.comparing(Metrica::resultado)).toList();
    }
}
