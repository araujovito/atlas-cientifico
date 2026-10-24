package dev.atlas.documentos;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

	@GetMapping("/{documentoId}/extracoes/{execucaoId}/paginas")
	public List<PaginaPdf> listarPaginas(
			@PathVariable UUID documentoId,
			@PathVariable UUID execucaoId) {
		return processador.listarPaginas(documentoId, execucaoId);
	}

	@PostMapping("/{documentoId}/extracoes/{execucaoId}/medidas")
	public MedidaRegistrada registrarMedida(
			@PathVariable UUID documentoId,
			@PathVariable UUID execucaoId,
			@RequestBody MedidaCandidata medida) {
		return processador.registrarMedida(documentoId, execucaoId, medida);
	}
}
