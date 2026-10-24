package dev.atlas;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
@SpringBootTest(properties={"atlas.fila.ativa=false","management.tracing.export.otlp.enabled=true",
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
    @Autowired ObservationRegistry observations;
    @AfterAll static void stop(){collector.stop(1);}
    @Test void exportaSpanOtlpSomenteAoColetorLocalConfigurado() throws Exception {
        Observation.createNotStarted("atlas.pdf.execucao",observations)
                .lowCardinalityKeyValue("resultado","concluido").observe(()->{});
        assertThat(corpo.get(15,TimeUnit.SECONDS)).contains("atlas.pdf.execucao","resultado","concluido")
                .doesNotContain("X-Atlas-Chave","trecho","valor_original");
    }
}
