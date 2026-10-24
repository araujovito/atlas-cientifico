package dev.atlas.extracao;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/medidas")
public class RevisaoController {
    private final RevisaoService revisoes;
    public RevisaoController(RevisaoService revisoes) { this.revisoes=revisoes; }
    @GetMapping("/{id}") public RevisaoService.Atual obter(@PathVariable UUID id) { return revisoes.obter(id); }
    @GetMapping("/{id}/revisoes") public List<RevisaoService.Revisao> historico(@PathVariable UUID id) { return revisoes.historico(id); }
    @PostMapping("/{id}/revisoes") public RevisaoService.Atual revisar(@PathVariable UUID id,@Valid @RequestBody RevisaoService.Pedido pedido) { return revisoes.revisar(id,pedido); }
}
