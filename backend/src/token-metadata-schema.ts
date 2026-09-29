import { z } from 'zod';
import { isPublicHttpsUrl } from './public-https.js';
import { TokenName, TokenSymbol } from './token-launch-schema.js';

export { isPublicHttpsUrl } from './public-https.js';

export const TOKEN_METADATA_MAX_IMAGE_BYTES = 2 * 1_024 * 1_024;
export const TOKEN_METADATA_MAX_BASE64_LENGTH = Math.ceil(TOKEN_METADATA_MAX_IMAGE_BYTES / 3) * 4;
export const TOKEN_METADATA_BODY_LIMIT = TOKEN_METADATA_MAX_BASE64_LENGTH + 16_384;

export const TokenImageMediaTypeSchema = z.enum(['image/jpeg', 'image/png', 'image/webp']);
export type TokenImageMediaType = z.infer<typeof TokenImageMediaTypeSchema>;

const PublicHttpsUrl = z.string().min(12).max(500).refine(isPublicHttpsUrl, 'URL must use public HTTPS');
const CanonicalBase64 = z.string().min(4).max(TOKEN_METADATA_MAX_BASE64_LENGTH)
  .regex(/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/);

export const TokenMetadataRequestSchema = z.object({
  name: TokenName,
  symbol: TokenSymbol,
  description: z.string().max(280).refine(value => !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value),
    'Description contains unsupported control characters'),
  imageBase64: CanonicalBase64,
  imageMediaType: TokenImageMediaTypeSchema,
  websiteUrl: PublicHttpsUrl.optional(),
  xUrl: PublicHttpsUrl.optional(),
  telegramUrl: PublicHttpsUrl.optional(),
}).strict();
export type TokenMetadataRequest = z.infer<typeof TokenMetadataRequestSchema>;

export const TokenMetadataPublicationSchema = z.object({
  metadataUri: PublicHttpsUrl.refine(value => Buffer.byteLength(value, 'utf8') <= 200,
    'Metadata URI exceeds the Metaplex 200-byte limit'),
  imageUri: PublicHttpsUrl,
  contentHash: z.string().length(64).regex(/^[a-f0-9]{64}$/),
}).strict();
export type TokenMetadataPublication = z.infer<typeof TokenMetadataPublicationSchema>;

export const TokenMetadataImageParamsSchema = z.object({
  filename: z.string().regex(/^[a-f0-9]{64}\.(?:jpg|png|webp)$/),
}).strict();
export const TokenMetadataDocumentParamsSchema = z.object({
  filename: z.string().regex(/^[a-f0-9]{64}\.json$/),
}).strict();
