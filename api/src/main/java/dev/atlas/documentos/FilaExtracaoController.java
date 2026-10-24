package dev.atlas.documentos;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class FilaExtracaoController {
    private final FilaExtracaoService fila;
    public FilaExtracaoController(FilaExtracaoService fila) { this.fila=fila; }
    @PostMapping("/documentos/{documento}/processamentos")
    public ResponseEntity<FilaExtracaoService.Tarefa> agendar(@PathVariable UUID documento) {
        var tarefa=fila.agendar(documento);
        return ResponseEntity.accepted().location(URI.create("/api/processamentos/"+tarefa.id())).body(tarefa);
    }
    @GetMapping("/processamentos/{id}")
    public FilaExtracaoService.Tarefa obter(@PathVariable UUID id) { return fila.obter(id); }
    @GetMapping("/processamentos/{id}/eventos")
    public List<FilaExtracaoService.Evento> eventos(@PathVariable UUID id) { return fila.eventos(id); }
    @PostMapping("/processamentos/{id}/retentar")
    public FilaExtracaoService.Tarefa retentar(@PathVariable UUID id) { return fila.retentar(id); }
    @GetMapping("/processamentos")
    public List<FilaExtracaoService.Tarefa> listar(@RequestParam(defaultValue="FALHOU") String estado,@RequestParam(defaultValue="50") int limite) {
        return fila.listar(estado,limite);
    }
}
