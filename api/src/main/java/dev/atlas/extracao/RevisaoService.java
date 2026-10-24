package dev.atlas.extracao;

import dev.atlas.documentos.MedidaCandidata;
import dev.atlas.documentos.ProcessadorDocumentoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
public class RevisaoService {
    public record Evidencia(int pagina,String trecho,Integer inicioCaractere,Integer fimCaractere) {}
    public record Snapshot(MedidaCandidata medida,List<Evidencia> evidencias) {}
    public record Atual(UUID medidaId,UUID documentoId,UUID execucaoId,int versao,Snapshot original,Snapshot atual) {}
    public record Pedido(@NotBlank @Size(max=1000) String motivo,
            @Min(0) int versaoEsperada,@NotNull @Valid MedidaCandidata corrigida) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejeitarCampo(String campo,Object valor){throw new IllegalArgumentException("Campo não aceito no pedido de revisão.");}
    }
    public record Revisao(UUID id,int versao,String autorDeclarado,String motivo,Snapshot anterior,Snapshot correcao,Instant registradoEm,UUID usuarioId) {}
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ProcessadorDocumentoService processador;
    private final ObjectMapper mapper;
    private final dev.atlas.acesso.UsuarioAtual usuario;
    public RevisaoService(JdbcClient jdbc,TransactionTemplate tx,ProcessadorDocumentoService processador,ObjectMapper mapper,dev.atlas.acesso.UsuarioAtual usuario) {
        this.jdbc=jdbc;this.tx=tx;this.processador=processador;this.mapper=mapper;this.usuario=usuario;
    }

    public Atual obter(UUID medida) {
        var base=jdbc.sql("""
                SELECT m.*,x.objeto,x.condicoes::text,s.versao_documento_id,s.execucao_id
                FROM medida m JOIN experimento x ON x.id=m.experimento_id JOIN estudo s ON s.id=x.estudo_id WHERE m.id=:id
                """).param("id",medida).query((rs,row) -> new Atual(medida,rs.getObject("versao_documento_id",UUID.class),rs.getObject("execucao_id",UUID.class),0,
                    new Snapshot(new MedidaCandidata(rs.getString("objeto"),rs.getString("metrica"),rs.getString("valor_original"),rs.getString("unidade_original"),
                        rs.getString("estado"),null,null,mapper.readValue(rs.getString("condicoes"),new tools.jackson.core.type.TypeReference<java.util.Map<String,Object>>(){})),List.of()),null))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Medida não encontrada."));
        var evidencias=jdbc.sql("SELECT * FROM evidencia WHERE medida_id=:id ORDER BY pagina,inicio_caractere,id").param("id",medida)
                .query((rs,row) -> new Evidencia(rs.getInt("pagina"),rs.getString("trecho"),rs.getObject("inicio_caractere",Integer.class),rs.getObject("fim_caractere",Integer.class))).list();
        var m=base.original().medida(); var primeira=evidencias.isEmpty()?null:evidencias.getFirst();
        var original=new Snapshot(new MedidaCandidata(m.objeto(),m.metrica(),m.valorOriginal(),m.unidadeOriginal(),m.estado(),
                primeira==null?null:primeira.pagina(),primeira==null?null:primeira.trecho(),m.condicoes()),evidencias);
        var ultima=jdbc.sql("SELECT * FROM revisao_medida WHERE medida_id=:id ORDER BY versao DESC LIMIT 1").param("id",medida).query(this::lerRevisao).optional();
        return new Atual(medida,base.documentoId(),base.execucaoId(),ultima.map(Revisao::versao).orElse(0),original,ultima.map(Revisao::correcao).orElse(original));
    }

    public Atual revisar(UUID medida,Pedido pedido) {
        var autor=usuario.obter();
        return tx.execute(s -> {
            jdbc.sql("""
                    SELECT m.id FROM medida m JOIN experimento x ON x.id=m.experimento_id
                    JOIN estudo s ON s.id=x.estudo_id JOIN versao_documento d ON d.id=s.versao_documento_id
                    WHERE m.id=:id AND d.dono_id=:u FOR UPDATE OF m
                    """).param("id",medida).param("u",autor.id()).query(UUID.class).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Medida não encontrada."));
            var antes=obter(medida);
            if(antes.versao()!=pedido.versaoEsperada()) throw new ResponseStatusException(HttpStatus.CONFLICT,"A medida recebeu outra revisão. Recarregue antes de corrigir.");
            var v=processador.validarMedida(antes.documentoId(),antes.execucaoId(),pedido.corrigida());
            var evidencias="AUSENTE".equals(v.medida().estado()) ? List.<Evidencia>of() : List.of(new Evidencia(v.medida().pagina(),v.medida().trecho(),v.inicio(),v.fim()));
            var correcao=new Snapshot(v.medida(),evidencias);
            jdbc.sql("""
                    INSERT INTO revisao_medida(medida_id,versao,autor_declarado,motivo,anterior,correcao,usuario_id)
                    VALUES (:id,:v,:a,:m,CAST(:ant AS jsonb),CAST(:nova AS jsonb),:usuario)
                    """).param("id",medida).param("v",antes.versao()+1).param("a",autor.id().toString()).param("m",pedido.motivo().strip())
                    .param("usuario",autor.id()).param("ant",mapper.writeValueAsString(antes.atual())).param("nova",mapper.writeValueAsString(correcao)).update();
            return obter(medida);
        });
    }

    public List<Revisao> historico(UUID medida) {
        obter(medida);
        return jdbc.sql("SELECT * FROM revisao_medida WHERE medida_id=:id ORDER BY versao LIMIT 1000").param("id",medida).query(this::lerRevisao).list();
    }
    private Revisao lerRevisao(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Revisao(rs.getObject("id",UUID.class),rs.getInt("versao"),rs.getString("autor_declarado"),rs.getString("motivo"),
                mapper.readValue(rs.getString("anterior"),Snapshot.class),mapper.readValue(rs.getString("correcao"),Snapshot.class),rs.getTimestamp("registrado_em").toInstant(),rs.getObject("usuario_id",UUID.class));
    }
}
