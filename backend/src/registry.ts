import { z } from 'zod';
import entries from './registry.json' with { type: 'json' };
const Entry = z.object({
  symbol: z.string(), classification: z.literal('common_equity'),
  isin: z.string().regex(/^US[A-Z0-9]{9}\d$/), cusip: z.string().regex(/^[A-Z0-9]{9}$/),
  backed: z.object({ assetId: z.string(), symbol: z.string(), productIsin: z.string(), classificationSource: z.url() }),
  backpack: z.object({ assetId: z.string(), cusip: z.string() }), reviewedAt: z.string(),
}).refine(entry => entry.isin.slice(2, 11) === entry.cusip && entry.backpack.cusip === entry.cusip, 'CUSIP must match the reviewed US ISIN');
export const Registry = z.array(Entry).parse(entries);
export type RegistryEntry = z.infer<typeof Entry>;
