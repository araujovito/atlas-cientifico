package dev.atlas;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import dev.atlas.documentos.*;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties={"atlas.fila.ativa=false", "atlas.documentos.diretorio=${java.io.tmpdir}/atlas-interrupcao-test"})
class InterrupcaoProcessamentoTest {
    @Autowired ProcessadorDocumentoService processador;
    @Autowired JdbcClient jdbc;
    @Autowired UploadProperties properties;
    @MockitoBean ExtratorPdfIsolado extrator;

    @Test void interrupcaoNaoTransformaDocumentoEmFalhaPermanente() throws Exception {
        String hash=UUID.randomUUID().toString().replace("-","").repeat(2);
        Path diretorio=properties.diretorio(); Files.createDirectories(diretorio);
        Path arquivo=diretorio.resolve(hash+".pdf"); Files.writeString(arquivo,"%PDF-sintetico");
        try {
            UUID id=jdbc.sql("""
                    INSERT INTO versao_documento(dono_id, origem,sha256,chave_armazenamento,tamanho_bytes,autorizacao_declarada,autorizacao_confirmada_em)
                    VALUES ('00000000-0000-0000-0000-000000000001', 'UPLOAD',:hash,:chave,14,true,now()) RETURNING id
                    """).param("hash",hash).param("chave",hash+".pdf").query(UUID.class).single();
            when(extrator.extrair(any(),anyInt())).thenThrow(new InterruptedIOException("Interrompido"));
            assertThatThrownBy(() -> processador.processar(id)).isInstanceOfSatisfying(ResponseStatusException.class,
                    e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
            assertThat(jdbc.sql("SELECT count(*) FROM execucao_processamento WHERE versao_documento_id=:id")
                    .param("id",id).query(Long.class).single()).isZero();
        } finally { Files.deleteIfExists(arquivo); }
    }
}
