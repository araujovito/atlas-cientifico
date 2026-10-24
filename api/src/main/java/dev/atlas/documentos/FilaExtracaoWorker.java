package dev.atlas.documentos;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class FilaExtracaoWorker {
    private static final Logger log=LoggerFactory.getLogger(FilaExtracaoWorker.class);
    private final FilaExtracaoService fila;
    private final ProcessadorDocumentoService processador;
    private final boolean ativa;
    private final MeterRegistry metrics;
    private final ObservationRegistry observations;
    public FilaExtracaoWorker(FilaExtracaoService fila, ProcessadorDocumentoService processador,
            @Value("${atlas.fila.ativa:true}") boolean ativa, MeterRegistry metrics, ObservationRegistry observations) {
        this.fila=fila; this.processador=processador; this.ativa=ativa; this.metrics=metrics; this.observations=observations;
    }

    @Scheduled(fixedDelayString="${atlas.fila.intervalo-ms:1000}",initialDelayString="${atlas.fila.inicio-ms:1000}")
    public void tick() { if(ativa) executarUma(); }

    public boolean executarUma() {
        var proxima=fila.reservar();
        if(proxima.isEmpty()) return false;
        var reserva=proxima.get();
        var tempo=Timer.start(metrics);
        var observation=Observation.start("atlas.pdf.execucao",observations);
        String resultado="erro_temporario";
        try {
            resultado=fila.concluir(reserva,processador.processar(reserva.tarefa().documentoId())) ? "concluido" : "reserva_perdida";
        }
        catch(ResponseStatusException e) {
            int status=e.getStatusCode().value();
            boolean permanente=status>=400 && status<500 && status!=429;
            resultado=permanente ? "erro_permanente" : "erro_temporario";
            fila.falhar(reserva,e.getReason()==null?"Falha no processamento.":e.getReason(),permanente);
        } catch(RuntimeException e) {
            log.warn("Falha temporária na tarefa {}",reserva.tarefa().id(),e);
            fila.falhar(reserva,"Falha temporária no processamento.",false);
        } finally {
            // Sem IDs, textos ou DOIs nos rótulos: cardinalidade limitada por construção.
            tempo.stop(Timer.builder("atlas.pdf.tentativa").tag("resultado",resultado).register(metrics));
            observation.lowCardinalityKeyValue("resultado",resultado).stop();
        }
        return true;
    }
}
