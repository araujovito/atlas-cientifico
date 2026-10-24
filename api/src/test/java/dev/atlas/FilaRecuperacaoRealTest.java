package dev.atlas;

import static org.assertj.core.api.Assertions.*;
import dev.atlas.documentos.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties={"atlas.fila.ativa=false","atlas.documentos.diretorio=${java.io.tmpdir}/atlas-fila-real"})
class FilaRecuperacaoRealTest {
    @Autowired FilaExtracaoService fila;
    @Autowired FilaExtracaoWorker worker;
    @Autowired ProcessadorDocumentoService processador;
    @Autowired UploadDocumentoService upload;
    @Autowired JdbcClient jdbc;

    @Test void interrupcaoDepoisDePersistirAntesDeConfirmarNaoDuplicaExecucaoOuPagina() throws Exception {
        byte[] bytes;
        try(var doc=new PDDocument(); var out=new ByteArrayOutputStream()) {
            var page=new PDPage(); doc.addPage(page);
            try(var stream=new PDPageContentStream(doc,page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(72,720);stream.showText("Resultado 160 mAh/g "+UUID.randomUUID());stream.endText();
            }
            doc.save(out); bytes=out.toByteArray();
        }
        var recebido=upload.receber(new MockMultipartFile("arquivo","teste.pdf","application/pdf",bytes),true,null);
        var tarefa=fila.agendar(recebido.id()); fila.reservar().orElseThrow();
        var primeira=processador.processar(recebido.id()); // trabalho concluído, mas trabalhador interrompido antes do ack
        jdbc.sql("UPDATE tarefa_extracao SET reservado_ate=now()-interval '1 second' WHERE id=:id").param("id",tarefa.id()).update();
        assertThat(worker.executarUma()).isTrue();
        assertThat(fila.obter(tarefa.id()).execucaoId()).isEqualTo(primeira.execucaoId());
        assertThat(fila.obter(tarefa.id()).tentativas()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM execucao_processamento WHERE versao_documento_id=:id").param("id",recebido.id()).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM pagina_texto WHERE execucao_id=:id").param("id",primeira.execucaoId()).query(Long.class).single()).isEqualTo(1);
        java.nio.file.Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"),"atlas-fila-real",recebido.sha256()+".pdf"));
    }
}
