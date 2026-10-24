package dev.atlas.documentos;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/documentos")
public class UploadDocumentoController {

	private final UploadDocumentoService service;
	private final dev.atlas.acesso.UsuarioAtual usuario;

	public UploadDocumentoController(UploadDocumentoService service,dev.atlas.acesso.UsuarioAtual usuario) {
		this.service = service;
		this.usuario = usuario;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public DocumentoUpload upload(
			@RequestParam("arquivo") MultipartFile arquivo,
			@RequestParam(value = "autorizacaoConfirmada", required = false) Boolean autorizacaoConfirmada,
			@RequestParam(value = "titulo", required = false) String titulo) {
		if (autorizacaoConfirmada == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirme que tem autorização para processar este PDF.");
		}
		return service.receber(arquivo, autorizacaoConfirmada, titulo,usuario.id());
	}
}
