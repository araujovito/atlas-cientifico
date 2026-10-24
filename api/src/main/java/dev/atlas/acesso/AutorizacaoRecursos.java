package dev.atlas.acesso;

import jakarta.servlet.http.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;

/** Guarda de rotas privadas antes de validação do corpo ou efeitos do controlador. */
@Component
public class AutorizacaoRecursos implements HandlerInterceptor {
    private final JdbcClient jdbc;
    private final UsuarioAtual usuario;
    public AutorizacaoRecursos(JdbcClient jdbc,UsuarioAtual usuario){this.jdbc=jdbc;this.usuario=usuario;}
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
        String pattern=(String)request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if(pattern==null || !pattern.startsWith("/api/")) return true;
        @SuppressWarnings("unchecked") var ids=(Map<String,String>)request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if(ids==null || ids.isEmpty()) return true;
        // Rotas públicas por desenho: o próprio controlador só entrega documentos marcados PUBLICO.
        if(pattern.startsWith("/api/publico/")) return true;
        UUID dono=usuario.id();
        String documento=ids.getOrDefault("documentoId",ids.get("documento"));
        if(documento!=null){
            exigir("SELECT EXISTS(SELECT 1 FROM versao_documento WHERE id=:id AND dono_id=:u)",documento,dono);
            if(ids.containsKey("execucaoId")) {
                boolean pertence=jdbc.sql("SELECT EXISTS(SELECT 1 FROM execucao_processamento WHERE id=:e AND versao_documento_id=:d AND usuario_id=:u)")
                        .param("e",uuid(ids.get("execucaoId"))).param("d",uuid(documento)).param("u",dono).query(Boolean.class).single();
                if(!pertence) throw ausente();
            }
            return true;
        }
        if(pattern.startsWith("/api/colecoes/")) {
            exigir("SELECT EXISTS(SELECT 1 FROM colecao WHERE id=:id AND dono_id=:u)",ids.get("id"),dono);return true;
        }
        if(pattern.startsWith("/api/processamentos/")) {
            exigir("SELECT EXISTS(SELECT 1 FROM tarefa_extracao t JOIN versao_documento d ON d.id=t.documento_id WHERE t.id=:id AND d.dono_id=:u)",ids.get("id"),dono);return true;
        }
        if(pattern.startsWith("/api/medidas/")) {exigirMedida(uuid(ids.get("id")),dono);return true;}
        // Obras e relações da fonte são públicas/compartilhadas; documentos associados não são.
        if(pattern.startsWith("/api/catalogo/obras/")) return true;
        throw ausente(); // rota com ID nova precisa declarar sua política e ter teste.
    }
    public void exigirMedida(UUID id,UUID dono){
        exigir("""
                SELECT EXISTS(SELECT 1 FROM medida m JOIN experimento x ON x.id=m.experimento_id
                JOIN estudo s ON s.id=x.estudo_id JOIN versao_documento d ON d.id=s.versao_documento_id
                WHERE m.id=:id AND d.dono_id=:u)
                """,id.toString(),dono);
    }
    private void exigir(String sql,String id,UUID dono){
        if(!jdbc.sql(sql).param("id",uuid(id)).param("u",dono).query(Boolean.class).single()) throw ausente();
    }
    private UUID uuid(String id){
        try{return UUID.fromString(id);}catch(IllegalArgumentException|NullPointerException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Identificador inválido.");}
    }
    private static ResponseStatusException ausente(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Recurso não encontrado.");}
}
