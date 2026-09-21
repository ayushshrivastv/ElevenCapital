export type CacheResult<T> = { status: 'ok' | 'stale' | 'unavailable'; value?: T; receivedAt?: string };

/** Single flight, bounded stale fallback, and outage backoff. No fixtures on failure. */
export class AsyncCache<T> {
  private entry?: { value: T; at: number };
  private pending?: Promise<CacheResult<T>>;
  private failedAt?: number;
  constructor(private readonly ttlMs = 60_000, private readonly maxAgeMs = 300_000,
    private readonly now = Date.now, private readonly retryMs = 10_000) {}

  private fallback(): CacheResult<T> {
    return this.entry && this.now() - this.entry.at <= this.maxAgeMs
      ? { status: 'stale', value: this.entry.value, receivedAt: new Date(this.entry.at).toISOString() }
      : { status: 'unavailable' };
  }

  async get(loader: () => Promise<T>): Promise<CacheResult<T>> {
    if (this.pending) return this.pending;
    if (this.failedAt !== undefined && this.now() - this.failedAt < this.retryMs) return this.fallback();
    if (this.entry && this.now() - this.entry.at < this.ttlMs) {
      return { status: 'ok', value: this.entry.value, receivedAt: new Date(this.entry.at).toISOString() };
    }
    this.pending = (async (): Promise<CacheResult<T>> => {
      try {
        const value = await loader();
        this.entry = { value, at: this.now() };
        this.failedAt = undefined;
        return { status: 'ok', value, receivedAt: new Date(this.entry.at).toISOString() };
      } catch {
        this.failedAt = this.now();
        return this.fallback();
      }
    })();
    try { return await this.pending; } finally { this.pending = undefined; }
  }
}

export function limitConcurrency(maximum: number) {
  let running = 0;
  const waiting: (() => void)[] = [];
  return async <T>(work: () => Promise<T>): Promise<T> => {
    if (running >= maximum) await new Promise<void>(resolve => waiting.push(resolve));
    else running++;
    try { return await work(); } finally {
      const next = waiting.shift();
      if (next) next(); else running--;
    }
  };
}
