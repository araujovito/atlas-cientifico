package dev.atlas.acesso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Limite de uso por conta nas rotas caras: envio de PDF, processamento, consultas a fontes externas e download.
 * Roda depois da autorização por dono, então quem não pode acessar o recurso (404) não gasta o próprio limite.
 */
@Component
public class LimiteDeUso implements HandlerInterceptor {
    private record Regra(String balde) {}
    private static final Map<String, Regra> REGRAS = Map.ofEntries(
        Map.entry("POST /api/documentos", new Regra("upload")),
        Map.entry("POST /api/documentos/{documentoId}/extracoes", new Regra("processar")),
        Map.entry("POST /api/documentos/{documento}/processamentos", new Regra("processar")),
        Map.entry("GET /api/catalogo/obras/por-doi", new Regra("fonte")),
        Map.entry("GET /api/catalogo/obras/busca", new Regra("fonte")),
        Map.entry("GET /api/catalogo/obras/autores", new Regra("fonte")),
        Map.entry("POST /api/catalogo/obras", new Regra("fonte")),
        Map.entry("POST /api/catalogo/obras/{obra}/citacoes", new Regra("fonte")),
        Map.entry("GET /api/documentos/{documentoId}/original", new Regra("download")),
        Map.entry("GET /api/publico/documentos/{id}/original", new Regra("download")));

    private final LimiteTentativas limites;
    private final LimitesProperties config;
    private final UsuarioAtual usuario;
    public LimiteDeUso(LimiteTentativas limites, LimitesProperties config, UsuarioAtual usuario) {
        this.limites = limites; this.config = config; this.usuario = usuario;
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String padrao = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (padrao == null) return true;
        var regra = REGRAS.get(request.getMethod() + " " + padrao);
        if (regra == null) return true;
        int maximo = switch (regra.balde()) {
            case "upload" -> config.usoUploadPorConta();
            case "processar" -> config.usoProcessarPorConta();
            case "fonte" -> config.usoFontePorConta();
            default -> config.usoDownloadPorConta();
        };
        limites.consumir("uso:" + regra.balde() + ":" + usuario.id(), maximo, config.usoJanela());
        return true;
    }

    /** Usado pelo teste de inventário: toda rota limitada precisa existir de fato. */
    public static java.util.Set<String> rotasLimitadas() { return REGRAS.keySet(); }
}
