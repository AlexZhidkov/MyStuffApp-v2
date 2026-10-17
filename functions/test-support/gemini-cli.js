import { execFile } from "node:child_process";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";

// Real installed client, isolated from the Member's settings and credentials.
export async function isolatedGeminiCli(t) {
  const directory = await mkdtemp(join(tmpdir(), "mystuff-gemini-"));
  const previousHome = process.env.GEMINI_CLI_HOME;
  process.env.GEMINI_CLI_HOME = directory;
  t.after(async () => {
    if (previousHome === undefined) delete process.env.GEMINI_CLI_HOME;
    else process.env.GEMINI_CLI_HOME = previousHome;
    await rm(directory, { recursive: true, force: true });
  });
  const { MCPOAuthTokenStorage, createTransport } = await import(pathToFileURL(process.env.GEMINI_CLI_BUNDLE));
  return {
    storage: new MCPOAuthTokenStorage(),
    createTransport,
    async configure(mcpServers) {
      await mkdir(join(directory, ".gemini"), { recursive: true });
      await writeFile(join(directory, ".gemini/settings.json"), JSON.stringify({ mcpServers }));
      await writeFile(join(directory, ".gemini/trustedFolders.json"), JSON.stringify({ [directory]: "TRUST_FOLDER" }));
    },
    async list() {
      const result = await promisify(execFile)(process.execPath,
        [join(dirname(process.env.GEMINI_CLI_BUNDLE), "gemini.js"), "mcp", "list"],
        { cwd: directory, env: process.env, timeout: 20000 });
      return result.stdout + result.stderr;
    },
  };
}
