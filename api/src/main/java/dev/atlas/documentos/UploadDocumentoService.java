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
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UploadDocumentoService {

	private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
	private final JdbcClient jdbc;
	private final UploadProperties properties;
	private final CotasProperties cotas;
	private final org.springframework.transaction.support.TransactionTemplate transacoes;

	public UploadDocumentoService(JdbcClient jdbc, UploadProperties properties, CotasProperties cotas,
			org.springframework.transaction.support.TransactionTemplate transacoes) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.cotas = cotas;
		this.transacoes = transacoes;
	}

	public record Uso(long bytesUsados, long bytesLimite, long documentos, int documentosLimite) {}

	public Uso uso(UUID dono) {
		return jdbc.sql("SELECT coalesce(sum(tamanho_bytes),0) AS bytes, count(*) AS docs FROM versao_documento WHERE dono_id=:dono")
				.param("dono", dono).query((rs, n) -> new Uso(rs.getLong("bytes"), cotas.bytesPorUsuario(), rs.getLong("docs"), cotas.documentosPorUsuario())).single();
	}

	private static ResponseStatusException cotaEsgotada(Uso uso) {
		return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Cota de armazenamento esgotada: "
				+ uso.documentos() + " de " + uso.documentosLimite() + " documentos e "
				+ uso.bytesUsados() / (1024 * 1024) + " de " + uso.bytesLimite() / (1024 * 1024) + " MiB em uso.");
	}

	// Sem @Transactional: a leitura do multipart, o hash e a cópia para o disco não usam o banco,
	// e uma transação aberta nesse intervalo prenderia uma conexão do pool durante o I/O.
	// O único comando que precisa ser atômico é o INSERT ... ON CONFLICT.
	public DocumentoUpload receber(MultipartFile arquivo, boolean autorizacaoConfirmada, String titulo, UUID dono) {
		java.util.Objects.requireNonNull(dono,"Dono obrigatório.");
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
		var existente = buscarPorHash(hash,dono);
		if (existente.isPresent()) {
			return existente.get();
		}

		// Recusa antes de gravar no disco; a checagem definitiva e atômica fica no INSERT, abaixo.
		var antes = uso(dono);
		if (antes.documentos() >= antes.documentosLimite() || antes.bytesUsados() + arquivo.getSize() > antes.bytesLimite()) throw cotaEsgotada(antes);

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
		// A trava por conta serializa envios simultâneos: sem ela, dois uploads leem o mesmo uso e ambos passam da cota.
		// O arquivo já gravado pode ser de outra conta (mesmo hash), por isso não é apagado se o INSERT for recusado.
		var inserido = transacoes.execute(tx -> {
			jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:chave, 0))").param("chave", "cota:" + dono).query((rs, n) -> 0).single();
			var existe = jdbc.sql("SELECT EXISTS(SELECT 1 FROM versao_documento WHERE sha256=:hash AND dono_id=:dono)")
					.param("hash", hash).param("dono", dono).query(Boolean.class).single();
			var agoraUso = uso(dono);
			if (!existe && (agoraUso.documentos() >= agoraUso.documentosLimite()
					|| agoraUso.bytesUsados() + arquivo.getSize() > agoraUso.bytesLimite())) throw cotaEsgotada(agoraUso);
			// ON CONFLICT em vez de capturar DuplicateKeyException: no Postgres uma violação
			// de unicidade aborta a transação, e a consulta seguinte falharia (25P02)
			// quando dois envios do mesmo PDF chegassem ao mesmo tempo.
			return jdbc.sql("""
					INSERT INTO versao_documento (origem, sha256, tamanho_bytes, chave_armazenamento,
					                             autorizacao_declarada, autorizacao_confirmada_em, titulo_declarado, dono_id)
					VALUES ('UPLOAD', :hash, :tamanho, :chave, true, :confirmado, :titulo, :dono)
					ON CONFLICT (dono_id,sha256) DO NOTHING
					RETURNING id
					""").param("hash", hash).param("tamanho", arquivo.getSize()).param("chave", destino.getFileName().toString())
					.param("confirmado", java.sql.Timestamp.from(agora)).param("titulo", tituloSeguro).param("dono", dono)
					.query(UUID.class).optional();
		});
		if (inserido.isPresent()) {
			return new DocumentoUpload(inserido.get(), hash, arquivo.getSize(), false, agora);
		}
		return buscarPorHash(hash,dono).orElseThrow();
	}

	private java.util.Optional<DocumentoUpload> buscarPorHash(String hash,UUID dono) {
		return jdbc.sql("SELECT id, tamanho_bytes, coletado_em FROM versao_documento WHERE sha256 = :hash AND dono_id=:dono")
				.param("hash", hash).param("dono",dono)
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
