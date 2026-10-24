document.querySelector('#operation-refresh').onclick = async () => {
  const status = document.querySelector('#operation-status');
  const failures = document.querySelector('#operation-failures');
  const refresh = document.querySelector('#operation-refresh');
  if (refresh.disabled) return;
  refresh.disabled = true;
  failures.replaceChildren();
  try {
    const summary = await request('/api/operacao');
    status.textContent = summary.length ? summary.map(s => `${s.estado}: ${s.quantidade} (${s.reservasExpiradas} reserva(s) expirada(s))`).join(' · ') : 'Nenhuma tarefa registrada.';
    const jobs = await request('/api/processamentos?estado=FALHOU&limite=50');
    for (const job of jobs) {
      const article = document.createElement('article');
      const text = document.createElement('p'); text.textContent = `${job.id} · ${job.tentativas} tentativa(s) · ${job.detalhe || 'Sem detalhe'}`;
      const retry = document.createElement('button'); retry.textContent = 'Reativar tarefa';
      retry.onclick = async () => {
        retry.disabled = true;
        try { await request(`/api/processamentos/${job.id}/retentar`, {method:'POST'}); article.remove(); status.textContent = 'Tarefa reativada. Atualize o painel para conferir o resultado.'; }
        catch(error) { status.textContent = error.message; retry.disabled = false; }
      };
      article.append(text,retry); failures.append(article);
    }
  } catch(error) { status.textContent = error.message; }
  finally { refresh.disabled = false; }
};
