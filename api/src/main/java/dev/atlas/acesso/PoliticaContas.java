package dev.atlas.acesso;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("atlas.contas")
public record PoliticaContas(@DefaultValue("ABERTO") Cadastro cadastro,
        @DefaultValue("") Set<String> emailsPermitidos,
        @DefaultValue("COMPARTILHADO") PdfOriginal pdfOriginal) {
    public PoliticaContas {
        java.util.Objects.requireNonNull(cadastro);
        java.util.Objects.requireNonNull(pdfOriginal);
        emailsPermitidos=emailsPermitidos==null?Set.of():emailsPermitidos.stream().map(String::strip)
                .map(e->e.toLowerCase(java.util.Locale.ROOT)).filter(e->!e.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public boolean permite(String email){return cadastro==Cadastro.ABERTO || cadastro==Cadastro.LISTA && emailsPermitidos.contains(email.toLowerCase(java.util.Locale.ROOT));}
    public enum Cadastro { ABERTO, LISTA, FECHADO }
    /** DESABILITADO: ninguém baixa o original. SOMENTE_DONO: só quem enviou. COMPARTILHADO: o dono pode marcar documentos como públicos. */
    public enum PdfOriginal { DESABILITADO, SOMENTE_DONO, COMPARTILHADO }
}
