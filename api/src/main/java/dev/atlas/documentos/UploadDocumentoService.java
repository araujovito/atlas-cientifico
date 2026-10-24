package dev.atlas.documentos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UploadDocumentoService {

	private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
	private final JdbcClient jdbc;
	private final UploadProperties properties;

	public UploadDocumentoService(JdbcClient jdbc, UploadProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	@Transactional
	public DocumentoUpload receber(MultipartFile arquivo, boolean autorizacaoConfirmada, String titulo) {
		if (!autorizacaoConfirmada) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirme que tem autorização para processar este PDF.");
		}
		if (arquivo == null || arquivo.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Envie um arquivo PDF não vazio.");
		}
		if (arquivo.getSize() > properties.tamanhoMaximoBytes()) {
			throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "O PDF excede o limite permitido.");
		}
		validarPdf(arquivo);

		String tituloSeguro = titulo == null || titulo.isBlank() ? null : titulo.trim();
		String hash = sha256(arquivo);
		var existente = buscarPorHash(hash);
		if (existente.isPresent()) {
			return existente.get();
		}

		Path diretorio = properties.diretorio().toAbsolutePath().normalize();
		Path destino = diretorio.resolve(hash + ".pdf").normalize();
		if (!destino.startsWith(diretorio)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destino de armazenamento inválido.");
		}
		try {
			Files.createDirectories(diretorio);
			Path temporario = Files.createTempFile(diretorio, ".atlas-", ".upload");
			try (InputStream input = arquivo.getInputStream()) {
				Files.copy(input, temporario, StandardCopyOption.REPLACE_EXISTING);
			}
			try {
				Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException e) {
				Files.deleteIfExists(temporario);
				if (!Files.exists(destino)) throw e;
			}
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Não foi possível armazenar o PDF.", e);
		}

		// ON CONFLICT em vez de capturar DuplicateKeyException: no Postgres uma violação
		// de unicidade aborta a transação, e a consulta seguinte falharia (25P02)
		// quando dois envios do mesmo PDF chegassem ao mesmo tempo.
		Instant agora = Instant.now();
		var inserido = jdbc.sql("""
				INSERT INTO versao_documento (origem, sha256, tamanho_bytes, chave_armazenamento,
				                             autorizacao_declarada, autorizacao_confirmada_em, titulo_declarado)
				VALUES ('UPLOAD', :hash, :tamanho, :chave, true, :confirmado, :titulo)
				ON CONFLICT (sha256) DO NOTHING
				RETURNING id
				""").param("hash", hash).param("tamanho", arquivo.getSize()).param("chave", destino.getFileName().toString())
				.param("confirmado", java.sql.Timestamp.from(agora)).param("titulo", tituloSeguro)
				.query(UUID.class).optional();
		if (inserido.isPresent()) {
			return new DocumentoUpload(inserido.get(), hash, arquivo.getSize(), false, agora);
		}
		return buscarPorHash(hash).orElseThrow();
	}

	private java.util.Optional<DocumentoUpload> buscarPorHash(String hash) {
		return jdbc.sql("SELECT id, tamanho_bytes, coletado_em FROM versao_documento WHERE sha256 = :hash")
				.param("hash", hash)
				.query((rs, row) -> new DocumentoUpload(rs.getObject("id", UUID.class), hash,
						rs.getLong("tamanho_bytes"), true, rs.getTimestamp("coletado_em").toInstant()))
				.optional();
	}

	private void validarPdf(MultipartFile arquivo) {
		try (InputStream input = arquivo.getInputStream()) {
			byte[] assinatura = input.readNBytes(PDF_MAGIC.length);
			if (!MessageDigest.isEqual(assinatura, PDF_MAGIC)) {
				throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "O conteúdo enviado não tem assinatura de PDF.");
			}
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não foi possível ler o arquivo enviado.", e);
		}
	}

	private String sha256(MultipartFile arquivo) {
		try (InputStream input = arquivo.getInputStream()) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			int lidos;
			while ((lidos = input.read(buffer)) != -1) digest.update(buffer, 0, lidos);
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não foi possível ler o arquivo enviado.", e);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 indisponível", e);
		}
	}
}
