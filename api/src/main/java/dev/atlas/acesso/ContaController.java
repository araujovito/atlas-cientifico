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
    /** Resposta que carrega o código de recuperação, mostrado uma única vez. */
    public record EstadoComCodigo(SessaoService.Usuario usuario,String csrfToken,String codigoRecuperacao) {}
    public record Recuperacao(String email,String codigo,String novaSenha) {}
    public record SenhaAtual(String senha) {}
    public record Codigo(String codigoRecuperacao) {}
    private final SessaoService sessoes;
    private final LimiteTentativas limites;
    private final LimitesProperties config;
    public ContaController(SessaoService sessoes,LimiteTentativas limites,LimitesProperties config){this.sessoes=sessoes;this.limites=limites;this.config=config;}
    /** Endereço usado nos baldes: o da conexão, ou o último valor do cabeçalho que o operador declarou confiável. */
    String ip(HttpServletRequest r){
        if(!config.cabecalhoIp().isBlank()){
            String valor=r.getHeader(config.cabecalhoIp());
            if(valor!=null && !valor.isBlank()){var partes=valor.split(",");return partes[partes.length-1].strip();}
        }
        return r.getRemoteAddr();
    }
    @GetMapping("/sessao") public Estado estado(HttpServletRequest request,HttpServletResponse response){
        String token=request.getCookies()==null?null:Arrays.stream(request.getCookies()).filter(c->c.getName().equals(SessaoService.COOKIE)).map(Cookie::getValue).findFirst().orElse(null);
        var existente=sessoes.carregar(token);
        if(existente.isPresent()) return new Estado(existente.get().usuario(),existente.get().csrf());
        limites.consumir("sessao:"+ip(request),config.sessaoPorIp(),config.sessaoJanela());
        return responder(sessoes.criar(null),response);
    }
    @PostMapping(value="/cadastro",consumes="application/json")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public EstadoComCodigo cadastrar(@RequestBody Credenciais pedido,HttpServletRequest request,HttpServletResponse response){
        // Antes do Argon2: o custo de memória é o que a recusa precisa proteger.
        limites.consumir("cadastro:"+ip(request),config.cadastroPorIp(),config.cadastroJanela());
        return responderComCodigo(sessoes.cadastrar(pedido.email(),pedido.senha(),anterior(request)),response);
    }
    @PostMapping(value="/login",consumes="application/json")
    public Estado entrar(@RequestBody Credenciais pedido,HttpServletRequest request,HttpServletResponse response){
        String ip=ip(request);
        limites.consumir("login-ip:"+ip,config.loginPorIp(),config.loginJanela());
        String email=pedido.email()==null?"":pedido.email().strip().toLowerCase(java.util.Locale.ROOT);
        // Por e-mail+IP, para quem erra a própria senha não ser trancado por outro IP; e por e-mail, em limite mais alto.
        String parChave=LimiteTentativas.anonimo("falha-par",email+"|"+ip),emailChave=LimiteTentativas.anonimo("falha-email",email);
        limites.exigirAbaixo(parChave,config.falhasPorEmailEIp(),config.loginJanela());
        limites.exigirAbaixo(emailChave,config.falhasPorEmail(),config.loginJanela());
        try{
            var criada=sessoes.entrar(pedido.email(),pedido.senha(),anterior(request));
            // Entrar com a senha certa prova que quem tentou é o dono: zera os dois contadores.
            limites.limpar(parChave);limites.limpar(emailChave);
            return responder(criada,response);
        }catch(org.springframework.web.server.ResponseStatusException e){
            if(e.getStatusCode().value()==401){limites.registrar(parChave,config.loginJanela());limites.registrar(emailChave,config.loginJanela());}
            throw e;
        }
    }
    @PostMapping(value="/recuperar",consumes="application/json")
    public EstadoComCodigo recuperar(@RequestBody Recuperacao pedido,HttpServletRequest request,HttpServletResponse response){
        String ip=ip(request);
        limites.consumir("recuperar-ip:"+ip,config.loginPorIp(),config.loginJanela());
        String email=pedido.email()==null?"":pedido.email().strip().toLowerCase(java.util.Locale.ROOT);
        String parChave=LimiteTentativas.anonimo("falha-recuperar-par",email+"|"+ip),emailChave=LimiteTentativas.anonimo("falha-recuperar-email",email);
        limites.exigirAbaixo(parChave,config.falhasPorEmailEIp(),config.loginJanela());
        limites.exigirAbaixo(emailChave,config.falhasPorEmail(),config.loginJanela());
        try{
            var resultado=sessoes.recuperar(pedido.email(),pedido.codigo(),pedido.novaSenha(),anterior(request));
            limites.limpar(parChave);limites.limpar(emailChave);
            return responderComCodigo(resultado,response);
        }catch(org.springframework.web.server.ResponseStatusException e){
            if(e.getStatusCode().value()==401){limites.registrar(parChave,config.loginJanela());limites.registrar(emailChave,config.loginJanela());}
            throw e;
        }
    }
    @PostMapping(value="/codigo-recuperacao",consumes="application/json")
    public Codigo novoCodigo(@RequestBody SenhaAtual pedido,HttpServletRequest request){
        var usuario=anterior(request).usuario();
        // A conferência de senha custa Argon2: o mesmo limite de falhas do login vale aqui.
        String chave="codigo-recuperacao:"+usuario.id();
        limites.exigirAbaixo(chave,config.falhasPorEmailEIp(),config.loginJanela());
        try{ return new Codigo(sessoes.renovarCodigo(usuario.id(),pedido.senha())); }
        catch(org.springframework.web.server.ResponseStatusException e){
            if(e.getStatusCode().value()==401) limites.registrar(chave,config.loginJanela());
            throw e;
        }
    }
    @PostMapping("/logout") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void sair(HttpServletRequest request,HttpServletResponse response){
        sessoes.revogar(anterior(request));cookie(response,"",Duration.ZERO);
    }
    private SessaoService.Sessao anterior(HttpServletRequest r){return (SessaoService.Sessao)r.getAttribute(FiltroSessao.ATRIBUTO);}
    private EstadoComCodigo responderComCodigo(SessaoService.ComCodigo c,HttpServletResponse response){
        var estado=responder(c.criada(),response);
        return new EstadoComCodigo(estado.usuario(),estado.csrfToken(),c.codigo());
    }
    private Estado responder(SessaoService.Criada criada,HttpServletResponse response){
        cookie(response,criada.token(),Duration.between(java.time.Instant.now(),criada.sessao().expiraEm()));
        return new Estado(criada.sessao().usuario(),criada.sessao().csrf());
    }
    private void cookie(HttpServletResponse r,String valor,Duration idade){
        r.addHeader("Set-Cookie",ResponseCookie.from(SessaoService.COOKIE,valor).path("/").httpOnly(true).secure(true).sameSite("Strict").maxAge(idade).build().toString());
    }
}
