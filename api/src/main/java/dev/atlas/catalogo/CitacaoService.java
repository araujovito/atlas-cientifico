package dev.atlas.catalogo;

import dev.atlas.fontes.openalex.OpenAlexClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class CitacaoService {
    public record No(String id,UUID obraId,String titulo,Integer ano) {}
    public record Ligacao(String origem,String destino,String fonte,Instant coletadoEm) {}
    public record Grafo(List<No> nos,List<Ligacao> ligacoes,int totalReferencias,int deslocamento,Instant conferidoEm) {}
    private record Snapshot(String origem,List<String> referencias,Instant coletado,Instant conferido) {}
    private final JdbcClient jdbc;
    private final CatalogoService catalogo;
    private final OpenAlexClient fonte;
    private final ObjectMapper mapper;
    public CitacaoService(JdbcClient jdbc,CatalogoService catalogo,OpenAlexClient fonte,ObjectMapper mapper) {
        this.jdbc=jdbc;this.catalogo=catalogo;this.fonte=fonte;this.mapper=mapper;
    }

    public Grafo atualizar(UUID obra) {
        var registro=catalogo.obter(obra);
        String origem=registro.identificadores().stream().filter(i -> i.tipo().equals("OPENALEX")).map(ObraCatalogada.Identificador::valor).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Esta obra não tem identificador OpenAlex."));
        var referencias=fonte.referencias(origem).stream().distinct().filter(ref -> !ref.equals(origem)).toList();
        String json=mapper.writeValueAsString(referencias);
        jdbc.sql("""
                INSERT INTO citacao_snapshot(obra_id,fonte,origem_id,referencias,sha256)
                VALUES (:obra,'OPENALEX',:origem,CAST(:json AS jsonb),:hash)
                ON CONFLICT(obra_id,fonte,sha256) DO UPDATE SET conferido_em=now()
                """).param("obra",obra).param("origem",origem).param("json",json).param("hash",hash(json)).update();
        return obter(obra,20,0);
    }

    public Grafo obter(UUID obra,int limite,int deslocamento) {
        CatalogoService.validarPagina(limite,deslocamento);
        var registro=catalogo.resumo(obra);
        var snapshot=jdbc.sql("SELECT * FROM citacao_snapshot WHERE obra_id=:id ORDER BY conferido_em DESC,id DESC LIMIT 1").param("id",obra)
                .query((rs,row) -> new Snapshot(rs.getString("origem_id"),mapper.readValue(rs.getString("referencias"),new TypeReference<List<String>>(){}),
                        rs.getTimestamp("coletado_em").toInstant(),rs.getTimestamp("conferido_em").toInstant()))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Referências ainda não consultadas para esta obra."));
        List<String> refs=snapshot.referencias().stream().skip(deslocamento).limit(limite).toList();
        Map<String,No> conhecidos=refs.isEmpty()?Map.of():jdbc.sql("""
                SELECT i.valor,o.id,o.titulo,o.ano FROM identificador i JOIN obra o ON o.id=i.obra_id
                WHERE i.tipo='OPENALEX' AND i.valor IN (:refs)
                """).param("refs",refs).query((rs,row) -> new No(rs.getString("valor"),rs.getObject("id",UUID.class),rs.getString("titulo"),rs.getObject("ano",Integer.class)))
                .list().stream().collect(Collectors.toMap(No::id,n -> n));
        var nos=new ArrayList<No>(); nos.add(new No(snapshot.origem(),obra,registro.titulo(),registro.ano()));
        var ligacoes=new ArrayList<Ligacao>();
        for(String ref:refs) {
            nos.add(conhecidos.getOrDefault(ref,new No(ref,null,null,null)));
            ligacoes.add(new Ligacao(snapshot.origem(),ref,"OPENALEX",snapshot.coletado()));
        }
        return new Grafo(List.copyOf(nos),List.copyOf(ligacoes),snapshot.referencias().size(),deslocamento,snapshot.conferido());
    }
    private static String hash(String valor) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
