const selectedMeasures = new Set();
function selectionLabel() { document.querySelector('#comparison-selection').textContent = `${selectedMeasures.size} medida(s) selecionada(s).`; }
function cancelarRevisao() {
  reviewing = undefined;
  document.querySelector('#review-fields').hidden = true;
  document.querySelector('#review-reason').required = false;
  document.querySelector('#save-button').textContent = 'Salvar medida';
}
document.querySelector('#cancel-review').onclick = cancelarRevisao;

function adicionarAcoesMedida(article, measure) {
  const label = document.createElement('label'); const check = document.createElement('input'); check.type = 'checkbox';
  check.checked = selectedMeasures.has(measure.medidaId);
  check.onchange = () => { if (check.checked) selectedMeasures.add(measure.medidaId); else selectedMeasures.delete(measure.medidaId); selectionLabel(); };
  label.append(check, document.createTextNode('Selecionar para comparação')); article.append(label);
  const review = document.createElement('button'); review.type = 'button'; review.textContent = 'Ver atual / revisar';
  review.onclick = async () => {
    if (document.querySelector('#upload-button').disabled || document.querySelector('#save-button').disabled) return;
    review.disabled = true;
    document.querySelector('#upload-button').disabled = true;
    document.querySelector('#save-button').disabled = true;
    try {
      const current = await request(`/api/medidas/${measure.medidaId}`);
      const history = await request(`/api/medidas/${measure.medidaId}/revisoes`);
      const m = current.atual.medida;
      reviewing = current;
      for (const [field, key] of Object.entries({ object: 'objeto', metric: 'metrica', value: 'valorOriginal', unit: 'unidadeOriginal', state: 'estado', excerpt: 'trecho' })) {
        document.getElementById(field).value = m[key] ?? '';
      }
      document.querySelector('#conditions').value = JSON.stringify(m.condicoes);
      document.querySelector('#evidence-page').value = m.pagina ?? '';
      document.querySelector('#review-fields').hidden = false;
      document.querySelector('#review-reason').required = true;
      document.querySelector('#save-button').textContent = 'Salvar revisão';
      document.querySelector('#review-history').textContent = JSON.stringify({ original: current.original, revisoes: history }, null, 2);
      updateMeasureState();
      showStatus(`Revisando a versão ${current.versao}. Complete o motivo antes de salvar. A autoria vem da sua conta.`);
      document.querySelector('#candidate').scrollIntoView({ block: 'start' });
    } catch (error) { showStatus(error.message, true); }
    finally { review.disabled = false; document.querySelector('#upload-button').disabled = false; document.querySelector('#save-button').disabled = false; }
  };
  article.append(review);
}

document.querySelector('#clear-comparison').onclick = () => {
  selectedMeasures.clear(); selectionLabel();
  document.querySelectorAll('#measure-list input[type=checkbox]').forEach(input => { input.checked = false; });
  document.querySelector('#comparison-result').replaceChildren();
};

document.querySelector('#compare-measures').onclick = async event => {
  const status = document.querySelector('#comparison-status'); const button = event.currentTarget;
  if (selectedMeasures.size < 2 || selectedMeasures.size > 20) { status.textContent = 'Escolha entre 2 e 20 medidas distintas.'; return; }
  button.disabled = true;
  try {
    const result = await request(`/api/comparacoes?${new URLSearchParams({ medidas: [...selectedMeasures].join(',') })}`);
    status.textContent = result.podeAlinharNumericamente ? 'Os campos declarados permitem alinhamento numérico.' : result.motivos.join(' ');
    const table = document.createElement('table'); const head = document.createElement('tr');
    ['Objeto / métrica', 'Valor original', 'Normalizado', 'Estado / revisão', 'Condições', 'Evidência'].forEach(text => { const th = document.createElement('th'); th.textContent = text; head.append(th); });
    table.append(head);
    for (const line of result.linhas) {
      const row = document.createElement('tr'); const r = line.registro; const m = r.atual.medida;
      [`${m.objeto} / ${m.metrica}`, `${m.valorOriginal ?? 'Ausente'} ${m.unidadeOriginal ?? ''}`,
        line.normalizado ? `${line.normalizado.valor} ${line.normalizado.unidade}` : 'Sem conversão', `${m.estado} / v${r.versao}`, JSON.stringify(m.condicoes)].forEach(text => {
        const cell = document.createElement('td'); cell.textContent = text; row.append(cell);
      });
      const cell = document.createElement('td');
      for (const evidence of r.atual.evidencias) {
        const source = document.createElement('button'); source.textContent = `Página ${evidence.pagina}: ${evidence.trecho}`;
        source.onclick = async () => {
          if (document.querySelector('#upload-button').disabled || document.querySelector('#save-button').disabled) return;
          document.querySelector('#upload-button').disabled = true; document.querySelector('#save-button').disabled = true;
          const anterior = { documento: documentId, execucao: executionId };
          try { documentId = r.documentoId; cancelarRevisao(); await mostrarExecucao({ execucaoId: r.execucaoId, estado: 'CONCLUIDO' }); showEvidence(evidence); }
          catch (error) { documentId = anterior.documento; executionId = anterior.execucao; showStatus(error.message,true); }
          finally { document.querySelector('#upload-button').disabled = false; document.querySelector('#save-button').disabled = false; }
        };
        cell.append(source);
      }
      row.append(cell); table.append(row);
    }
    const output = document.querySelector('#comparison-result'); output.replaceChildren(table); output.style.overflowX = 'auto';
  } catch (error) { status.textContent = error.message; }
  finally { button.disabled = false; }
};
