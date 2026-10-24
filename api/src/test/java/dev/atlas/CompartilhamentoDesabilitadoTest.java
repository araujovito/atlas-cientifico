package dev.atlas;

import static dev.atlas.ContasTeste.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.atlas.documentos.UploadDocumentoService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "atlas.fila.ativa=false", "atlas.contas.pdf-original=SOMENTE_DONO",
		"atlas.documentos.diretorio=${java.io.tmpdir}/atlas-compartilhamento-so-dono" })
@AutoConfigureMockMvc
class CompartilhamentoDesabilitadoTest {
	@Autowired MockMvc mvc;
	@Autowired UploadDocumentoService upload;

	@Test
	void comPoliticaSomenteDonoNinguemCompartilhaMasODonoBaixaOProprio() throws Exception {
		var a = cadastrar(mvc, "so-dono-" + UUID.randomUUID() + "@example.com");
		var b = cadastrar(mvc, "so-dono-b-" + UUID.randomUUID() + "@example.com");
		UUID doc = upload.receber(new MockMultipartFile("arquivo", "x.pdf", "application/pdf",
				("%PDF-1.4 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)), true, null, a.id()).id();
		mvc.perform(com(put("/api/documentos/" + doc + "/compartilhamento"), a).contentType("application/json").content("{\"publico\":true}"))
				.andExpect(status().isConflict());
		mvc.perform(com(get("/api/documentos/" + doc + "/original"), a)).andExpect(status().isOk());
		mvc.perform(com(get("/api/publico/documentos/" + doc + "/original"), b)).andExpect(status().isNotFound());
		assertThat(mvc.perform(com(get("/api/publico/documentos"), b)).andReturn().getResponse().getContentAsString()).isEqualTo("[]");
	}
}
