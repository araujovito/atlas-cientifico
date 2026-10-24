package dev.atlas.acesso;

import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ConfiguracaoAcessoWeb implements WebMvcConfigurer {
    private final AutorizacaoRecursos autorizacao;
    private final LimiteDeUso uso;
    public ConfiguracaoAcessoWeb(AutorizacaoRecursos autorizacao,LimiteDeUso uso){this.autorizacao=autorizacao;this.uso=uso;}
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(autorizacao).addPathPatterns("/api/**").order(0);
        // Depois da autorização: recurso alheio responde 404 sem gastar o limite de quem perguntou.
        registry.addInterceptor(uso).addPathPatterns("/api/**").order(1);
    }
}
