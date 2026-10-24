package dev.atlas.dominio.baterias;

import dev.atlas.extracao.RevisaoService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/comparacoes")
public class ComparacaoController {
    private final RevisaoService revisoes;
    private final dev.atlas.acesso.AutorizacaoRecursos acesso;
    private final dev.atlas.acesso.UsuarioAtual usuario;
    public ComparacaoController(RevisaoService revisoes,dev.atlas.acesso.AutorizacaoRecursos acesso,dev.atlas.acesso.UsuarioAtual usuario) { this.revisoes=revisoes;this.acesso=acesso;this.usuario=usuario; }
    public record Linha(RevisaoService.Atual registro,NormalizadorUnidades.Valor normalizado) {}
    public record Comparacao(boolean podeAlinharNumericamente,List<String> motivos,List<Linha> linhas) {}
    private static final Set<String> CONDICOES=Set.of("taxa_corrente","carga_catodo","eletrolito","temperatura","janela_tensao");

    @GetMapping
    public Comparacao comparar(@RequestParam List<UUID> medidas) {
        if(medidas.size()<2 || medidas.size()>20 || medidas.stream().distinct().count()!=medidas.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Escolha entre 2 e 20 medidas distintas.");
        medidas.forEach(id->acesso.exigirMedida(id,usuario.id()));
        var linhas=medidas.stream().map(revisoes::obter).map(r -> new Linha(r,
                NormalizadorUnidades.normalizar(r.atual().medida().valorOriginal(),r.atual().medida().unidadeOriginal()).orElse(null))).toList();
        var motivos=new ArrayList<String>(); var primeira=linhas.getFirst(); var base=primeira.registro().atual().medida();
        for(var linha:linhas) {
            var m=linha.registro().atual().medida();
            if(!"REPORTADO".equals(m.estado())) motivos.add("Há medida ausente, inferida ou pendente de revisão.");
            if(!base.metrica().equalsIgnoreCase(m.metrica()) || !base.objeto().equalsIgnoreCase(m.objeto())) motivos.add("Métrica ou objeto diferentes.");
            if(linha.normalizado()==null || primeira.normalizado()==null || !linha.normalizado().unidade().equals(primeira.normalizado().unidade())) motivos.add("Valor ou unidade não permitem conversão explícita.");
            if(!m.condicoes().keySet().containsAll(CONDICOES) || CONDICOES.stream().anyMatch(k -> m.condicoes().get(k)==null || m.condicoes().get(k).toString().isBlank()))
                motivos.add("Condições incompletas: taxa_corrente, carga_catodo, eletrolito, temperatura e janela_tensao são obrigatórias para alinhamento.");
            if(!base.condicoes().equals(m.condicoes())) motivos.add("Condições experimentais declaradas diferentes.");
        }
        return new Comparacao(motivos.isEmpty(),motivos.stream().distinct().toList(),linhas);
    }
}
