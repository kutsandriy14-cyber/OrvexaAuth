/**
 * OrvexaAuth Beta Cloudflare Worker (KV-based Database Server)
 * 
 * This worker implements the OrvexaAuth Beta API utilizing Cloudflare Workers KV
 * for global, fast, and 24/7 serverless database hosting with a generous free tier.
 * 
 * Setup instructions:
 * 1. Create a Workers KV namespace named "ORVEXAAUTH_KV".
 * 2. Bind the namespace to this worker under the name "ORVEXAAUTH_KV".
 * 3. Deploy the worker!
 */

// OrvexaAuth Beta uses public HTTPS endpoints with server-issued bearer sessions.
// Do not put API secrets in this source file.
const SESSION_TTL_SECONDS = 60 * 60 * 24 * 30; // 30 days
const LAN_SESSION_TTL_SECONDS = 4 * 60 * 60; // 4 hours
const LAN_TICKET_TTL_SECONDS = 5 * 60; // 5 minutes

// CORS headers helper
function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type, X-Database-Name, X-App-Name, Authorization",
    "Access-Control-Allow-Methods": "GET, PUT, POST, DELETE, OPTIONS",
    "Access-Control-Max-Age": "86400",
  };
}

// Generate JSON response helper
function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...corsHeaders()
    }
  });
}

// Error response helper
function errorResponse(message, status = 400) {
  return jsonResponse({ status: "error", message }, status);
}

// Check service key verification
function checkServiceKey(_request) {
  // Public beta mode: no service key is required.
  return true;
}

function sanitizeUser(user) {
  if (!user) return null;
  const safe = { ...user };
  delete safe.passwordHash;
  delete safe.totpSecret;
  delete safe.totpPendingSecret;
  delete safe.totpPendingExpiresAt;
  return safe;
}

async function createSession(kv, dbName, user, request, body = {}) {
  const token = crypto.randomUUID();
  const createdAt = Date.now();
  const expiresAt = createdAt + SESSION_TTL_SECONDS * 1000;
  const session = {
    userId: user.id,
    email: user.email,
    createdAt,
    expiresAt,
    deviceName: String(body.deviceName || request.headers.get("X-Device-Name") || "").slice(0, 120),
    deviceType: String(body.deviceType || request.headers.get("X-Device-Type") || "unknown").slice(0, 40),
    appName: String(request.headers.get("X-App-Name") || body.appName || "Unknown_App").slice(0, 120)
  };
  await kv.put(`${dbName}:session:${token}`, JSON.stringify(session), { expirationTtl: SESSION_TTL_SECONDS });
  return { token, ...session };
}

async function getSession(kv, dbName, token) {
  if (!token) return null;
  const raw = await kv.get(`${dbName}:session:${token}`);
  if (!raw) return null;
  try {
    const session = JSON.parse(raw);
    if (!session.expiresAt || session.expiresAt <= Date.now()) {
      await kv.delete(`${dbName}:session:${token}`);
      return null;
    }
    return { token, ...session };
  } catch (_e) {
    await kv.delete(`${dbName}:session:${token}`);
    return null;
  }
}

async function requireAuth(request, kv, dbName) {
  const header = request.headers.get("Authorization") || "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  return match ? getSession(kv, dbName, match[1].trim()) : null;
}

async function findUserByCredentials(kv, dbName, email, passwordHash) {
  const normalizedEmail = String(email || "").trim().toLowerCase();
  if (!normalizedEmail || !passwordHash) return null;
  const userId = await kv.get(`${dbName}:email_to_id:${normalizedEmail}`);
  if (!userId) return null;
  const rawUser = await kv.get(`${dbName}:user:${userId}`);
  if (!rawUser) return null;
  const user = JSON.parse(rawUser);
  return user.passwordHash === passwordHash ? user : null;
}

async function readUser(kv, dbName, userId) {
  const raw = await kv.get(`${dbName}:user:${userId}`);
  return raw ? JSON.parse(raw) : null;
}

async function resolveUserId(kv, dbName, value) {
  const raw = String(value || "").trim();
  if (!raw) return null;
  if (/^\d+$/.test(raw)) return (await readUser(kv, dbName, raw)) ? raw : null;
  const id = await kv.get(`${dbName}:email_to_id:${raw.toLowerCase()}`);
  return id || null;
}

function pairKey(dbName, firstId, secondId) {
  const ids = [String(firstId), String(secondId)].sort();
  return `${dbName}:friend:${ids[0]}:${ids[1]}`;
}

async function groupForMember(kv, dbName, groupId, userId) {
  const raw = await kv.get(`${dbName}:group:${groupId}`);
  if (!raw) return null;
  const group = JSON.parse(raw);
  return Array.isArray(group.memberIds) && group.memberIds.map(String).includes(String(userId)) ? group : null;
}

function base32Encode(bytes) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0;
  let value = 0;
  let output = "";
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += alphabet[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) output += alphabet[(value << (5 - bits)) & 31];
  return output;
}

function base32Decode(input) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = 0;
  let value = 0;
  const output = [];
  for (const char of String(input || "").toUpperCase().replace(/[^A-Z2-7]/g, "")) {
    const index = alphabet.indexOf(char);
    if (index < 0) continue;
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      output.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return new Uint8Array(output);
}

async function totpCode(secret, counter) {
  const key = await crypto.subtle.importKey("raw", base32Decode(secret), { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  const bytes = new Uint8Array(8);
  let remaining = BigInt(counter);
  for (let index = 7; index >= 0; index--) {
    bytes[index] = Number(remaining & 255n);
    remaining >>= 8n;
  }
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", key, bytes));
  const offset = signature[signature.length - 1] & 15;
  const value = ((signature[offset] & 127) << 24) | (signature[offset + 1] << 16) | (signature[offset + 2] << 8) | signature[offset + 3];
  return String(value % 1000000).padStart(6, "0");
}

async function verifyTotp(secret, code) {
  const normalizedCode = String(code || "").replace(/\s/g, "");
  if (!/^\d{6}$/.test(normalizedCode) || !secret) return false;
  const currentCounter = Math.floor(Date.now() / 30000);
  for (let offset = -1; offset <= 1; offset++) {
    if ((await totpCode(secret, currentCounter + offset)) === normalizedCode) return true;
  }
  return false;
}

function newTotpSecret() {
  const bytes = new Uint8Array(20);
  crypto.getRandomValues(bytes);
  return base32Encode(bytes);
}

async function areFriends(kv, dbName, firstId, secondId) {
  const raw = await kv.get(pairKey(dbName, firstId, secondId));
  return Boolean(raw && JSON.parse(raw).status === "accepted");
}

async function writeUserEvent(kv, dbName, userId, type, request, detail = {}, env = {}, ctx) {
  const key = `${dbName}:security_events:${userId}`;
  const raw = await kv.get(key);
  const events = raw ? JSON.parse(raw) : [];
  const event = {
    id: crypto.randomUUID(),
    type: String(type).slice(0, 80),
    createdAt: Date.now(),
    appName: String(request?.headers?.get("X-App-Name") || "Unknown_App").slice(0, 120),
    deviceName: String(request?.headers?.get("X-Device-Name") || "").slice(0, 120),
    detail
  };
  events.push(event);
  await kv.put(key, JSON.stringify(events.slice(-200)));
  const noticesKey = `${dbName}:notifications:${userId}`;
  const rawNotices = await kv.get(noticesKey);
  const notices = rawNotices ? JSON.parse(rawNotices) : [];
  notices.push({ ...event, read: false });
  await kv.put(noticesKey, JSON.stringify(notices.slice(-200)));
  const webhookUrl = env.DISCORD_WEBHOOK_URL || (typeof DISCORD_WEBHOOK_URL !== "undefined" ? DISCORD_WEBHOOK_URL : "");
  if (webhookUrl) {
    const task = fetch(webhookUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ content: `OrvexaAuth: ${event.type} for user ${userId} at ${new Date(event.createdAt).toISOString()}` })
    }).catch(() => undefined);
    if (ctx && typeof ctx.waitUntil === "function") ctx.waitUntil(task);
    else await task;
  }
  return event;
}

async function sessionAllowsMinecraft(kv, dbName, minecraftSession, userId) {
  if (String(minecraftSession.ownerId) === String(userId)) return true;
  if (Array.isArray(minecraftSession.allowedUserIds) && minecraftSession.allowedUserIds.map(String).includes(String(userId))) return true;
  return minecraftSession.accessMode === "friends" && areFriends(kv, dbName, minecraftSession.ownerId, userId);
}

function lanSessionKey(dbName, sessionId) {
  return `${dbName}:lan_session:${sessionId}`;
}

function lanTicketKey(dbName, ticket) {
  return `${dbName}:lan_ticket:${ticket}`;
}

function lanRelayId(env, dbName, sessionId) {
  return env.LAN_RELAY.idFromName(`${dbName}:${sessionId}`);
}

async function readLanSession(kv, dbName, sessionId) {
  const raw = await kv.get(lanSessionKey(dbName, sessionId));
  if (!raw) return null;
  try {
    const session = JSON.parse(raw);
    if (!session.expiresAt || Number(session.expiresAt) <= Date.now() || session.state === "closed") {
      await kv.delete(lanSessionKey(dbName, sessionId));
      return null;
    }
    return session;
  } catch (_error) {
    await kv.delete(lanSessionKey(dbName, sessionId));
    return null;
  }
}

async function sessionAllowsLan(kv, dbName, lanSession, userId) {
  if (String(lanSession.hostUserId) === String(userId)) return true;
  if (Array.isArray(lanSession.allowedUserIds) && lanSession.allowedUserIds.map(String).includes(String(userId))) return true;
  return lanSession.accessMode === "friends" && areFriends(kv, dbName, lanSession.hostUserId, userId);
}

function publicLanSession(lanSession, hostUser = null) {
  return {
    id: lanSession.id,
    hostUserId: String(lanSession.hostUserId),
    hostName: hostUser ? `${hostUser.firstName || ""} ${hostUser.lastName || ""}`.trim() : "",
    title: lanSession.title,
    accessMode: lanSession.accessMode,
    state: lanSession.state,
    createdAt: lanSession.createdAt,
    updatedAt: lanSession.updatedAt,
    expiresAt: lanSession.expiresAt,
    isHost: false
  };
}

function lanRelayUrl(request, sessionId, ticket) {
  const url = new URL(request.url);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = `/api/lan/relay/${encodeURIComponent(sessionId)}`;
  url.search = new URLSearchParams({ ticket }).toString();
  return url.toString();
}

async function issueLanTicket(env, dbName, lanSession, userId, role) {
  const ticket = crypto.randomUUID();
  const createdAt = Date.now();
  const expiresAt = Math.min(createdAt + LAN_TICKET_TTL_SECONDS * 1000, Number(lanSession.expiresAt));
  if (expiresAt <= createdAt) throw new Error("LAN session has expired");
  const ticketRecord = {
    ticket,
    dbName,
    sessionId: lanSession.id,
    userId: String(userId),
    role,
    createdAt,
    expiresAt
  };
  await registerLanRelayTicket(env, dbName, lanSession.id, ticketRecord);
  return ticketRecord;
}

async function initialiseLanRelay(env, dbName, lanSession) {
  if (!env.LAN_RELAY) return;
  const relay = env.LAN_RELAY.get(lanRelayId(env, dbName, lanSession.id));
  await relay.fetch("https://lan-relay.internal/initialize", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ dbName, sessionId: lanSession.id, expiresAt: lanSession.expiresAt })
  });
}

async function registerLanRelayTicket(env, dbName, sessionId, ticketRecord) {
  if (!env.LAN_RELAY) throw new Error("LAN relay is not configured");
  const relay = env.LAN_RELAY.get(lanRelayId(env, dbName, sessionId));
  const response = await relay.fetch("https://lan-relay.internal/ticket", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(ticketRecord)
  });
  if (!response.ok) throw new Error(`Unable to register LAN relay ticket (${response.status})`);
}

async function closeLanRelay(env, dbName, sessionId, code = 4001, reason = "LAN session closed") {
  if (!env.LAN_RELAY) return;
  const relay = env.LAN_RELAY.get(lanRelayId(env, dbName, sessionId));
  await relay.fetch("https://lan-relay.internal/close", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, reason })
  });
}

async function hasAdminAccess(request, env) {
  const configuredSecret = String(env?.ADMIN_SECRET || "");
  const header = request.headers.get("Authorization") || "";
  const match = header.match(/^Admin\s+(.+)$/i);
  if (!configuredSecret || !match) return false;

  const suppliedSecret = match[1].trim();
  if (suppliedSecret.length !== configuredSecret.length) return false;
  let difference = 0;
  for (let index = 0; index < suppliedSecret.length; index++) {
    difference |= suppliedSecret.charCodeAt(index) ^ configuredSecret.charCodeAt(index);
  }
  return difference === 0;
}

async function listAllKeys(kv, prefix) {
  const keys = [];
  let cursor;
  do {
    const page = await kv.list({ prefix, cursor });
    keys.push(...page.keys);
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return keys;
}

async function listUserSessions(kv, dbName, userId) {
  const sessionKeys = await listAllKeys(kv, `${dbName}:session:`);
  const sessions = [];
  for (const keyInfo of sessionKeys) {
    const raw = await kv.get(keyInfo.name);
    if (!raw) continue;
    try {
      const session = JSON.parse(raw);
      if (String(session.userId) !== String(userId)) continue;
      sessions.push({
        tokenHint: keyInfo.name.substring(`${dbName}:session:`.length).slice(-8),
        createdAt: session.createdAt,
        expiresAt: session.expiresAt,
        active: Number(session.expiresAt || 0) > Date.now(),
        deviceName: session.deviceName || "",
        deviceType: session.deviceType || "unknown",
        appName: session.appName || "Unknown_App"
      });
    } catch (_error) {}
  }
  return sessions.sort((left, right) => Number(right.createdAt || 0) - Number(left.createdAt || 0));
}

async function deleteUserRecords(kv, dbName, user, env = {}) {
  const userId = String(user.id);
  const userKey = `${dbName}:user:${userId}`;
  await kv.delete(userKey);
  await kv.delete(`${dbName}:email_to_id:${String(user.email || "").toLowerCase()}`);

  for (const keyInfo of await listAllKeys(kv, `${dbName}:file:${userId}:`)) await kv.delete(keyInfo.name);
  for (const keyInfo of await listAllKeys(kv, `${dbName}:security_events:${userId}`)) await kv.delete(keyInfo.name);
  for (const keyInfo of await listAllKeys(kv, `${dbName}:notifications:${userId}`)) await kv.delete(keyInfo.name);

  for (const keyInfo of await listAllKeys(kv, `${dbName}:session:`)) {
    const raw = await kv.get(keyInfo.name);
    try {
      if (raw && String(JSON.parse(raw).userId) === userId) await kv.delete(keyInfo.name);
    } catch (_error) {}
  }

  for (const keyInfo of await listAllKeys(kv, `${dbName}:friend:`)) {
    const raw = await kv.get(keyInfo.name);
    try {
      const relation = raw && JSON.parse(raw);
      if (relation && (String(relation.requesterId) === userId || String(relation.targetId) === userId)) await kv.delete(keyInfo.name);
    } catch (_error) {}
  }

  for (const keyInfo of await listAllKeys(kv, `${dbName}:block:`)) {
    const raw = await kv.get(keyInfo.name);
    try {
      const block = raw && JSON.parse(raw);
      if (block && (String(block.userId) === userId || String(block.targetId) === userId)) await kv.delete(keyInfo.name);
    } catch (_error) {}
  }

  for (const keyInfo of await listAllKeys(kv, `${dbName}:group:`)) {
    const raw = await kv.get(keyInfo.name);
    try {
      const group = raw && JSON.parse(raw);
      if (!group || !Array.isArray(group.memberIds) || !group.memberIds.map(String).includes(userId)) continue;
      if (String(group.ownerId) === userId) {
        await kv.delete(keyInfo.name);
        await kv.delete(`${dbName}:group_chat:${group.id}`);
      } else {
        group.memberIds = group.memberIds.map(String).filter(memberId => memberId !== userId);
        await kv.put(keyInfo.name, JSON.stringify(group));
      }
    } catch (_error) {}
  }

  for (const keyInfo of await listAllKeys(kv, `${dbName}:minecraft_session:`)) {
    const raw = await kv.get(keyInfo.name);
    try {
      const session = raw && JSON.parse(raw);
      if (!session) continue;
      if (String(session.ownerId) === userId) {
        await kv.delete(keyInfo.name);
      } else if (Array.isArray(session.allowedUserIds) && session.allowedUserIds.map(String).includes(userId)) {
        session.allowedUserIds = session.allowedUserIds.map(String).filter(allowedId => allowedId !== userId);
        await kv.put(keyInfo.name, JSON.stringify(session));
      }
    } catch (_error) {}
  }

  for (const keyInfo of await listAllKeys(kv, `${dbName}:lan_session:`)) {
    const raw = await kv.get(keyInfo.name);
    try {
      const lanSession = raw && JSON.parse(raw);
      if (!lanSession) continue;
      if (String(lanSession.hostUserId) === userId) {
        await kv.delete(keyInfo.name);
        await closeLanRelay(env, dbName, lanSession.id, 4001, "Host account deleted");
      } else if (Array.isArray(lanSession.allowedUserIds) && lanSession.allowedUserIds.map(String).includes(userId)) {
        lanSession.allowedUserIds = lanSession.allowedUserIds.map(String).filter(allowedId => allowedId !== userId);
        lanSession.updatedAt = Date.now();
        await kv.put(keyInfo.name, JSON.stringify(lanSession), {
          expirationTtl: Math.max(1, Math.ceil((Number(lanSession.expiresAt) - Date.now()) / 1000))
        });
      }
    } catch (_error) {}
  }
}

async function handleAdminRequest(request, url, kv, dbName, env = {}) {
  const path = url.pathname;
  const method = request.method;
  const userMatch = path.match(/^\/api\/admin\/users\/([^/]+)$/);
  const sessionMatch = path.match(/^\/api\/admin\/users\/([^/]+)\/sessions$/);
  const banMatch = path.match(/^\/api\/admin\/users\/([^/]+)\/ban$/);

  if (path === "/api/admin/stats" && method === "GET") {
    const userKeys = await listAllKeys(kv, `${dbName}:user:`);
    const sessionKeys = await listAllKeys(kv, `${dbName}:session:`);
    let activeSessions = 0;
    for (const keyInfo of sessionKeys) {
      const raw = await kv.get(keyInfo.name);
      try { if (raw && Number(JSON.parse(raw).expiresAt || 0) > Date.now()) activeSessions++; } catch (_error) {}
    }
    return jsonResponse({ users: userKeys.length, sessions: sessionKeys.length, activeSessions, generatedAt: Date.now() });
  }

  if (path === "/api/admin/users" && method === "GET") {
    const query = String(url.searchParams.get("query") || "").trim().toLowerCase();
    const userKeys = await listAllKeys(kv, `${dbName}:user:`);
    const users = [];
    for (const keyInfo of userKeys) {
      const raw = await kv.get(keyInfo.name);
      try {
        const user = raw && JSON.parse(raw);
        if (!user) continue;
        const searchable = `${user.id} ${user.email || ""} ${user.firstName || ""} ${user.lastName || ""}`.toLowerCase();
        if (!query || searchable.includes(query)) users.push(sanitizeUser(user));
      } catch (_error) {}
    }
    users.sort((left, right) => Number(right.createdAt || 0) - Number(left.createdAt || 0));
    return jsonResponse({ users, total: users.length });
  }

  if (sessionMatch && method === "GET") {
    const user = await readUser(kv, dbName, sessionMatch[1]);
    if (!user) return errorResponse("User not found", 404);
    return jsonResponse({ user: sanitizeUser(user), sessions: await listUserSessions(kv, dbName, user.id) });
  }

  if (banMatch && method === "PUT") {
    const user = await readUser(kv, dbName, banMatch[1]);
    if (!user) return errorResponse("User not found", 404);
    const body = await request.json();
    user.isBanned = Boolean(body.banned);
    user.banReason = user.isBanned ? String(body.reason || "Administrative restriction").slice(0, 500) : "";
    user.bannedAt = user.isBanned ? Date.now() : null;
    await kv.put(`${dbName}:user:${user.id}`, JSON.stringify(user));
    if (user.isBanned) {
      const allSessionKeys = await listAllKeys(kv, `${dbName}:session:`);
      for (const keyInfo of allSessionKeys) {
        const raw = await kv.get(keyInfo.name);
        try { if (raw && String(JSON.parse(raw).userId) === String(user.id)) await kv.delete(keyInfo.name); } catch (_error) {}
      }
    }
    return jsonResponse({ status: "success", user: sanitizeUser(user) });
  }

  if (userMatch && method === "DELETE") {
    const user = await readUser(kv, dbName, userMatch[1]);
    if (!user) return errorResponse("User not found", 404);
    await deleteUserRecords(kv, dbName, user, env);
    return jsonResponse({ status: "success", deletedUserId: String(user.id) });
  }

  return errorResponse("Admin route not found", 404);
}

async function handleFetch(request, env = {}, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    // Handle CORS preflight options
    if (method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: corsHeaders()
      });
    }

    // Initialize KV store check
    const kv = env.ORVEXAAUTH_KV || (typeof ORVEXAAUTH_KV !== "undefined" ? ORVEXAAUTH_KV : null);
    if (!kv) {
      return jsonResponse({
        status: "error",
        message: "ORVEXAAUTH_KV namespace is not bound. Please bind ORVEXAAUTH_KV in Worker Settings."
      }, 500);
    }

    // Get database partition name
    const dbName = (request.headers.get("X-Database-Name") || "default")
      .trim()
      .replace(/[^a-zA-Z0-9_-]/g, "") || "default";

    // LAN relay tickets are verified inside the Durable Object. They must not pass the
    // REST service-key and bearer-session checks because a Minecraft TCP bridge only has
    // the short-lived, one-time relay ticket embedded in its WSS address.
    if (/^\/api\/lan\/relay\/[^/]+$/.test(path)) {
      const sessionId = path.split("/")[4];
      if (!env.LAN_RELAY) return errorResponse("LAN relay is not configured", 503);
      return env.LAN_RELAY.get(lanRelayId(env, dbName, sessionId)).fetch(request);
    }

    // Allow status check without service key
    if (path === "/api/status" && method === "GET") {
      return jsonResponse({
        status: "ok",
        message: `OrvexaAuth Beta Cloudflare Server Active (DB: ${dbName})`,
        serverTime: Date.now()
      });
    }

    if (path.startsWith("/api/admin/")) {
      if (!(await hasAdminAccess(request, env))) return errorResponse("Forbidden: valid administrator credentials are required", 403);
      try {
        return await handleAdminRequest(request, url, kv, dbName, env);
      } catch (error) {
        console.error("OrvexaAuth admin route error", error);
        return errorResponse("Administrative request failed", 500);
      }
    }

    // Verify service key for all other secure endpoints
    if (!checkServiceKey(request)) {
      return errorResponse("Unauthorized: Invalid or missing X-Service-Key header", 403);
    }

    // Credential exchange and token validation/revocation are public; data routes require a bearer session.
    const isPublicRoute =
      (path === "/api/register" && method === "POST") ||
      (path === "/api/login" && method === "POST") ||
      (path === "/api/sessions" && method === "POST") ||
      (path.startsWith("/api/sessions/") && (method === "GET" || method === "DELETE")) ||
      (path === "/api/qr/sessions" && method === "POST") ||
      (path.startsWith("/api/qr/sessions/") && method === "GET");
    const authSession = isPublicRoute ? null : await requireAuth(request, kv, dbName);
    if (!isPublicRoute && !authSession) {
      return errorResponse("Unauthorized: a valid Bearer session is required", 401);
    }
    if (authSession) {
      const sessionUser = await readUser(kv, dbName, authSession.userId);
      if (!sessionUser) return errorResponse("Unauthorized: account no longer exists", 401);
      if (sessionUser.isBanned) return errorResponse("Account is restricted", 403);
    }

    try {
      // User enumeration is an administrator-only operation.
      if (path === "/api/users" && method === "GET") {
        return errorResponse("Forbidden: use the administrator API", 403);
      }

      // 2. REGISTER USER
      if (path === "/api/register" && method === "POST") {
        const body = await request.json();
        const email = (body.email || "").trim().toLowerCase();
        const passwordHash = body.passwordHash;

        if (!email || !passwordHash) {
          return errorResponse("Email and passwordHash are required", 400);
        }

        const emailKey = `${dbName}:email_to_id:${email}`;
        const existingId = await kv.get(emailKey);
        if (existingId) {
          return errorResponse("Email already registered", 409);
        }

        // Generate deterministic 32-bit positive integer ID based on email hash
        let hash = 0;
        for (let i = 0; i < email.length; i++) {
          hash = (hash << 5) - hash + email.charCodeAt(i);
          hash |= 0;
        }
        const userId = Math.abs(hash) % 1000000000;

        const profileData = {
          id: userId,
          email,
          passwordHash,
          firstName: body.firstName || "",
          lastName: body.lastName || "",
          birthDate: body.birthDate || "",
          gender: body.gender || "Rather not say",
          avatarColor: typeof body.avatarColor === "number" ? body.avatarColor : -12543232,
          keyProtect: body.keyProtect || "",
          createdAt: Date.now()
        };

        const userKey = `${dbName}:user:${userId}`;
        await kv.put(userKey, JSON.stringify(profileData));
        await kv.put(emailKey, userId.toString());

        const session = await createSession(kv, dbName, profileData, request, body);

        // Log history activity
        await logAppEvent(kv, dbName, email, "register", request.headers.get("X-App-Name") || body.appName);
        await writeUserEvent(kv, dbName, userId, "account_registered", request, {}, env, ctx);

        return jsonResponse({ ...sanitizeUser(profileData), sessionToken: session.token, expiresAt: session.expiresAt }, 201);
      }

      // 3. LOGIN USER
      if (path === "/api/login" && method === "POST") {
        const body = await request.json();
        const email = (body.email || "").trim().toLowerCase();
        const passwordHash = body.passwordHash;

        if (!email || !passwordHash) {
          return errorResponse("Email and passwordHash are required", 400);
        }

        const emailKey = `${dbName}:email_to_id:${email}`;
        const userId = await kv.get(emailKey);
        if (!userId) {
          return errorResponse("User account not found", 404);
        }

        const userKey = `${dbName}:user:${userId}`;
        const rawUser = await kv.get(userKey);
        if (!rawUser) {
          return errorResponse("User account not found", 404);
        }

        const profileData = JSON.parse(rawUser);
        if (profileData.passwordHash !== passwordHash) {
          return errorResponse("Invalid password", 401);
        }
        if (profileData.isBanned) {
          return errorResponse("Account is restricted", 403);
        }
        if (profileData.totpSecret && !(await verifyTotp(profileData.totpSecret, body.totpCode))) {
          return errorResponse("Two-factor authentication code is required or invalid", 428);
        }

        const session = await createSession(kv, dbName, profileData, request, body);

        // Log history activity
        await logAppEvent(kv, dbName, email, "login", request.headers.get("X-App-Name") || body.appName);
        await writeUserEvent(kv, dbName, userId, "session_created", request, { deviceType: session.deviceType }, env, ctx);

        return jsonResponse({ ...sanitizeUser(profileData), sessionToken: session.token, expiresAt: session.expiresAt }, 200);
      }

      // 4. CREATE, VALIDATE, AND REVOKE SESSIONS
      if (path === "/api/sessions" && method === "POST") {
        const body = await request.json();
        const email = (body.email || "").trim().toLowerCase();
        const user = await findUserByCredentials(kv, dbName, email, body.passwordHash);
        if (!user) return errorResponse("Invalid email or password", 401);
        if (user.totpSecret && !(await verifyTotp(user.totpSecret, body.totpCode))) {
          return errorResponse("Two-factor authentication code is required or invalid", 428);
        }
        const session = await createSession(kv, dbName, user, request, body);
        await writeUserEvent(kv, dbName, user.id, "session_created", request, { deviceType: session.deviceType }, env, ctx);
        return jsonResponse({ token: session.token, sessionToken: session.token, expiresAt: session.expiresAt, user: sanitizeUser(user) }, 201);
      }

      if (path.startsWith("/api/sessions/") && (method === "GET" || method === "DELETE")) {
        const token = path.substring("/api/sessions/".length).trim();
        const session = await getSession(kv, dbName, token);
        if (method === "DELETE") {
          if (!session) return errorResponse("Session not found", 404);
          await kv.delete(`${dbName}:session:${token}`);
          return jsonResponse({ status: "success", message: "Session revoked" });
        }
        if (!session) return errorResponse("Session not found or expired", 401);
        return jsonResponse({ valid: true, ...session });
      }

      // 4b. QR LOGIN: a desktop client creates a short-lived request; an authenticated mobile session approves it.
      if (path === "/api/qr/sessions" && method === "POST") {
        const body = await request.json();
        const requestId = crypto.randomUUID();
        const createdAt = Date.now();
        const expiresAt = createdAt + 5 * 60 * 1000;
        const qrRequest = {
          id: requestId,
          status: "pending",
          createdAt,
          expiresAt,
          deviceName: String(body.deviceName || "Desktop browser").slice(0, 120),
          deviceType: String(body.deviceType || "desktop").slice(0, 40),
          appName: String(body.appName || "OrvexaAuth Web").slice(0, 120)
        };
        await kv.put(`${dbName}:qr:${requestId}`, JSON.stringify(qrRequest), { expirationTtl: 5 * 60 });
        // The Android client handles this documented deep link and then calls the
        // authenticated approval endpoint. Keep the QR payload stable across web
        // and desktop clients instead of exposing an API-shaped pseudo URL.
        return jsonResponse({ requestId, status: qrRequest.status, expiresAt, approveUrl: `orvexaauth://qr/approve?request=${encodeURIComponent(requestId)}` }, 201);
      }

      if (/^\/api\/qr\/sessions\/[^/]+$/.test(path) && method === "GET") {
        const requestId = path.split("/")[4];
        const raw = await kv.get(`${dbName}:qr:${requestId}`);
        if (!raw) return errorResponse("QR request not found or expired", 404);
        const qrRequest = JSON.parse(raw);
        if (qrRequest.expiresAt <= Date.now()) return errorResponse("QR request expired", 410);
        const response = { requestId: qrRequest.id, status: qrRequest.status, expiresAt: qrRequest.expiresAt };
        if (qrRequest.status === "approved") {
          response.sessionToken = qrRequest.sessionToken;
          response.user = sanitizeUser(await readUser(kv, dbName, qrRequest.userId));
        }
        return jsonResponse(response);
      }

      if (/^\/api\/qr\/sessions\/[^/]+\/approve$/.test(path) && method === "POST") {
        const requestId = path.split("/")[4];
        const raw = await kv.get(`${dbName}:qr:${requestId}`);
        if (!raw) return errorResponse("QR request not found or expired", 404);
        const qrRequest = JSON.parse(raw);
        if (qrRequest.expiresAt <= Date.now()) return errorResponse("QR request expired", 410);
        if (qrRequest.status !== "pending") return errorResponse("QR request was already handled", 409);
        const user = await readUser(kv, dbName, authSession.userId);
        if (!user) return errorResponse("User account not found", 404);
        const session = await createSession(kv, dbName, user, request, qrRequest);
        qrRequest.status = "approved";
        qrRequest.userId = String(user.id);
        qrRequest.approvedAt = Date.now();
        qrRequest.sessionToken = session.token;
        await kv.put(`${dbName}:qr:${requestId}`, JSON.stringify(qrRequest), { expirationTtl: Math.max(1, Math.ceil((qrRequest.expiresAt - Date.now()) / 1000)) });
        await writeUserEvent(kv, dbName, user.id, "qr_login_approved", request, { deviceName: qrRequest.deviceName }, env, ctx);
        return jsonResponse({ status: "approved", expiresAt: session.expiresAt });
      }

      // 5. PROFILES, ACTIVE SESSIONS, DEVICES, FRIENDS, BLOCKS, AND GROUPS
      if (/^\/api\/users\/[^/]+$/.test(path) && method === "GET") {
        const userId = path.split("/")[3];
        const user = await readUser(kv, dbName, userId);
        if (!user) return errorResponse("User not found", 404);
        const sessionList = await kv.list({ prefix: `${dbName}:session:` });
        let online = false;
        for (const item of sessionList.keys) {
          const raw = await kv.get(item.name);
          if (raw) {
            try {
              const session = JSON.parse(raw);
              if (String(session.userId) === String(userId) && session.expiresAt > Date.now()) { online = true; break; }
            } catch (_e) {}
          }
        }
        return jsonResponse({ ...sanitizeUser(user), online });
      }

      if (/^\/api\/users\/[^/]+\/(sessions|devices)$/.test(path) && method === "GET") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const sessionList = await kv.list({ prefix: `${dbName}:session:` });
        const sessions = [];
        for (const item of sessionList.keys) {
          const raw = await kv.get(item.name);
          if (!raw) continue;
          try {
            const session = JSON.parse(raw);
            if (String(session.userId) === String(userId) && session.expiresAt > Date.now()) {
              const token = item.name.substring(`${dbName}:session:`.length);
              sessions.push({ tokenHint: token.slice(-8), createdAt: session.createdAt, expiresAt: session.expiresAt, deviceName: session.deviceName || "", deviceType: session.deviceType || "unknown", appName: session.appName || "" });
            }
          } catch (_e) {}
        }
        return jsonResponse(sessions);
      }

      if (/^\/api\/users\/[^/]+\/sessions$/.test(path) && method === "DELETE") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const sessionList = await kv.list({ prefix: `${dbName}:session:` });
        let deleted = 0;
        for (const item of sessionList.keys) {
          const raw = await kv.get(item.name);
          if (!raw) continue;
          try {
            if (String(JSON.parse(raw).userId) === String(userId)) { await kv.delete(item.name); deleted++; }
          } catch (_e) {}
        }
        return jsonResponse({ status: "success", deleted });
      }

      if (path === "/api/friends/request" && method === "POST") {
        const body = await request.json();
        const targetId = await resolveUserId(kv, dbName, body.targetUserId || body.targetEmail);
        if (!targetId) return errorResponse("Target user not found", 404);
        if (String(targetId) === String(authSession.userId)) return errorResponse("Cannot add yourself", 400);
        if (await kv.get(`${dbName}:block:${authSession.userId}:${targetId}`) || await kv.get(`${dbName}:block:${targetId}:${authSession.userId}`)) return errorResponse("Friend request blocked", 403);
        const key = pairKey(dbName, authSession.userId, targetId);
        const existing = await kv.get(key);
        if (existing) {
          const relation = JSON.parse(existing);
          if (relation.status === "accepted") return errorResponse("Users are already friends", 409);
          return errorResponse("Friend request already exists", 409);
        }
        const relation = { requesterId: String(authSession.userId), targetId: String(targetId), status: "pending", createdAt: Date.now(), updatedAt: Date.now() };
        await kv.put(key, JSON.stringify(relation));
        return jsonResponse({ status: "success", ...relation }, 201);
      }

      if (/^\/api\/friends\/[^/]+$/.test(path) && method === "GET") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const result = await kv.list({ prefix: `${dbName}:friend:` });
        const friends = [];
        for (const item of result.keys) {
          const raw = await kv.get(item.name);
          if (!raw) continue;
          try {
            const relation = JSON.parse(raw);
            if (relation.status !== "accepted" && relation.requesterId !== String(userId) && relation.targetId !== String(userId)) continue;
            const otherId = relation.requesterId === String(userId) ? relation.targetId : relation.requesterId;
            const other = await readUser(kv, dbName, otherId);
            friends.push({ ...relation, user: other ? sanitizeUser(other) : null, direction: relation.requesterId === String(userId) ? "outgoing" : "incoming" });
          } catch (_e) {}
        }
        return jsonResponse(friends);
      }

      if (/^\/api\/friends\/[^/]+\/accept$/.test(path) && method === "PUT") {
        const requesterId = path.split("/")[3];
        const key = pairKey(dbName, authSession.userId, requesterId);
        const raw = await kv.get(key);
        if (!raw) return errorResponse("Friend request not found", 404);
        const relation = JSON.parse(raw);
        if (String(relation.targetId) !== String(authSession.userId) || String(relation.requesterId) !== String(requesterId) || relation.status !== "pending") return errorResponse("Invalid friend request", 403);
        relation.status = "accepted"; relation.updatedAt = Date.now();
        await kv.put(key, JSON.stringify(relation));
        return jsonResponse({ status: "success", ...relation });
      }

      if (/^\/api\/friends\/[^/]+$/.test(path) && method === "DELETE") {
        const otherId = path.split("/")[3];
        await kv.delete(pairKey(dbName, authSession.userId, otherId));
        return jsonResponse({ status: "success", message: "Friend relation removed" });
      }

      if (path === "/api/blocks" && method === "POST") {
        const body = await request.json();
        const targetId = await resolveUserId(kv, dbName, body.targetUserId || body.targetEmail);
        if (!targetId) return errorResponse("Target user not found", 404);
        if (String(targetId) === String(authSession.userId)) return errorResponse("Cannot block yourself", 400);
        await kv.put(`${dbName}:block:${authSession.userId}:${targetId}`, JSON.stringify({ userId: String(authSession.userId), targetId: String(targetId), createdAt: Date.now() }));
        await kv.delete(pairKey(dbName, authSession.userId, targetId));
        return jsonResponse({ status: "success", targetId: String(targetId) }, 201);
      }

      if (/^\/api\/blocks\/[^/]+$/.test(path) && method === "GET") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const result = await kv.list({ prefix: `${dbName}:block:${userId}:` });
        const blocks = [];
        for (const item of result.keys) {
          const raw = await kv.get(item.name);
          if (!raw) continue;
          try {
            const block = JSON.parse(raw);
            const target = await readUser(kv, dbName, block.targetId);
            blocks.push({ ...block, user: target ? sanitizeUser(target) : null });
          } catch (_e) {}
        }
        return jsonResponse(blocks);
      }

      if (/^\/api\/blocks\/[^/]+\/[^/]+$/.test(path) && method === "DELETE") {
        const parts = path.split("/");
        if (String(authSession.userId) !== String(parts[3])) return errorResponse("Forbidden", 403);
        await kv.delete(`${dbName}:block:${parts[3]}:${parts[4]}`);
        return jsonResponse({ status: "success", message: "User unblocked" });
      }

      if (path === "/api/groups" && method === "GET") {
        const result = await kv.list({ prefix: `${dbName}:group:` });
        const groups = [];
        for (const item of result.keys) {
          const raw = await kv.get(item.name);
          if (!raw) continue;
          try {
            const group = JSON.parse(raw);
            if (Array.isArray(group.memberIds) && group.memberIds.map(String).includes(String(authSession.userId))) {
              groups.push(group);
            }
          } catch (_e) {}
        }
        return jsonResponse(groups.sort((left, right) => Number(right.createdAt || 0) - Number(left.createdAt || 0)));
      }

      if (path === "/api/groups" && method === "POST") {
        const body = await request.json();
        const name = String(body.name || "").trim().slice(0, 120);
        if (!name) return errorResponse("Group name is required", 400);
        const groupId = crypto.randomUUID();
        const group = { id: groupId, name, description: String(body.description || "").slice(0, 500), ownerId: String(authSession.userId), memberIds: [String(authSession.userId)], createdAt: Date.now() };
        await kv.put(`${dbName}:group:${groupId}`, JSON.stringify(group));
        return jsonResponse(group, 201);
      }

      if (/^\/api\/groups\/[^/]+$/.test(path) && method === "GET") {
        const groupId = path.split("/")[3];
        const group = await groupForMember(kv, dbName, groupId, authSession.userId);
        if (!group) return errorResponse("Group not found or access denied", 404);
        const members = [];
        for (const memberId of group.memberIds) {
          const member = await readUser(kv, dbName, memberId);
          if (member) members.push(sanitizeUser(member));
        }
        return jsonResponse({ ...group, members });
      }

      if (/^\/api\/groups\/[^/]+\/members$/.test(path) && method === "POST") {
        const groupId = path.split("/")[3];
        const raw = await kv.get(`${dbName}:group:${groupId}`);
        if (!raw) return errorResponse("Group not found", 404);
        const group = JSON.parse(raw);
        if (String(group.ownerId) !== String(authSession.userId)) return errorResponse("Only the group owner can add members", 403);
        const targetId = await resolveUserId(kv, dbName, (await request.json()).targetUserId);
        if (!targetId) return errorResponse("Target user not found", 404);
        if (!group.memberIds.map(String).includes(String(targetId))) group.memberIds.push(String(targetId));
        await kv.put(`${dbName}:group:${groupId}`, JSON.stringify(group));
        return jsonResponse(group);
      }

      if (/^\/api\/groups\/[^/]+\/messages$/.test(path) && (method === "GET" || method === "POST")) {
        const groupId = path.split("/")[3];
        const group = await groupForMember(kv, dbName, groupId, authSession.userId);
        if (!group) return errorResponse("Group not found or access denied", 404);
        const key = `${dbName}:group_chat:${groupId}`;
        const raw = await kv.get(key);
        const messages = raw ? JSON.parse(raw) : [];
        if (method === "GET") return jsonResponse(messages);
        const body = await request.json();
        const messageText = String(body.text || "").trim();
        if (!messageText) return errorResponse("Message text is required", 400);
        const message = { id: messages.length + 1, senderId: String(authSession.userId), text: messageText.slice(0, 4000), timestamp: Date.now() };
        messages.push(message);
        await kv.put(key, JSON.stringify(messages.slice(-500)));
        return jsonResponse(message, 201);
      }

      // 5b. SECURITY CENTER, TOTP, NOTIFICATIONS, AND MINECRAFT ACCESS CONTROL
      if (/^\/api\/users\/[^/]+\/totp\/setup$/.test(path) && method === "POST") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const user = await readUser(kv, dbName, userId);
        if (!user) return errorResponse("User not found", 404);
        const secret = newTotpSecret();
        user.totpPendingSecret = secret;
        user.totpPendingExpiresAt = Date.now() + 10 * 60 * 1000;
        await kv.put(`${dbName}:user:${userId}`, JSON.stringify(user));
        const label = encodeURIComponent(`OrvexaAuth:${user.email}`);
        return jsonResponse({ secret, expiresAt: user.totpPendingExpiresAt, otpauthUri: `otpauth://totp/${label}?secret=${secret}&issuer=OrvexaAuth&algorithm=SHA1&digits=6&period=30` }, 201);
      }

      if (/^\/api\/users\/[^/]+\/totp\/confirm$/.test(path) && method === "POST") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const user = await readUser(kv, dbName, userId);
        const body = await request.json();
        if (!user || !user.totpPendingSecret || user.totpPendingExpiresAt <= Date.now()) return errorResponse("TOTP setup expired; create a new setup request", 410);
        if (!(await verifyTotp(user.totpPendingSecret, body.code))) return errorResponse("Invalid authentication code", 400);
        user.totpSecret = user.totpPendingSecret;
        delete user.totpPendingSecret;
        delete user.totpPendingExpiresAt;
        await kv.put(`${dbName}:user:${userId}`, JSON.stringify(user));
        await writeUserEvent(kv, dbName, userId, "totp_enabled", request, {}, env, ctx);
        return jsonResponse({ status: "success", enabled: true });
      }

      if (/^\/api\/users\/[^/]+\/totp$/.test(path) && method === "DELETE") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const user = await readUser(kv, dbName, userId);
        const body = await request.json();
        if (!user || !user.totpSecret) return errorResponse("Two-factor authentication is not enabled", 404);
        if (!(await verifyTotp(user.totpSecret, body.code))) return errorResponse("Invalid authentication code", 400);
        delete user.totpSecret;
        await kv.put(`${dbName}:user:${userId}`, JSON.stringify(user));
        await writeUserEvent(kv, dbName, userId, "totp_disabled", request, {}, env, ctx);
        return jsonResponse({ status: "success", enabled: false });
      }

      if (/^\/api\/users\/[^/]+\/events$/.test(path) && method === "GET") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const raw = await kv.get(`${dbName}:security_events:${userId}`);
        return jsonResponse(raw ? JSON.parse(raw).slice(-100).reverse() : []);
      }

      if (/^\/api\/users\/[^/]+\/notifications$/.test(path) && method === "GET") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const raw = await kv.get(`${dbName}:notifications:${userId}`);
        return jsonResponse(raw ? JSON.parse(raw).slice(-100).reverse() : []);
      }

      if (/^\/api\/users\/[^/]+\/notifications\/read$/.test(path) && method === "POST") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const raw = await kv.get(`${dbName}:notifications:${userId}`);
        const notifications = raw ? JSON.parse(raw) : [];
        const body = await request.json();
        const ids = Array.isArray(body.ids) ? new Set(body.ids.map(String)) : null;
        for (const notification of notifications) {
          if (!ids || ids.has(String(notification.id))) notification.read = true;
        }
        await kv.put(`${dbName}:notifications:${userId}`, JSON.stringify(notifications.slice(-200)));
        return jsonResponse({ status: "success" });
      }

      if (/^\/api\/users\/[^/]+\/push-subscriptions$/.test(path) && method === "POST") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) !== String(userId)) return errorResponse("Forbidden", 403);
        const body = await request.json();
        if (!body.endpoint || !body.keys?.p256dh || !body.keys?.auth) return errorResponse("A valid Web Push subscription is required", 400);
        const key = `${dbName}:push_subscriptions:${userId}`;
        const raw = await kv.get(key);
        const subscriptions = raw ? JSON.parse(raw) : [];
        const deduplicated = subscriptions.filter(item => item.endpoint !== body.endpoint);
        deduplicated.push({ endpoint: String(body.endpoint).slice(0, 2000), keys: { p256dh: String(body.keys.p256dh).slice(0, 600), auth: String(body.keys.auth).slice(0, 300) }, createdAt: Date.now() });
        await kv.put(key, JSON.stringify(deduplicated.slice(-10)));
        return jsonResponse({ status: "success", subscriptions: deduplicated.length });
      }

      if (path === "/api/minecraft/sessions" && method === "POST") {
        const body = await request.json();
        const title = String(body.title || "Minecraft session").trim().slice(0, 120);
        if (!title) return errorResponse("Session title is required", 400);
        const minecraftSession = {
          id: crypto.randomUUID(),
          ownerId: String(authSession.userId),
          title,
          address: String(body.address || "").trim().slice(0, 253),
          port: Number.isInteger(body.port) ? body.port : 25565,
          accessMode: ["private", "friends", "invite"].includes(body.accessMode) ? body.accessMode : "invite",
          allowedUserIds: [],
          createdAt: Date.now(),
          updatedAt: Date.now()
        };
        await kv.put(`${dbName}:minecraft_session:${minecraftSession.id}`, JSON.stringify(minecraftSession));
        await writeUserEvent(kv, dbName, authSession.userId, "minecraft_session_created", request, { sessionId: minecraftSession.id }, env, ctx);
        return jsonResponse(minecraftSession, 201);
      }

      if (path === "/api/minecraft/sessions" && method === "GET") {
        const result = await kv.list({ prefix: `${dbName}:minecraft_session:` });
        const sessions = [];
        for (const key of result.keys) {
          const raw = await kv.get(key.name);
          if (!raw) continue;
          const minecraftSession = JSON.parse(raw);
          if (await sessionAllowsMinecraft(kv, dbName, minecraftSession, authSession.userId)) sessions.push(minecraftSession);
        }
        return jsonResponse(sessions);
      }

      if (/^\/api\/minecraft\/sessions\/[^/]+$/.test(path) && method === "GET") {
        const sessionId = path.split("/")[4];
        const raw = await kv.get(`${dbName}:minecraft_session:${sessionId}`);
        if (!raw) return errorResponse("Minecraft session not found", 404);
        const minecraftSession = JSON.parse(raw);
        if (!(await sessionAllowsMinecraft(kv, dbName, minecraftSession, authSession.userId))) return errorResponse("You do not have access to this Minecraft session", 403);
        return jsonResponse(minecraftSession);
      }

      if (/^\/api\/minecraft\/sessions\/[^/]+\/invites$/.test(path) && method === "POST") {
        const sessionId = path.split("/")[4];
        const raw = await kv.get(`${dbName}:minecraft_session:${sessionId}`);
        if (!raw) return errorResponse("Minecraft session not found", 404);
        const minecraftSession = JSON.parse(raw);
        if (String(minecraftSession.ownerId) !== String(authSession.userId)) return errorResponse("Only the session owner can manage invites", 403);
        const targetId = await resolveUserId(kv, dbName, (await request.json()).targetUserId);
        if (!targetId) return errorResponse("Target user not found", 404);
        if (String(targetId) !== String(authSession.userId) && !(await areFriends(kv, dbName, authSession.userId, targetId))) return errorResponse("Only accepted friends can be invited", 403);
        if (!minecraftSession.allowedUserIds.map(String).includes(String(targetId))) minecraftSession.allowedUserIds.push(String(targetId));
        minecraftSession.updatedAt = Date.now();
        await kv.put(`${dbName}:minecraft_session:${sessionId}`, JSON.stringify(minecraftSession));
        await writeUserEvent(kv, dbName, targetId, "minecraft_invite_received", request, { sessionId, title: minecraftSession.title }, env, ctx);
        return jsonResponse(minecraftSession);
      }

      if (/^\/api\/minecraft\/sessions\/[^/]+\/invites\/[^/]+$/.test(path) && method === "DELETE") {
        const parts = path.split("/");
        const sessionId = parts[4];
        const targetId = parts[6];
        const raw = await kv.get(`${dbName}:minecraft_session:${sessionId}`);
        if (!raw) return errorResponse("Minecraft session not found", 404);
        const minecraftSession = JSON.parse(raw);
        if (String(minecraftSession.ownerId) !== String(authSession.userId)) return errorResponse("Only the session owner can manage invites", 403);
        minecraftSession.allowedUserIds = minecraftSession.allowedUserIds.filter(id => String(id) !== String(targetId));
        minecraftSession.updatedAt = Date.now();
        await kv.put(`${dbName}:minecraft_session:${sessionId}`, JSON.stringify(minecraftSession));
        return jsonResponse(minecraftSession);
      }

      if (/^\/api\/minecraft\/sessions\/[^/]+\/join-check$/.test(path) && method === "POST") {
        const sessionId = path.split("/")[4];
        const raw = await kv.get(`${dbName}:minecraft_session:${sessionId}`);
        if (!raw) return errorResponse("Minecraft session not found", 404);
        const minecraftSession = JSON.parse(raw);
        const allowed = await sessionAllowsMinecraft(kv, dbName, minecraftSession, authSession.userId);
        return jsonResponse({ allowed, sessionId, accessMode: minecraftSession.accessMode });
      }

      // 5c. VIRTUAL LAN GAME SESSIONS. The Worker never stores a home address,
      // Minecraft port or bearer token; the Durable Object only relays a single
      // authenticated host/guest binary stream after each side consumes a ticket.
      if (/^\/api\/lan\/relay\/[^/]+$/.test(path) && method === "GET") {
        const sessionId = path.split("/")[4];
        const ticket = String(url.searchParams.get("ticket") || "").trim();
        if (!ticket) return errorResponse("A LAN relay ticket is required", 401);
        if (!env.LAN_RELAY) return errorResponse("LAN relay is not configured", 503);
        const relay = env.LAN_RELAY.get(lanRelayId(env, dbName, sessionId));
        return relay.fetch(request);
      }

      if (path === "/api/lan/sessions" && method === "POST") {
        if (!env.LAN_RELAY) return errorResponse("LAN relay is not configured", 503);
        const body = await request.json();
        const title = String(body.title || body.worldName || "Minecraft LAN world").trim().slice(0, 120);
        if (!title) return errorResponse("World name is required", 400);
        const accessMode = body.accessMode === "friends" ? "friends" : "invite";
        const requestedInvitees = Array.isArray(body.allowedUserIds) ? body.allowedUserIds : [];
        const allowedUserIds = [];
        for (const requestedId of requestedInvitees.slice(0, 20)) {
          const targetId = await resolveUserId(kv, dbName, requestedId);
          if (!targetId) return errorResponse("Invited account not found", 404);
          if (String(targetId) === String(authSession.userId)) continue;
          if (!(await areFriends(kv, dbName, authSession.userId, targetId))) {
            return errorResponse("Only accepted friends can be invited", 403);
          }
          if (!allowedUserIds.includes(String(targetId))) allowedUserIds.push(String(targetId));
        }
        const createdAt = Date.now();
        const lanSession = {
          id: crypto.randomUUID(),
          hostUserId: String(authSession.userId),
          title,
          accessMode,
          allowedUserIds,
          state: "open",
          createdAt,
          updatedAt: createdAt,
          expiresAt: createdAt + LAN_SESSION_TTL_SECONDS * 1000
        };
        await kv.put(lanSessionKey(dbName, lanSession.id), JSON.stringify(lanSession), {
          expirationTtl: LAN_SESSION_TTL_SECONDS
        });
        await initialiseLanRelay(env, dbName, lanSession);
        await writeUserEvent(kv, dbName, authSession.userId, "lan_session_created", request,
                             { sessionId: lanSession.id, title }, env, ctx);
        const hostTicket = await issueLanTicket(env, dbName, lanSession, authSession.userId, "host");
        return jsonResponse({
          session: { ...publicLanSession(lanSession), isHost: true, allowedUserIds },
          hostTicket: hostTicket.ticket,
          ticketExpiresAt: hostTicket.expiresAt,
          relayUrl: lanRelayUrl(request, lanSession.id, hostTicket.ticket)
        }, 201);
      }

      if (path === "/api/lan/sessions" && method === "GET") {
        const keys = await listAllKeys(kv, `${dbName}:lan_session:`);
        const sessions = [];
        for (const keyInfo of keys) {
          const raw = await kv.get(keyInfo.name);
          if (!raw) continue;
          try {
            const lanSession = JSON.parse(raw);
            if (Number(lanSession.expiresAt) <= Date.now() || lanSession.state !== "open") {
              await kv.delete(keyInfo.name);
              continue;
            }
            if (!(await sessionAllowsLan(kv, dbName, lanSession, authSession.userId))) continue;
            const host = await readUser(kv, dbName, lanSession.hostUserId);
            sessions.push({
              ...publicLanSession(lanSession, host),
              isHost: String(lanSession.hostUserId) === String(authSession.userId),
              ...(String(lanSession.hostUserId) === String(authSession.userId)
                ? { allowedUserIds: lanSession.allowedUserIds }
                : {})
            });
          } catch (_error) {}
        }
        sessions.sort((left, right) => Number(right.createdAt) - Number(left.createdAt));
        return jsonResponse({ sessions });
      }

      if (/^\/api\/lan\/sessions\/[^/]+$/.test(path) && method === "GET") {
        const sessionId = path.split("/")[4];
        const lanSession = await readLanSession(kv, dbName, sessionId);
        if (!lanSession) return errorResponse("LAN session not found or expired", 404);
        if (!(await sessionAllowsLan(kv, dbName, lanSession, authSession.userId))) {
          return errorResponse("You do not have access to this LAN session", 403);
        }
        const host = await readUser(kv, dbName, lanSession.hostUserId);
        return jsonResponse({
          session: {
            ...publicLanSession(lanSession, host),
            isHost: String(lanSession.hostUserId) === String(authSession.userId),
            ...(String(lanSession.hostUserId) === String(authSession.userId)
              ? { allowedUserIds: lanSession.allowedUserIds }
              : {})
          }
        });
      }

      if (/^\/api\/lan\/sessions\/[^/]+\/host-ticket$/.test(path) && method === "POST") {
        const sessionId = path.split("/")[4];
        const lanSession = await readLanSession(kv, dbName, sessionId);
        if (!lanSession) return errorResponse("LAN session not found or expired", 404);
        if (String(lanSession.hostUserId) !== String(authSession.userId)) {
          return errorResponse("Only the LAN host can request a host ticket", 403);
        }
        const ticket = await issueLanTicket(env, dbName, lanSession, authSession.userId, "host");
        return jsonResponse({ sessionId, role: "host", ticket: ticket.ticket, expiresAt: ticket.expiresAt,
                              relayUrl: lanRelayUrl(request, sessionId, ticket.ticket) });
      }

      if (/^\/api\/lan\/sessions\/[^/]+\/join-ticket$/.test(path) && method === "POST") {
        const sessionId = path.split("/")[4];
        const lanSession = await readLanSession(kv, dbName, sessionId);
        if (!lanSession) return errorResponse("LAN session not found or expired", 404);
        if (String(lanSession.hostUserId) === String(authSession.userId)) {
          return errorResponse("The LAN host must use a host ticket", 409);
        }
        if (!(await sessionAllowsLan(kv, dbName, lanSession, authSession.userId))) {
          return errorResponse("You do not have access to this LAN session", 403);
        }
        const ticket = await issueLanTicket(env, dbName, lanSession, authSession.userId, "guest");
        await writeUserEvent(kv, dbName, authSession.userId, "lan_join_ticket_issued", request,
                             { sessionId, title: lanSession.title }, env, ctx);
        return jsonResponse({ sessionId, role: "guest", ticket: ticket.ticket, expiresAt: ticket.expiresAt,
                              relayUrl: lanRelayUrl(request, sessionId, ticket.ticket) });
      }

      if (/^\/api\/lan\/sessions\/[^/]+\/invites$/.test(path) && method === "POST") {
        const sessionId = path.split("/")[4];
        const lanSession = await readLanSession(kv, dbName, sessionId);
        if (!lanSession) return errorResponse("LAN session not found or expired", 404);
        if (String(lanSession.hostUserId) !== String(authSession.userId)) {
          return errorResponse("Only the LAN host can manage invites", 403);
        }
        const targetId = await resolveUserId(kv, dbName, (await request.json()).targetUserId);
        if (!targetId) return errorResponse("Invited account not found", 404);
        if (String(targetId) !== String(authSession.userId) && !(await areFriends(kv, dbName, authSession.userId, targetId))) {
          return errorResponse("Only accepted friends can be invited", 403);
        }
        if (!lanSession.allowedUserIds.map(String).includes(String(targetId))) lanSession.allowedUserIds.push(String(targetId));
        lanSession.updatedAt = Date.now();
        await kv.put(lanSessionKey(dbName, sessionId), JSON.stringify(lanSession), {
          expirationTtl: Math.max(1, Math.ceil((Number(lanSession.expiresAt) - Date.now()) / 1000))
        });
        await writeUserEvent(kv, dbName, targetId, "lan_invite_received", request,
                             { sessionId, title: lanSession.title }, env, ctx);
        return jsonResponse({ session: { ...publicLanSession(lanSession), isHost: true, allowedUserIds: lanSession.allowedUserIds } });
      }

      if (/^\/api\/lan\/sessions\/[^/]+\/invites\/[^/]+$/.test(path) && method === "DELETE") {
        const parts = path.split("/");
        const sessionId = parts[4];
        const targetId = parts[6];
        const lanSession = await readLanSession(kv, dbName, sessionId);
        if (!lanSession) return errorResponse("LAN session not found or expired", 404);
        if (String(lanSession.hostUserId) !== String(authSession.userId)) {
          return errorResponse("Only the LAN host can manage invites", 403);
        }
        lanSession.allowedUserIds = lanSession.allowedUserIds.map(String).filter(id => id !== String(targetId));
        lanSession.updatedAt = Date.now();
        await kv.put(lanSessionKey(dbName, sessionId), JSON.stringify(lanSession), {
          expirationTtl: Math.max(1, Math.ceil((Number(lanSession.expiresAt) - Date.now()) / 1000))
        });
        return jsonResponse({ session: { ...publicLanSession(lanSession), isHost: true, allowedUserIds: lanSession.allowedUserIds } });
      }

      if (/^\/api\/lan\/sessions\/[^/]+$/.test(path) && method === "DELETE") {
        const sessionId = path.split("/")[4];
        const lanSession = await readLanSession(kv, dbName, sessionId);
        if (!lanSession) return errorResponse("LAN session not found or expired", 404);
        if (String(lanSession.hostUserId) !== String(authSession.userId)) {
          return errorResponse("Only the LAN host can close this session", 403);
        }
        await kv.delete(lanSessionKey(dbName, sessionId));
        await closeLanRelay(env, dbName, sessionId);
        await writeUserEvent(kv, dbName, authSession.userId, "lan_session_closed", request,
                             { sessionId, title: lanSession.title }, env, ctx);
        return jsonResponse({ status: "success", sessionId });
      }

      // 6. UPDATE PROFILE
      if (path.startsWith("/api/users/") && method === "PUT" && !path.endsWith("/password")) {
        const userId = path.split("/")[3];
        if (String(authSession.userId) != String(userId)) return errorResponse("Forbidden", 403);
        const userKey = `${dbName}:user:${userId}`;
        const rawUser = await kv.get(userKey);
        if (!rawUser) {
          return errorResponse("User not found", 404);
        }

        const body = await request.json();
        const profileData = JSON.parse(rawUser);

        profileData.firstName = body.firstName !== undefined ? body.firstName : profileData.firstName;
        profileData.lastName = body.lastName !== undefined ? body.lastName : profileData.lastName;
        profileData.birthDate = body.birthDate !== undefined ? body.birthDate : profileData.birthDate;
        profileData.gender = body.gender !== undefined ? body.gender : profileData.gender;
        profileData.phoneNumber = body.phoneNumber !== undefined ? body.phoneNumber : profileData.phoneNumber;
        profileData.recoveryEmail = body.recoveryEmail !== undefined ? body.recoveryEmail : profileData.recoveryEmail;

        await kv.put(userKey, JSON.stringify(profileData));
        return jsonResponse(sanitizeUser(profileData), 200);
      }

      // 5. UPDATE PASSWORD
      if (path.startsWith("/api/users/") && path.endsWith("/password") && method === "PUT") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) != String(userId)) return errorResponse("Forbidden", 403);
        const userKey = `${dbName}:user:${userId}`;
        const rawUser = await kv.get(userKey);
        if (!rawUser) {
          return errorResponse("User not found", 404);
        }

        const body = await request.json();
        const passwordHash = body.passwordHash;
        if (!passwordHash) {
          return errorResponse("passwordHash is required", 400);
        }

        const profileData = JSON.parse(rawUser);
        profileData.passwordHash = passwordHash;

        await kv.put(userKey, JSON.stringify(profileData));
        return jsonResponse({ status: "success", message: "Password successfully updated" }, 200);
      }

      // 6. DELETE USER
      if (/^\/api\/users\/[^/]+$/.test(path) && method === "DELETE") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) != String(userId)) return errorResponse("Forbidden", 403);
        const userKey = `${dbName}:user:${userId}`;
        const rawUser = await kv.get(userKey);
        if (!rawUser) {
          return errorResponse("User not found", 404);
        }
        const body = await request.json();
        const profileData = JSON.parse(rawUser);
        if (!body.passwordHash || body.passwordHash !== profileData.passwordHash) {
          return errorResponse("Current password is incorrect", 401);
        }
        await deleteUserRecords(kv, dbName, profileData, env);
        return jsonResponse({ status: "success", message: "User account deleted" }, 200);
      }

      // 7. LIST FILES
      if (path.startsWith("/api/users/") && path.endsWith("/storage") && method === "GET") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) != String(userId)) return errorResponse("Forbidden", 403);
        const filePrefix = `${dbName}:file:${userId}:`;
        const listResult = await kv.list({ prefix: filePrefix });
        
        const filesList = [];
        for (const k of listResult.keys) {
          const meta = k.metadata || {};
          const displayName = k.name.substring(filePrefix.length);
          filesList.push({
            name: displayName,
            size: meta.size || 0,
            updatedAt: meta.updatedAt || Date.now()
          });
        }
        return jsonResponse(filesList);
      }

      // 8. UPLOAD FILE
      if (path.startsWith("/api/users/") && path.endsWith("/storage") && method === "POST") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) != String(userId)) return errorResponse("Forbidden", 403);
        const body = await request.json();
        const fileName = (body.fileName || "").split("/").pop(); // sanitize path
        const content = body.content || "";
        const contentBase64 = body.contentBase64 || "";

        if (!fileName) {
          return errorResponse("fileName is required", 400);
        }

        const fileKey = `${dbName}:file:${userId}:${fileName}`;
        let fileData;
        let size = 0;

        if (contentBase64) {
          // Decode Base64 to ArrayBuffer for binary safety
          const binaryStr = atob(contentBase64);
          const bytes = new Uint8Array(binaryStr.length);
          for (let i = 0; i < binaryStr.length; i++) {
            bytes[i] = binaryStr.charCodeAt(i);
          }
          fileData = bytes.buffer;
          size = bytes.length;
        } else {
          fileData = content;
          size = new Blob([content]).size;
        }

        // Store content alongside metadata
        await kv.put(fileKey, fileData, {
          metadata: {
            size,
            updatedAt: Date.now()
          }
        });

        return jsonResponse({
          status: "success",
          message: "File uploaded successfully",
          file: {
            name: fileName,
            size,
            updatedAt: Date.now()
          }
        });
      }

      // 9. DOWNLOAD FILE
      if (path.startsWith("/api/users/") && path.includes("/storage/") && method === "GET") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) != String(userId)) return errorResponse("Forbidden", 403);
        const fileName = path.substring(path.indexOf("/storage/") + 9);
        const fileKey = `${dbName}:file:${userId}:${fileName}`;

        const fileMeta = await kv.getWithMetadata(fileKey, { type: "arrayBuffer" });
        if (!fileMeta || !fileMeta.value) {
          return errorResponse("File not found", 404);
        }

        return new Response(fileMeta.value, {
          status: 200,
          headers: {
            "Content-Type": "application/octet-stream",
            "Content-Disposition": `attachment; filename="${fileName}"`,
            ...corsHeaders()
          }
        });
      }

      // 10. DELETE FILE
      if (path.startsWith("/api/users/") && path.includes("/storage/") && method === "DELETE") {
        const userId = path.split("/")[3];
        if (String(authSession.userId) != String(userId)) return errorResponse("Forbidden", 403);
        const fileName = path.substring(path.indexOf("/storage/") + 9);
        const fileKey = `${dbName}:file:${userId}:${fileName}`;

        const exists = await kv.get(fileKey);
        if (!exists) {
          return errorResponse("File not found", 404);
        }

        await kv.delete(fileKey);
        return jsonResponse({ status: "success", message: "File deleted successfully" });
      }

      // 12. GET MESSAGES
      if (path === "/api/messages" && method === "GET") {
        const user1 = (url.searchParams.get("user1") || "").trim().toLowerCase();
        const user2 = (url.searchParams.get("user2") || "").trim().toLowerCase();

        if (!user1 || !user2) {
          return jsonResponse([]);
        }

        if (user1 !== String(authSession.email).toLowerCase() && user2 !== String(authSession.email).toLowerCase()) {
          return errorResponse("Forbidden", 403);
        }
        const sortedUsers = [user1, user2].sort();
        const chatKey = `${dbName}:chat:${sortedUsers[0]}_and_${sortedUsers[1]}`;
        const rawMessages = await kv.get(chatKey);
        
        if (rawMessages) {
          return jsonResponse(JSON.parse(rawMessages));
        }
        return jsonResponse([]);
      }

      // 12. SEND MESSAGE
      if (path === "/api/messages" && method === "POST") {
        const body = await request.json();
        const sender = (body.senderEmail || "").trim().toLowerCase();
        const receiver = (body.receiverEmail || "").trim().toLowerCase();
        const text = (body.text || "").trim();

        if (!sender || !receiver || !text) {
          return errorResponse("senderEmail, receiverEmail and text are required", 400);
        }
        if (sender !== String(authSession.email).toLowerCase()) return errorResponse("Forbidden", 403);

        const sortedUsers = [sender, receiver].sort();
        const chatKey = `${dbName}:chat:${sortedUsers[0]}_and_${sortedUsers[1]}`;
        
        const rawMessages = await kv.get(chatKey);
        const messages = rawMessages ? JSON.parse(rawMessages) : [];

        const msgId = messages.length + 1;
        const newMsg = {
          id: msgId,
          senderEmail: sender,
          receiverEmail: receiver,
          text,
          timestamp: Date.now()
        };
        messages.push(newMsg);

        await kv.put(chatKey, JSON.stringify(messages));
        return jsonResponse({ status: "success", message: "Message sent", id: msgId });
      }

      // 15. CLEAR DATABASE PARTITION is disabled in the public beta.
      if (path === "/api/database/clear" && method === "POST") {
        return errorResponse("Database clearing is disabled in the public beta", 403);
      }

      // Route not found
      return errorResponse("Route not found", 404);

    } catch (e) {
      return errorResponse(`Server Error: ${e.message}`, 500);
    }
  }

export default {
  fetch(request, env, ctx) {
    return handleFetch(request, env, ctx);
  }
};

// Cloudflare Durable Object for exactly one authenticated host/guest pair.
// It forwards binary Minecraft TCP frames untouched and accepts only tickets
// minted by the API above; it cannot connect to arbitrary network addresses.
export class LanRelay {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.hostSocket = null;
    this.guestSocket = null;
    this.sessionMeta = null;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/initialize" && request.method === "POST") {
      const meta = await request.json();
      if (!meta.dbName || !meta.sessionId || !Number(meta.expiresAt)) return new Response("Invalid session metadata", { status: 400 });
      this.sessionMeta = { dbName: String(meta.dbName), sessionId: String(meta.sessionId), expiresAt: Number(meta.expiresAt) };
      await this.state.storage.put("sessionMeta", this.sessionMeta);
      await this.state.storage.setAlarm(this.sessionMeta.expiresAt);
      return new Response(null, { status: 204 });
    }

    if (url.pathname === "/close" && request.method === "POST") {
      const detail = await request.json().catch(() => ({}));
      this.closeAll(Number(detail.code) || 4001, String(detail.reason || "LAN session closed"));
      await this.state.storage.deleteAll();
      return new Response(null, { status: 204 });
    }

    if (url.pathname === "/ticket" && request.method === "POST") {
      const ticket = await request.json();
      const sessionMeta = await this.getSessionMeta();
      if (!sessionMeta || String(ticket?.dbName) !== sessionMeta.dbName || String(ticket?.sessionId) !== sessionMeta.sessionId ||
          !ticket?.ticket || !['host', 'guest'].includes(ticket?.role) || Number(ticket?.expiresAt) <= Date.now()) {
        return new Response("Invalid LAN relay ticket", { status: 400 });
      }
      await this.state.storage.put(`ticket:${ticket.ticket}`, {
        ticket: String(ticket.ticket),
        dbName: String(ticket.dbName),
        sessionId: String(ticket.sessionId),
        userId: String(ticket.userId),
        role: String(ticket.role),
        createdAt: Number(ticket.createdAt),
        expiresAt: Number(ticket.expiresAt)
      });
      return new Response(null, { status: 204 });
    }

    if (request.headers.get("Upgrade") !== "websocket") return new Response("WebSocket upgrade required", { status: 426 });
    const ticketValue = String(url.searchParams.get("ticket") || "").trim();
    if (!ticketValue) return new Response("LAN relay ticket is required", { status: 401 });

    const sessionMeta = await this.getSessionMeta();
    if (!sessionMeta) return new Response("LAN relay session is not initialized", { status: 404 });
    const ticket = await this.state.storage.get(`ticket:${ticketValue}`);
    if (!ticket || sessionMeta.sessionId !== ticket.sessionId || Number(ticket.expiresAt) <= Date.now()) {
      return new Response("LAN relay ticket is no longer valid", { status: 401 });
    }
    if ((ticket.role === "host" && this.hostSocket) || (ticket.role === "guest" && this.guestSocket)) {
      return new Response("This LAN session role is already connected", { status: 409 });
    }
    if (!['host', 'guest'].includes(ticket.role)) return new Response("Invalid LAN relay role", { status: 401 });

    // Durable Objects serialize events, so deleting after the occupancy check
    // makes the ticket one-time even when the caller retries rapidly.
    await this.state.storage.delete(`ticket:${ticketValue}`);
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();
    this.attachSocket(ticket.role, server);
    this.emitReady();
    return new Response(null, { status: 101, webSocket: client });
  }

  async alarm() {
    this.closeAll(4008, "LAN session expired");
    const meta = await this.getSessionMeta();
    if (meta) await this.env.ORVEXAAUTH_KV.delete(`${meta.dbName}:lan_session:${meta.sessionId}`);
    await this.state.storage.deleteAll();
  }

  async getSessionMeta() {
    if (!this.sessionMeta) this.sessionMeta = await this.state.storage.get("sessionMeta");
    return this.sessionMeta;
  }

  attachSocket(role, socket) {
    if (role === "host") this.hostSocket = socket;
    else this.guestSocket = socket;
    socket.addEventListener("message", event => {
      const target = role === "host" ? this.guestSocket : this.hostSocket;
      if (!target) return;
      try { target.send(event.data); } catch (_error) { this.closeSocket(role === "host" ? "guest" : "host", 1011, "Relay delivery failed"); }
    });
    socket.addEventListener("close", () => this.handleSocketClosed(role));
    socket.addEventListener("error", () => this.handleSocketClosed(role));
  }

  handleSocketClosed(role) {
    if (role === "host") {
      this.hostSocket = null;
      this.closeSocket("guest", 4002, "LAN host disconnected");
    } else {
      this.guestSocket = null;
      this.sendControl(this.hostSocket, { type: "guest_disconnected" });
    }
  }

  emitReady() {
    if (this.hostSocket && this.guestSocket) {
      this.sendControl(this.hostSocket, { type: "ready", peer: "guest" });
      this.sendControl(this.guestSocket, { type: "ready", peer: "host" });
    }
  }

  sendControl(socket, message) {
    if (!socket) return;
    try { socket.send(JSON.stringify(message)); } catch (_error) {}
  }

  closeSocket(role, code, reason) {
    const socket = role === "host" ? this.hostSocket : this.guestSocket;
    if (!socket) return;
    if (role === "host") this.hostSocket = null;
    else this.guestSocket = null;
    try { socket.close(code, reason); } catch (_error) {}
  }

  closeAll(code, reason) {
    this.closeSocket("host", code, reason);
    this.closeSocket("guest", code, reason);
  }
}

// Logging function to save history
async function logAppEvent(kv, dbName, email, action, appNameInput) {
  let appName = appNameInput || "Unknown_App";
  const sanitizedAppName = appName.replace(/[^a-zA-Z0-9_-]/g, "") || "Unknown_App";
  
  const historyKey = `${dbName}:history:${sanitizedAppName}`;
  const rawHistory = await kv.get(historyKey);
  let history = rawHistory ? JSON.parse(rawHistory) : [];

  history.push({
    email,
    action,
    timestamp: Date.now()
  });

  if (history.length > 1000) {
    history = history.slice(-1000);
  }

  await kv.put(historyKey, JSON.stringify(history));
}
