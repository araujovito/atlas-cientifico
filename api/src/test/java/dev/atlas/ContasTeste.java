package dev.atlas;

import dev.atlas.acesso.SessaoService;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

final class ContasTeste {
    static final String SENHA="Senha de teste longa 123!";
    record Cliente(Cookie cookie,String csrf,UUID id,String email) {}
    static Cliente pre(MockMvc mvc) throws Exception {
        var resposta=mvc.perform(get("/api/auth/sessao")).andExpect(status().isOk()).andReturn().getResponse();
        var json=new ObjectMapper().readTree(resposta.getContentAsString());
        return new Cliente(resposta.getCookie(SessaoService.COOKIE),json.get("csrfToken").asText(),null,null);
    }
    static Cliente cadastrar(MockMvc mvc,String email) throws Exception {
        var pre=pre(mvc);
        var resposta=mvc.perform(com(post("/api/auth/cadastro"),pre).contentType("application/json")
                .content(new ObjectMapper().writeValueAsString(java.util.Map.of("email",email,"senha",SENHA))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var json=new ObjectMapper().readTree(resposta.getContentAsString());
        return new Cliente(resposta.getCookie(SessaoService.COOKIE),json.get("csrfToken").asText(),UUID.fromString(json.get("usuario").get("id").asText()),email.toLowerCase());
    }
    static MockHttpServletRequestBuilder com(MockHttpServletRequestBuilder pedido,Cliente cliente){
        return pedido.cookie(cliente.cookie()).header("X-Atlas-CSRF",cliente.csrf());
    }
}
