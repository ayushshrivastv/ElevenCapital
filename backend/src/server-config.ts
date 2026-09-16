import { constants } from 'node:fs';
import { open } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';

export const BACKEND_SECRETS_PATH = join(homedir(), 'Library', 'Application Support', 'ElevenCapital', 'backend-secrets.json');
const MAX_SECRETS_BYTES = 16_384;

type BackendSecrets = { JUPITER_API_KEY?: string };
export type BackendSecretStatus = Readonly<{ jupiterApiKeyConfigured: boolean }>;

function validApiKey(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 2_048 && value.trim() === value &&
    !/[\u0000-\u001f\u007f]/.test(value);
}

async function readSecrets(path: string): Promise<BackendSecrets> {
  let handle;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  } catch (failure) {
    if ((failure as NodeJS.ErrnoException).code === 'ENOENT') return {};
    throw new Error('Backend secrets file could not be opened securely.');
  }
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > MAX_SECRETS_BYTES) throw new Error('Backend secrets file is invalid.');
    if (process.platform !== 'win32') {
      const permissions = stat.mode & 0o777;
      if ((permissions & 0o077) !== 0 || (permissions & 0o400) === 0 || (permissions & 0o100) !== 0) {
        throw new Error('Backend secrets file permissions must be owner-only (chmod 600).');
      }
      if (typeof process.getuid === 'function' && stat.uid !== process.getuid()) {
        throw new Error('Backend secrets file must be owned by the backend user.');
      }
    }
    let parsed: unknown;
    try { parsed = JSON.parse(await handle.readFile({ encoding: 'utf8' })); }
    catch { throw new Error('Backend secrets file must contain valid JSON.'); }
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      throw new Error('Backend secrets file must contain a JSON object.');
    }
    const record = parsed as Record<string, unknown>;
    if (Object.keys(record).some(key => key !== 'JUPITER_API_KEY') ||
      (record.JUPITER_API_KEY !== undefined && !validApiKey(record.JUPITER_API_KEY))) {
      throw new Error('Backend secrets file contains unsupported or invalid configuration.');
    }
    return record.JUPITER_API_KEY === undefined ? {} : { JUPITER_API_KEY: record.JUPITER_API_KEY };
  } finally { await handle.close(); }
}

/** Load once from the local user profile before constructing any server-side clients. */
export async function configureBackendServerEnvironment(path = BACKEND_SECRETS_PATH): Promise<BackendSecretStatus> {
  const secrets = await readSecrets(path);
  if (secrets.JUPITER_API_KEY !== undefined) process.env.JUPITER_API_KEY = secrets.JUPITER_API_KEY;
  const configured = process.env.JUPITER_API_KEY;
  if (configured !== undefined && !validApiKey(configured)) throw new Error('Jupiter API key configuration is invalid.');
  return Object.freeze({ jupiterApiKeyConfigured: configured !== undefined });
}
