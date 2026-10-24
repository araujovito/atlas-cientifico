package dev.atlas;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Diagnóstico, não teste de regressão: só roda com -Datlas.diagnostico=true. Imprime o que o navegador vê durante
 * cadastro e primeira chamada autenticada, com prazos curtos para nunca ficar preso. Serve para investigar diferenças
 * entre motores (ex.: cookie __Host-/Secure em http://127.0.0.1 no WebKit).
 */
@EnabledIfSystemProperty(named = "atlas.diagnostico", matches = "true")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiagnosticoNavegadorTest {
	@LocalServerPort int porta;

	private static String descrever(java.util.List<com.microsoft.playwright.options.Cookie> cookies) {
		return cookies.stream().map(c -> c.name + "{secure=" + c.secure + ",httpOnly=" + c.httpOnly + ",sameSite=" + c.sameSite + ",path=" + c.path + "}")
				.collect(java.util.stream.Collectors.joining("; ", "[", "]"));
	}

	private static void d(String texto) { System.out.println("DIAGNOSTICO " + texto); }

	@Test
	void cadastroEPrimeiraChamadaAutenticada() {
		String motor = System.getProperty("atlas.navegador", "chromium");
		try (var playwright = Playwright.create()) {
			var tipo = switch (motor) { case "firefox" -> playwright.firefox(); case "webkit" -> playwright.webkit(); default -> playwright.chromium(); };
			try (var navegador = tipo.launch(); var contexto = navegador.newContext()) {
				d("motor=" + tipo.name() + " " + navegador.version());
				Page pagina = contexto.newPage();
				pagina.setDefaultTimeout(8000);
				pagina.onConsoleMessage(m -> d("console[" + m.type() + "] " + m.text()));
				pagina.onPageError(e -> d("pageerror " + e));
				pagina.onRequestFailed(r -> d("requestfailed " + r.method() + " " + r.url() + " " + r.failure()));
				pagina.onResponse(r -> { if (r.url().contains("/api/")) d("resposta " + r.request().method() + " " + r.url().replaceAll("^.*?/api/", "/api/")
						+ " -> " + r.status() + " set-cookie=" + r.headers().getOrDefault("set-cookie", "-")); });
				String base = "http://127.0.0.1:" + porta + "/";
				pagina.navigate(base);
				try { pagina.locator("#auth-register:not([disabled])").waitFor(); d("botao de cadastro habilitado"); }
				catch (RuntimeException e) { d("botao de cadastro NAO habilitou: " + e.getMessage().lines().findFirst().orElse("")); }
				d("auth-state inicial = " + pagina.locator("#auth-state").textContent());
				d("cookies apos carregar = " + descrever(contexto.cookies()));
				pagina.locator("#auth-email").fill("diag-" + UUID.randomUUID() + "@example.com");
				pagina.locator("#auth-password").fill("Senha de diagnostico 123");
				pagina.locator("#auth-register").click();
				try { pagina.locator("#recovery-panel").waitFor(); d("painel de recuperacao apareceu"); }
				catch (RuntimeException e) { d("painel de recuperacao NAO apareceu: " + e.getMessage().lines().findFirst().orElse("")); }
				d("auth-state apos cadastro = " + pagina.locator("#auth-state").textContent());
				d("cookies apos cadastro = " + descrever(contexto.cookies()));
				d("document.cookie (visivel ao JS) = '" + pagina.evaluate("document.cookie") + "'");
				Object status = pagina.evaluate("fetch('/api/conta/uso',{credentials:'same-origin'}).then(r => r.status)");
				d("GET /api/conta/uso depois do cadastro -> " + status);
				d("isSecureContext = " + pagina.evaluate("window.isSecureContext"));
			}
		}
	}
}
