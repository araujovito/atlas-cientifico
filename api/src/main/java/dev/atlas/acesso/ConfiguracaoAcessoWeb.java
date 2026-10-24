package dev.atlas.acesso;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ConfiguracaoAcessoWeb implements WebMvcConfigurer {
    private final AutorizacaoRecursos autorizacao;
    public ConfiguracaoAcessoWeb(AutorizacaoRecursos autorizacao){this.autorizacao=autorizacao;}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(autorizacao).addPathPatterns("/api/**");}
}
