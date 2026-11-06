// Synthetic local integration fixture. Never loads deployment configuration or credentials.
import { existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdtemp, writeFile, rm, mkdir, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
const repo = resolve(process.argv[2]);
const moduleAt = path => import(pathToFileURL(join(repo, path)).href);
const [{ Store }, { Memory }, { Runtime }, { loadConfig }, { handleProduct }, { Media }, { LifeClient }] = await Promise.all([
  moduleAt('src/store.js'), moduleAt('src/memory.js'), moduleAt('src/runtime.js'), moduleAt('src/config.js'), moduleAt('src/product-http.js'), moduleAt('src/media.js'), moduleAt('src/life-client.js'),
]);
const root = await realpath(await mkdtemp(join(tmpdir(), 'shadow-app-contract-')));
await writeFile(join(root, 'config.json'), JSON.stringify({ version: 1, stateDirectory: root, batchMs: 100,
  qq: { mode: 'disabled' }, general: { enabled: true }, memory: { autoLearn: false } }));
const config = loadConfig(join(root, 'config.json'), {});
if (['commitments','commitment-page'].includes(process.argv[4])) config.companionContinuity = { enabled: true };
if (process.argv[4] === 'mobile') {
  config.mobile = { enabled: true, voice: true };
  config.voice = { ...config.voice, enabled: true, command: '/synthetic/unused' };
}
if (process.argv[4] === 'growth') config.companionBehavior = { enabled: true };
let browserServer;
if (process.argv[4] === 'browser') {
  let writes=0;
  browserServer=createServer((req,res)=>{
    if(req.method==='POST'){writes++;req.resume();}
    res.setHeader('content-type','text/html; charset=utf-8');
    res.end(`<!doctype html><title>合成接手页面</title><p>已提交 ${writes} 次</p><form method="post"><label>验证码<input name="code" type="password"></label><button>提交合成记录</button></form>`);
  });
  await new Promise(resolve=>browserServer.listen(0,'127.0.0.1',resolve));
  const executablePath=[process.env.LOSNA_TEST_BROWSER,'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome','/usr/bin/chromium','/usr/bin/chromium-browser'].find(p=>p&&existsSync(p));
  if(!executablePath)throw new Error('A local test browser is required');
  const {browserConfig}=await moduleAt('src/browser.js');
  config.browser=browserConfig({enabled:true,executablePath,allowedOrigins:[`http://127.0.0.1:${browserServer.address().port}`]},root);
}
let lifeServer, life;
if (process.argv[3]) {
  lifeServer = createServer(async (req, res) => {
    if (req.headers.authorization !== 'Bearer synthetic-fixture') { res.writeHead(403); res.end(); return; }
    res.setHeader('content-type', 'application/json');
    if (req.url === '/api/capabilities?include_schemas=true') { res.end(JSON.stringify({ capabilities: [] })); return; }
    if (req.method !== 'POST' || req.url !== '/api/assets') { res.writeHead(404); res.end(); return; }
    const chunks = []; for await (const chunk of req) chunks.push(Buffer.from(chunk));
    const bytes = Buffer.concat(chunks), sha256 = createHash('sha256').update(bytes).digest('hex');
    res.end(JSON.stringify({ protocol: 'shadow.asset', asset_id: `asset_${sha256.slice(0,24)}`, asset_version_id: `assetv_${sha256.slice(0,24)}`, sha256, bytes: bytes.length, media_type: req.headers['content-type'] }));
  });
  await new Promise(resolve => lifeServer.listen(0, '127.0.0.1', resolve));
  const file = join(root, 'life.json');
  await writeFile(file, JSON.stringify({ api: `http://127.0.0.1:${lifeServer.address().port}`, token: 'synthetic-fixture', mediaRoots: [join(root, 'media')] }));
  config.life = { command: join(resolve(process.argv[3]), 'skills/life-operator/scripts/life'), args: ['--config', file] };
  life = new LifeClient(config.life);
}
const store = new Store(join(root, 'state.sqlite'));
const runtime = new Runtime({ store, memory: new Memory(store), config,
  speech: process.argv[4] === 'mobile' ? () => ({ synthesize: async () => ({ bytes: Buffer.concat([Buffer.from('ID3'), Buffer.alloc(200)]), type: 'audio/mpeg' }) }) : undefined,
  driver: { availableTools: () => [], run: async (context, tools, _prompt, options) => {
    if (context.kind === 'chat') return { state: 'completed', text: 'synthetic Losna reply' };
    await mkdir(options.workspace.directory, { recursive: true });
    await writeFile(join(options.workspace.directory, 'report.txt'), 'synthetic artifact content');
    await tools.find(tool => tool.name === 'companion_finish').execute({ state: 'completed', summary: 'synthetic work finished', artifacts: ['report.txt'] });
    return { state: 'completed', pendingJobs: [], steps: 1 };
  } },
  media: life ? new Media(join(root, 'media'), { saveImage: async () => ({ id: 'synthetic-model-image' }) }, []) : undefined,
  broker: { life: life ?? { connect: async () => [] }, close: async () => life?.close() }, qq: { close: async () => {} },
});
store.put('sources', { id: 'fixture-evidence', person: 'owner', text: 'synthetic work request', state: 'processed' });
store.put('work', { id: 'fixture-work', person: 'owner', entryId: 'losna', domain: 'general', workspace: 'scratch',
  goal: 'synthetic report', state: 'waiting', paused: true, sourceIds: ['fixture-evidence'], plan: [{ content: 'write report', status: 'pending' }] });
if (process.argv[4] === 'browser') {
  const context={person:'owner',kind:'work',workId:'fixture-work'};
  const opened=await runtime.browser.command(context,{action:'open',url:config.browser.allowedOrigins[0],commandId:'fixture-open-page'});
  await runtime.browser.command(context,{action:'handoff',sessionId:opened.sessionId,commandId:'fixture-handoff-page'});
  runtime.memory.save('owner',{text:'合成浏览器来源'},['fixture-evidence']);
}
if (['commitments','commitment-page'].includes(process.argv[4])) {
  const source = store.get('sources', 'fixture-evidence');
  store.put('sources', { ...source, ...runtime.origin([], { entryId: 'losna' }), receivedAt: Date.now() });
  runtime.continuity.manage(runtime.context('owner', ['fixture-evidence'], 'chat'), {
    action: 'create', goal: '合成长期阅读目标', workId: 'fixture-work', completion: 'user_judgment',
  });
  runtime.memory.save('owner', { text: '合成持续事项来源' }, ['fixture-evidence']);
}
if (process.argv[4] === 'commitment-page') {
  const seed=store.all('commitments')[0];
  for(let i=0;i<123;i++)store.put('commitments',{...seed,id:`browse-${i}`,goal:`合成持续目标 ${i}`,result:'完整结果 100% _ +'});
}
if (process.argv[4] === 'mobile') {
  config.mobile = { enabled: true, voice: true };
  config.voice = { ...config.voice, enabled: true, command: '/synthetic/unused' };
}
if (process.argv[4] === 'growth') {
  const now = Date.now(), persona = runtime.personas.resolve('losna');
  const source = store.put('sources', { id: 'growth-habit-source', person: 'owner', ...runtime.origin([], { entryId: 'losna' }),
    text: '以后回复简短一点。', receivedAt: now, occurredAt: new Date(now).toISOString(), state: 'processed' });
  runtime.behavior.observe(source);
  store.put('sources', { ...source, id: 'growth-reading-source', text: '合成阅读依据' });
  const reading = { id: 'synthetic-reading', kind: 'reading', person: 'owner', entryId: 'losna', personaId: persona.id,
    basePersonaId: persona.baseId, sourceIds: ['growth-reading-source'], epoch: store.epoch('owner'),
    title: '合成现场录音', url: 'https://example.com/reading', text: '合成材料记下了一段现场留白。', summary: '合成阅读摘要',
    contentHash: 'synthetic-content', state: 'completed', createdAt: now };
  store.put('readings', reading);
  const { saveReadingView } = await moduleAt('src/reading-views.js');
  saveReadingView(store, runtime.memory, reading, { topic: '现场留白', opinion: '我更喜欢保留一小段留白', evidenceQuote: reading.text }, now);
  runtime.memory.save('owner', { text: '合成阅读来源' }, ['growth-reading-source']);
}
if (process.argv[4] === 'works') {
  runtime.memory.save('owner', { text: 'synthetic work evidence' }, ['fixture-evidence']);
  for (let n = 0; n < 123; n++) store.put('work', { id: `synthetic-work-${n}`, person: 'owner', entryId: 'losna',
    goal: `合成资料 ${n}`, result: '完整报告 100% _ +', state: 'completed', sourceIds: ['fixture-evidence'] });
}
if (process.argv[4] === 'memories') {
  for (let n = 0; n < 123; n++) {
    const id = `memory-source-${n}`;
    store.put('sources', { id, person: 'owner', text: `synthetic memory evidence ${n}`, state: 'processed' });
    runtime.memory.save('owner', { text: `合成茶饮偏好 ${n}`, kind: 'preference' }, [id]);
  }
}
let mobileHandler;
if (process.argv[4] === 'mobile') {
  store.put('outbox', { id: 'mobile-reply', person: 'owner', entryId: 'losna', sourceIds: [], state: 'sent', epoch: 0, text: 'synthetic spoken reply', sentAt: Date.now() });
  const { installAdmin } = await moduleAt('src/admin.js');
  installAdmin({ webServer: { register: route => { mobileHandler = route.handler; return () => {}; }, tapIndex: () => () => {} },
    connection: { requestRejection: req => { if (req.headers.authorization === 'Bearer synthetic-owner') { delete req.headers.authorization; return null; } return 401; } } }, runtime, {});
}
const server = createServer(async (req, res) => {
  try {
    if (mobileHandler) { await mobileHandler(req, res); return; }
    const url = new URL(req.url, 'http://localhost');
    if (!url.pathname.startsWith('/losna/agent/')) { res.writeHead(404); res.end(); return; }
    await handleProduct(runtime.product, req, res, url.pathname.slice('/losna'.length), url.searchParams);
    if (!['commitments','commitment-page','browser'].includes(process.argv[4]) && req.method === 'POST' && url.pathname.endsWith('/commands')) runtime.runWork('fixture-work').catch(() => {});
  } catch { res.writeHead(500); res.end(); }
});
server.listen(0, '127.0.0.1', () => process.stdout.write(`http://127.0.0.1:${server.address().port}/\n`));
let closing = false;
async function close() {
  if (closing) return; closing = true;
  server.close(); lifeServer?.close(); browserServer?.close(); await runtime.close(); store.close(); await rm(root, { recursive: true, force: true });
  process.exit(0);
}
process.on('SIGTERM', close);
process.stdin.resume(); process.stdin.on('end', close);
