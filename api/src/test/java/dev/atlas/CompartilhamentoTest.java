package dev.atlas;

import static dev.atlas.ContasTeste.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.atlas.documentos.UploadDocumentoService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "atlas.fila.ativa=false", "atlas.documentos.diretorio=${java.io.tmpdir}/atlas-compartilhamento-teste" })
@AutoConfigureMockMvc
class CompartilhamentoTest {
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired UploadDocumentoService upload;
	Cliente a, b;
	byte[] bytes;
	UUID doc;

	@BeforeEach
	void preparar() throws Exception {
		a = cadastrar(mvc, "dono-" + UUID.randomUUID() + "@example.com");
		b = cadastrar(mvc, "outro-" + UUID.randomUUID() + "@example.com");
		bytes = ("%PDF-1.4 original " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
		doc = upload.receber(new MockMultipartFile("arquivo", "x.pdf", "application/pdf", bytes), true, "Meu artigo", a.id()).id();
	}

	private static String corpo(boolean publico) { return "{\"publico\":" + publico + "}"; }

	@Test
	void donoBaixaOOriginalComCabecalhosSegurosEOutraContaRecebe404() throws Exception {
		var resposta = mvc.perform(com(get("/api/documentos/" + doc + "/original"), a)).andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/pdf"))
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"" + doc + ".pdf\""))
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Content-Security-Policy", "sandbox")).andReturn().getResponse();
		assertThat(resposta.getContentAsByteArray()).isEqualTo(bytes);
		mvc.perform(com(get("/api/documentos/" + doc + "/original"), b)).andExpect(status().isNotFound());
		mvc.perform(com(get("/api/documentos/" + doc + "/compartilhamento"), b)).andExpect(status().isNotFound());
		mvc.perform(com(get("/api/documentos/" + doc + "/compartilhamento"), a)).andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.compartilhamento").value("PRIVADO"));
		mvc.perform(com(put("/api/documentos/" + doc + "/compartilhamento"), b).contentType("application/json").content(corpo(true)))
				.andExpect(status().isNotFound());
	}

	@Test
	void documentoSoFicaAcessivelAOutrasContasDepoisDoDonoCompartilharEVoltaAFicarPrivado() throws Exception {
		String publico = "/api/publico/documentos/" + doc + "/original";
		mvc.perform(com(get(publico), b)).andExpect(status().isNotFound());
		mvc.perform(com(put("/api/documentos/" + doc + "/compartilhamento"), a).contentType("application/json").content(corpo(true)))
				.andExpect(status().isOk());
		var baixado = mvc.perform(com(get(publico), b)).andExpect(status().isOk())
				.andExpect(header().string("Content-Security-Policy", "sandbox")).andReturn().getResponse();
		assertThat(baixado.getContentAsByteArray()).isEqualTo(bytes);
		String lista = mvc.perform(com(get("/api/publico/documentos"), b)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(lista).contains(doc.toString(), "Meu artigo").doesNotContain(a.email(), a.id().toString());
		mvc.perform(com(put("/api/documentos/" + doc + "/compartilhamento"), a).contentType("application/json").content(corpo(false)))
				.andExpect(status().isOk());
		mvc.perform(com(get(publico), b)).andExpect(status().isNotFound());
		assertThat(mvc.perform(com(get("/api/publico/documentos"), b)).andReturn().getResponse().getContentAsString()).doesNotContain(doc.toString());
	}

	@Test
	void documentoBloqueadoPeloOperadorSaiDaListaNaoVoltaPelaApiEODonoAindaBaixaOProprio() throws Exception {
		mvc.perform(com(put("/api/documentos/" + doc + "/compartilhamento"), a).contentType("application/json").content(corpo(true))).andExpect(status().isOk());
		jdbc.sql("UPDATE versao_documento SET compartilhamento='BLOQUEADO' WHERE id=:id").param("id", doc).update();
		mvc.perform(com(get("/api/publico/documentos/" + doc + "/original"), b)).andExpect(status().isNotFound());
		assertThat(mvc.perform(com(get("/api/publico/documentos"), b)).andReturn().getResponse().getContentAsString()).doesNotContain(doc.toString());
		mvc.perform(com(put("/api/documentos/" + doc + "/compartilhamento"), a).contentType("application/json").content(corpo(true))).andExpect(status().isConflict());
		mvc.perform(com(put("/api/documentos/" + doc + "/compartilhamento"), a).contentType("application/json").content(corpo(false))).andExpect(status().isConflict());
		mvc.perform(com(get("/api/documentos/" + doc + "/original"), a)).andExpect(status().isOk());
	}

	@Test
	void arquivoAusenteDoDiscoDevolve404EListagemValidaLimites() throws Exception {
		mvc.perform(com(put("/api/documentos/" + doc + "/compartilhamento"), a).contentType("application/json").content(corpo(true))).andExpect(status().isOk());
		jdbc.sql("UPDATE versao_documento SET chave_armazenamento='inexistente.pdf' WHERE id=:id").param("id", doc).update();
		mvc.perform(com(get("/api/publico/documentos/" + doc + "/original"), b)).andExpect(status().isNotFound());
		// Não deixa um documento público sem arquivo na lista para os outros testes que compartilham o banco.
		jdbc.sql("UPDATE versao_documento SET compartilhamento='PRIVADO' WHERE id=:id").param("id", doc).update();
		mvc.perform(com(get("/api/publico/documentos").param("limite", "0"), b)).andExpect(status().isBadRequest());
		mvc.perform(com(get("/api/publico/documentos").param("limite", "101"), b)).andExpect(status().isBadRequest());
		mvc.perform(get("/api/publico/documentos")).andExpect(status().isUnauthorized());
	}
}
