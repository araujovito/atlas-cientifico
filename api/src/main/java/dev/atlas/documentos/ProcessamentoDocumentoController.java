package dev.atlas.documentos;

import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documentos")
public class ProcessamentoDocumentoController {

	private final ProcessadorDocumentoService processador;

	public ProcessamentoDocumentoController(ProcessadorDocumentoService processador) {
		this.processador = processador;
	}

	@PostMapping("/{documentoId}/extracoes")
	public ResultadoProcessamentoDocumento extrair(@PathVariable UUID documentoId) {
		return processador.processar(documentoId);
	}
}
