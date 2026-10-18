import express from "express";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { mcpAuthRouter } from "@modelcontextprotocol/sdk/server/auth/router.js";
import { requireBearerAuth } from "@modelcontextprotocol/sdk/server/auth/middleware/bearerAuth.js";
import { OAuthError, ServerError } from "@modelcontextprotocol/sdk/server/auth/errors.js";
import { z } from "zod";
import { createMcpOAuth, INVENTORY_READ_SCOPE } from "./mcp-oauth.js";
import { listMcpChildren, readMcpItem } from "./mcp-inventory.js";
import { searchMcpInventory } from "./mcp-search.js";

export function createMcpApp({ database, authentication, origin, firebaseConfig, now, logger = console }) {
  const canonicalOrigin = new URL(origin);
  if (canonicalOrigin.origin !== origin ||
      (canonicalOrigin.protocol !== "https:" &&
       !["localhost", "127.0.0.1"].includes(canonicalOrigin.hostname))) {
    throw new Error("MCP_ORIGIN must be an HTTPS origin (or local development origin).");
  }
  const app = express();
  const oauth = createMcpOAuth({ database, authentication, origin, now });
  app.disable("x-powered-by");
  app.use((_request, response, next) => {
    response.set({ "Cache-Control": "no-store", "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY",
      "Content-Security-Policy": "frame-ancestors 'none'; base-uri 'none'; form-action 'self'" });
    next();
  });
  app.get("/.well-known/oauth-authorization-server", (_request, response) => {
    response.json({
      issuer: `${origin}/`, authorization_endpoint: `${origin}/authorize`,
      token_endpoint: `${origin}/token`, registration_endpoint: `${origin}/register`,
      response_types_supported: ["code"], grant_types_supported: ["authorization_code", "refresh_token"],
      token_endpoint_auth_methods_supported: ["none", "client_secret_post"],
      code_challenge_methods_supported: ["S256"], scopes_supported: [INVENTORY_READ_SCOPE],
      authorization_response_iss_parameter_supported: true,
    });
  });
  app.use(mcpAuthRouter({
    provider: oauth.provider, issuerUrl: new URL(origin),
    resourceServerUrl: new URL(`${origin}/mcp`), scopesSupported: [INVENTORY_READ_SCOPE],
    resourceName: "MyStuff Inventory",
    authorizationOptions: { rateLimit: false },
    tokenOptions: { rateLimit: false },
    clientRegistrationOptions: { rateLimit: false },
  }));
  app.use(express.json());
  app.get("/oauth/config", (_request, response) => response.json(firebaseConfig));
  app.use("/oauth", express.static(new URL("./mcp-browser/", import.meta.url).pathname, {
    index: false, redirect: false, cacheControl: false,
  }));
  app.get("/oauth/approve", (_request, response) => {
    response.sendFile(new URL("./mcp-browser/approve.html", import.meta.url).pathname, { cacheControl: false });
  });
  for (const action of ["preview", "approve", "cancel"]) {
    app.post(`/oauth/${action}`, async (request, response, next) => {
      try {
        if (request.headers.origin !== origin || !request.is("application/json")) {
          response.status(403).json({ error: "invalid_request" });
          return;
        }
        response.json(await oauth[action](request));
      } catch (error) { next(error); }
    });
  }

  const authorize = requireBearerAuth({
    verifier: oauth.provider, requiredScopes: [INVENTORY_READ_SCOPE],
    expectedResource: new URL(`${origin}/mcp`),
    resourceMetadataUrl: `${origin}/.well-known/oauth-protected-resource/mcp`,
  });
  app.use("/mcp", (request, response, next) => {
    if (request.headers.origin && request.headers.origin !== origin) {
      response.status(403).end();
      return;
    }
    next();
  }, authorize);
  app.post("/mcp", async (request, response, next) => {
    const server = new McpServer({ name: "mystuff", version: "1.0.0" });
    server.registerTool("get_item", {
      title: "Read Item details",
      description: "Read a synced Item by stable ID in the approved Household. Returns its complete Item Path, name, and Description.",
      inputSchema: { itemId: z.string() },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    }, async ({ itemId }) => {
      try {
        const item = await readMcpItem(database, request.auth.extra.connectionId, itemId, now);
        return { content: [{ type: "text", text: JSON.stringify(item) }], structuredContent: item };
      } catch {
        return { isError: true, content: [{ type: "text", text: "Item or Inventory data is unavailable. Check the Item ID and current Household Access." }] };
      }
    });
    server.registerTool("list_children", {
      title: "List Child Items",
      description: "List up to 20 direct Child Item names and stable IDs in shared order. Omit parentItemId to start at the approved Household root. Pass nextCursor as cursor with the same parentItemId to continue; null means complete. Follow Child Item IDs to browse deeper.",
      inputSchema: { parentItemId: z.string().optional(), cursor: z.string().optional() },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    }, async (arguments_) => {
      try {
        const page = await listMcpChildren(database, request.auth.extra.connectionId, arguments_, now);
        return { content: [{ type: "text", text: JSON.stringify(page) }], structuredContent: page };
      } catch {
        return { isError: true, content: [{ type: "text", text: "Child Items or Inventory data is unavailable. Check the Parent Item ID, continuation marker, and current Household Access." }] };
      }
    });
    server.registerTool("search_inventory", {
      title: "Search Inventory",
      description: "Search synced Item names, Tags, and Descriptions in the approved Household. Returns complete Item Paths, names, Descriptions, and stable IDs. Literal-only fallback is marked while conceptual Search is unavailable. Returns up to 20 Items; pass nextCursor as cursor with the same query to continue; null means complete.",
      inputSchema: { query: z.string(), cursor: z.string().optional() },
      annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    }, async (arguments_) => {
      try {
        const page = await searchMcpInventory(database, request.auth.extra.connectionId, arguments_, now);
        return { content: [{ type: "text", text: JSON.stringify(page) }], structuredContent: page };
      } catch {
        return { isError: true, content: [{ type: "text", text: "Search or Inventory data is unavailable. Check the query, continuation marker, and current Household Access." }] };
      }
    });
    // Stateless JSON responses work across cold starts and instances, and fit
    // Firebase Hosting's HTTP function rewrite without sticky sessions or SSE.
    const transport = new StreamableHTTPServerTransport({
      sessionIdGenerator: undefined, enableJsonResponse: true,
    });
    response.on("close", () => { void server.close(); });
    try {
      await server.connect(transport);
      await transport.handleRequest(request, response, request.body);
    } catch (error) { next(error); }
  });
  app.all("/mcp", (_request, response) => response.status(405).set("Allow", "POST").end());
  app.use((error, _request, response, _next) => {
    if (response.headersSent) return;
    if (error instanceof OAuthError) {
      response.status(error instanceof ServerError ? 500 : 400).json(error.toResponseObject());
    } else {
      logger.error("MCP request failed", { name: error.name, code: error.code });
      response.status(503).json({ error: "temporarily_unavailable" });
    }
  });
  return app;
}
