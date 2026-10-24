package dev.atlas;

import static dev.atlas.ContasTeste.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.atlas.acesso.LimiteDeUso;
import dev.atlas.documentos.UploadDocumentoService;
import dev.atlas.fontes.openalex.OpenAlexClient;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "atlas.fila.ativa=false", "atlas.limites.uso-upload-por-conta=3", "atlas.limites.uso-download-por-conta=2",
		"atlas.limites.uso-fonte-por-conta=2", "atlas.documentos.diretorio=${java.io.tmpdir}/atlas-uso-teste" })
@AutoConfigureMockMvc
class LimiteDeUsoTest {
	@Autowired MockMvc mvc;
	@Autowired UploadDocumentoService upload;
	@Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping rotas;
	@MockitoBean OpenAlexClient fonte;

	private static MockMultipartFile pdf() {
		return new MockMultipartFile("arquivo", "a.pdf", "application/pdf", ("%PDF-1.4 " + UUID.randomUUID()).getBytes());
	}

	@Test
	void uploadsAcimaDoLimiteDaContaRecebem429MasOutraContaNaoEAfetada() throws Exception {
		var a = cadastrar(mvc, "uso-a-" + UUID.randomUUID() + "@example.com");
		var b = cadastrar(mvc, "uso-b-" + UUID.randomUUID() + "@example.com");
		for (int i = 0; i < 3; i++)
			mvc.perform(com(multipart("/api/documentos").file(pdf()).param("autorizacaoConfirmada", "true"), a)).andExpect(status().isOk());
		mvc.perform(com(multipart("/api/documentos").file(pdf()).param("autorizacaoConfirmada", "true"), a))
				.andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
		mvc.perform(com(multipart("/api/documentos").file(pdf()).param("autorizacaoConfirmada", "true"), b)).andExpect(status().isOk());
	}

	@Test
	void consultasAFonteExternaTemLimitePorConta() throws Exception {
		when(fonte.buscarAutores(anyString())).thenReturn(List.of());
		var a = cadastrar(mvc, "fonte-" + UUID.randomUUID() + "@example.com");
		for (int i = 0; i < 2; i++) mvc.perform(com(get("/api/catalogo/obras/autores").param("nome", "Silva"), a)).andExpect(status().isOk());
		mvc.perform(com(get("/api/catalogo/obras/autores").param("nome", "Silva"), a)).andExpect(status().isTooManyRequests());
	}

	@Test
	void tentativaDeBaixarRecursoAlheioDa404ENaoGastaOLimiteDeDownloadDeQuemPerguntou() throws Exception {
		var a = cadastrar(mvc, "dl-a-" + UUID.randomUUID() + "@example.com");
		var b = cadastrar(mvc, "dl-b-" + UUID.randomUUID() + "@example.com");
		UUID docA = upload.receber(pdf(), true, null, a.id()).id(), docB = upload.receber(pdf(), true, null, b.id()).id();
		for (int i = 0; i < 5; i++) mvc.perform(com(get("/api/documentos/" + docA + "/original"), b)).andExpect(status().isNotFound());
		mvc.perform(com(get("/api/documentos/" + docB + "/original"), b)).andExpect(status().isOk());
		mvc.perform(com(get("/api/documentos/" + docB + "/original"), b)).andExpect(status().isOk());
		mvc.perform(com(get("/api/documentos/" + docB + "/original"), b)).andExpect(status().isTooManyRequests());
	}

	@Test
	void todaRotaLimitadaExisteDeFatoNoMapeamento() {
		var existentes = new HashSet<String>();
		rotas.getHandlerMethods().forEach((mapping, handler) -> mapping.getPatternValues().forEach(p ->
				mapping.getMethodsCondition().getMethods().forEach(m -> existentes.add(m.name() + " " + p))));
		assertThat(existentes).containsAll(LimiteDeUso.rotasLimitadas());
	}
}
