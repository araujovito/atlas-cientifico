package dev.atlas.acesso;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
public class UsuarioAtual {
    static final String ATRIBUTO=UsuarioAtual.class.getName();
    public SessaoService.Usuario obter() {
        var attributes=RequestContextHolder.getRequestAttributes();
        if(attributes instanceof ServletRequestAttributes request
                && request.getRequest().getAttribute(ATRIBUTO) instanceof SessaoService.Usuario usuario) return usuario;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Entre na sua conta.");
    }
    public UUID id(){return obter().id();}
}
