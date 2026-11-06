// Synthetic local integration fixture. Never loads deployment configuration or credentials.
import { createServer } from 'node:http';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
const repo = resolve(process.argv[2]);
const moduleAt = path => import(pathToFileURL(join(repo, path)).href);
const [{ Store }, { Memory }, { Runtime }, { loadConfig }, { handleProduct }] = await Promise.all([
  moduleAt('src/store.js'), moduleAt('src/memory.js'), moduleAt('src/runtime.js'), moduleAt('src/config.js'), moduleAt('src/product-http.js'),
]);
const root = await mkdtemp(join(tmpdir(), 'shadow-app-contract-'));
await writeFile(join(root, 'config.json'), JSON.stringify({ version: 1, stateDirectory: root, batchMs: 100,
  qq: { mode: 'disabled' }, memory: { autoLearn: false } }));
const config = loadConfig(join(root, 'config.json'), {});
const store = new Store(join(root, 'state.sqlite'));
const runtime = new Runtime({ store, memory: new Memory(store), config,
  driver: { availableTools: () => [], run: async () => ({ state: 'completed', text: 'synthetic Losna reply' }) },
  broker: { life: { connect: async () => [] } }, qq: { close: async () => {} },
});
const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (!url.pathname.startsWith('/losna/agent/')) { res.writeHead(404); res.end(); return; }
    await handleProduct(runtime.product, req, res, url.pathname.slice('/losna'.length), url.searchParams);
  } catch { res.writeHead(500); res.end(); }
});
server.listen(0, '127.0.0.1', () => process.stdout.write(`http://127.0.0.1:${server.address().port}/\n`));
let closing = false;
async function close() {
  if (closing) return; closing = true;
  server.close(); await runtime.close(); store.close(); await rm(root, { recursive: true, force: true });
  process.exit(0);
}
process.on('SIGTERM', close);
process.stdin.resume(); process.stdin.on('end', close);
