package dev.atlas.acesso;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limites de tentativas por janela fixa, em segundos. Os padrões são escolhas do revisor, sem medição de tráfego real.
 * {@code cabecalhoIp} vazio usa o endereço da conexão; atrás de proxy ele é o do proxy e todos dividem um balde.
 * Só informe um cabeçalho (ex.: X-Forwarded-For) se a porta da aplicação for alcançável apenas pelo proxy: o último
 * valor da lista é o que o proxy confiável acrescentou.
 */
@ConfigurationProperties("atlas.limites")
public record LimitesProperties(
        @DefaultValue("5") int cadastroPorIp, @DefaultValue("3600") int cadastroJanela,
        @DefaultValue("20") int loginPorIp, @DefaultValue("5") int falhasPorEmailEIp,
        @DefaultValue("20") int falhasPorEmail, @DefaultValue("900") int loginJanela,
        @DefaultValue("60") int sessaoPorIp, @DefaultValue("600") int sessaoJanela,
        @DefaultValue("") String cabecalhoIp,
        @DefaultValue("30") int usoUploadPorConta, @DefaultValue("60") int usoProcessarPorConta,
        @DefaultValue("60") int usoFontePorConta, @DefaultValue("120") int usoDownloadPorConta,
        @DefaultValue("600") int usoJanela) {
    public LimitesProperties {
        for (int v : new int[] {cadastroPorIp, cadastroJanela, loginPorIp, falhasPorEmailEIp, falhasPorEmail, loginJanela, sessaoPorIp, sessaoJanela, usoUploadPorConta, usoProcessarPorConta, usoFontePorConta, usoDownloadPorConta, usoJanela})
            if (v < 1 || v > 1_000_000) throw new IllegalArgumentException("Limites devem ficar entre 1 e 1000000.");
    }
}
