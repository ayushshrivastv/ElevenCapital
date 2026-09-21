import { Decimal } from 'decimal.js';
import WebSocket from 'ws';
import { parseProviderJson } from './http.js';
import { backpackActivity, nonnegativeAmount } from './activity.js';
import { DecimalString, type Stock } from './schema.js';
const Money = Decimal.clone({ precision: 128 });

/** Official externalTicker values, never venue volume or a different issuer's token. */
export function applyExternalTicker(stock: Stock, raw: unknown, received: number): Stock | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const row = raw as Record<string, unknown>;
  if (stock.provider !== 'backpack' || row.e !== 'externalTicker' || row.s !== `${stock.providerAssetId}_USDC`) return null;
  const close = nonnegativeAmount(row.c), open = nonnegativeAmount(row.o), volume = nonnegativeAmount(row.V);
  if (close === null || new Money(close).lte(0) || open === null || new Money(open).lte(0) || volume === null) return null;
  const eventTime = String(row.E ?? '');
  if (!/^\d{13,17}$/.test(eventTime)) return null;
  const at = Number(BigInt(eventTime) / 1000n);
  if (!Number.isSafeInteger(at) || at > received + 5_000 || at < received - 300_000) return null;
  if (stock.quote.asOf !== null && Date.parse(stock.quote.asOf) >= at) return null;
  const receivedAt = new Date(received).toISOString();
  const change = new Money(close).minus(open);
  // Division can recur forever. Bound the derived percentage; preserve source price/volume exactly.
  const percent = DecimalString.safeParse(change.div(open).times(100).toSignificantDigits(30).toFixed());
  const amount = DecimalString.safeParse(change.toFixed());
  return { ...stock, quote: { ...stock.quote, price: close, changeAmount: amount.success ? amount.data : null,
    changePercent: percent.success ? percent.data : null, asOf: new Date(at).toISOString(), receivedAt },
    volume24h: volume, volume24hBasis: 'quote_currency_turnover',
    activity: { ...backpackActivity(volume, receivedAt), updatedAt: new Date(at).toISOString() } };
}

/** One shared upstream connection, diffed subscriptions and automatic resubscription. */
export class BackpackLive {
  private socket?: WebSocket;
  private wanted = new Set<string>();
  private subscribed = new Set<string>();
  private retry?: ReturnType<typeof setTimeout>;
  private heartbeat?: ReturnType<typeof setInterval>;
  private stopped = false;
  private attempts = 0;
  private alive = true;
  readonly metrics = { connected: false, messages: 0, reconnects: 0, lastMessageAt: null as string | null };
  constructor(private readonly tick: (symbol: string, data: unknown, received: number) => void,
    private readonly now = Date.now, private readonly url = 'wss://ws.backpack.exchange') {}
  setSymbols(symbols: Iterable<string>) {
    this.wanted = new Set([...symbols].filter(symbol => /^[A-Za-z0-9.\-]+_USDC$/.test(symbol)).slice(0, 500));
    if (!this.wanted.size) { this.disconnect(); return; }
    if (!this.socket && !this.retry && !this.stopped) this.connect();
    this.sync();
  }
  stop() { this.stopped = true; this.disconnect(); }
  private disconnect() {
    if (this.retry) clearTimeout(this.retry);
    if (this.heartbeat) clearInterval(this.heartbeat);
    this.retry = undefined; this.heartbeat = undefined;
    const socket = this.socket; this.socket = undefined;
    this.subscribed.clear(); this.metrics.connected = false;
    socket?.removeAllListeners(); socket?.on('error', () => {}); socket?.terminate();
  }
  private connect() {
    if (this.stopped || !this.wanted.size) return;
    const socket = new WebSocket(this.url, { handshakeTimeout: 10_000, maxPayload: 1_000_000 });
    this.socket = socket;
    socket.on('open', () => {
      this.attempts = 0; this.alive = true; this.metrics.connected = true; this.sync();
      this.heartbeat = setInterval(() => { if (!this.alive) { socket.terminate(); return; } this.alive = false; socket.ping(); }, 20_000);
      this.heartbeat.unref();
    });
    socket.on('pong', () => { this.alive = true; });
    socket.on('message', raw => {
      try {
        const message = parseProviderJson(raw.toString()) as { stream?: string; data?: Record<string, unknown> };
        if (!message.stream?.startsWith('externalTicker.') || !message.data) return;
        const symbol = message.stream.slice('externalTicker.'.length);
        if (!this.wanted.has(symbol)) return;
        this.metrics.messages++; this.metrics.lastMessageAt = new Date(this.now()).toISOString();
        this.tick(symbol, message.data, this.now());
      } catch { /* Invalid upstream frames never replace valid financial observations. */ }
    });
    socket.on('error', () => {});
    socket.on('close', () => {
      if (this.socket !== socket) return;
      this.disconnect();
      if (this.stopped || !this.wanted.size) return;
      this.metrics.reconnects++;
      const delay = Math.min(30_000, 1000 * 2 ** this.attempts++) + Math.floor(Math.random() * 500);
      this.retry = setTimeout(() => { this.retry = undefined; this.connect(); }, delay); this.retry.unref();
    });
  }
  private sync() {
    if (this.socket?.readyState !== WebSocket.OPEN) return;
    for (const [method, symbols] of [
      ['UNSUBSCRIBE', [...this.subscribed].filter(value => !this.wanted.has(value))],
      ['SUBSCRIBE', [...this.wanted].filter(value => !this.subscribed.has(value))],
    ] as const) {
      for (let offset = 0; offset < symbols.length; offset += 50) {
        this.socket.send(JSON.stringify({ method, params: symbols.slice(offset, offset + 50).map(symbol => `externalTicker.${symbol}`) }));
      }
    }
    this.subscribed = new Set(this.wanted);
  }
}
