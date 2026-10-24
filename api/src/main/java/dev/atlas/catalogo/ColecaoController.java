package dev.atlas.catalogo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Coleções compartilhadas pelo operador da instância, com o mesmo acesso de /api/**. */
@RestController
@RequestMapping("/api/colecoes")
public class ColecaoController {
    private final JdbcClient jdbc;
    private final CatalogoService catalogo;
    private final dev.atlas.acesso.UsuarioAtual usuario;
    public ColecaoController(JdbcClient jdbc, CatalogoService catalogo,dev.atlas.acesso.UsuarioAtual usuario) { this.jdbc = jdbc; this.catalogo = catalogo;this.usuario=usuario; }
    public record Nome(@NotBlank @Size(max=200) String nome) {}
    public record Colecao(UUID id, String nome) {}

    @PostMapping
    public Colecao criar(@Valid @RequestBody Nome pedido) {
        return jdbc.sql("INSERT INTO colecao(nome,dono_id) VALUES (:nome,:u) RETURNING id,nome")
                .param("nome", pedido.nome().strip()).param("u",usuario.id()).query((rs,row) -> new Colecao(rs.getObject("id",UUID.class),rs.getString("nome"))).single();
    }
    @GetMapping
    public List<Colecao> listar(@RequestParam(defaultValue="50") int limite, @RequestParam(defaultValue="0") int deslocamento) {
        CatalogoService.validarPagina(limite,deslocamento);
        return jdbc.sql("SELECT id,nome FROM colecao WHERE dono_id=:u ORDER BY criado_em,id LIMIT :n OFFSET :o")
                .param("n",limite).param("o",deslocamento).param("u",usuario.id()).query((rs,row) -> new Colecao(rs.getObject("id",UUID.class),rs.getString("nome"))).list();
    }
    @PutMapping("/{id}/obras/{obra}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void adicionar(@PathVariable UUID id, @PathVariable UUID obra) {
        exigir(id); catalogo.obter(obra);
        jdbc.sql("INSERT INTO colecao_obra(colecao_id,obra_id) VALUES (:c,:o) ON CONFLICT DO NOTHING").param("c",id).param("o",obra).update();
    }
    @DeleteMapping("/{id}/obras/{obra}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remover(@PathVariable UUID id, @PathVariable UUID obra) {
        exigir(id);
        jdbc.sql("DELETE FROM colecao_obra WHERE colecao_id=:c AND obra_id=:o").param("c",id).param("o",obra).update();
    }
    @GetMapping("/{id}/obras")
    public List<ObraCatalogada> obras(@PathVariable UUID id, @RequestParam(defaultValue="50") int limite,
            @RequestParam(defaultValue="0") int deslocamento) {
        exigir(id); CatalogoService.validarPagina(limite,deslocamento);
        return catalogo.obter(jdbc.sql("SELECT obra_id FROM colecao_obra WHERE colecao_id=:id ORDER BY adicionada_em,obra_id LIMIT :n OFFSET :o")
                .param("id",id).param("n",limite).param("o",deslocamento).query(UUID.class).list());
    }
    private void exigir(UUID id) {
        if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM colecao WHERE id=:id AND dono_id=:u)").param("id",id).param("u",usuario.id()).query(Boolean.class).single())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Coleção não encontrada.");
    }
}
