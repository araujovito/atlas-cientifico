package dev.atlas;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.atlas.catalogo.CatalogoController;
import dev.atlas.catalogo.ObraEncontrada;
import dev.atlas.fontes.openalex.OpenAlexClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CatalogoController.class)
class CatalogoControllerTest {
	@Autowired MockMvc mvc;
	@MockitoBean OpenAlexClient openAlex;

	@Test
	void consultaDoiRetornaMetadadosComFonteEIdentificadores() throws Exception {
		when(openAlex.buscarPorDoi("10.1234/artigo")).thenReturn(new ObraEncontrada(
				"OPENALEX", "https://openalex.org/W123", "10.1234/artigo", "Estudo de exemplo", 2022, List.of("Uma autora")));
		mvc.perform(get("/api/catalogo/obras/por-doi").param("doi", "10.1234/artigo"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fonte").value("OPENALEX"))
				.andExpect(jsonPath("$.openAlexId").value("https://openalex.org/W123"))
				.andExpect(jsonPath("$.doi").value("10.1234/artigo"))
				.andExpect(jsonPath("$.titulo").value("Estudo de exemplo"))
				.andExpect(jsonPath("$.ano").value(2022))
				.andExpect(jsonPath("$.autores[0]").value("Uma autora"));
	}

	@Test
	void exigeParametroDoiAntesDeConsultarFonte() throws Exception {
		mvc.perform(get("/api/catalogo/obras/por-doi")).andExpect(status().isBadRequest());
		verifyNoInteractions(openAlex);
	}
}
