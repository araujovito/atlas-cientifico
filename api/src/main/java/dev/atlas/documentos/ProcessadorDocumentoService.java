package dev.atlas.documentos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Processamento síncrono e idempotente para a primeira entrega do Atlas. */
@Service
public class ProcessadorDocumentoService {

	private static final Logger log = LoggerFactory.getLogger(ProcessadorDocumentoService.class);
	public static final String VERSAO_PROCESSADOR = "pdfbox-3.0.8-v1";
	private final JdbcClient jdbc;
	private final UploadProperties properties;
	private final ExtratorPdfIsolado extrator;
	private final TransactionTemplate transacoes;
	private final ObjectMapper objectMapper;

	public ProcessadorDocumentoService(
			JdbcClient jdbc,
			UploadProperties properties,
			ExtratorPdfIsolado extrator,
			TransactionTemplate transacoes,
			ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.extrator = extrator;
		this.transacoes = transacoes;
		this.objectMapper = objectMapper;
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
			extracao = extrator.extrair(caminho, properties.paginasMaximas());
		} catch (PrazoPdfExcedidoException e) {
			throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,e.getMessage(),e);
		} catch (LimiteDePaginasExcedidoException e) {
			log.warn("Documento {} recusado: {}", documentoId, e.getMessage());
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e);
		} catch (PdfProtegidoException e) {
			log.warn("Documento {} protegido por senha", documentoId);
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
				"O PDF está protegido por senha; envie uma versão sem proteção.", e);
		} catch (IOException e) {
			log.warn("Falha ao extrair texto do documento {}", documentoId, e);
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
		exigirExecucaoDoDocumento(documentoId, execucaoId);

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

	/** Uma linha por evidência, em ordem estável. limite é truncado em 500; antes eram devolvidas todas. */
	public List<MedidaComEvidencia> listarMedidas(UUID documentoId, UUID execucaoId, int limite, int deslocamento) {
		if (limite < 1 || deslocamento < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limite deve ser pelo menos 1 e deslocamento não pode ser negativo.");
		}
		int limiteEfetivo = Math.min(limite, 500);
		exigirExecucaoDoDocumento(documentoId, execucaoId);

		return jdbc.sql("""
				SELECT m.id AS medida_id, x.objeto, m.metrica, m.valor_original,
			       m.unidade_original, m.estado, x.condicoes::text AS condicoes,
			       e.pagina, e.trecho, e.inicio_caractere, e.fim_caractere
				FROM estudo s
				JOIN experimento x ON x.estudo_id = s.id
				JOIN medida m ON m.experimento_id = x.id
				LEFT JOIN evidencia e ON e.medida_id = m.id
				WHERE s.versao_documento_id = :documento AND s.execucao_id = :execucao
				ORDER BY m.registrada_em, m.id, e.pagina, e.inicio_caractere
				LIMIT :limite OFFSET :deslocamento
				""")
			.param("documento", documentoId).param("execucao", execucaoId)
			.param("limite", limiteEfetivo).param("deslocamento", deslocamento)
			.query((rs, row) -> new MedidaComEvidencia(
				rs.getObject("medida_id", UUID.class), rs.getString("objeto"), rs.getString("metrica"),
				rs.getString("valor_original"), rs.getString("unidade_original"), rs.getString("estado"),
				lerCondicoes(rs.getString("condicoes")),
				(rs.getObject("pagina") == null ? null : rs.getInt("pagina")), rs.getString("trecho"),
				(rs.getObject("inicio_caractere") == null ? null : rs.getInt("inicio_caractere")),
				(rs.getObject("fim_caractere") == null ? null : rs.getInt("fim_caractere"))))
			.list();
	}

	private tools.jackson.databind.JsonNode lerCondicoes(String json) {
		try {
			return objectMapper.readTree(json);
		} catch (tools.jackson.core.JacksonException e) {
			throw new IllegalStateException("Condições experimentais inválidas no banco de dados.", e);
		}
	}

	@Transactional
	public MedidaRegistrada registrarMedida(UUID documentoId, UUID execucaoId, MedidaCandidata medida) {
		if (medida == null || vazio(medida.objeto()) || vazio(medida.metrica())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Objeto e métrica são obrigatórios.");
		}
		validarTamanhos(medida);
		if (!List.of("REPORTADO", "INFERIDO", "AUSENTE", "PENDENTE_REVISAO").contains(medida.estado())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado da medida inválido.");
		}
		boolean ausente = "AUSENTE".equals(medida.estado());
		if (ausente != (medida.valorOriginal() == null) || (!ausente && medida.valorOriginal().isBlank())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Medida ausente não pode ter valor; os demais estados exigem valor.");
		}

		UUID versaoDocumento = jdbc.sql("""
				SELECT versao_documento_id FROM execucao_processamento
				WHERE id = :execucao AND versao_documento_id = :documento
				""")
			.param("execucao", execucaoId).param("documento", documentoId)
			.query(UUID.class).optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Execução não encontrada para este documento."));
		String estadoExecucao = jdbc.sql("SELECT estado FROM execucao_processamento WHERE id=:execucao")
			.param("execucao", execucaoId).query(String.class).single();
		if ("PRECISA_OCR".equals(estadoExecucao)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
				"Esta extração precisa de OCR; não é possível registrar medidas sem texto pesquisável.");
		}

		String trecho = medida.trecho();
		Integer inicioCaractere = null;
		Integer fimCaractere = null;
		if (!ausente) {
			if (medida.pagina() == null || medida.pagina() < 1 || vazio(trecho)) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe página e trecho literal da evidência.");
			}
			String textoPagina = jdbc.sql("SELECT texto FROM pagina_texto WHERE execucao_id=:execucao AND numero=:pagina")
				.param("execucao", execucaoId).param("pagina", medida.pagina())
				.query(String.class).optional()
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "A página informada não existe nesta extração."));
			var achado = LocalizadorDeTrecho.localizar(textoPagina, trecho);
			if (achado.situacao() == LocalizadorDeTrecho.Situacao.AUSENTE) {
				throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"O trecho informado não aparece na página (só diferenças de espaço e de quebra de linha são toleradas).");
			}
			// Posição ambígua: o destaque apontaria para uma ocorrência que pode não ser a citada.
			if (achado.situacao() == LocalizadorDeTrecho.Situacao.AMBIGUO) {
				throw new ResponseStatusException(HttpStatus.CONFLICT,
					"O trecho aparece mais de uma vez nesta página. Amplie o trecho para identificar a ocorrência.");
			}
			// REPORTADO afirma que o valor está no texto; a evidência precisa conter o que ela sustenta.
			if ("REPORTADO".equals(medida.estado())
					&& !LocalizadorDeTrecho.normalizar(achado.trechoOriginal())
						.contains(LocalizadorDeTrecho.normalizar(medida.valorOriginal()))) {
				throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
					"O valor informado não aparece no trecho. Use PENDENTE_REVISAO ou INFERIDO se ele não foi reportado literalmente.");
			}
			// Guarda o trecho como está na página, não como foi digitado.
			trecho = achado.trechoOriginal();
			inicioCaractere = achado.inicio();
			fimCaractere = achado.fim();
		}

		// O no-op em DO UPDATE faz o RETURNING devolver o id também quando a linha já existe.
		UUID estudoId = jdbc.sql("""
				INSERT INTO estudo (versao_documento_id, execucao_id) VALUES (:documento, :execucao)
				ON CONFLICT (execucao_id) DO UPDATE SET execucao_id = EXCLUDED.execucao_id
				RETURNING id
				""")
			.param("documento", versaoDocumento).param("execucao", execucaoId).query(UUID.class).single();
		String condicoesJson = serializarCondicoes(medida.condicoes());
		UUID experimentoId = jdbc.sql("""
				INSERT INTO experimento (estudo_id, objeto, condicoes)
				VALUES (:estudo, :objeto, CAST(:condicoes AS jsonb))
				ON CONFLICT (estudo_id, objeto, condicoes) DO UPDATE SET objeto = EXCLUDED.objeto
				RETURNING id
				""")
			.param("estudo", estudoId).param("objeto", medida.objeto().trim())
			.param("condicoes", condicoesJson).query(UUID.class).single();
		UUID medidaId = jdbc.sql("""
				INSERT INTO medida (experimento_id, metrica, valor_original, unidade_original, estado)
				VALUES (:experimento, :metrica, :valor, :unidade, :estado)
				ON CONFLICT (experimento_id, metrica, estado, (COALESCE(valor_original, '')), (COALESCE(unidade_original, '')))
				DO UPDATE SET metrica = EXCLUDED.metrica
				RETURNING id
				""")
			.param("experimento", experimentoId).param("metrica", medida.metrica().trim())
			.param("valor", medida.valorOriginal()).param("unidade", medida.unidadeOriginal())
			.param("estado", medida.estado()).query(UUID.class).single();
		UUID evidenciaId = null;
		if (!ausente) {
			evidenciaId = jdbc.sql("""
					INSERT INTO evidencia (medida_id, pagina, trecho, inicio_caractere, fim_caractere)
					VALUES (:medida, :pagina, :trecho, :inicio, :fim)
					ON CONFLICT (medida_id, pagina, inicio_caractere, fim_caractere) WHERE inicio_caractere IS NOT NULL
					DO UPDATE SET trecho = EXCLUDED.trecho
					RETURNING id
					""")
				.param("medida", medidaId).param("pagina", medida.pagina()).param("trecho", trecho)
				.param("inicio", inicioCaractere).param("fim", fimCaractere)
				.query(UUID.class).single();
		}
		return new MedidaRegistrada(estudoId, experimentoId, medidaId, evidenciaId, medida.estado());
	}

	private void exigirExecucaoDoDocumento(UUID documentoId, UUID execucaoId) {
		boolean pertence = jdbc.sql("""
				SELECT EXISTS (
				    SELECT 1 FROM execucao_processamento
				    WHERE id = :execucao AND versao_documento_id = :documento
				)
				""")
			.param("execucao", execucaoId).param("documento", documentoId)
			.query(Boolean.class).single();
		if (!pertence) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execução não encontrada para este documento.");
		}
	}

	private void validarTamanhos(MedidaCandidata medida) {
		limitar("objeto", medida.objeto(), 300);
		limitar("metrica", medida.metrica(), 300);
		limitar("valorOriginal", medida.valorOriginal(), 100);
		limitar("unidadeOriginal", medida.unidadeOriginal(), 50);
		limitar("trecho", medida.trecho(), 5000);
		if (medida.condicoes() != null && medida.condicoes().size() > 50) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Condições experimentais: no máximo 50 chaves.");
		}
		if (serializarCondicoes(medida.condicoes()).length() > 4000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Condições experimentais: no máximo 4000 caracteres.");
		}
	}

	private void limitar(String campo, String valor, int maximo) {
		if (valor != null && valor.length() > maximo) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O campo " + campo + " excede " + maximo + " caracteres.");
		}
	}

	private boolean vazio(String valor) {
		return valor == null || valor.isBlank();
	}

	private String serializarCondicoes(Map<String, Object> condicoes) {
		try {
			return objectMapper.writeValueAsString(condicoes == null ? Map.of() : condicoes);
		} catch (tools.jackson.core.JacksonException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "As condições experimentais não podem ser serializadas como JSON.", e);
		}
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

	/**
	 * A chave nova é só o nome do arquivo, relativo ao diretório configurado. Chaves antigas guardam
	 * o caminho absoluto; resolve() devolve um caminho absoluto sem alterá-lo, então elas seguem valendo.
	 */
	private Path caminhoSeguro(String chaveArmazenamento) {
		Path diretorio = properties.diretorio().toAbsolutePath().normalize();
		Path caminho;
		try {
			caminho = diretorio.resolve(chaveArmazenamento).normalize();
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
