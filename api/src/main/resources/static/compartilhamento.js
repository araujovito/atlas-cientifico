// Compartilhamento do PDF original. Usa documentId e request() da página principal.
const shareState = document.querySelector('#share-state');
const shareToggle = document.querySelector('#share-toggle');
const shareDownload = document.querySelector('#share-download');
let shareCurrent;

function mostrarCompartilhamento(valor) {
  shareCurrent = valor;
  const publico = valor === 'PUBLICO', bloqueado = valor === 'BLOQUEADO';
  shareState.textContent = bloqueado ? 'O operador bloqueou o compartilhamento deste PDF.'
    : publico ? 'Este PDF é público: outras contas podem baixá-lo.' : 'Este PDF é privado: só você o baixa.';
  shareToggle.textContent = publico ? 'Tornar privado' : 'Tornar público';
  shareToggle.disabled = bloqueado;
}

async function carregarCompartilhamento() {
  if (!documentId) return;
  const atual = documentId;
  shareDownload.href = `/api/documentos/${atual}/original`;
  try {
    const estado = await request(`/api/documentos/${atual}/compartilhamento`);
    if (atual === documentId) mostrarCompartilhamento(estado.compartilhamento);
  } catch (error) { shareState.textContent = error.message; }
}

shareToggle.onclick = async () => {
  if (shareToggle.disabled || !documentId) return;
  shareToggle.disabled = true;
  const atual = documentId;
  try {
    const estado = await request(`/api/documentos/${atual}/compartilhamento`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ publico: shareCurrent !== 'PUBLICO' })
    });
    if (atual === documentId) mostrarCompartilhamento(estado.compartilhamento);
  } catch (error) { shareState.textContent = error.message; shareToggle.disabled = false; }
};

// A seção de extração aparece quando um documento é carregado; atualiza o estado nesse momento.
new MutationObserver(() => { if (!document.querySelector('#extraction').hidden) carregarCompartilhamento(); })
  .observe(document.querySelector('#extraction'), { attributes: true, attributeFilter: ['hidden'] });

document.querySelector('#public-refresh').onclick = async event => {
  const button = event.currentTarget, status = document.querySelector('#public-status'), list = document.querySelector('#public-list');
  if (button.disabled) return;
  button.disabled = true;
  try {
    const itens = await request('/api/publico/documentos?limite=50');
    list.replaceChildren();
    status.textContent = itens.length ? `${itens.length} documento(s) público(s).` : 'Nenhum documento público ainda.';
    for (const item of itens) {
      const p = document.createElement('p');
      const link = document.createElement('a');
      link.href = `/api/publico/documentos/${encodeURIComponent(item.id)}/original`;
      link.setAttribute('download', '');
      link.textContent = `${item.titulo || 'Sem título'} (${(item.tamanhoBytes / 1024).toFixed(0)} KiB)`;
      p.append(link); list.append(p);
    }
  } catch (error) { status.textContent = error.message; }
  finally { button.disabled = false; }
};
