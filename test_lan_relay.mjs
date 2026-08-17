import { randomUUID } from 'node:crypto';

const baseUrl = 'https://orvexaauth-api.bot724524.workers.dev';
const testId = randomUUID().slice(0, 8);
const passwordHash = `lan-test-${randomUUID()}`;
const hostEmail = `lan-host-${testId}@example.invalid`;
const guestEmail = `lan-guest-${testId}@example.invalid`;
const report = { testId, assertions: [] };
let host;
let guest;
let lanSession;
let hostSocket;
let guestSocket;

function assert(condition, message) {
  if (!condition) throw new Error(message);
  report.assertions.push(message);
}

async function api(path, { method = 'GET', token, body } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      'X-App-Name': 'OrvexaAuth-LAN-Verification'
    },
    ...(body ? { body: JSON.stringify(body) } : {})
  });
  const text = await response.text();
  let payload = null;
  try { payload = text ? JSON.parse(text) : null; } catch { payload = { raw: text }; }
  if (!response.ok) throw new Error(`${method} ${path} returned ${response.status}: ${payload?.error || payload?.message || text}`);
  return payload;
}

function openSocket(url, label) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(url);
    socket.binaryType = 'arraybuffer';
    const timer = setTimeout(() => reject(new Error(`${label} WebSocket connection timed out`)), 15000);
    socket.addEventListener('open', () => { clearTimeout(timer); resolve(socket); }, { once: true });
    socket.addEventListener('error', () => { clearTimeout(timer); reject(new Error(`${label} WebSocket connection was rejected`)); }, { once: true });
  });
}

function waitForMessage(socket, label, predicate) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${label} did not receive the expected relay frame`)), 15000);
    const handler = async event => {
      let value = event.data;
      if (typeof value === 'string') {
        try { value = JSON.parse(value); } catch { /* non-control string */ }
      } else if (value instanceof Blob) {
        value = new Uint8Array(await value.arrayBuffer());
      } else if (value instanceof ArrayBuffer) {
        value = new Uint8Array(value);
      }
      if (!predicate(value)) return;
      clearTimeout(timer);
      socket.removeEventListener('message', handler);
      resolve(value);
    };
    socket.addEventListener('message', handler);
  });
}

function waitForClose(socket, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${label} was not closed after session deletion`)), 15000);
    socket.addEventListener('close', event => { clearTimeout(timer); resolve(event); }, { once: true });
  });
}

async function waitForGuestSession(sessionId) {
  const deadline = Date.now() + 90000;
  let lastListed = [];
  do {
    const listed = await api('/api/lan/sessions', { token: guest.sessionToken });
    lastListed = listed.sessions || [];
    if (lastListed.some(item => item.id === sessionId)) return lastListed;
    await new Promise(resolve => setTimeout(resolve, 3000));
  } while (Date.now() < deadline);
  throw new Error(`invited guest did not discover LAN session within 90 seconds; last list contained ${lastListed.length} session(s)`);
}

async function cleanup() {
  try { if (hostSocket && hostSocket.readyState < WebSocket.CLOSING) hostSocket.close(); } catch { /* best effort */ }
  try { if (guestSocket && guestSocket.readyState < WebSocket.CLOSING) guestSocket.close(); } catch { /* best effort */ }
  if (lanSession && host?.sessionToken) {
    try { await api(`/api/lan/sessions/${lanSession.id}`, { method: 'DELETE', token: host.sessionToken }); } catch { /* already closed */ }
  }
  if (host?.id && host?.sessionToken) {
    try { await api(`/api/users/${host.id}`, { method: 'DELETE', token: host.sessionToken, body: { passwordHash } }); } catch { /* best effort */ }
  }
  if (guest?.id && guest?.sessionToken) {
    try { await api(`/api/users/${guest.id}`, { method: 'DELETE', token: guest.sessionToken, body: { passwordHash } }); } catch { /* best effort */ }
  }
}

try {
  const unauthorized = await fetch(`${baseUrl}/api/lan/sessions`);
  assert(unauthorized.status === 401, 'LAN API rejects requests without a Bearer session');

  host = await api('/api/register', { method: 'POST', body: { email: hostEmail, passwordHash, firstName: 'LAN', lastName: 'Host' } });
  guest = await api('/api/register', { method: 'POST', body: { email: guestEmail, passwordHash, firstName: 'LAN', lastName: 'Guest' } });
  assert(host.sessionToken && guest.sessionToken, 'temporary host and guest accounts received independent sessions');

  await api('/api/friends/request', { method: 'POST', token: host.sessionToken, body: { targetUserId: String(guest.id) } });
  await api(`/api/friends/${host.id}/accept`, { method: 'PUT', token: guest.sessionToken });
  assert(true, 'host and guest friendship was accepted before invitation');

  const created = await api('/api/lan/sessions', {
    method: 'POST',
    token: host.sessionToken,
    body: { title: 'Automated Internet Relay Verification', accessMode: 'invite', allowedUserIds: [String(guest.id)] }
  });
  lanSession = created.session;
  assert(created.hostTicket && created.relayUrl?.startsWith('wss://'), 'host received a public WSS relay URL through Cloudflare');

  const listed = await waitForGuestSession(lanSession.id);
  assert(listed.some(item => item.id === lanSession.id), 'invited guest can discover the host LAN session');

  const guestTicket = await api(`/api/lan/sessions/${lanSession.id}/join-ticket`, { method: 'POST', token: guest.sessionToken });
  assert(guestTicket.relayUrl?.startsWith('wss://'), 'guest received a separate public WSS relay URL through Cloudflare');

  hostSocket = await openSocket(created.relayUrl, 'host');
  guestSocket = await openSocket(guestTicket.relayUrl, 'guest');
  await Promise.all([
    waitForMessage(hostSocket, 'host', value => value?.type === 'ready' && value.peer === 'guest'),
    waitForMessage(guestSocket, 'guest', value => value?.type === 'ready' && value.peer === 'host')
  ]);
  assert(true, 'Cloudflare Durable Object paired the public host and guest WebSockets');

  const hostPayload = new Uint8Array([0x4f, 0x52, 0x56, 0x45, 0x58, 0x41, 0x01]);
  const guestReceivesHost = waitForMessage(guestSocket, 'guest', value => value instanceof Uint8Array && value.length === hostPayload.length && value.every((byte, index) => byte === hostPayload[index]));
  hostSocket.send(hostPayload);
  await guestReceivesHost;
  assert(true, 'binary traffic travelled from host to guest through the public Cloudflare relay');

  const guestPayload = new Uint8Array([0x41, 0x58, 0x45, 0x56, 0x52, 0x4f, 0x02]);
  const hostReceivesGuest = waitForMessage(hostSocket, 'host', value => value instanceof Uint8Array && value.length === guestPayload.length && value.every((byte, index) => byte === guestPayload[index]));
  guestSocket.send(guestPayload);
  await hostReceivesGuest;
  assert(true, 'binary traffic travelled from guest to host through the public Cloudflare relay');

  const replayRejected = await new Promise(resolve => {
    const replay = new WebSocket(created.relayUrl);
    const timer = setTimeout(() => { try { replay.close(); } catch {} resolve(false); }, 6000);
    replay.addEventListener('open', () => { clearTimeout(timer); replay.close(); resolve(false); }, { once: true });
    replay.addEventListener('error', () => { clearTimeout(timer); resolve(true); }, { once: true });
  });
  assert(replayRejected, 'consumed host relay ticket cannot establish a second WebSocket connection');

  const hostClosed = waitForClose(hostSocket, 'host socket');
  const guestClosed = waitForClose(guestSocket, 'guest socket');
  await api(`/api/lan/sessions/${lanSession.id}`, { method: 'DELETE', token: host.sessionToken });
  lanSession = null;
  await Promise.all([hostClosed, guestClosed]);
  assert(true, 'closing the LAN session closes both public tunnel endpoints');

  console.log(JSON.stringify({ status: 'passed', ...report }, null, 2));
} catch (error) {
  console.error(JSON.stringify({ status: 'failed', ...report, error: error.message }, null, 2));
  process.exitCode = 1;
} finally {
  await cleanup();
}
