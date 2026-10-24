// Protótipo de um operador: todas as operações usam a chave compartilhada da tela.
const libraryStatus = document.querySelector('#library-status');
let libraryOffset = 0;
let searchPage = 1;
let discoveryQuery;

function libraryMessage(text, error = false) {
  libraryStatus.textContent = text;
  libraryStatus.className = error ? 'error' : 'success';
}

function bindAction(selector, action, eventName = 'click') {
  document.querySelector(selector).addEventListener(eventName, async event => {
    event.preventDefault();
    const control = event.currentTarget;
    const button = control.tagName === 'FORM' ? control.querySelector('button') : control;
    if (button.disabled) return;
    button.disabled = true;
    let preserveDisabled = false;
    try { preserveDisabled = await action() === false; } catch (error) { libraryMessage(error.message, true); }
    finally { if (!preserveDisabled) button.disabled = false; }
  });
}

async function importWork(work) {
  const saved = await request('/api/catalogo/obras', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ doi: work.doi })
  });
  libraryMessage(`Obra salva: ${saved.titulo}`);
  await refreshLibrary();
}

bindAction('#save-work', async () => { if (workToSave) await importWork(workToSave); });

async function refreshLibrary() {
  const works = await request(`/api/catalogo/obras?limite=20&deslocamento=${libraryOffset}`);
  renderLibrary(works);
  document.querySelector('#library-previous').disabled = libraryOffset === 0;
  document.querySelector('#library-next').disabled = works.length < 20;
  const collections = await request('/api/colecoes?limite=100');
  const select = document.querySelector('#collection-select');
  const previous = select.value;
  select.replaceChildren(...collections.map(collection => {
    const option = document.createElement('option'); option.value = collection.id; option.textContent = collection.nome; return option;
  }));
  if (collections.some(item => item.id === previous)) select.value = previous;
}

function renderLibrary(works) {
  const output = document.querySelector('#library-list'); output.replaceChildren();
  if (!works.length) output.textContent = 'Nenhuma obra nesta página.';
  works.forEach(work => {
    const article = document.createElement('article'); article.dataset.workId = work.id;
    const title = document.createElement('h3'); title.textContent = work.titulo;
    const ids = document.createElement('p'); ids.textContent = work.identificadores.map(id => `${id.tipo}: ${id.valor}`).join(' · ');
    article.append(title, ids);
    const choose = document.createElement('button'); choose.textContent = 'Usar esta obra para o PDF';
    choose.onclick = () => {
      if (document.querySelector('#upload-button').disabled || document.querySelector('#save-button').disabled) return;
      activeWorkId = work.id; document.querySelector('#active-work').textContent = `Obra do próximo PDF: ${work.titulo}`;
    };
    const add = document.createElement('button'); add.textContent = 'Adicionar à coleção';
    add.onclick = async () => {
      const collection = document.querySelector('#collection-select').value;
      if (!collection) return libraryMessage('Crie ou escolha uma coleção.', true);
      add.disabled = true;
      try { await request(`/api/colecoes/${collection}/obras/${work.id}`, { method: 'PUT' }); libraryMessage('Obra adicionada à coleção.'); }
      catch (error) { libraryMessage(error.message,true); } finally { add.disabled = false; }
    };
    article.append(choose, add); output.append(article);
  });
}

bindAction('#library-refresh', async () => { libraryOffset = 0; await refreshLibrary(); });
async function changeLibrary(delta) {
  const previous = libraryOffset; libraryOffset = Math.max(0,libraryOffset+delta);
  try { await refreshLibrary(); } catch (error) { libraryOffset = previous; throw error; }
  return false;
}
bindAction('#library-previous', () => changeLibrary(-20));
bindAction('#library-next', () => changeLibrary(20));
bindAction('#collection-form', async () => {
  await request('/api/colecoes', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ nome: document.querySelector('#collection-name').value }) });
  document.querySelector('#collection-form').reset(); await refreshLibrary(); libraryMessage('Coleção criada.');
}, 'submit');
bindAction('#collection-show', async () => {
  const id = document.querySelector('#collection-select').value;
  if (!id) return libraryMessage('Escolha uma coleção.',true);
  renderLibrary(await request(`/api/colecoes/${id}/obras?limite=100`));
  document.querySelector('#library-previous').disabled = true; document.querySelector('#library-next').disabled = true;
});
bindAction('#unlink-work', async () => { activeWorkId = undefined; document.querySelector('#active-work').textContent = 'O próximo PDF será enviado sem vínculo com uma obra.'; });

bindAction('#author-form', async () => {
  const query = new URLSearchParams({ nome: document.querySelector('#author-name').value });
  const authors = await request(`/api/catalogo/obras/autores?${query}`);
  const select = document.querySelector('#author-filter');
  select.replaceChildren(new Option('Todos os autores',''), ...authors.map(author => new Option(`${author.nome || 'Nome não informado'} (${author.id.split('/').at(-1)})`,author.id)));
  libraryMessage(authors.length ? 'Selecione o autor e execute a busca por filtros.' : 'Nenhum autor encontrado.');
}, 'submit');

async function discover() {
  const params = new URLSearchParams({ ...discoveryQuery, pagina: String(searchPage), tamanho: '10' });
  const output = document.querySelector('#discovery-results'); output.replaceChildren();
  const result = await request(`/api/catalogo/obras/busca?${params}`);
  document.querySelector('#discovery-page').textContent = `Página ${result.pagina} · ${result.total} resultado(s)`;
  for (const work of result.resultados) {
    const article = document.createElement('article'); const title = document.createElement('h3'); title.textContent = work.titulo || work.doi;
    const authors = document.createElement('p'); authors.textContent = `${work.autores.join('; ')} · ${work.ano ?? 'Ano não informado'}`;
    const save = document.createElement('button'); save.textContent = 'Salvar no catálogo';
    save.onclick = async () => { save.disabled = true; try { await importWork(work); } catch (error) { libraryMessage(error.message,true); } finally { save.disabled = false; } };
    article.append(title,authors,save); output.append(article);
  }
  document.querySelector('#search-previous').disabled = searchPage === 1;
  document.querySelector('#search-next').disabled = result.pagina*result.tamanho >= result.total || result.resultados.length < 10;
}
bindAction('#discovery-form', async () => {
  discoveryQuery = { tema: document.querySelector('#theme').value, autor: document.querySelector('#author-filter').value };
  const year = document.querySelector('#search-year').value; if (year) discoveryQuery.ano = year;
  searchPage = 1; await discover();
}, 'submit');
async function changeSearch(delta) {
  const previous = searchPage; searchPage = Math.max(1,searchPage+delta);
  try { await discover(); } catch (error) { searchPage = previous; throw error; }
  return false;
}
bindAction('#search-previous', () => changeSearch(-1));
bindAction('#search-next', () => changeSearch(1));
