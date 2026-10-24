package dev.atlas.seguranca;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FiltroChaveApiNormalizacaoTest {

	@Test
	void reduzOUriAFormaQueOMvcEnxerga() {
		assertEquals("/api/x", FiltroChaveApi.normalizar("/api;a=b/x"));
		assertEquals("/api/x", FiltroChaveApi.normalizar("/%61pi/x"));
		assertEquals("/api/x", FiltroChaveApi.normalizar("//api//x"));
		assertEquals("/api/a+b", FiltroChaveApi.normalizar("/api/a+b"));
		assertEquals("/api/x", FiltroChaveApi.normalizar("/y/../api/x"));
		assertEquals("/api/x", FiltroChaveApi.normalizar("/api/./x"));
		assertEquals("/index.html", FiltroChaveApi.normalizar("/index.html"));
	}
}
