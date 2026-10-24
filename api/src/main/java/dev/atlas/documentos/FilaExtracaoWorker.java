package dev.atlas.documentos;

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
    public FilaExtracaoWorker(FilaExtracaoService fila, ProcessadorDocumentoService processador,
            @Value("${atlas.fila.ativa:true}") boolean ativa) { this.fila=fila; this.processador=processador; this.ativa=ativa; }

    @Scheduled(fixedDelayString="${atlas.fila.intervalo-ms:1000}",initialDelayString="${atlas.fila.inicio-ms:1000}")
    public void tick() { if(ativa) executarUma(); }

    public boolean executarUma() {
        var proxima=fila.reservar();
        if(proxima.isEmpty()) return false;
        var reserva=proxima.get();
        try { fila.concluir(reserva,processador.processar(reserva.tarefa().documentoId())); }
        catch(ResponseStatusException e) {
            int status=e.getStatusCode().value();
            fila.falhar(reserva,e.getReason()==null?"Falha no processamento.":e.getReason(),status>=400 && status<500 && status!=429);
        } catch(RuntimeException e) {
            log.warn("Falha temporária na tarefa {}",reserva.tarefa().id(),e);
            fila.falhar(reserva,"Falha temporária no processamento.",false);
        }
        return true;
    }
}
