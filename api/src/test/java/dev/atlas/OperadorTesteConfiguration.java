package dev.atlas;

import dev.atlas.acesso.SessaoService;
import jakarta.servlet.http.Cookie;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Só testes históricos: sessão real do fixture legado, não desabilita filtros. */
@TestConfiguration(proxyBeanMethods=false)
class OperadorTesteConfiguration {
    @Bean MockMvcBuilderCustomizer operadorMockMvc(JdbcClient jdbc,SessaoService sessoes){
        jdbc.sql("UPDATE usuario SET ativo=true WHERE id=:u").param("u",ContasTeste.LEGADO).update();
        var sessao=sessoes.criar(new SessaoService.Usuario(ContasTeste.LEGADO,"operador-legado@atlas.invalid"));
        return builder->builder.defaultRequest(get("/").cookie(new Cookie(SessaoService.COOKIE,sessao.token()))
                .header("X-Atlas-CSRF",sessao.sessao().csrf()));
    }
}
