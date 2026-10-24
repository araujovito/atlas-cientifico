package dev.atlas.catalogo;

import dev.atlas.fontes.openalex.OpenAlexClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/catalogo/obras")
public class CatalogoController {
	private final OpenAlexClient openAlex;

	public CatalogoController(OpenAlexClient openAlex) {
		this.openAlex = openAlex;
	}

	@GetMapping("/por-doi")
	public ObraEncontrada buscarPorDoi(@RequestParam String doi) {
		return openAlex.buscarPorDoi(doi);
	}
}
