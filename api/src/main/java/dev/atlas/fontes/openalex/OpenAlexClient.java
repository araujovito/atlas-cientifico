package dev.atlas.fontes.openalex;

import dev.atlas.catalogo.Doi;
import dev.atlas.catalogo.ObraEncontrada;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAlexClient implements AutoCloseable {
	private final OpenAlexProperties properties;
	private final ObjectMapper mapper;
	private final HttpClient http;
	private final Clock clock;
	private Instant proximaConsulta = Instant.MIN;

	@Autowired
	public OpenAlexClient(OpenAlexProperties properties, ObjectMapper mapper) {
		this(properties, mapper, Clock.systemUTC());
	}

	OpenAlexClient(OpenAlexProperties properties, ObjectMapper mapper, Clock clock) {
		this.properties = properties;
		this.mapper = mapper;
		this.clock = clock;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
				.followRedirects(HttpClient.Redirect.NEVER).build();
	}

	public ObraEncontrada buscarPorDoi(String entrada) {
		Doi doi;
		try {
			doi = new Doi(entrada);
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
		}
		reservarConsulta();
		// O DOI entra somente no caminho codificado de uma origem configurada pelo operador.
		URI uri = UriComponentsBuilder.fromUri(properties.urlBase()).replacePath("/works/")
				.pathSegment("doi:" + doi.valor()).queryParam("select", "id,doi,title,publication_year,authorships")
				.build().encode().toUri();
		var request = HttpRequest.newBuilder(uri).timeout(properties.tempoLimite())
				.header("Accept", "application/json").header("User-Agent", "AtlasCientifico/0.0.1");
		if (properties.chaveApi() != null && !properties.chaveApi().isBlank()) {
			request.header("Authorization", "Bearer " + properties.chaveApi().strip());
		}
		HttpResponse<String> response;
		var pendente = http.sendAsync(request.GET().build(), HttpResponse.BodyHandlers.ofString());
		try {
			// No Java 21, HttpRequest.timeout não cobre um corpo que fica lento após os cabeçalhos.
			response = pendente.get(properties.tempoLimite().toNanos(), TimeUnit.NANOSECONDS);
		} catch (TimeoutException e) {
			pendente.cancel(true);
			throw prazoExcedido();
		} catch (ExecutionException e) {
			if (e.getCause() instanceof HttpTimeoutException) throw prazoExcedido();
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Não foi possível consultar o OpenAlex.");
		} catch (InterruptedException e) {
			pendente.cancel(true);
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Consulta ao OpenAlex interrompida.");
		}
		if (response.statusCode() == 404) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nenhuma obra encontrada no OpenAlex para este DOI.");
		}
		if (response.statusCode() == 429) {
			adiarConsulta(response.headers().firstValue("Retry-After").orElse("60"));
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "O OpenAlex atingiu seu limite de consultas. Aguarde antes de tentar novamente.");
		}
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "O OpenAlex recusou o acesso. Confira a chave configurada no servidor.");
		}
		if (response.statusCode() != 200) {
			throw respostaInvalida();
		}
		return lerObra(response.body(), doi);
	}

	private ObraEncontrada lerObra(String body, Doi consultado) {
		JsonNode obra;
		try {
			obra = mapper.readTree(body);
		} catch (JacksonException e) {
			throw respostaInvalida();
		}
		if (obra == null || !obra.isObject()) throw respostaInvalida();
		String id = obra.path("id").asText("");
		Doi recebido;
		try {
			recebido = new Doi(obra.path("doi").asText(null));
		} catch (IllegalArgumentException e) {
			throw respostaInvalida();
		}
		if (!id.matches("https://openalex\\.org/W[0-9]+") || !recebido.equals(consultado)) throw respostaInvalida();
		var autores = new ArrayList<String>();
		if (obra.path("authorships").isArray()) {
			for (JsonNode autoria : obra.path("authorships")) {
				String nome = texto(autoria.path("author").path("display_name"));
				if (nome != null) autores.add(nome);
			}
		}
		Integer ano = obra.path("publication_year").isIntegralNumber() ? obra.path("publication_year").intValue() : null;
		return new ObraEncontrada("OPENALEX", id, recebido.valor(), texto(obra.path("title")), ano, autores);
	}

	private static String texto(JsonNode node) {
		return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
	}

	private synchronized void reservarConsulta() {
		Instant agora = clock.instant();
		if (agora.isBefore(proximaConsulta)) {
			throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Aguarde antes de fazer outra consulta ao OpenAlex.");
		}
		// Até 5 inícios de consulta por segundo por instância, abaixo do limite anônimo de 10.
		proximaConsulta = agora.plusMillis(200);
	}

	private synchronized void adiarConsulta(String retryAfter) {
		Instant agora = clock.instant();
		Instant liberado = agora.plusSeconds(60);
		try {
			liberado = agora.plusSeconds(Math.max(1, Math.min(86400, Long.parseLong(retryAfter))));
		} catch (NumberFormatException e) {
			try {
				Instant data = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				if (data.isAfter(agora)) liberado = data;
			} catch (DateTimeParseException ignored) { /* sem cabeçalho utilizável: espera 60 segundos */ }
		}
		if (liberado.isAfter(proximaConsulta)) proximaConsulta = liberado;
	}

	private static ResponseStatusException respostaInvalida() {
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O OpenAlex devolveu uma resposta indisponível ou inválida.");
	}

	private static ResponseStatusException prazoExcedido() {
		return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "O OpenAlex demorou a responder. Tente novamente mais tarde.");
	}

	@Override
	@PreDestroy
	public void close() {
		http.close();
	}
}
