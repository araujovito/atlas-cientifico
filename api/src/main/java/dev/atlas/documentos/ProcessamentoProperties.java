package dev.atlas.documentos;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("atlas.processamento")
public record ProcessamentoProperties(@DefaultValue("20s") Duration tempoLimite,
        @DefaultValue("256") int memoriaMaximaMb, @DefaultValue("") String workerJar) {
    public ProcessamentoProperties {
        if (tempoLimite == null || tempoLimite.isNegative() || tempoLimite.isZero() || tempoLimite.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("Prazo do PDF deve ser positivo e de até um minuto.");
        if (memoriaMaximaMb < 64 || memoriaMaximaMb > 1024) throw new IllegalArgumentException("Memória do parser deve ficar entre 64 e 1024 MiB.");
    }
}
