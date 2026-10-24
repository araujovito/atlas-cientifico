package dev.atlas;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties={"atlas.fila.ativa=false","atlas.documentos.diretorio=${java.io.tmpdir}/atlas-telemetria","management.tracing.export.otlp.enabled=true",
        "management.tracing.sampling.probability=1","management.opentelemetry.tracing.export.schedule-delay=100ms"})
class TelemetriaTest {
    static final CompletableFuture<String> corpo=new CompletableFuture<>();
    static final HttpServer collector;
    static {
        try {
            collector=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            collector.createContext("/v1/traces",exchange -> {
                String bytes=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.ISO_8859_1);
                exchange.getResponseHeaders().set("Content-Type","application/x-protobuf");
                exchange.sendResponseHeaders(200,-1); exchange.close();
                if(bytes.contains("atlas.pdf.execucao")) corpo.complete(bytes);
            }); collector.start();
        } catch(Exception e) {throw new ExceptionInInitializerError(e);}
    }
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry properties) {
        properties.add("management.opentelemetry.tracing.export.otlp.endpoint",()->"http://127.0.0.1:"+collector.getAddress().getPort()+"/v1/traces");
    }
    @Autowired dev.atlas.documentos.UploadDocumentoService upload;
    @Autowired dev.atlas.documentos.FilaExtracaoService fila;
    @Autowired dev.atlas.documentos.FilaExtracaoWorker worker;
    @AfterAll static void stop(){collector.stop(1);}

    @Test void spanDoTrabalhadorRealChegaSoAoColetorLocalSemConteudoDoDocumento() throws Exception {
        String segredo="SEGREDO-"+java.util.UUID.randomUUID();
        byte[] bytes;
        try(var doc=new org.apache.pdfbox.pdmodel.PDDocument(); var out=new java.io.ByteArrayOutputStream()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage(); doc.addPage(page);
            try(var stream=new org.apache.pdfbox.pdmodel.PDPageContentStream(doc,page)) {
                stream.beginText(); stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(72,720); stream.showText("Capacidade 160 mAh/g "+segredo); stream.endText();
            }
            doc.save(out); bytes=out.toByteArray();
        }
        String titulo="TITULO-"+java.util.UUID.randomUUID();
        var recebido=upload.receber(new org.springframework.mock.web.MockMultipartFile("arquivo","t.pdf","application/pdf",bytes),true,titulo,ContasTeste.LEGADO);
        fila.agendar(recebido.id());
        // Caminho real: reserva, extração em subprocesso, conclusão e o span que o trabalhador abre.
        assertThat(worker.executarUma()).isTrue();
        assertThat(corpo.get(15,TimeUnit.SECONDS)).contains("atlas.pdf.execucao","resultado","concluido")
                .doesNotContain(segredo,titulo,recebido.sha256(),recebido.id().toString(),"X-Atlas-Chave","trecho","valor_original");
        java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"atlas-telemetria",recebido.sha256()+".pdf"));
    }
}
