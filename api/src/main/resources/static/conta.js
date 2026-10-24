// Cookie de sessão é HttpOnly. Só o token CSRF fica na memória desta página.
let atlasCsrf;
let atlasUsuario;
function limparSessaoLocal() {
  atlasCsrf = undefined; atlasUsuario = undefined;
  document.querySelector('#workspace').hidden = true;
  for (const key of ['atlas-processamento','atlas-chave','atlas-usuario']) {
    try { sessionStorage.removeItem(key); } catch { /* armazenamento opcional */ }
  }
}
async function contaRequest(path,body) {
  const options = {credentials:'same-origin', signal:AbortSignal.timeout(15000)};
  if (body !== undefined) {
    options.method = 'POST';
    options.headers = {'Content-Type':'application/json','X-Atlas-CSRF':atlasCsrf || ''};
    options.body = JSON.stringify(body);
  }
  const response = await fetch(`/api/auth/${path}`,options);
  if (!response.ok) {
    let detail = 'Não foi possível concluir a solicitação.';
    try { detail = (await response.json()).detail || detail; } catch { /* mensagem fixa */ }
    throw new Error(detail);
  }
  return response.status === 204 ? null : response.json();
}
function mostrarCodigo(codigo) {
  document.querySelector('#recovery-code-value').textContent = codigo;
  document.querySelector('#recovery-panel').hidden = false;
}
document.querySelector('#recovery-ack').onclick = () => {
  document.querySelector('#recovery-panel').hidden = true;
  document.querySelector('#recovery-code-value').textContent = '';
};
function mostrarConta(estado) {
  atlasCsrf = estado.csrfToken;
  atlasUsuario = estado.usuario;
  if (estado.codigoRecuperacao) mostrarCodigo(estado.codigoRecuperacao);
  try {
    if (sessionStorage.getItem('atlas-usuario') !== (atlasUsuario?.id || '')) {
      sessionStorage.removeItem('atlas-processamento');
      document.querySelector('#resume-processing').hidden = true;
    }
    sessionStorage.removeItem('atlas-chave');
    sessionStorage.setItem('atlas-usuario',atlasUsuario?.id || '');
  } catch { /* armazenamento opcional */ }
  document.querySelector('#workspace').hidden = !atlasUsuario;
  document.querySelector('#auth-form').hidden = Boolean(atlasUsuario);
  document.querySelector('#account-tools').hidden = !atlasUsuario;
  document.querySelector('#account-email').textContent = atlasUsuario?.email || '';
  document.querySelector('#auth-state').textContent = atlasUsuario ? 'Conta conectada.' : 'Entre ou crie uma conta para continuar.';
  document.querySelector('#auth-password').value = '';
  if (atlasUsuario) mostrarUso(); else document.querySelector('#account-usage').textContent = '';
}
async function mostrarUso() {
  const campo = document.querySelector('#account-usage');
  try {
    const response = await fetch('/api/conta/uso', {credentials:'same-origin', signal:AbortSignal.timeout(15000)});
    if (!response.ok) { campo.textContent = ''; return; }
    const uso = await response.json();
    const mib = bytes => (bytes / (1024 * 1024)).toFixed(1);
    campo.textContent = `Em uso: ${uso.documentos} de ${uso.documentosLimite} documentos · ${mib(uso.bytesUsados)} de ${mib(uso.bytesLimite)} MiB`;
  } catch { campo.textContent = ''; }
}
async function carregarConta() {
  try { mostrarConta(await contaRequest('sessao')); }
  catch { document.querySelector('#auth-state').textContent = 'Não foi possível consultar sua sessão. Recarregue a página para tentar novamente.'; }
  document.querySelector('#auth-login').disabled = !atlasCsrf;
  document.querySelector('#auth-register').disabled = !atlasCsrf;
}
async function autenticar(path) {
  const form = document.querySelector('#auth-form'); if (!form.reportValidity()) return;
  const login = document.querySelector('#auth-login'), register = document.querySelector('#auth-register');
  if (login.disabled || register.disabled) return;
  login.disabled = true; register.disabled = true;
  try {
    // A pré-sessão vence em 30 minutos; renova cookie e token CSRF antes de enviar, para o login não ficar preso em 401/403.
    atlasCsrf = (await contaRequest('sessao')).csrfToken;
    mostrarConta(await contaRequest(path,{email:document.querySelector('#auth-email').value,senha:document.querySelector('#auth-password').value}));
  } catch(error) { document.querySelector('#auth-state').textContent = error.message || 'Falha na conexão. Tente novamente.'; }
  finally { login.disabled = false; register.disabled = false; }
}
document.querySelector('#auth-forgot').onclick = () => {
  document.querySelector('#recover-form').hidden = false;
  document.querySelector('#recover-email').value = document.querySelector('#auth-email').value;
};
document.querySelector('#recover-cancel').onclick = () => { document.querySelector('#recover-form').hidden = true; };
document.querySelector('#recover-form').onsubmit = async event => {
  event.preventDefault();
  const botao = document.querySelector('#recover-submit');
  if (botao.disabled) return;
  botao.disabled = true;
  try {
    atlasCsrf = (await contaRequest('sessao')).csrfToken;
    mostrarConta(await contaRequest('recuperar', {
      email: document.querySelector('#recover-email').value, codigo: document.querySelector('#recover-code').value,
      novaSenha: document.querySelector('#recover-password').value }));
    document.querySelector('#recover-form').hidden = true;
    document.querySelector('#recover-form').reset();
  } catch (error) { document.querySelector('#auth-state').textContent = error.message || 'Falha na conexão. Tente novamente.'; }
  finally { botao.disabled = false; }
};
document.querySelector('#renew-code').onclick = async event => {
  const botao = event.currentTarget, campo = document.querySelector('#renew-password');
  if (botao.disabled || !campo.value) return;
  botao.disabled = true;
  try {
    const resposta = await contaRequest('codigo-recuperacao', { senha: campo.value });
    mostrarCodigo(resposta.codigoRecuperacao);
    campo.value = '';
  } catch (error) { document.querySelector('#auth-state').textContent = error.message; }
  finally { botao.disabled = false; }
};
document.querySelector('#auth-form').onsubmit = event => { event.preventDefault(); autenticar('login'); };
document.querySelector('#auth-register').onclick = () => autenticar('cadastro');
document.querySelector('#auth-logout').onclick = async event => {
  const button = event.currentTarget; button.disabled = true;
  try {
    await contaRequest('logout',{});
    limparSessaoLocal();
    // Remove DOM, seleções e requisições pendentes da conta anterior, não só a identidade.
    location.replace('/');
  } catch(error) { document.querySelector('#auth-state').textContent = error.message; button.disabled = false; }
};
document.addEventListener('DOMContentLoaded',carregarConta);
