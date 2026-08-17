import fs from 'node:fs';

const sourcePath = '/home/ubuntu/netauth-github/termux-server/cloudflare_worker.js';
const outputPath = '/home/ubuntu/netauth-github/.orvexaauth-worker-deploy-input.json';
const source = fs.readFileSync(sourcePath, 'utf8');
const boundary = 'OrvexaWorker' + Date.now();
const metadata = {
  main_module: 'worker.js',
  bindings: [
    { name: 'ADMIN_SECRET', type: 'inherit' },
    { name: 'ORVEXAAUTH_KV', type: 'kv_namespace', namespace_id: 'e4ed575dddd64d67a58226ae1b967f09' },
    { name: 'LAN_RELAY', type: 'durable_object_namespace', class_name: 'LanRelay' }
  ],
  migrations: { tag: 'orvexaauth-lan-relay-v1', new_sqlite_classes: ['LanRelay'] }
};
const multipart = [
  `--${boundary}`,
  'Content-Disposition: form-data; name="metadata"',
  'Content-Type: application/json',
  '',
  JSON.stringify(metadata),
  `--${boundary}`,
  'Content-Disposition: form-data; name="worker.js"; filename="worker.js"',
  'Content-Type: application/javascript+module',
  '',
  source,
  `--${boundary}--`,
  ''
].join('\r\n');
const code = `async () => cloudflare.request({ method: 'PUT', path: \`/accounts/\${accountId}/workers/scripts/orvexaauth-api\`, query: { bindings_inherit: 'strict' }, body: ${JSON.stringify(multipart)}, contentType: ${JSON.stringify(`multipart/form-data; boundary=${boundary}`)}, rawBody: true })`;
fs.writeFileSync(outputPath, JSON.stringify({ code }), 'utf8');
console.log(outputPath);
