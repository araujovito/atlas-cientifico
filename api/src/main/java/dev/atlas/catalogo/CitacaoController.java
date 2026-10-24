package dev.atlas.catalogo;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/catalogo/obras/{obra}/citacoes")
public class CitacaoController {
    private final CitacaoService citacoes;
    public CitacaoController(CitacaoService citacoes){this.citacoes=citacoes;}
    @PostMapping public CitacaoService.Grafo atualizar(@PathVariable UUID obra){return citacoes.atualizar(obra);}
    @GetMapping public CitacaoService.Grafo obter(@PathVariable UUID obra,@RequestParam(defaultValue="20") int limite,@RequestParam(defaultValue="0") int deslocamento){return citacoes.obter(obra,limite,deslocamento);}
}
