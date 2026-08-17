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

// OrvexaAuth Beta is intentionally public: authentication is handled by login/passwordHash.
// Do not put API secrets in this source file.

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

        // Log history activity
        await logAppEvent(kv, dbName, email, "register", request.headers.get("X-App-Name") || body.appName);

        return jsonResponse(profileData, 201);
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

        // Log history activity
        await logAppEvent(kv, dbName, email, "login", request.headers.get("X-App-Name") || body.appName);

        return jsonResponse(profileData, 200);
      }

      // 4. UPDATE PROFILE
      if (path.startsWith("/api/users/") && method === "PUT" && !path.endsWith("/password")) {
        const userId = path.split("/")[3];
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
        return jsonResponse(profileData, 200);
      }

      // 5. UPDATE PASSWORD
      if (path.startsWith("/api/users/") && path.endsWith("/password") && method === "PUT") {
        const userId = path.split("/")[3];
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
        const fileName = path.substring(path.indexOf("/storage/") + 9);
        const fileKey = `${dbName}:file:${userId}:${fileName}`;

        const exists = await kv.get(fileKey);
        if (!exists) {
          return errorResponse("File not found", 404);
        }

        await kv.delete(fileKey);
        return jsonResponse({ status: "success", message: "File deleted successfully" });
      }

      // 11. GET MESSAGES
      if (path === "/api/messages" && method === "GET") {
        const user1 = (url.searchParams.get("user1") || "").trim().toLowerCase();
        const user2 = (url.searchParams.get("user2") || "").trim().toLowerCase();

        if (!user1 || !user2) {
          return jsonResponse([]);
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

      // 13. CLEAR DATABASE PARTITION
      if (path === "/api/database/clear" && method === "POST") {
        const prefix = `${dbName}:`;
        const listResult = await kv.list({ prefix });
        for (const k of listResult.keys) {
          await kv.delete(k.name);
        }
        return jsonResponse({
          status: "success",
          message: `Database partition '${dbName}' has been completely wiped on the server`
        });
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
