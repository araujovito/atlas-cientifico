package dev.atlas.acesso;

import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.Arrays;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class ContaController {
    public record Credenciais(String email,String senha) {}
    public record Estado(SessaoService.Usuario usuario,String csrfToken) {}
    private final SessaoService sessoes;
    public ContaController(SessaoService sessoes){this.sessoes=sessoes;}
    @GetMapping("/sessao") public Estado estado(HttpServletRequest request,HttpServletResponse response){
        String token=request.getCookies()==null?null:Arrays.stream(request.getCookies()).filter(c->c.getName().equals(SessaoService.COOKIE)).map(Cookie::getValue).findFirst().orElse(null);
        var existente=sessoes.carregar(token);
        if(existente.isPresent()) return new Estado(existente.get().usuario(),existente.get().csrf());
        return responder(sessoes.criar(null),response);
    }
    @PostMapping(value="/cadastro",consumes="application/json")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Estado cadastrar(@RequestBody Credenciais pedido,HttpServletRequest request,HttpServletResponse response){
        return responder(sessoes.cadastrar(pedido.email(),pedido.senha(),anterior(request)),response);
    }
    @PostMapping(value="/login",consumes="application/json")
    public Estado entrar(@RequestBody Credenciais pedido,HttpServletRequest request,HttpServletResponse response){
        return responder(sessoes.entrar(pedido.email(),pedido.senha(),anterior(request)),response);
    }
    @PostMapping("/logout") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void sair(HttpServletRequest request,HttpServletResponse response){
        sessoes.revogar(anterior(request));cookie(response,"",Duration.ZERO);
    }
    private SessaoService.Sessao anterior(HttpServletRequest r){return (SessaoService.Sessao)r.getAttribute(FiltroSessao.ATRIBUTO);}
    private Estado responder(SessaoService.Criada criada,HttpServletResponse response){
        cookie(response,criada.token(),Duration.between(java.time.Instant.now(),criada.sessao().expiraEm()));
        return new Estado(criada.sessao().usuario(),criada.sessao().csrf());
    }
    private void cookie(HttpServletResponse r,String valor,Duration idade){
        r.addHeader("Set-Cookie",ResponseCookie.from(SessaoService.COOKIE,valor).path("/").httpOnly(true).secure(true).sameSite("Strict").maxAge(idade).build().toString());
    }
}
