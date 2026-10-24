package dev.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Garante que as regras de integridade valem no banco, não só no código. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EsquemaTest {

	@Autowired JdbcClient jdbc;
	@Autowired TransactionTemplate tx;

	private UUID novaExperimento() {
		return tx.execute(s -> {
			UUID obra = jdbc.sql("INSERT INTO obra (titulo) VALUES ('Artigo de teste') RETURNING id")
					.query(UUID.class).single();
			UUID versao = jdbc.sql("""
					INSERT INTO versao_documento (obra_id, origem, sha256, tamanho_bytes,
					                              chave_armazenamento, autorizacao_declarada)
					VALUES (:o, 'UPLOAD', :h, 10, 'k', true) RETURNING id
					""").param("o", obra).param("h", hashAleatorio()).query(UUID.class).single();
			UUID exec = jdbc.sql("""
					INSERT INTO execucao_processamento (versao_documento_id, versao_processador,
					                                    estado, chave_idempotencia)
					VALUES (:v, '0', 'PENDENTE', :c) RETURNING id
					""").param("v", versao).param("c", UUID.randomUUID().toString()).query(UUID.class).single();
			UUID estudo = jdbc.sql("INSERT INTO estudo (versao_documento_id, execucao_id) VALUES (:v, :e) RETURNING id")
					.param("v", versao).param("e", exec).query(UUID.class).single();
			return jdbc.sql("INSERT INTO experimento (estudo_id, objeto) VALUES (:s, 'LiFePO4') RETURNING id")
					.param("s", estudo).query(UUID.class).single();
		});
	}

	private static String hashAleatorio() {
		return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
	}

	@Test
	void medidaReportadaSemEvidenciaNaoFazCommit() {
		UUID experimento = novaExperimento();

		assertThatThrownBy(() -> tx.executeWithoutResult(s ->
				jdbc.sql("""
						INSERT INTO medida (experimento_id, metrica, valor_original, unidade_original, estado)
						VALUES (:e, 'capacidade', '160', 'mAh/g', 'REPORTADO')
						""").param("e", experimento).update()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("sem evidência");
	}

	@Test
	void medidaComEvidenciaNaMesmaTransacaoFazCommit() {
		UUID experimento = novaExperimento();

		tx.executeWithoutResult(s -> {
			UUID medida = jdbc.sql("""
					INSERT INTO medida (experimento_id, metrica, valor_original, unidade_original, estado)
					VALUES (:e, 'capacidade', '160', 'mAh/g', 'REPORTADO') RETURNING id
					""").param("e", experimento).query(UUID.class).single();
			jdbc.sql("INSERT INTO evidencia (medida_id, pagina, trecho) VALUES (:m, 4, 'delivered 160 mAh/g')")
					.param("m", medida).update();
		});

		assertThat(jdbc.sql("SELECT count(*) FROM medida WHERE experimento_id = :e")
				.param("e", experimento).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void evidenciaExigeAmbosOffsetsOuNenhum() {
		UUID experimento = novaExperimento();
		UUID medida = tx.execute(s -> {
			UUID id = jdbc.sql("""
					INSERT INTO medida (experimento_id, metrica, valor_original, estado)
					VALUES (:e, 'capacidade', '160', 'REPORTADO') RETURNING id
					""").param("e", experimento).query(UUID.class).single();
			jdbc.sql("INSERT INTO evidencia (medida_id, pagina, trecho) VALUES (:m, 1, '160 mAh/g')")
					.param("m", id).update();
			return id;
		});

		assertThatThrownBy(() -> jdbc.sql("UPDATE evidencia SET inicio_caractere=0 WHERE medida_id=:m")
				.param("m", medida).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void medidaAusenteNaoPrecisaDeEvidenciaMasNaoPodeTerValor() {
		UUID experimento = novaExperimento();

		tx.executeWithoutResult(s -> jdbc.sql(
				"INSERT INTO medida (experimento_id, metrica, estado) VALUES (:e, 'incerteza', 'AUSENTE')")
				.param("e", experimento).update());

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql("""
				INSERT INTO medida (experimento_id, metrica, valor_original, estado)
				VALUES (:e, 'incerteza', '3', 'AUSENTE')
				""").param("e", experimento).update()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void valorNormalizadoExigeUnidade() {
		UUID experimento = novaExperimento();

		assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.sql("""
				INSERT INTO medida (experimento_id, metrica, valor_original, valor_normalizado, estado)
				VALUES (:e, 'capacidade', '160', 160, 'PENDENTE_REVISAO')
				""").param("e", experimento).update()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void mesmoArquivoNaoEntraDuasVezes() {
		String hash = hashAleatorio();
		UUID obra = jdbc.sql("INSERT INTO obra (titulo) VALUES ('Outro') RETURNING id").query(UUID.class).single();
		String insert = """
				INSERT INTO versao_documento (obra_id, origem, sha256, tamanho_bytes,
				                              chave_armazenamento, autorizacao_declarada)
				VALUES (:o, 'UPLOAD', :h, 10, 'k', true)
				""";
		jdbc.sql(insert).param("o", obra).param("h", hash).update();

		assertThatThrownBy(() -> jdbc.sql(insert).param("o", obra).param("h", hash).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void documentoSemAutorizacaoDeclaradaEhRecusado() {
		UUID obra = jdbc.sql("INSERT INTO obra (titulo) VALUES ('Sem autorização') RETURNING id")
				.query(UUID.class).single();

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO versao_documento (obra_id, origem, sha256, tamanho_bytes,
				                              chave_armazenamento, autorizacao_declarada)
				VALUES (:o, 'UPLOAD', :h, 10, 'k', false)
				""").param("o", obra).param("h", hashAleatorio()).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
