package dev.atlas.acesso;

import dev.atlas.documentos.UploadDocumentoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conta")
public class UsoController {
	private final UploadDocumentoService uploads;
	private final UsuarioAtual usuario;
	public UsoController(UploadDocumentoService uploads, UsuarioAtual usuario) { this.uploads = uploads; this.usuario = usuario; }

	/** Uso e limites da própria conta; nunca de outra. */
	@GetMapping("/uso")
	public UploadDocumentoService.Uso uso() { return uploads.uso(usuario.id()); }
}
