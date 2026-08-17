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

    // Allow status check without service key
    if (path === "/api/status" && method === "GET") {
      return jsonResponse({
        status: "ok",
        message: `OrvexaAuth Beta Cloudflare Server Active (DB: ${dbName})`,
        serverTime: Date.now()
      });
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
      (path.startsWith("/api/sessions/") && (method === "GET" || method === "DELETE"));
    const authSession = isPublicRoute ? null : await requireAuth(request, kv, dbName);
    if (!isPublicRoute && !authSession) {
      return errorResponse("Unauthorized: a valid Bearer session is required", 401);
    }

    try {
      // 1. GET ALL USERS
      if (path === "/api/users" && method === "GET") {
        const listPrefix = `${dbName}:user:`;
        const listResult = await kv.list({ prefix: listPrefix });
        const users = [];
        
        for (const keyInfo of listResult.keys) {
          const rawUser = await kv.get(keyInfo.name);
          if (rawUser) {
            try {
              const u = JSON.parse(rawUser);
              delete u.passwordHash; // Exclude sensitive hashes from listing
              users.push(u);
            } catch (e) {}
          }
        }
        return jsonResponse(users);
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
          phoneNumber: body.phoneNumber || "",
          recoveryEmail: body.recoveryEmail || "",
          keyProtect: body.keyProtect || "",
          createdAt: Date.now()
        };

        const userKey = `${dbName}:user:${userId}`;
        await kv.put(userKey, JSON.stringify(profileData));
        await kv.put(emailKey, userId.toString());

        const session = await createSession(kv, dbName, profileData, request, body);

        // Log history activity
        await logAppEvent(kv, dbName, email, "register", request.headers.get("X-App-Name") || body.appName);

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

        const session = await createSession(kv, dbName, profileData, request, body);

        // Log history activity
        await logAppEvent(kv, dbName, email, "login", request.headers.get("X-App-Name") || body.appName);

        return jsonResponse({ ...sanitizeUser(profileData), sessionToken: session.token, expiresAt: session.expiresAt }, 200);
      }

      // 4. CREATE, VALIDATE, AND REVOKE SESSIONS
      if (path === "/api/sessions" && method === "POST") {
        const body = await request.json();
        const email = (body.email || "").trim().toLowerCase();
        const user = await findUserByCredentials(kv, dbName, email, body.passwordHash);
        if (!user) return errorResponse("Invalid email or password", 401);
        const session = await createSession(kv, dbName, user, request, body);
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
      if (path.startsWith("/api/users/") && method === "DELETE" && !path.includes("/storage")) {
        const userId = path.split("/")[3];
        if (String(authSession.userId) != String(userId)) return errorResponse("Forbidden", 403);
        const userKey = `${dbName}:user:${userId}`;
        const rawUser = await kv.get(userKey);
        if (!rawUser) {
          return errorResponse("User not found", 404);
        }

        const profileData = JSON.parse(rawUser);
        const emailKey = `${dbName}:email_to_id:${profileData.email}`;

        // Delete user & mapping
        await kv.delete(userKey);
        await kv.delete(emailKey);

        // Delete all associated files
        const filePrefix = `${dbName}:file:${userId}:`;
        const fileList = await kv.list({ prefix: filePrefix });
        for (const f of fileList.keys) {
          await kv.delete(f.name);
        }

        return jsonResponse({ status: "success", message: "User account and files deleted successfully" }, 200);
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

addEventListener("fetch", event => {
  event.respondWith(handleFetch(event.request, {}, event));
});

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
