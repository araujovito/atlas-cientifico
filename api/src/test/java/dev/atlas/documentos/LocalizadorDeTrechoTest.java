package dev.atlas.documentos;

import static org.assertj.core.api.Assertions.assertThat;
import static dev.atlas.documentos.LocalizadorDeTrecho.Situacao.AMBIGUO;
import static dev.atlas.documentos.LocalizadorDeTrecho.Situacao.AUSENTE;
import static dev.atlas.documentos.LocalizadorDeTrecho.Situacao.ENCONTRADO;

import org.junit.jupiter.api.Test;

class LocalizadorDeTrechoTest {

	@Test
	void encontraTrechoLiteralComAPosicaoEmPontosDeCodigo() {
		var r = LocalizadorDeTrecho.localizar("😀 Capacidade 125 mAh/g no ciclo", "Capacidade 125 mAh/g");

		assertThat(r.situacao()).isEqualTo(ENCONTRADO);
		assertThat(r.inicio()).isEqualTo(2);
		assertThat(r.fim()).isEqualTo(22);
		assertThat(r.trechoOriginal()).isEqualTo("Capacidade 125 mAh/g");
	}

	@Test
	void trechoQueAtravessaQuebraDeLinhaDevolveOTextoOriginalDaPagina() {
		var r = LocalizadorDeTrecho.localizar("Medimos a capacidade\nde 125 mAh/g depois.", "capacidade de 125 mAh/g");

		assertThat(r.situacao()).isEqualTo(ENCONTRADO);
		assertThat(r.trechoOriginal()).isEqualTo("capacidade\nde 125 mAh/g");
		assertThat(r.fim() - r.inicio()).isEqualTo("capacidade\nde 125 mAh/g".length());
	}

	@Test
	void espacosRepetidosETabulacaoNaCitacaoSaoTolerados() {
		var r = LocalizadorDeTrecho.localizar("taxa 0.5 C no ciclo 1", "  taxa \t 0.5   C ");

		assertThat(r.situacao()).isEqualTo(ENCONTRADO);
		assertThat(r.trechoOriginal()).isEqualTo("taxa 0.5 C");
	}

	@Test
	void trechoRepetidoEAmbiguo() {
		var r = LocalizadorDeTrecho.localizar("0.5 C aqui e 0.5\nC ali", "0.5 C");

		assertThat(r.situacao()).isEqualTo(AMBIGUO);
	}

	@Test
	void trechoAusenteOuVazioNaoEEncontrado() {
		assertThat(LocalizadorDeTrecho.localizar("texto da pagina", "outra coisa").situacao()).isEqualTo(AUSENTE);
		assertThat(LocalizadorDeTrecho.localizar("texto da pagina", "   ").situacao()).isEqualTo(AUSENTE);
	}

	@Test
	void diferencaDeCaracterNaoETolerada() {
		assertThat(LocalizadorDeTrecho.localizar("125 mAh/g", "125 mAh-g").situacao()).isEqualTo(AUSENTE);
		assertThat(LocalizadorDeTrecho.localizar("Capacidade", "capacidade").situacao()).isEqualTo(AUSENTE);
	}
}
