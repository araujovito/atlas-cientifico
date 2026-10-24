package dev.atlas.catalogo;

import dev.atlas.fontes.openalex.OpenAlexClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CatalogoService {
    private final JdbcClient jdbc;
    private final TransactionTemplate transacoes;
    private final ObjectMapper mapper;
    private final OpenAlexClient fonte;

    public CatalogoService(JdbcClient jdbc, TransactionTemplate transacoes, ObjectMapper mapper, OpenAlexClient fonte) {
        this.jdbc = jdbc; this.transacoes = transacoes; this.mapper = mapper; this.fonte = fonte;
    }

    public ObraCatalogada importar(String entrada) {
        String doi = normalizar(entrada);
        var existente = jdbc.sql("SELECT obra_id FROM identificador WHERE tipo='DOI' AND valor=:doi")
                .param("doi", doi).query(UUID.class).optional();
        // Reenvio não depende de nova chamada ao provedor nem altera os metadados anteriores.
        return existente.map(this::obter).orElseGet(() -> registrar(fonte.buscarPorDoi(doi)));
    }

    /** Recebe apenas resultados dos conectores do servidor; não é um endpoint de JSON arbitrário. */
    public ObraCatalogada registrar(ObraEncontrada recebida) {
        String doi = normalizar(recebida.doi());
        if (!Set.of("OPENALEX", "UNPAYWALL", "EUROPEPMC").contains(recebida.fonte())) {
            throw new IllegalArgumentException("Fonte de metadados desconhecida.");
        }
        if (recebida.openAlexId() != null && !recebida.openAlexId().matches("https://openalex\\.org/W[0-9]+")) {
            throw new IllegalArgumentException("Identificador OpenAlex inválido.");
        }
        List<ObraCatalogada.Identificador> ids = new ArrayList<>();
        ids.add(new ObraCatalogada.Identificador("DOI", doi));
        if (recebida.openAlexId() != null) ids.add(new ObraCatalogada.Identificador("OPENALEX", recebida.openAlexId()));
        String registro = mapper.writeValueAsString(recebida);
        return transacoes.execute(tx -> {
            // Ordem total evita deadlock; chaves compartilhadas serializam importações concorrentes.
            ids.stream().map(id -> id.tipo() + ":" + id.valor()).sorted().forEach(chave ->
                    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:chave, 0))")
                            .param("chave", chave).query((rs, row) -> 0).single());
            var obras = jdbc.sql("""
                    SELECT DISTINCT obra_id FROM identificador
                    WHERE (tipo='DOI' AND valor=:doi) OR (tipo='OPENALEX' AND valor=:oa)
                    """).param("doi", doi).param("oa", recebida.openAlexId()).query(UUID.class).list();
            if (obras.size() > 1) throw conflito();
            UUID obra;
            if (obras.isEmpty()) {
                String titulo = recebida.titulo() == null || recebida.titulo().isBlank() ? doi : recebida.titulo();
                obra = jdbc.sql("INSERT INTO obra(titulo,ano) VALUES (:titulo,:ano) RETURNING id")
                        .param("titulo", titulo).param("ano", recebida.ano()).query(UUID.class).single();
            } else {
                obra = obras.getFirst();
                boolean outroDoi = jdbc.sql("SELECT EXISTS(SELECT 1 FROM identificador WHERE obra_id=:id AND tipo='DOI' AND valor<>:doi)")
                        .param("id", obra).param("doi", doi).query(Boolean.class).single();
                if (outroDoi) throw conflito();
                // Uma obra com identificador OpenAlex já definido não aceita outro: seria fusão silenciosa.
                boolean outroOpenAlex = recebida.openAlexId() != null && jdbc.sql(
                        "SELECT EXISTS(SELECT 1 FROM identificador WHERE obra_id=:id AND tipo='OPENALEX' AND valor<>:oa)")
                        .param("id", obra).param("oa", recebida.openAlexId()).query(Boolean.class).single();
                if (outroOpenAlex) throw conflito();
            }
            for (var id : ids) jdbc.sql("INSERT INTO identificador(obra_id,tipo,valor) VALUES (:obra,:tipo,:valor) ON CONFLICT DO NOTHING")
                    .param("obra", obra).param("tipo", id.tipo()).param("valor", id.valor()).update();
            jdbc.sql("""
                    INSERT INTO obra_metadados(obra_id,fonte,registro,sha256)
                    VALUES (:obra,:fonte,CAST(:json AS jsonb),:hash) ON CONFLICT DO NOTHING
                    """).param("obra", obra).param("fonte", recebida.fonte()).param("json", registro).param("hash", hash(registro)).update();
            return obter(obra);
        });
    }

    public ObraCatalogada obter(UUID id) {
        var base = jdbc.sql("SELECT titulo,ano FROM obra WHERE id=:id").param("id", id)
                .query((rs, row) -> new ObraCatalogada(id, rs.getString("titulo"), rs.getObject("ano", Integer.class), List.of(), List.of()))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Obra não encontrada."));
        var ids = jdbc.sql("SELECT tipo,valor FROM identificador WHERE obra_id=:id ORDER BY tipo,valor").param("id", id)
                .query((rs, row) -> new ObraCatalogada.Identificador(rs.getString("tipo"), rs.getString("valor"))).list();
        var registros = jdbc.sql("SELECT registro::text FROM obra_metadados WHERE obra_id=:id ORDER BY coletado_em,id")
                .param("id", id).query(String.class).list().stream().map(mapper::readTree).toList();
        return new ObraCatalogada(id, base.titulo(), base.ano(), ids, registros);
    }

    public List<ObraCatalogada> listar(int limite, int deslocamento) {
        validarPagina(limite, deslocamento);
        return jdbc.sql("SELECT id FROM obra ORDER BY criado_em,id LIMIT :limite OFFSET :offset")
                .param("limite", limite).param("offset", deslocamento).query(UUID.class).list().stream().map(this::obter).toList();
    }

    public void associarDocumento(UUID documento, UUID obra) {
        obter(obra);
        int alterados = jdbc.sql("UPDATE versao_documento SET obra_id=:obra WHERE id=:doc AND (obra_id IS NULL OR obra_id=:obra)")
                .param("obra", obra).param("doc", documento).update();
        if (alterados == 0) {
            boolean existe = jdbc.sql("SELECT EXISTS(SELECT 1 FROM versao_documento WHERE id=:doc)").param("doc", documento).query(Boolean.class).single();
            throw new ResponseStatusException(existe ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                    existe ? "Documento já associado a outra obra." : "Documento não encontrado.");
        }
    }

    static void validarPagina(int limite, int deslocamento) {
        if (limite < 1 || limite > 100 || deslocamento < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limite entre 1 e 100; deslocamento não negativo.");
    }

    private static String normalizar(String valor) {
        try { return new Doi(valor).valor(); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
    private static ResponseStatusException conflito() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Identificadores apontam para obras diferentes; a importação não foi fundida.");
    }
    private static String hash(String valor) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
