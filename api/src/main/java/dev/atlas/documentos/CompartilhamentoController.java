package dev.atlas.documentos;

import dev.atlas.acesso.PoliticaContas;
import dev.atlas.acesso.UsuarioAtual;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Devolução do PDF original. O dono sempre pode baixar o seu (salvo política DESABILITADO); outras contas só baixam o que o
 * dono marcou como PUBLICO e o operador não bloqueou. Qualquer outra situação responde 404, sem distinguir o motivo.
 */
@RestController
@RequestMapping("/api")
public class CompartilhamentoController {
	public record Pedido(boolean publico) {}
	public record Estado(String compartilhamento) {}
	public record Publico(UUID id, String titulo, long tamanhoBytes, String sha256, Instant coletadoEm) {}

	private final JdbcClient jdbc;
	private final UsuarioAtual usuario;
	private final PoliticaContas politica;
	private final UploadProperties upload;

	public CompartilhamentoController(JdbcClient jdbc, UsuarioAtual usuario, PoliticaContas politica, UploadProperties upload) {
		this.jdbc = jdbc; this.usuario = usuario; this.politica = politica; this.upload = upload;
	}

	@PutMapping("/documentos/{documentoId}/compartilhamento")
	public Estado definir(@PathVariable UUID documentoId, @RequestBody Pedido pedido) {
		if (pedido.publico() && politica.pdfOriginal() != PoliticaContas.PdfOriginal.COMPARTILHADO)
			throw new ResponseStatusException(HttpStatus.CONFLICT, "O compartilhamento está desabilitado nesta instância.");
		String novo = pedido.publico() ? "PUBLICO" : "PRIVADO";
		var atualizado = jdbc.sql("""
				UPDATE versao_documento SET compartilhamento = :novo
				WHERE id = :id AND dono_id = :dono AND compartilhamento <> 'BLOQUEADO' RETURNING compartilhamento
				""").param("novo", novo).param("id", documentoId).param("dono", usuario.id()).query(String.class).optional();
		if (atualizado.isPresent()) return new Estado(atualizado.get());
		// O interceptor já garantiu que o documento é do usuário: se não atualizou, é porque o operador o bloqueou.
		throw new ResponseStatusException(HttpStatus.CONFLICT, "Este documento foi bloqueado pelo operador e não pode ser compartilhado.");
	}

	@GetMapping("/documentos/{documentoId}/original")
	public ResponseEntity<Resource> originalDoDono(@PathVariable UUID documentoId) {
		if (politica.pdfOriginal() == PoliticaContas.PdfOriginal.DESABILITADO) throw naoEncontrado();
		return entregar(jdbc.sql("SELECT chave_armazenamento FROM versao_documento WHERE id = :id AND dono_id = :dono")
				.param("id", documentoId).param("dono", usuario.id()).query(String.class).optional().orElseThrow(CompartilhamentoController::naoEncontrado), documentoId);
	}

	@GetMapping("/publico/documentos")
	public List<Publico> listar(@RequestParam(defaultValue = "50") int limite, @RequestParam(defaultValue = "0") int deslocamento) {
		if (limite < 1 || limite > 100 || deslocamento < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limite entre 1 e 100; deslocamento não negativo.");
		if (politica.pdfOriginal() != PoliticaContas.PdfOriginal.COMPARTILHADO) return List.of();
		return jdbc.sql("""
				SELECT id, titulo_declarado, tamanho_bytes, sha256, coletado_em FROM versao_documento
				WHERE compartilhamento = 'PUBLICO' ORDER BY coletado_em DESC, id LIMIT :n OFFSET :o
				""").param("n", limite).param("o", deslocamento)
				.query((rs, row) -> new Publico(rs.getObject("id", UUID.class), rs.getString("titulo_declarado"), rs.getLong("tamanho_bytes"),
						rs.getString("sha256"), rs.getTimestamp("coletado_em").toInstant())).list();
	}

	@GetMapping("/publico/documentos/{id}/original")
	public ResponseEntity<Resource> originalPublico(@PathVariable UUID id) {
		if (politica.pdfOriginal() != PoliticaContas.PdfOriginal.COMPARTILHADO) throw naoEncontrado();
		return entregar(jdbc.sql("SELECT chave_armazenamento FROM versao_documento WHERE id = :id AND compartilhamento = 'PUBLICO'")
				.param("id", id).query(String.class).optional().orElseThrow(CompartilhamentoController::naoEncontrado), id);
	}

	private ResponseEntity<Resource> entregar(String chave, UUID id) {
		Path diretorio = upload.diretorio().toAbsolutePath().normalize();
		Path arquivo = diretorio.resolve(chave).normalize();
		if (!arquivo.startsWith(diretorio) || !java.nio.file.Files.isRegularFile(arquivo)) throw naoEncontrado();
		// Conteúdo de terceiros: sempre anexo (nunca renderizado na origem do site), sem sniffing e em sandbox.
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".pdf\"")
				.header("X-Content-Type-Options", "nosniff").header("Content-Security-Policy", "sandbox")
				.header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(new FileSystemResource(arquivo));
	}

	private static ResponseStatusException naoEncontrado() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso não encontrado."); }
}
