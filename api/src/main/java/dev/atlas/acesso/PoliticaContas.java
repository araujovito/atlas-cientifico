package dev.atlas.acesso;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("atlas.contas")
public record PoliticaContas(@DefaultValue("ABERTO") Cadastro cadastro,
        @DefaultValue("") Set<String> emailsPermitidos,
        @DefaultValue("DESABILITADO") PdfOriginal pdfOriginal) {
    public enum Cadastro { ABERTO, LISTA, FECHADO }
    // Compartilhamento entre pessoas não pertence à Fase A. Não existe rota de original.
    public enum PdfOriginal { DESABILITADO, SOMENTE_DONO }
}
