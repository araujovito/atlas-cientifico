package dev.atlas.documentos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Processamento síncrono e idempotente para a primeira entrega do Atlas. */
@Service
public class ProcessadorDocumentoService {

	private static final String VERSAO_PROCESSADOR = "pdfbox-3.0.8-v1";
	private final JdbcClient jdbc;
	private final UploadProperties properties;
	private final ExtratorTextoPdf extrator;
	private final TransactionTemplate transacoes;

	public ProcessadorDocumentoService(
			JdbcClient jdbc,
			UploadProperties properties,
			ExtratorTextoPdf extrator,
			TransactionTemplate transacoes) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.extrator = extrator;
		this.transacoes = transacoes;
	}

	public ResultadoProcessamentoDocumento processar(UUID documentoId) {
		DocumentoArmazenado documento = jdbc.sql("""
				SELECT id, sha256, chave_armazenamento
				FROM versao_documento
				WHERE id = :id
				""").param("id", documentoId)
			.query((rs, row) -> new DocumentoArmazenado(
					rs.getObject("id", UUID.class), rs.getString("sha256"), rs.getString("chave_armazenamento")))
			.optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento não encontrado."));

		String chave = documento.sha256() + ":" + VERSAO_PROCESSADOR;
		var existente = buscarExecucao(documento.id(), chave);
		if (existente.isPresent()) {
			return existente.get();
		}

		Path caminho = caminhoSeguro(documento.chaveArmazenamento());
		if (!Files.isRegularFile(caminho)) {
			throw new ResponseStatusException(HttpStatus.GONE, "O arquivo armazenado não está disponível.");
		}

		ResultadoExtracaoPdf extracao;
		try {
			extracao = extrator.extrair(caminho);
		} catch (IOException e) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Não foi possível extrair texto deste PDF.", e);
		}

		String estado = extracao.estado() == EstadoExtracaoPdf.PRECISA_OCR ? "PRECISA_OCR" : "CONCLUIDO";
		Instant agora = Instant.now();
		return transacoes.execute(status -> {
			var inserida = jdbc.sql("""
					INSERT INTO execucao_processamento
					    (versao_documento_id, versao_processador, estado, chave_idempotencia,
					     iniciado_em, finalizado_em)
					VALUES (:documento, :versao, :estado, :chave, :agora, :agora)
					ON CONFLICT (chave_idempotencia) DO NOTHING
					RETURNING id
					""")
				.param("documento", documento.id())
				.param("versao", VERSAO_PROCESSADOR)
				.param("estado", estado)
				.param("chave", chave)
				.param("agora", Timestamp.from(agora))
				.query(UUID.class)
				.optional();

			if (inserida.isEmpty()) {
				return buscarExecucao(documento.id(), chave).orElseThrow();
			}

			UUID execucaoId = inserida.get();
			for (PaginaPdf pagina : extracao.paginas()) {
				jdbc.sql("INSERT INTO pagina_texto (execucao_id, numero, texto) VALUES (:execucao, :numero, :texto)")
						.param("execucao", execucaoId)
						.param("numero", pagina.numero())
						.param("texto", pagina.texto())
						.update();
			}
			return new ResultadoProcessamentoDocumento(
					execucaoId, documento.id(), VERSAO_PROCESSADOR, estado, extracao.paginas().size(), false);
		});
	}

	public List<PaginaPdf> listarPaginas(UUID documentoId, UUID execucaoId) {
		boolean pertenceAoDocumento = jdbc.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM execucao_processamento
				    WHERE id = :execucao AND versao_documento_id = :documento
				)
				""")
			.param("execucao", execucaoId)
			.param("documento", documentoId)
			.query(Boolean.class)
			.single();

		if (!pertenceAoDocumento) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execução não encontrada para este documento.");
		}

		return jdbc.sql("""
				SELECT numero, texto
				FROM pagina_texto
				WHERE execucao_id = :execucao
				ORDER BY numero
				""")
			.param("execucao", execucaoId)
			.query((rs, row) -> new PaginaPdf(rs.getInt("numero"), rs.getString("texto")))
			.list();
	}

	private java.util.Optional<ResultadoProcessamentoDocumento> buscarExecucao(UUID documentoId, String chave) {
		return jdbc.sql("""
				SELECT e.id, e.estado, count(p.numero) AS paginas
				FROM execucao_processamento e
				LEFT JOIN pagina_texto p ON p.execucao_id = e.id
				WHERE e.versao_documento_id = :documento AND e.chave_idempotencia = :chave
				GROUP BY e.id, e.estado
				""")
			.param("documento", documentoId)
			.param("chave", chave)
			.query((rs, row) -> new ResultadoProcessamentoDocumento(
					rs.getObject("id", UUID.class), documentoId, VERSAO_PROCESSADOR,
					rs.getString("estado"), rs.getInt("paginas"), true))
			.optional();
	}

	private Path caminhoSeguro(String chaveArmazenamento) {
		Path diretorio = properties.diretorio().toAbsolutePath().normalize();
		Path caminho;
		try {
			caminho = Path.of(chaveArmazenamento).toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Caminho do documento inválido.", e);
		}
		if (!caminho.startsWith(diretorio)) {
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Documento fora do diretório de armazenamento.");
		}
		return caminho;
	}

	private record DocumentoArmazenado(UUID id, String sha256, String chaveArmazenamento) {
	}
}
