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
    /** Quantos snapshots de metadados por obra aparecem nas respostas. */
    static final int METADADOS_MAXIMOS = 20;
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
        var obras = obter(List.of(id));
        if (obras.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Obra não encontrada.");
        return obras.getFirst();
    }

    /** Três consultas para qualquer quantidade de obras; obras inexistentes são omitidas, a ordem é a recebida. */
    List<ObraCatalogada> obter(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var bases = new java.util.HashMap<UUID, ObraCatalogada>();
        jdbc.sql("SELECT id,titulo,ano FROM obra WHERE id IN (:ids)").param("ids", ids)
                .query((rs, row) -> new ObraCatalogada(rs.getObject("id", UUID.class), rs.getString("titulo"),
                        rs.getObject("ano", Integer.class), List.of(), List.of()))
                .list().forEach(o -> bases.put(o.id(), o));
        var identificadores = new java.util.HashMap<UUID, List<ObraCatalogada.Identificador>>();
        jdbc.sql("SELECT obra_id,tipo,valor FROM identificador WHERE obra_id IN (:ids) ORDER BY tipo,valor").param("ids", ids)
                .query((rs, row) -> java.util.Map.entry(rs.getObject("obra_id", UUID.class),
                        new ObraCatalogada.Identificador(rs.getString("tipo"), rs.getString("valor"))))
                .list().forEach(e -> identificadores.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        var registros = new java.util.HashMap<UUID, List<tools.jackson.databind.JsonNode>>();
        // Só os registros mais recentes de cada obra: cada reimportação com conteúdo diferente cria um snapshot,
        // e devolvê-los todos faria a resposta crescer sem limite. Os anteriores continuam no banco.
        jdbc.sql("""
                SELECT obra_id,registro FROM (
                    SELECT obra_id,registro::text AS registro,coletado_em,id,
                           row_number() OVER (PARTITION BY obra_id ORDER BY coletado_em DESC,id DESC) AS posicao
                    FROM obra_metadados WHERE obra_id IN (:ids)) recentes
                WHERE posicao <= :maximo ORDER BY coletado_em,id
                """).param("ids", ids).param("maximo", METADADOS_MAXIMOS)
                .query((rs, row) -> java.util.Map.entry(rs.getObject("obra_id", UUID.class), mapper.readTree(rs.getString("registro"))))
                .list().forEach(e -> registros.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return ids.stream().map(bases::get).filter(java.util.Objects::nonNull)
                .map(b -> new ObraCatalogada(b.id(), b.titulo(), b.ano(),
                        identificadores.getOrDefault(b.id(), List.of()), registros.getOrDefault(b.id(), List.of()))).toList();
    }

    /** Título e ano sem carregar os metadados completos. */
    public ObraCatalogada resumo(UUID id) {
        return jdbc.sql("SELECT titulo,ano FROM obra WHERE id=:id").param("id", id)
                .query((rs, row) -> new ObraCatalogada(id, rs.getString("titulo"), rs.getObject("ano", Integer.class), List.of(), List.of()))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Obra não encontrada."));
    }

    public List<ObraCatalogada> listar(int limite, int deslocamento) {
        validarPagina(limite, deslocamento);
        return obter(jdbc.sql("SELECT id FROM obra ORDER BY criado_em,id LIMIT :limite OFFSET :offset")
                .param("limite", limite).param("offset", deslocamento).query(UUID.class).list());
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
