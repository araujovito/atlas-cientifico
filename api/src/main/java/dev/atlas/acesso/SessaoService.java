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
    /** Nome do cookie sem o prefixo __Host-, que só existe com Secure. Usado apenas quando cookie-seguro=false. */
    public static final String COOKIE_SEM_TLS="atlas-sessao";
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SessaoService.class);
    private final boolean cookieSeguro;
    public record Usuario(UUID id,String email) {}
    public record Sessao(String hash,Usuario usuario,String csrf,Instant expiraEm) {}
    public record Criada(String token,Sessao sessao) {}
    /** Sessão nova mais o código de recuperação, que só existe em claro neste retorno. */
    public record ComCodigo(Criada criada,String codigo) {}
    private static final String ALFABETO="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final PoliticaContas politica;
    private final SecureRandom random=new SecureRandom();
    // Argon2id: 19 MiB, duas iterações e paralelismo 1. Custo não calibrado sob carga.
    private final Argon2PasswordEncoder encoder=new Argon2PasswordEncoder(16,32,1,19456,2);
    private final String hashInexistente=encoder.encode("somente-para-equalizar-o-caminho-de-login");

    public SessaoService(JdbcClient jdbc,TransactionTemplate tx,PoliticaContas politica,
            @org.springframework.beans.factory.annotation.Value("${atlas.contas.cookie-seguro:true}") boolean cookieSeguro){
        this.jdbc=jdbc;this.tx=tx;this.politica=politica;this.cookieSeguro=cookieSeguro;
        if(!cookieSeguro) log.warn("atlas.contas.cookie-seguro=false: o cookie de sessão sai SEM Secure e SEM o prefixo __Host-. Use só em http://localhost, para navegadores que recusam Secure sem HTTPS (Safari). Nunca em produção.");
    }
    public boolean cookieSeguro(){return cookieSeguro;}
    public String nomeCookie(){return cookieSeguro?COOKIE:COOKIE_SEM_TLS;}
    public Optional<Sessao> carregar(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return jdbc.sql("""
                SELECT s.*,u.email FROM sessao s LEFT JOIN usuario u ON u.id=s.usuario_id
                WHERE s.token_hash=:hash AND s.expira_em>now() AND (s.usuario_id IS NULL OR u.ativo)
                """).param("hash",hash(token)).query((rs,n)->new Sessao(rs.getString("token_hash"),
                    rs.getObject("usuario_id")==null?null:new Usuario(rs.getObject("usuario_id",UUID.class),rs.getString("email")),
                    rs.getString("csrf_token"),rs.getTimestamp("expira_em").toInstant())).optional();
    }
    /** Teto de pré-sessões anônimas vivas: sem ele, um laço de GET /api/auth/sessao enche a tabela. */
    public static final int ANONIMAS_MAXIMAS=5000;

    /** Remove sessões vencidas; sem isto as linhas nunca saem da tabela. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=600_000,initialDelay=60_000)
    public int limparExpiradas(){return jdbc.sql("DELETE FROM sessao WHERE expira_em<=now()").update();}

    public Criada criar(Usuario usuario) {
        if(usuario==null && jdbc.sql("SELECT count(*) FROM sessao WHERE usuario_id IS NULL AND expira_em>now()").query(Long.class).single()>=ANONIMAS_MAXIMAS)
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Muitas sessões abertas. Tente novamente em alguns minutos.");
        String token=token(),csrf=HexFormat.of().formatHex(bytes());
        Instant expira=Instant.now().plusSeconds(usuario==null?1800:28800);
        jdbc.sql("INSERT INTO sessao(token_hash,usuario_id,csrf_token,expira_em) VALUES (:h,:u,:c,:e)")
                .param("h",hash(token)).param("u",usuario==null?null:usuario.id()).param("c",csrf)
                .param("e",java.sql.Timestamp.from(expira)).update();
        return new Criada(token,new Sessao(hash(token),usuario,csrf,expira));
    }
    public ComCodigo cadastrar(String email,String senha,Sessao anterior) {
        String normal=email(email); validarSenha(senha);
        if(!politica.permite(normal)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Cadastro não disponível para este e-mail.");
        String senhaHash=encoder.encode(senha),codigo=novoCodigo();
        return tx.execute(s->{
            var id=jdbc.sql("INSERT INTO usuario(email,senha_hash,recuperacao_hash) VALUES (:e,:h,:r) ON CONFLICT(email) DO NOTHING RETURNING id")
                    .param("e",normal).param("h",senhaHash).param("r",hashCodigo(codigo)).query(UUID.class).optional();
            if(id.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Não foi possível cadastrar esta conta.");
            revogar(anterior); return new ComCodigo(criar(new Usuario(id.get(),normal)),codigo);
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
    /** Troca a senha com o código de recuperação; encerra todas as sessões da conta e devolve um código NOVO. */
    public ComCodigo recuperar(String email,String codigo,String novaSenha,Sessao anterior) {
        String normal=email(email); validarSenha(novaSenha);
        var conta=jdbc.sql("SELECT id,recuperacao_hash FROM usuario WHERE email=:e AND ativo")
                .param("e",normal).query((rs,n)->Map.entry(rs.getObject("id",UUID.class),Optional.ofNullable(rs.getString("recuperacao_hash")))).optional();
        byte[] enviado=hashCodigo(codigo==null?"":codigo).getBytes(StandardCharsets.UTF_8);
        // Compara sempre, mesmo sem conta ou sem código, para o tempo não distinguir os casos.
        byte[] guardado=conta.flatMap(Map.Entry::getValue).orElse("0".repeat(64)).strip().getBytes(StandardCharsets.UTF_8);
        if(conta.isEmpty() || conta.get().getValue().isEmpty() || !MessageDigest.isEqual(enviado,guardado))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"E-mail ou código de recuperação incorretos.");
        String senhaHash=encoder.encode(novaSenha),novo=novoCodigo(); UUID id=conta.get().getKey();
        return tx.execute(s->{
            jdbc.sql("UPDATE usuario SET senha_hash=:h,recuperacao_hash=:r WHERE id=:id").param("h",senhaHash).param("r",hashCodigo(novo)).param("id",id).update();
            jdbc.sql("DELETE FROM sessao WHERE usuario_id=:id").param("id",id).update();
            revogar(anterior); return new ComCodigo(criar(new Usuario(id,normal)),novo);
        });
    }
    /** Gera outro código para quem já está logado, exigindo a senha atual; o anterior deixa de valer. */
    public String renovarCodigo(UUID usuario,String senhaAtual) {
        var hash=jdbc.sql("SELECT senha_hash FROM usuario WHERE id=:id AND ativo").param("id",usuario).query(String.class).optional();
        boolean ok=encoder.matches(senhaAtual==null?"":senhaAtual,hash.filter(h->h.startsWith("$argon2id$")).orElse(hashInexistente));
        if(!ok || hash.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Senha incorreta.");
        String novo=novoCodigo();
        jdbc.sql("UPDATE usuario SET recuperacao_hash=:r WHERE id=:id").param("r",hashCodigo(novo)).param("id",usuario).update();
        return novo;
    }
    /** 20 caracteres de um alfabeto de 32 (100 bits), em grupos de 4; sem 0/O/1/I para não confundir ao copiar. */
    private String novoCodigo(){
        var b=new StringBuilder();
        for(int i=0;i<20;i++){ if(i>0 && i%4==0) b.append('-'); b.append(ALFABETO.charAt(random.nextInt(32))); }
        return b.toString();
    }
    static String hashCodigo(String codigo){
        return hash(codigo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]",""));
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
