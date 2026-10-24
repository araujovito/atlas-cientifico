// Teste destrutivo SOMENTE sobre o projeto Docker temporário criado por este script.
// Não usa o compose de desenvolvimento, seus volumes nem seus documentos.
import {spawnSync} from 'node:child_process';
import {randomBytes} from 'node:crypto';
import {mkdtemp, readFile, unlink, rmdir} from 'node:fs/promises';
import {tmpdir, cpus, totalmem} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../',import.meta.url));
const project = `atlas-smoke-${Date.now()}-${process.pid}`;
const env = {...process.env, ATLAS_DB_PASSWORD:randomBytes(24).toString('hex'), ATLAS_CHAVE_API:randomBytes(24).toString('hex'), ATLAS_PORT:process.env.ATLAS_SMOKE_PORT || '18081'};
const dir = await mkdtemp(join(tmpdir(),'atlas-smoke-'));
const fixture = join(dir,'synthetic.pdf');
const compose = (...args) => {
  const r = spawnSync('docker',['compose','-p',project,'-f','deploy/compose.yml',...args],{cwd:root,env,encoding:'utf8'});
  if(r.status!==0) throw new Error(`Compose falhou: ${r.stderr}`);
  return r.stdout;
};
const base = `http://127.0.0.1:${env.ATLAS_PORT}`;
const request = async (path,options={}) => {
  const r = await fetch(base+path,{...options,headers:{'X-Atlas-Chave':env.ATLAS_CHAVE_API,...options.headers},signal:AbortSignal.timeout(10000)});
  if(!r.ok) throw new Error(`${path}: HTTP ${r.status}: ${await r.text()}`);
  return r.json();
};
const wait = ms => new Promise(resolve=>setTimeout(resolve,ms));
try {
  const generated = spawnSync('./mvnw',['--batch-mode','--no-transfer-progress','exec:java','-Dexec.mainClass=dev.atlas.FixturePdfMain',`-Dexec.args=${fixture}`],{cwd:join(root,'api'),env,encoding:'utf8'});
  if(generated.status!==0) throw new Error(`Fixture falhou: ${generated.stderr}\n${generated.stdout}`);
  compose('up','-d','--wait','--wait-timeout','180');
  const bytes = await readFile(fixture);
  const samples = [];
  const upload = async () => {
    const form = new FormData(); form.append('arquivo',new Blob([bytes],{type:'application/pdf'}),'synthetic.pdf'); form.append('autorizacaoConfirmada','true');
    const start=performance.now(); const value=await request('/api/documentos',{method:'POST',body:form});
    samples.push(performance.now()-start); return value;
  };
  const documentos = await Promise.all(Array.from({length:8},upload));
  const ids = [...new Set(documentos.map(d=>d.id))];
  if(ids.length!==1 || !ids[0]) throw new Error('Uploads concorrentes duplicaram documento.');
  // Interromper o serviço antes da tarefa: o comando tem de sobreviver sem consumidor.
  compose('stop','worker');
  const jobs = await Promise.all(Array.from({length:8},()=>request(`/api/documentos/${ids[0]}/processamentos`,{method:'POST'})));
  if(new Set(jobs.map(j=>j.id)).size!==1) throw new Error('Comando repetido duplicou tarefa.');
  const jobId=jobs[0].id;
  const waiting = await request(`/api/processamentos/${jobId}`);
  if(waiting.estado!=='PENDENTE') throw new Error('Tarefa não ficou durável enquanto trabalhador estava parado.');
  compose('start','worker');
  const started=performance.now(); let job;
  for(let i=0;i<120;i++) {
    job=await request(`/api/processamentos/${jobId}`);
    if(['CONCLUIDO','PRECISA_OCR','FALHOU'].includes(job.estado)) break;
    await wait(1000);
  }
  if(job.estado!=='CONCLUIDO') throw new Error(`Parser empacotado não concluiu: ${JSON.stringify(job)}`);
  const pages=await request(`/api/documentos/${ids[0]}/extracoes/${job.execucaoId}/paginas`);
  if(!JSON.stringify(pages).includes('125 mAh/g')) throw new Error('Texto sintético não encontrado.');
  const repeated=await request(`/api/documentos/${ids[0]}/processamentos`,{method:'POST'});
  if(repeated.execucaoId!==job.execucaoId) throw new Error('Repetição não reutilizou execução.');
  const ordered=[...samples].sort((a,b)=>a-b);
  console.log(JSON.stringify({metodo:'smoke sintético, 8 uploads concorrentes do mesmo PDF; não é benchmark científico',
    host:{cpu:cpus()[0]?.model,cpus:cpus().length,memoriaBytes:totalmem(),node:process.version},
    imagem:compose('images','--format','json').trim(),bytes:bytes.length,concorrencia:8,
    uploadMs:{min:ordered[0],mediana:(ordered[3]+ordered[4])/2,max:ordered.at(-1)},
    retomadaAteConclusaoMs:performance.now()-started,documentosUnicos:ids.length,tarefasUnicas:1,execucaoReutilizada:true,
    operacao:await request('/api/operacao')},null,2));
} finally {
  // O nome é gerado aqui; somente estes volumes sintéticos são removidos.
  compose('down','--volumes','--remove-orphans');
  await unlink(fixture).catch(e=>{if(e.code!=='ENOENT') throw e;});
  await rmdir(dir);
}
