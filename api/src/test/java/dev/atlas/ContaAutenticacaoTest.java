package dev.atlas;

import dev.atlas.acesso.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static dev.atlas.ContasTeste.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties={"atlas.fila.ativa=false","atlas.contas.cadastro=ABERTO"})
@AutoConfigureMockMvc
class ContaAutenticacaoTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired SessaoService sessoes;
    private void inserirAnonimas(int quantidade,String expiraEm){
        jdbc.sql("INSERT INTO sessao(token_hash,usuario_id,csrf_token,criado_em,expira_em) SELECT encode(sha256(gen_random_uuid()::text::bytea),'hex'),NULL,'c',now()-interval '1 hour',"+expiraEm
                +" FROM generate_series(1,:n)").param("n",quantidade).update();
    }
    @Test void limpezaRemoveSessoesVencidasEMantemAsVivas() {
        inserirAnonimas(3,"now()-interval '1 minute'");
        var viva=sessoes.criar(null);
        assertThat(sessoes.limparExpiradas()).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM sessao WHERE expira_em<=now()").query(Long.class).single()).isZero();
        assertThat(sessoes.carregar(viva.token())).isPresent();
    }
    @Test void tetoDePreSessoesAnonimasRecusaComServicoIndisponivel() {
        long vivas=jdbc.sql("SELECT count(*) FROM sessao WHERE usuario_id IS NULL AND expira_em>now()").query(Long.class).single();
        inserirAnonimas((int)(SessaoService.ANONIMAS_MAXIMAS-vivas),"now()+interval '5 minutes'");
        try {
            assertThatThrownBy(() -> sessoes.criar(null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(e -> assertThat(((org.springframework.web.server.ResponseStatusException)e).getStatusCode().value()).isEqualTo(503));
        } finally {
            jdbc.sql("DELETE FROM sessao WHERE usuario_id IS NULL AND csrf_token='c'").update();
        }
    }
    @Test void cadastraComArgon2idECookieERotacionaSessaoNoLogin() throws Exception {
        String email="conta-"+UUID.randomUUID()+"@example.com";
        var c=cadastrar(mvc,email);
        String hash=jdbc.sql("SELECT senha_hash FROM usuario WHERE id=:u").param("u",c.id()).query(String.class).single();
        assertThat(hash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(SENHA);
        String armazenado=jdbc.sql("SELECT token_hash FROM sessao WHERE usuario_id=:u").param("u",c.id()).query(String.class).single();
        assertThat(armazenado).isEqualTo(SessaoService.hash(c.cookie().getValue())).isNotEqualTo(c.cookie().getValue());
        mvc.perform(com(get("/api/auth/sessao"),c)).andExpect(jsonPath("$.usuario.email").value(email));
        var resposta=mvc.perform(com(post("/api/auth/login"),c).contentType("application/json")
                .content("{\"email\":\""+email.toUpperCase()+"\",\"senha\":\""+SENHA+"\"}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(resposta.getHeader("Set-Cookie")).contains("HttpOnly","Secure","SameSite=Strict","Path=/").doesNotContain("Domain=");
        assertThat(resposta.getCookie(SessaoService.COOKIE).getValue()).isNotEqualTo(c.cookie().getValue());
        assertThat(sessoes.carregar(c.cookie().getValue())).isEmpty();
    }
    @Test void csrfEOrigemValemTambemParaLoginECadastro() throws Exception {
        var p=pre(mvc);
        for(String path:new String[]{"/api/auth/cadastro","/api/auth/login"}) {
            mvc.perform(post(path).cookie(p.cookie()).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
            mvc.perform(com(post(path),p).header("Origin","https://outro.example").contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/auth/login").header("X-Atlas-Chave","chave-qualquer").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/colecoes").header("X-Atlas-Chave","chave-qualquer")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api;x=1/colecoes")).andExpect(status().isUnauthorized());
    }
    @Test void logoutRevogaESessaoExpiradaNaoAutentica() throws Exception {
        var c=cadastrar(mvc,"revoga-"+UUID.randomUUID()+"@example.com");
        mvc.perform(post("/api/auth/logout").cookie(c.cookie())).andExpect(status().isForbidden());
        mvc.perform(com(post("/api/auth/logout"),c)).andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(com(get("/api/colecoes"),c)).andExpect(status().isUnauthorized());
        var d=cadastrar(mvc,"expira-"+UUID.randomUUID()+"@example.com");
        jdbc.sql("UPDATE sessao SET criado_em=now()-interval '2 days',expira_em=now()-interval '1 day' WHERE usuario_id=:u").param("u",d.id()).update();
        mvc.perform(com(get("/api/colecoes"),d)).andExpect(status().isUnauthorized());
    }
    @Test void contaLegadaNaoPodeSerReclamadaESenhaErradaNaoEntra() throws Exception {
        var c=cadastrar(mvc,"senha-"+UUID.randomUUID()+"@example.com");
        mvc.perform(com(post("/api/auth/login"),c).contentType("application/json")
                .content("{\"email\":\""+c.email()+"\",\"senha\":\"Uma senha errada longa\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(com(post("/api/auth/cadastro"),c).contentType("application/json")
                .content("{\"email\":\"operador-legado@atlas.invalid\",\"senha\":\""+SENHA+"\"}"))
                .andExpect(status().isBadRequest());
    }
}
