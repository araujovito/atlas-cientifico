package dev.atlas.catalogo;

import dev.atlas.fontes.openalex.OpenAlexClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/catalogo/obras")
public class CatalogoController {
	private final OpenAlexClient openAlex;
	private final CatalogoService catalogo;

	public CatalogoController(OpenAlexClient openAlex, CatalogoService catalogo) {
		this.openAlex = openAlex;
		this.catalogo = catalogo;
	}

	@GetMapping("/por-doi")
	public ObraEncontrada buscarPorDoi(@RequestParam String doi) {
		return openAlex.buscarPorDoi(doi);
	}

	public record Importacao(String doi) {}
	@PostMapping
	public ObraCatalogada importar(@RequestBody Importacao pedido) { return catalogo.importar(pedido.doi()); }
	@GetMapping
	public List<ObraCatalogada> listar(@RequestParam(defaultValue="50") int limite, @RequestParam(defaultValue="0") int deslocamento) {
		return catalogo.listar(limite,deslocamento);
	}
	@GetMapping("/{id}")
	public ObraCatalogada obter(@PathVariable UUID id) { return catalogo.obter(id); }
	@PutMapping("/{id}/documentos/{documento}")
	@ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
	public void associar(@PathVariable UUID id, @PathVariable UUID documento) { catalogo.associarDocumento(documento,id); }
}
