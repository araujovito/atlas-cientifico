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
function mostrarConta(estado) {
  atlasCsrf = estado.csrfToken;
  atlasUsuario = estado.usuario;
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
    mostrarConta(await contaRequest(path,{email:document.querySelector('#auth-email').value,senha:document.querySelector('#auth-password').value}));
  } catch(error) { document.querySelector('#auth-state').textContent = error.message || 'Falha na conexão. Tente novamente.'; }
  finally { login.disabled = false; register.disabled = false; }
}
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
