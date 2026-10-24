package dev.atlas.fontes.openalex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class OpenAlexClientTest {
	private static final String DOI = "10.1038/s41467-022-28959-5";
	private static final Instant AGORA = Instant.parse("2026-10-24T12:00:00Z");
	private static final String OBRA = """
			{"id":"https://openalex.org/W4220682882","doi":"https://doi.org/10.1038/s41467-022-28959-5",
			 "title":"A dual-function liquid electrolyte additive", "publication_year":2022,
			 "authorships":[{"author":{"display_name":"Yuji Zhang"}},{"author":{"display_name":"Yu Wu"}}]}
			""";
	private HttpServer servidor;
	private ExecutorService executor;
	private OpenAlexClient client;
	private Clock clock;
	private final AtomicInteger consultas = new AtomicInteger();
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicReference<String> resposta = new AtomicReference<>(OBRA);
	private final AtomicReference<String> caminho = new AtomicReference<>();
	private final AtomicReference<String> query = new AtomicReference<>();
	private final AtomicReference<String> autorizacao = new AtomicReference<>();
	private final AtomicReference<String> espera = new AtomicReference<>();
	private volatile long atrasoMillis;
	private volatile boolean atrasarCorpo;

	@BeforeEach
	void prepararFonteLocal() throws Exception {
		clock = mock(Clock.class);
		when(clock.instant()).thenReturn(AGORA);
		servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newVirtualThreadPerTaskExecutor();
		servidor.setExecutor(executor);
		servidor.createContext("/", exchange -> {
			consultas.incrementAndGet();
			caminho.set(exchange.getRequestURI().getRawPath());
			query.set(exchange.getRequestURI().getRawQuery());
			autorizacao.set(exchange.getRequestHeaders().getFirst("Authorization"));
			try {
				if (atrasoMillis > 0 && !atrasarCorpo) Thread.sleep(atrasoMillis);
				if (espera.get() != null) exchange.getResponseHeaders().set("Retry-After", espera.get());
				if (status.get() == 301) exchange.getResponseHeaders().set("Location", "/outro-destino");
				byte[] corpo = resposta.get().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
				exchange.sendResponseHeaders(status.get(), corpo.length);
				if (atrasoMillis > 0 && atrasarCorpo) Thread.sleep(atrasoMillis);
				exchange.getResponseBody().write(corpo);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		servidor.start();
		client = novoClient("chave-somente-no-servidor", Duration.ofSeconds(2));
	}

	@AfterEach
	void fecharFonteLocal() {
		if (client != null) client.close();
		if (servidor != null) servidor.stop(0);
		if (executor != null) executor.close();
	}

	@ParameterizedTest
	@ValueSource(strings = {DOI, " HTTPS://DOI.ORG/10.1038/S41467-022-28959-5 ", "doi:10.1038/S41467-022-28959-5"})
	void normalizaDoiEMapeiaMetadados(String entrada) {
		var obra = client.buscarPorDoi(entrada);
		assertThat(obra.doi()).isEqualTo(DOI);
		assertThat(obra.openAlexId()).isEqualTo("https://openalex.org/W4220682882");
		assertThat(obra.titulo()).isEqualTo("A dual-function liquid electrolyte additive");
		assertThat(obra.ano()).isEqualTo(2022);
		assertThat(obra.autores()).containsExactly("Yuji Zhang", "Yu Wu");
		assertThat(obra.fonte()).isEqualTo("OPENALEX");
		assertThat(caminho.get()).isEqualTo("/works/doi:10.1038%2Fs41467-022-28959-5");
		assertThat(query.get()).isEqualTo("select=id,doi,title,publication_year,authorships");
		assertThat(autorizacao.get()).isEqualTo("Bearer chave-somente-no-servidor");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", "não é DOI", "https://exemplo.org/10.1234/artigo", "10.1234/tem espaço", "10.1234/tem\nquebra"})
	void recusaEntradaInvalidaAntesDeAcessarAFonte(String entrada) {
		esperarErro(400, () -> client.buscarPorDoi(entrada));
		assertThat(consultas).hasValue(0);
	}

	@Test
	void caracteresDeQueryEFragmentoFicamNoCaminho() {
		String especial = "10.1234/artigo?x=1#secao";
		resposta.set(OBRA.replace(DOI, especial));
		assertThat(client.buscarPorDoi(especial).doi()).isEqualTo(especial);
		assertThat(caminho.get()).contains("%3F", "%23");
		assertThat(query.get()).isEqualTo("select=id,doi,title,publication_year,authorships");
	}

	@Test
	void permiteConsultaAnonimaEPreservaMetadadosAusentes() {
		client.close();
		client = novoClient("", Duration.ofSeconds(2));
		resposta.set("{\"id\":\"https://openalex.org/W123\",\"doi\":\"https://doi.org/" + DOI + "\"}");
		var obra = client.buscarPorDoi(DOI);
		assertThat(autorizacao.get()).isNull();
		assertThat(obra.titulo()).isNull();
		assertThat(obra.ano()).isNull();
		assertThat(obra.autores()).isEmpty();
	}

	@Test
	void diferenciaObraNaoEncontradaDeFalhaDaFonte() {
		status.set(404);
		esperarErro(404, () -> client.buscarPorDoi(DOI));
		assertThat(consultas).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(ints = {301, 500, 503})
	void naoSegueRedirecionamentoNemRepeteErrosDaFonte(int httpStatus) {
		status.set(httpStatus);
		esperarErro(502, () -> client.buscarPorDoi(DOI));
		assertThat(consultas).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403})
	void credencialRecusadaNaoApareceComoErroDoVisitante(int httpStatus) {
		status.set(httpStatus);
		esperarErro(503, () -> client.buscarPorDoi(DOI));
	}

	@ParameterizedTest
	@ValueSource(strings = {"nao-json", "[]", "null", "{}",
			"{\"id\":\"javascript:alert(1)\",\"doi\":\"10.1234/artigo\"}"})
	void recusaRespostasMalformadasOuIdentificadoresInconsistentes(String body) {
		resposta.set(body);
		esperarErro(502, () -> client.buscarPorDoi(DOI));
	}

	@Test
	void naoApresentaOutraObraComoRespostaAoDoiConsultado() {
		resposta.set(OBRA.replace(DOI, "10.1234/outra-obra"));
		esperarErro(502, () -> client.buscarPorDoi(DOI));
	}

	@Test
	void limitaIniciosDeConsultaSemChamarAFonteDeNovo() {
		client.buscarPorDoi(DOI);
		esperarErro(429, () -> client.buscarPorDoi(DOI));
		assertThat(consultas).hasValue(1);
		when(clock.instant()).thenReturn(AGORA.plusMillis(200));
		client.buscarPorDoi(DOI);
		assertThat(consultas).hasValue(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"120", "Sat, 24 Oct 2026 12:02:00 GMT"})
	void respeitaRetryAfterSemRetentativaAutomatica(String retryAfter) {
		status.set(429);
		espera.set(retryAfter);
		esperarErro(503, () -> client.buscarPorDoi(DOI));
		when(clock.instant()).thenReturn(AGORA.plusSeconds(90));
		esperarErro(429, () -> client.buscarPorDoi(DOI));
		assertThat(consultas).hasValue(1);
		when(clock.instant()).thenReturn(AGORA.plusSeconds(121));
		status.set(200);
		client.buscarPorDoi(DOI);
		assertThat(consultas).hasValue(2);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void interrompeConsultaQueExcedePrazoMesmoDepoisDosCabecalhos(boolean depoisDosCabecalhos) {
		client.close();
		client = novoClient("", Duration.ofMillis(100));
		atrasoMillis = 400;
		atrasarCorpo = depoisDosCabecalhos;
		esperarErro(504, () -> client.buscarPorDoi(DOI));
	}

	private OpenAlexClient novoClient(String chave, Duration prazo) {
		return new OpenAlexClient(new OpenAlexProperties(URI.create("http://127.0.0.1:" + servidor.getAddress().getPort()),
				chave, prazo), new ObjectMapper(), clock);
	}

	private static void esperarErro(int statusEsperado, Runnable chamada) {
		assertThatThrownBy(chamada::run).isInstanceOfSatisfying(ResponseStatusException.class,
				e -> assertThat(e.getStatusCode().value()).isEqualTo(statusEsperado));
	}
}
