package dev.atlas;

import dev.atlas.acesso.PoliticaContas;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PoliticaContasTest {
    @Test void modosDeCadastroSaoConfiguraveisSemEscolhaImplicitaDeConvite(){
        var aberto=new PoliticaContas(PoliticaContas.Cadastro.ABERTO,Set.of(),PoliticaContas.PdfOriginal.DESABILITADO);
        assertThat(aberto.permite("pessoa@example.com")).isTrue();
        var fechado=new PoliticaContas(PoliticaContas.Cadastro.FECHADO,Set.of("pessoa@example.com"),PoliticaContas.PdfOriginal.DESABILITADO);
        assertThat(fechado.permite("pessoa@example.com")).isFalse();
    }
    @Test void listaNormalizaConfiguracaoENaoLiberaEmailsForaDaLista(){
        var lista=new PoliticaContas(PoliticaContas.Cadastro.LISTA,Set.of(" Pessoa@EXAMPLE.com ",""),PoliticaContas.PdfOriginal.DESABILITADO);
        assertThat(lista.permite("pessoa@example.com")).isTrue();
        assertThat(lista.permite("outra@example.com")).isFalse();
        assertThat(lista.emailsPermitidos()).containsExactly("pessoa@example.com");
    }
}
