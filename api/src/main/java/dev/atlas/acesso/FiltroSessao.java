package dev.atlas.acesso;

import dev.atlas.seguranca.FiltroChaveApi;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE+10)
public class FiltroSessao extends OncePerRequestFilter {
    static final String ATRIBUTO=FiltroSessao.class.getName();
    private final SessaoService sessoes;
    public FiltroSessao(SessaoService sessoes){this.sessoes=sessoes;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return !FiltroChaveApi.normalizar(request.getRequestURI()).startsWith("/api/");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        String path=FiltroChaveApi.normalizar(request.getRequestURI());
        response.setHeader("Cache-Control","no-store");
        String token=request.getCookies()==null?null:Arrays.stream(request.getCookies()).filter(c->c.getName().equals(SessaoService.COOKIE)).map(Cookie::getValue).findFirst().orElse(null);
        var sessao=sessoes.carregar(token).orElse(null);
        if(path.equals("/api/auth/sessao") && request.getMethod().equals("GET")){chain.doFilter(request,response);return;}
        boolean entrada=path.equals("/api/auth/login") || path.equals("/api/auth/cadastro");
        if(sessao==null || (!entrada && sessao.usuario()==null)){erro(response,401,"Entre na sua conta.");return;}
        if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
            if(!origemPermitida(request) || !SessaoService.igual(sessao.csrf(),request.getHeader("X-Atlas-CSRF"))){erro(response,403,"Validação CSRF recusada.");return;}
        }
        request.setAttribute(ATRIBUTO,sessao);
        if(sessao.usuario()!=null) request.setAttribute(UsuarioAtual.ATRIBUTO,sessao.usuario());
        chain.doFilter(request,response);
    }
    private static boolean origemPermitida(HttpServletRequest r){
        if("cross-site".equals(r.getHeader("Sec-Fetch-Site"))) return false;
        String origin=r.getHeader("Origin"); if(origin==null) return true;
        try{
            var u=URI.create(origin); int port=u.getPort()<0?("https".equals(u.getScheme())?443:80):u.getPort();
            return u.getUserInfo()==null && u.getRawQuery()==null && u.getRawFragment()==null
                    && (u.getRawPath()==null || u.getRawPath().isEmpty())
                    && r.getScheme().equals(u.getScheme()) && r.getServerName().equalsIgnoreCase(u.getHost()) && r.getServerPort()==port;
        }catch(IllegalArgumentException e){return false;}
    }
    private static void erro(HttpServletResponse r,int status,String detail) throws IOException {
        r.setStatus(status);r.setContentType("application/problem+json");r.setCharacterEncoding("UTF-8");
        r.getWriter().write("{\"status\":"+status+",\"detail\":\""+detail+"\"}");
    }
}
