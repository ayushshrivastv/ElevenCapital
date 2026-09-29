import { constants } from 'node:fs';
import { createHash, randomUUID } from 'node:crypto';
import { link, mkdir, open, readdir, rm } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { isPublicHttpsUrl, TOKEN_METADATA_MAX_IMAGE_BYTES, type TokenImageMediaType,
  type TokenMetadataPublication, type TokenMetadataRequest } from './token-metadata-schema.js';

const HASH = /^[a-f0-9]{64}$/;
const IMAGE_FILENAME = /^([a-f0-9]{64})\.(jpg|png|webp)$/;
const DOCUMENT_FILENAME = /^([a-f0-9]{64})\.json$/;
const PUBLIC_PATH = '/v1/token-launches/metadata';
const MAXIMUM_IMAGE_EDGE = 4_096;
const MAXIMUM_IMAGE_PIXELS = 16_777_216;
export const DEFAULT_TOKEN_METADATA_STORAGE_BYTES = 512 * 1_024 * 1_024;

type MetadataImage = Readonly<{ bytes: Buffer; contentType: TokenImageMediaType; hash: string }>;
type MetadataDocument = Readonly<{ bytes: Buffer; contentType: 'application/json; charset=utf-8'; hash: string }>;

export interface TokenMetadataApi {
  publish(request: TokenMetadataRequest): Promise<TokenMetadataPublication>;
  image(filename: string): Promise<MetadataImage | null>;
  document(filename: string): Promise<MetadataDocument | null>;
}

export class TokenMetadataError extends Error {
  constructor(readonly code: 'invalid_request' | 'metadata_unavailable' | 'rate_limited',
    readonly statusCode: 400 | 429 | 503, message: string) { super(message); }
}

export class TokenMetadataAdmissionLimiter {
  private startedAt: number;
  private count = 0;
  constructor(private readonly now = Date.now, private readonly limit = 30, private readonly windowMs = 60_000) {
    this.startedAt = now();
  }
  take(): void {
    const current = this.now();
    if (current < this.startedAt || current - this.startedAt >= this.windowMs) {
      this.startedAt = current; this.count = 0;
    }
    if (this.count >= this.limit) {
      throw new TokenMetadataError('rate_limited', 429, 'Token metadata uploads are temporarily limited. Try again shortly.');
    }
    this.count++;
  }
}

function unavailable(): never {
  throw new TokenMetadataError('metadata_unavailable', 503, 'Token metadata publishing is temporarily unavailable.');
}

function sha256(value: Uint8Array): string { return createHash('sha256').update(value).digest('hex'); }

function extension(mediaType: TokenImageMediaType): 'jpg' | 'png' | 'webp' {
  if (mediaType === 'image/jpeg') return 'jpg';
  if (mediaType === 'image/png') return 'png';
  return 'webp';
}

function mediaTypeForExtension(value: string): TokenImageMediaType {
  if (value === 'jpg') return 'image/jpeg';
  if (value === 'png') return 'image/png';
  return 'image/webp';
}

function validDimensions(width: number, height: number): boolean {
  return Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0 &&
    width <= MAXIMUM_IMAGE_EDGE && height <= MAXIMUM_IMAGE_EDGE && width * height <= MAXIMUM_IMAGE_PIXELS;
}

function validJpeg(bytes: Buffer): boolean {
  if (bytes.length < 12 || bytes[0] !== 0xff || bytes[1] !== 0xd8 ||
    bytes[bytes.length - 2] !== 0xff || bytes[bytes.length - 1] !== 0xd9) return false;
  let offset = 2;
  while (offset + 3 < bytes.length) {
    if (bytes[offset] !== 0xff) return false;
    while (offset < bytes.length && bytes[offset] === 0xff) offset++;
    const marker = bytes[offset++];
    if (marker === undefined || marker === 0xd9 || marker === 0xda) return false;
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
    if (offset + 2 > bytes.length) return false;
    const length = bytes.readUInt16BE(offset);
    if (length < 2 || offset + length > bytes.length) return false;
    if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
      if (length < 8) return false;
      return validDimensions(bytes.readUInt16BE(offset + 5), bytes.readUInt16BE(offset + 3));
    }
    offset += length;
  }
  return false;
}

function validPng(bytes: Buffer): boolean {
  return bytes.length >= 45 && bytes.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex')) &&
    bytes.readUInt32BE(8) === 13 && bytes.subarray(12, 16).toString('ascii') === 'IHDR' &&
    validDimensions(bytes.readUInt32BE(16), bytes.readUInt32BE(20)) &&
    bytes.readUInt32BE(bytes.length - 12) === 0 && bytes.subarray(bytes.length - 8, bytes.length - 4).toString('ascii') === 'IEND';
}

function validWebp(bytes: Buffer): boolean {
  if (bytes.length < 30 || bytes.subarray(0, 4).toString('ascii') !== 'RIFF' ||
    bytes.subarray(8, 12).toString('ascii') !== 'WEBP' || bytes.readUInt32LE(4) + 8 !== bytes.length) return false;
  const kind = bytes.subarray(12, 16).toString('ascii');
  const chunkBytes = bytes.readUInt32LE(16);
  if (20 + chunkBytes + (chunkBytes % 2) > bytes.length) return false;
  if (kind === 'VP8X' && chunkBytes >= 10) {
    const width = 1 + bytes.readUIntLE(24, 3); const height = 1 + bytes.readUIntLE(27, 3);
    return validDimensions(width, height);
  }
  if (kind === 'VP8L' && chunkBytes >= 5 && bytes[20] === 0x2f) {
    const width = 1 + bytes[21]! + ((bytes[22]! & 0x3f) << 8);
    const height = 1 + (bytes[22]! >> 6) + (bytes[23]! << 2) + ((bytes[24]! & 0x0f) << 10);
    return validDimensions(width, height);
  }
  if (kind === 'VP8 ' && chunkBytes >= 10 && bytes.subarray(23, 26).equals(Buffer.from([0x9d, 0x01, 0x2a]))) {
    return validDimensions(bytes.readUInt16LE(26) & 0x3fff, bytes.readUInt16LE(28) & 0x3fff);
  }
  return false;
}

function decodeImage(value: string, mediaType: TokenImageMediaType): Buffer {
  const bytes = Buffer.from(value, 'base64');
  if (bytes.length === 0 || bytes.length > TOKEN_METADATA_MAX_IMAGE_BYTES || bytes.toString('base64') !== value) {
    throw new Error('Invalid image encoding');
  }
  const valid = mediaType === 'image/jpeg' ? validJpeg(bytes) : mediaType === 'image/png' ? validPng(bytes) : validWebp(bytes);
  if (!valid) throw new Error('Image bytes do not match the declared media type');
  return bytes;
}

function configuredBaseUrl(value: string | undefined): string | null {
  if (!value || !isPublicHttpsUrl(value)) return null;
  const parsed = new URL(value);
  if (parsed.search || parsed.hash) return null;
  const path = parsed.pathname.replace(/\/+$/, '');
  const base = `${parsed.origin}${path === '/' ? '' : path}`;
  const longestMetadataUri = `${base}${PUBLIC_PATH}/documents/${'0'.repeat(64)}.json`;
  return Buffer.byteLength(longestMetadataUri, 'utf8') <= 200 ? base : null;
}

function configuredStorageDirectory(value: string | undefined): string | null {
  if (!value || value.includes('\u0000') || !isAbsolute(value)) return null;
  return resolve(value);
}

function configuredStorageBytes(value: number | string | undefined): number | null {
  if (value === undefined) return DEFAULT_TOKEN_METADATA_STORAGE_BYTES;
  const parsed = typeof value === 'number' ? value : /^\d+$/.test(value) ? Number(value) : Number.NaN;
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

async function readRegularFile(filename: string, maximumBytes: number): Promise<Buffer> {
  let handle: Awaited<ReturnType<typeof open>> | null = null;
  try {
    handle = await open(filename, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size <= 0 || stat.size > maximumBytes) throw new Error('Invalid metadata asset');
    const bytes = await handle.readFile();
    if (bytes.length !== stat.size || bytes.length > maximumBytes) throw new Error('Metadata asset changed while reading');
    return bytes;
  } finally { if (handle !== null) await handle.close(); }
}

async function verifyExisting(filename: string, expected: Buffer, expectedHash: string): Promise<void> {
  const current = await readRegularFile(filename, Math.max(expected.length, 1));
  if (current.length !== expected.length || sha256(current) !== expectedHash || !current.equals(expected)) {
    throw new Error('Immutable metadata asset conflict');
  }
}

async function verifyExistingOrMissing(filename: string, expected: Buffer, expectedHash: string): Promise<boolean> {
  try { await verifyExisting(filename, expected, expectedHash); return true; }
  catch (error) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false;
    throw error;
  }
}

async function syncDirectory(directory: string): Promise<void> {
  const handle = await open(directory, constants.O_RDONLY | (constants.O_DIRECTORY ?? 0) | (constants.O_NOFOLLOW ?? 0));
  try {
    if (!(await handle.stat()).isDirectory()) throw new Error('Metadata storage path is not a directory');
    await handle.sync();
  } finally { await handle.close(); }
}

async function ensureStorageDirectories(root: string, imageDirectory: string, documentDirectory: string): Promise<void> {
  await syncDirectory(root);
  for (const directory of [imageDirectory, documentDirectory]) {
    try { await mkdir(directory, { mode: 0o755 }); }
    catch (error) { if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error; }
    await syncDirectory(directory);
  }
  await syncDirectory(root);
}

async function storedBytes(directories: readonly string[]): Promise<number> {
  let total = 0;
  for (const directory of directories) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      if (!entry.isFile()) throw new Error('Metadata storage contains a non-regular entry');
      const handle = await open(join(directory, entry.name), constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0));
      try {
        const stat = await handle.stat();
        if (!stat.isFile() || !Number.isSafeInteger(stat.size) || total > Number.MAX_SAFE_INTEGER - stat.size) {
          throw new Error('Metadata storage size is invalid');
        }
        total += stat.size;
      } finally { await handle.close(); }
    }
  }
  return total;
}

async function writeImmutable(directory: string, filename: string, bytes: Buffer, hash: string): Promise<void> {
  const destination = join(directory, filename);
  if (await verifyExistingOrMissing(destination, bytes, hash)) return;
  const temporary = join(directory, `.tmp-${process.pid}-${randomUUID()}`);
  let handle: Awaited<ReturnType<typeof open>> | null = null;
  try {
    handle = await open(temporary, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL |
      (constants.O_NOFOLLOW ?? 0), 0o600);
    await handle.writeFile(bytes); await handle.sync(); await handle.chmod(0o444); await handle.close(); handle = null;
    try { await link(temporary, destination); }
    catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
      await verifyExisting(destination, bytes, hash);
    }
    await syncDirectory(directory);
  } finally {
    if (handle !== null) await handle.close();
    await rm(temporary, { force: true });
    await syncDirectory(directory);
  }
}

/** Durable content-addressed publisher for immutable Metaplex-compatible off-chain metadata. */
export class TokenMetadataService implements TokenMetadataApi {
  private readonly publicBaseUrl: string | null;
  private readonly storageDirectory: string | null;
  private readonly imageDirectory: string | null;
  private readonly documentDirectory: string | null;
  private readonly maximumStorageBytes: number | null;
  private activePublications = 0;
  private writeTail: Promise<void> = Promise.resolve();

  constructor(directory = process.env.TOKEN_METADATA_STORAGE_DIR,
    publicBaseUrl = process.env.TOKEN_METADATA_PUBLIC_BASE_URL,
    maximumStorageBytes: number | string | undefined = process.env.TOKEN_METADATA_MAX_STORAGE_BYTES,
    private readonly maximumConcurrentPublications = 2) {
    this.publicBaseUrl = configuredBaseUrl(publicBaseUrl);
    this.storageDirectory = configuredStorageDirectory(directory);
    this.imageDirectory = this.storageDirectory ? join(this.storageDirectory, 'images') : null;
    this.documentDirectory = this.storageDirectory ? join(this.storageDirectory, 'documents') : null;
    this.maximumStorageBytes = configuredStorageBytes(maximumStorageBytes);
  }

  async publish(request: TokenMetadataRequest): Promise<TokenMetadataPublication> {
    if (!this.publicBaseUrl || !this.storageDirectory || !this.imageDirectory || !this.documentDirectory ||
      !this.maximumStorageBytes || !Number.isInteger(this.maximumConcurrentPublications) ||
      this.maximumConcurrentPublications < 1 || this.activePublications >= this.maximumConcurrentPublications) return unavailable();
    this.activePublications++;
    try {
      let image: Buffer;
      try { image = decodeImage(request.imageBase64, request.imageMediaType); }
      catch { throw new TokenMetadataError('invalid_request', 400, 'Token metadata image is invalid.'); }
      const imageHash = sha256(image); const imageExtension = extension(request.imageMediaType);
      const imageUri = `${this.publicBaseUrl}${PUBLIC_PATH}/images/${imageHash}.${imageExtension}`;
      const extensions = {
        ...(request.websiteUrl ? { website: request.websiteUrl } : {}),
        ...(request.xUrl ? { twitter: request.xUrl } : {}),
        ...(request.telegramUrl ? { telegram: request.telegramUrl } : {}),
      };
      const metadata = {
        name: request.name,
        symbol: request.symbol,
        description: request.description,
        image: imageUri,
        ...(request.websiteUrl ? { external_url: request.websiteUrl } : {}),
        attributes: [],
        properties: { files: [{ uri: imageUri, type: request.imageMediaType }], category: 'image' },
        ...(Object.keys(extensions).length > 0 ? { extensions } : {}),
      };
      const document = Buffer.from(JSON.stringify(metadata), 'utf8'); const contentHash = sha256(document);
      await this.withWriteSection(async () => {
        await ensureStorageDirectories(this.storageDirectory!, this.imageDirectory!, this.documentDirectory!);
        const imageFilename = join(this.imageDirectory!, `${imageHash}.${imageExtension}`);
        const documentFilename = join(this.documentDirectory!, `${contentHash}.json`);
        const imageExists = await verifyExistingOrMissing(imageFilename, image, imageHash);
        const documentExists = await verifyExistingOrMissing(documentFilename, document, contentHash);
        const currentBytes = await storedBytes([this.imageDirectory!, this.documentDirectory!]);
        const additionalBytes = (imageExists ? 0 : image.length) + (documentExists ? 0 : document.length);
        if (currentBytes > this.maximumStorageBytes! - additionalBytes) return unavailable();
        if (!imageExists) await writeImmutable(this.imageDirectory!, `${imageHash}.${imageExtension}`, image, imageHash);
        if (!documentExists) await writeImmutable(this.documentDirectory!, `${contentHash}.json`, document, contentHash);
      });
      return { metadataUri: `${this.publicBaseUrl}${PUBLIC_PATH}/documents/${contentHash}.json`, imageUri, contentHash };
    } catch (error) {
      if (error instanceof TokenMetadataError) throw error;
      return unavailable();
    } finally { this.activePublications--; }
  }

  async image(filename: string): Promise<MetadataImage | null> {
    const match = IMAGE_FILENAME.exec(filename); if (!match) return null;
    const [, hash, fileExtension] = match; if (!hash || !fileExtension || !HASH.test(hash) || !this.imageDirectory) return unavailable();
    try {
      const bytes = await readRegularFile(join(this.imageDirectory, filename), TOKEN_METADATA_MAX_IMAGE_BYTES);
      return { bytes, contentType: mediaTypeForExtension(fileExtension), hash };
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT' || (error as NodeJS.ErrnoException).code === 'ELOOP') return null;
      return unavailable();
    }
  }

  async document(filename: string): Promise<MetadataDocument | null> {
    const match = DOCUMENT_FILENAME.exec(filename); if (!match) return null;
    const hash = match[1]; if (!hash || !HASH.test(hash) || !this.documentDirectory) return unavailable();
    try {
      const bytes = await readRegularFile(join(this.documentDirectory, filename), 64 * 1_024);
      return { bytes, contentType: 'application/json; charset=utf-8', hash };
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === 'ENOENT' || (error as NodeJS.ErrnoException).code === 'ELOOP') return null;
      return unavailable();
    }
  }

  private async withWriteSection<T>(operation: () => Promise<T>): Promise<T> {
    const previous = this.writeTail;
    let release!: () => void;
    this.writeTail = new Promise<void>(resolveTail => { release = resolveTail; });
    await previous;
    try { return await operation(); }
    finally { release(); }
  }
}
