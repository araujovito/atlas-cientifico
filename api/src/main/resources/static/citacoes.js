let citationWork;
let citationOffset = 0;
let citationRequest = 0;
async function abrirCitacoes(work, refresh = false, offset = 0) {
  const currentRequest = ++citationRequest;
  const status = document.querySelector('#citation-status');
  status.textContent = 'Consultando referências…';
  // Enquanto outra obra carrega, paginar o grafo antigo cancelaria a carga nova (a numeração acima descarta a mais velha).
  const controles = ['citation-previous','citation-next'].map(id => document.getElementById(id));
  const estadoAnterior = controles.map(control => control.disabled);
  controles.forEach(control => { control.disabled = true; });
  try {
    if (refresh) await request(`/api/catalogo/obras/${work}/citacoes`, { method: 'POST' });
    const graph = await request(`/api/catalogo/obras/${work}/citacoes?limite=10&deslocamento=${offset}`);
    if (currentRequest !== citationRequest) return;
    citationWork = work; citationOffset = offset;
    status.textContent = `${graph.ligacoes.length} de ${graph.totalReferencias} referência(s) · conferidas em ${new Date(graph.conferidoEm).toLocaleString()}`;
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg'); const height = Math.max(180, graph.ligacoes.length*70);
    svg.setAttribute('viewBox', `0 0 800 ${height}`); svg.setAttribute('role','img'); svg.setAttribute('aria-label','Obra de origem aponta para suas referências no OpenAlex');
    const defs = document.createElementNS(ns,'defs'); const marker = document.createElementNS(ns,'marker');
    marker.id = 'citation-arrow'; marker.setAttribute('viewBox','0 0 10 10'); marker.setAttribute('refX','9'); marker.setAttribute('refY','5'); marker.setAttribute('markerWidth','6'); marker.setAttribute('markerHeight','6'); marker.setAttribute('orient','auto');
    const triangle = document.createElementNS(ns,'path'); triangle.setAttribute('d','M 0 0 L 10 5 L 0 10 z'); triangle.setAttribute('fill','#526581'); marker.append(triangle); defs.append(marker); svg.append(defs);
    const node = (item, x, y) => {
      const circle = document.createElementNS(ns,'circle'); circle.setAttribute('cx',x); circle.setAttribute('cy',y); circle.setAttribute('r','12'); circle.setAttribute('fill','#155eef');
      const label = document.createElementNS(ns,'text'); label.setAttribute('x',x+18); label.setAttribute('y',y+5); label.setAttribute('font-size','12');
      label.textContent = `${item.id.split('/').at(-1)} · ${item.ano ?? 'ano desconhecido'}`;
      const title = document.createElementNS(ns,'title'); title.textContent = item.titulo || item.id; circle.append(title); svg.append(circle,label);
    };
    const origin = graph.nos[0];
    graph.nos.slice(1).forEach((target,index) => {
      const y = 35+index*70; const line = document.createElementNS(ns,'line');
      line.setAttribute('x1','42'); line.setAttribute('y1',height/2); line.setAttribute('x2','480'); line.setAttribute('y2',y); line.setAttribute('stroke','#526581'); line.setAttribute('marker-end','url(#citation-arrow)'); svg.append(line); node(target,500,y);
    });
    node(origin,30,height/2); document.querySelector('#citation-graph').replaceChildren(svg);
    const timeline = document.querySelector('#citation-timeline'); timeline.replaceChildren();
    [...graph.nos].sort((a,b) => (a.ano ?? 9999)-(b.ano ?? 9999)).forEach(item => {
      const p = document.createElement('p'); const link = document.createElement('a'); link.href = `https://openalex.org/${encodeURIComponent(item.id.split('/').at(-1))}`;
      link.target = '_blank'; link.rel = 'noopener noreferrer'; link.textContent = `${item.ano ?? 'Ano desconhecido'} · ${item.titulo || item.id}`; p.append(link);
      if (item.obraId && item.obraId !== work) {
        const expand = document.createElement('button'); expand.textContent = 'Explorar esta obra';
        expand.onclick = () => abrirCitacoes(item.obraId,true).catch(error => {status.textContent = error.message;}); p.append(expand);
      }
      timeline.append(p);
    });
    document.querySelector('#citation-previous').disabled = offset === 0;
    document.querySelector('#citation-next').disabled = offset+graph.ligacoes.length >= graph.totalReferencias;
  } catch(error) {
    if (currentRequest === citationRequest) { status.textContent = error.message; controles.forEach((control,i) => { control.disabled = estadoAnterior[i]; }); }
    throw error;
  }
}
for (const [id,delta] of [['citation-previous',-10],['citation-next',10]]) {
  document.getElementById(id).onclick = () => abrirCitacoes(citationWork,false,Math.max(0,citationOffset+delta)).catch(() => {});
}
