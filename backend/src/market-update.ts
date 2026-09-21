import type { Stock } from './schema.js';

/**
 * Internal publication metadata. `requestedAt` uses Node's monotonic clock and
 * exists only for backend latency measurement; it is never presented as a
 * market observation timestamp.
 */
export type CatalogMutation = {
  changedIds: readonly string[];
  removedIds: readonly string[];
  membershipChanged: boolean;
  requestedAt: number;
};

/** A freshness-normalized patch carried with an otherwise authoritative catalog. */
export type CatalogPatch = CatalogMutation & {
  stocks: readonly Stock[];
};
