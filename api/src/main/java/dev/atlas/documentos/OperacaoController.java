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
    public OperacaoController(JdbcClient jdbc, MeterRegistry metrics) { this.jdbc = jdbc; this.metrics = metrics; }
    public record Estado(String estado, long quantidade, long reservasExpiradas) {}
    @GetMapping("/api/operacao")
    public List<Estado> resumo() {
        return jdbc.sql("""
                SELECT estado, count(*) AS quantidade,
                       count(*) FILTER (WHERE estado='EM_ANDAMENTO' AND reservado_ate<now()) AS expiradas
                FROM tarefa_extracao GROUP BY estado ORDER BY estado
                """).query((rs,n) -> new Estado(rs.getString("estado"), rs.getLong("quantidade"), rs.getLong("expiradas"))).list();
    }
    public record Metrica(String resultado, long tentativas, double segundosTotais, double segundosMaximos) {}
    @GetMapping("/api/operacao/metricas")
    public List<Metrica> metricas() {
        return metrics.find("atlas.pdf.tentativa").timers().stream()
                .map(t -> new Metrica(t.getId().getTag("resultado"),t.count(),t.totalTime(TimeUnit.SECONDS),t.max(TimeUnit.SECONDS)))
                .sorted(java.util.Comparator.comparing(Metrica::resultado)).toList();
    }
}
