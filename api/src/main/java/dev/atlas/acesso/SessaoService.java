package dev.atlas.acesso;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SessaoService {
    public static final String COOKIE="__Host-atlas-sessao";
    public record Usuario(UUID id,String email) {}
    public record Sessao(String hash,Usuario usuario,String csrf,Instant expiraEm) {}
    public record Criada(String token,Sessao sessao) {}
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PoliticaContas politica;
    private final SecureRandom random=new SecureRandom();
    // Argon2id: 19 MiB, duas iterações e paralelismo 1. Custo não calibrado sob carga.
    private final Argon2PasswordEncoder encoder=new Argon2PasswordEncoder(16,32,1,19456,2);
    private final String hashInexistente=encoder.encode("somente-para-equalizar-o-caminho-de-login");

    public SessaoService(JdbcClient jdbc,TransactionTemplate tx,PoliticaContas politica){this.jdbc=jdbc;this.tx=tx;this.politica=politica;}
    public Optional<Sessao> carregar(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return jdbc.sql("""
                SELECT s.*,u.email FROM sessao s LEFT JOIN usuario u ON u.id=s.usuario_id
                WHERE s.token_hash=:hash AND s.expira_em>now() AND (s.usuario_id IS NULL OR u.ativo)
                """).param("hash",hash(token)).query((rs,n)->new Sessao(rs.getString("token_hash"),
                    rs.getObject("usuario_id")==null?null:new Usuario(rs.getObject("usuario_id",UUID.class),rs.getString("email")),
                    rs.getString("csrf_token"),rs.getTimestamp("expira_em").toInstant())).optional();
    }
    public Criada criar(Usuario usuario) {
        String token=token(),csrf=HexFormat.of().formatHex(bytes());
        Instant expira=Instant.now().plusSeconds(usuario==null?1800:28800);
        jdbc.sql("INSERT INTO sessao(token_hash,usuario_id,csrf_token,expira_em) VALUES (:h,:u,:c,:e)")
                .param("h",hash(token)).param("u",usuario==null?null:usuario.id()).param("c",csrf)
                .param("e",java.sql.Timestamp.from(expira)).update();
        return new Criada(token,new Sessao(hash(token),usuario,csrf,expira));
    }
    public Criada cadastrar(String email,String senha,Sessao anterior) {
        String normal=email(email); validarSenha(senha);
        boolean permitido=politica.cadastro()==PoliticaContas.Cadastro.ABERTO
                || politica.cadastro()==PoliticaContas.Cadastro.LISTA && politica.emailsPermitidos().stream().anyMatch(e->e.equalsIgnoreCase(normal));
        if(!permitido) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cadastro não disponível para este e-mail.");
        String senhaHash=encoder.encode(senha);
        return tx.execute(s->{
            var id=jdbc.sql("INSERT INTO usuario(email,senha_hash) VALUES (:e,:h) ON CONFLICT(email) DO NOTHING RETURNING id")
                    .param("e",normal).param("h",senhaHash).query(UUID.class).optional();
            if(id.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Não foi possível cadastrar esta conta.");
            revogar(anterior); return criar(new Usuario(id.get(),normal));
        });
    }
    public Criada entrar(String email,String senha,Sessao anterior) {
        String normal=email(email); validarSenha(senha);
        record Conta(UUID id,String email,String hash,boolean ativo) {}
        var conta=jdbc.sql("SELECT * FROM usuario WHERE email=:e").param("e",normal)
                .query((rs,n)->new Conta(rs.getObject("id",UUID.class),rs.getString("email"),rs.getString("senha_hash"),rs.getBoolean("ativo"))).optional();
        String hash=conta.filter(Conta::ativo).map(Conta::hash).filter(h->h.startsWith("$argon2id$")).orElse(hashInexistente);
        boolean senhaCorreta=encoder.matches(senha,hash);
        if(!senhaCorreta || conta.isEmpty() || !conta.get().ativo())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"E-mail ou senha incorretos.");
        return tx.execute(s->{revogar(anterior);return criar(new Usuario(conta.get().id(),normal));});
    }
    public void revogar(Sessao sessao){jdbc.sql("DELETE FROM sessao WHERE token_hash=:h").param("h",sessao.hash()).update();}
    public static boolean igual(String a,String b){return a!=null && b!=null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    public static String hash(String token){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private byte[] bytes(){var b=new byte[32];random.nextBytes(b);return b;}
    private String token(){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes());}
    private static String email(String email){
        if(email==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Informe um e-mail válido.");
        String normal=email.strip().toLowerCase(Locale.ROOT);
        if(normal.length()>254 || !normal.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || normal.endsWith(".invalid"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Informe um e-mail válido.");
        return normal;
    }
    private static void validarSenha(String senha){
        if(senha==null || senha.length()<12 || senha.length()>128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A senha deve ter entre 12 e 128 caracteres.");
    }
}
