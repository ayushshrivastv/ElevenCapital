import { createPublicKey, verify as verifySignature, type JsonWebKey } from 'node:crypto';
import { z } from 'zod';

const safeIdentifier = z.string().min(1).max(200).regex(/^[^\s\u0000-\u001f\u007f]+$/);
const PrivySubject = z.string().min(12).max(200).regex(/^did:privy:[A-Za-z0-9_-]+$/);
const JwtHeader = z.object({ alg: z.literal('ES256'), kid: safeIdentifier.optional(), typ: z.string().max(20).optional() }).passthrough();
const JwtClaims = z.object({
  iss: z.literal('privy.io'),
  aud: z.string(),
  sub: PrivySubject,
  sid: safeIdentifier,
  iat: z.number().int().positive(),
  exp: z.number().int().positive(),
}).passthrough();

const PublicJwk = z.object({
  kty: z.literal('EC'),
  crv: z.literal('P-256'),
  x: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
  y: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
  kid: safeIdentifier.optional(),
  use: z.literal('sig').optional(),
  alg: z.literal('ES256').optional(),
}).passthrough();
const Jwks = z.object({ keys: z.array(PublicJwk).min(1).max(8) }).strict();

export interface PrivyPrincipal { subject: string; sessionId: string }
export interface AccessTokenVerifier { verifyAuthorization(value: string | undefined): Promise<PrivyPrincipal> }
export interface JwksProvider { keys(forceRefresh?: boolean): Promise<JsonWebKey[]> }

export class WalletAuthenticationError extends Error {
  constructor(readonly code: 'authentication_required' | 'invalid_access_token' | 'authentication_unavailable',
    readonly statusCode: 401 | 503, message: string) { super(message); }
}
export class SubjectRateLimitError extends Error {}

function authenticationFailure(): never {
  throw new WalletAuthenticationError('invalid_access_token', 401, 'The secure wallet session is invalid or expired. Sign in again.');
}

function decodeBase64Url(value: string, maximumBytes: number): Buffer {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length > Math.ceil(maximumBytes * 4 / 3) + 2) authenticationFailure();
  const decoded = Buffer.from(value, 'base64url');
  if (decoded.length === 0 || decoded.length > maximumBytes || decoded.toString('base64url') !== value) authenticationFailure();
  return decoded;
}

function parseJsonPart<T>(value: string, maximumBytes: number, schema: z.ZodType<T>): T {
  try { return schema.parse(JSON.parse(decodeBase64Url(value, maximumBytes).toString('utf8'))); }
  catch (error) {
    if (error instanceof WalletAuthenticationError) throw error;
    return authenticationFailure();
  }
}

/** Strict local ES256 verification. Bearer tokens are never stored or logged. */
export class PrivyAccessTokenVerifier implements AccessTokenVerifier {
  constructor(private readonly appId: string, private readonly jwks: JwksProvider,
    private readonly now = () => Math.floor(Date.now() / 1_000)) {
    if (!safeIdentifier.safeParse(appId).success) throw new Error('PRIVY_APP_ID is required for wallet routes');
  }

  async verifyAuthorization(value: string | undefined): Promise<PrivyPrincipal> {
    if (typeof value !== 'string' || !/^Bearer [^\s]+$/.test(value) || value.length > 8_192) {
      throw new WalletAuthenticationError('authentication_required', 401, 'A verified Privy session is required.');
    }
    const token = value.slice(7);
    const parts = token.split('.');
    if (parts.length !== 3) authenticationFailure();
    const [encodedHeader, encodedClaims, encodedSignature] = parts as [string, string, string];
    const header = parseJsonPart(encodedHeader, 2_048, JwtHeader);
    const claims = parseJsonPart(encodedClaims, 6_144, JwtClaims);
    const signature = decodeBase64Url(encodedSignature, 80);
    const current = this.now();
    if (claims.aud !== this.appId || claims.exp <= current || claims.iat > current + 60 || claims.exp <= claims.iat ||
      claims.exp - claims.iat > 7_200 || claims.iat < current - 86_400) authenticationFailure();
    if (signature.length !== 64) authenticationFailure();

    const signingInput = Buffer.from(`${encodedHeader}.${encodedClaims}`, 'ascii');
    let keys: JsonWebKey[];
    try { keys = await this.jwks.keys(false); }
    catch { throw new WalletAuthenticationError('authentication_unavailable', 503, 'Wallet authentication is temporarily unavailable.'); }
    const verifyWith = (candidates: JsonWebKey[]): boolean => candidates.some(jwk => {
      try {
        const parsed = PublicJwk.parse(jwk);
        if (header.kid) {
          if (parsed.kid !== undefined && parsed.kid !== header.kid) return false;
          if (parsed.kid === undefined && candidates.length !== 1) return false;
        } else if (candidates.length !== 1) return false;
        return verifySignature('sha256', signingInput, { key: createPublicKey({ key: parsed as JsonWebKey, format: 'jwk' }), dsaEncoding: 'ieee-p1363' }, signature);
      } catch { return false; }
    });
    if (!verifyWith(keys)) {
      try { keys = await this.jwks.keys(true); }
      catch { throw new WalletAuthenticationError('authentication_unavailable', 503, 'Wallet authentication is temporarily unavailable.'); }
      if (!verifyWith(keys)) authenticationFailure();
    }
    return { subject: claims.sub, sessionId: claims.sid };
  }
}

/** Fetches the public verification key from Privy's public app configuration. */
export class PrivyPublicJwksProvider implements JwksProvider {
  private cached: { keys: JsonWebKey[]; expiresAt: number } | null = null;
  private inflight: Promise<JsonWebKey[]> | null = null;
  constructor(private readonly appId: string, private readonly appClientId: string,
    private readonly nativeAppId = 'com.elevencapital.app', private readonly now = Date.now,
    private readonly fetcher: typeof fetch = fetch) {
    if (![appId, appClientId, nativeAppId].every(value => safeIdentifier.safeParse(value).success)) {
      throw new Error('Privy public verification configuration is incomplete');
    }
  }

  async keys(forceRefresh = false): Promise<JsonWebKey[]> {
    if (!forceRefresh && this.cached && this.cached.expiresAt > this.now()) return this.cached.keys;
    if (this.inflight) return this.inflight;
    this.inflight = this.load().finally(() => { this.inflight = null; });
    return this.inflight;
  }

  private async load(): Promise<JsonWebKey[]> {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5_000);
    try {
      const response = await this.fetcher(`https://auth.privy.io/api/v1/apps/${encodeURIComponent(this.appId)}`, {
        signal: controller.signal,
        redirect: 'error',
        headers: {
          accept: 'application/json',
          'privy-app-id': this.appId,
          'privy-client-id': this.appClientId,
          'x-native-app-identifier': this.nativeAppId,
        },
      });
      if (!response.ok) throw new Error('Privy public configuration unavailable');
      const body = await response.text();
      if (Buffer.byteLength(body) > 256 * 1_024) throw new Error('Privy public configuration too large');
      const parsed = z.object({ verification_key: z.string().min(100).max(10_000) }).passthrough().parse(JSON.parse(body));
      // Privy's public app configuration currently returns a PEM with the
      // header, base64 body and footer concatenated onto one line. OpenSSL
      // requires line breaks around the body, so restore standard PEM framing.
      const compactPem = /^-----BEGIN PUBLIC KEY-----([A-Za-z0-9+/]+={0,2})-----END PUBLIC KEY-----$/.exec(parsed.verification_key);
      const verificationKey = compactPem
        ? `-----BEGIN PUBLIC KEY-----\n${compactPem[1]}\n-----END PUBLIC KEY-----\n`
        : parsed.verification_key;
      const exported = createPublicKey(verificationKey).export({ format: 'jwk' });
      const key = PublicJwk.parse({ ...exported, use: 'sig', alg: 'ES256' });
      const keys = Jwks.parse({ keys: [key] }).keys;
      this.cached = { keys, expiresAt: this.now() + 10 * 60_000 };
      return keys;
    } finally { clearTimeout(timeout); }
  }
}

export function configuredPrivyVerifier(environment: NodeJS.ProcessEnv = process.env): AccessTokenVerifier {
  const appId = environment.PRIVY_APP_ID;
  const appClientId = environment.PRIVY_APP_CLIENT_ID;
  if (!appId || !appClientId) return {
    verifyAuthorization: async () => { throw new WalletAuthenticationError('authentication_unavailable', 503,
      'Wallet authentication is not configured on this backend.'); },
  };
  return new PrivyAccessTokenVerifier(appId, new PrivyPublicJwksProvider(appId, appClientId,
    environment.PRIVY_NATIVE_APP_ID || 'com.elevencapital.app'));
}

export class SubjectRateLimiter {
  private readonly buckets = new Map<string, number[]>();
  constructor(private readonly now = Date.now, private readonly limit = 60, private readonly windowMs = 60_000,
    private readonly maximumSubjects = 10_000) {}
  take(subject: string): void {
    const threshold = this.now() - this.windowMs;
    const previous = (this.buckets.get(subject) ?? []).filter(value => value > threshold);
    if (previous.length >= this.limit) throw new SubjectRateLimitError('Wallet requests are temporarily limited. Try again shortly.');
    previous.push(this.now()); this.buckets.delete(subject); this.buckets.set(subject, previous);
    while (this.buckets.size > this.maximumSubjects) this.buckets.delete(this.buckets.keys().next().value!);
  }
}
