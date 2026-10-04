import type { FastifyInstance } from 'fastify';
import type { ServerResponse } from 'node:http';
import { PredictionPriceError, type PredictionPriceService, type PredictionSnapshot } from './prediction-pyth.js';

/** Read-only mobile transport. Signed boundary data must still be authenticated by Pyth on chain. */
export function registerPredictionRoutes(app: FastifyInstance, service: PredictionPriceService,
  asset: 'eth' | 'btc' = 'eth'): void {
  const basePath = `/v1/prediction/${asset}`;
  const sessions = new Set<() => void>();
  const emptyQuery = { type: 'object', additionalProperties: false, properties: {} } as const;
  const sendFailure = (error: unknown, reply: import('fastify').FastifyReply) => {
    const failure = error instanceof PredictionPriceError ? error : new PredictionPriceError('pyth_unavailable');
    if (failure.statusCode !== 400) reply.header('Retry-After', failure.statusCode === 429 ? '60' : '1');
    return reply.code(failure.statusCode).send({ error: failure.code, message: failure.message });
  };
  app.get(basePath, { schema: { querystring: emptyQuery } }, async (_request, reply) => {
    try { return await service.snapshot(); } catch (failure) { return sendFailure(failure, reply); }
  });
  app.get<{ Params: { timestamp: string } }>(`${basePath}/at/:timestamp`, { schema: {
    querystring: emptyQuery,
    params: { type: 'object', required: ['timestamp'], additionalProperties: false,
      properties: { timestamp: { type: 'string', pattern: '^[0-9]{1,10}$' } } },
  } }, async (request, reply) => {
    try { return await service.atTimestamp(Number(request.params.timestamp)); }
    catch (failure) { return sendFailure(failure, reply); }
  });
  app.get<{ Params: { poolId: string } }>(`${basePath}/round/:poolId`, { schema: {
    querystring: emptyQuery,
    params: { type: 'object', required: ['poolId'], additionalProperties: false,
      properties: { poolId: { type: 'string', pattern: '^[0-9]{1,10}$' } } },
  } }, async (request, reply) => {
    try { return await service.round(Number(request.params.poolId)); }
    catch (failure) { return sendFailure(failure, reply); }
  });
  app.get(`${basePath}/stream`, { schema: { querystring: emptyQuery } }, (_request, reply) => {
    if (sessions.size >= 64) return reply.code(503).header('Retry-After', '5')
      .send({ error: 'prediction_stream_busy', message: 'Prediction price is temporarily unavailable.' });
    reply.hijack();
    const response: ServerResponse = reply.raw;
    response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store',
      Connection: 'keep-alive', 'X-Content-Type-Options': 'nosniff', 'X-Accel-Buffering': 'no' });
    let ended = false, blocked = false;
    let pending: string | undefined;
    let blockedTimer: ReturnType<typeof setTimeout> | undefined;
    let unsubscribe = () => {};
    const close = () => {
      if (ended) return;
      ended = true; unsubscribe(); sessions.delete(close);
      if (blockedTimer) clearTimeout(blockedTimer);
      pending = undefined;
      response.removeListener('drain', drain);
      response.removeListener('close', close);
      response.removeListener('error', close);
      if (!response.destroyed && !response.writableEnded) response.end();
    };
    const write = (frame: string) => {
      if (ended || response.destroyed || response.writableEnded) { close(); return; }
      if (frame.length > 16_384 || response.writableLength > 64 * 1024) { response.destroy(); close(); return; }
      if (blocked) { pending = frame; return; }
      if (!response.write(frame)) {
        blocked = true;
        blockedTimer = setTimeout(() => { response.destroy(); close(); }, 10_000); blockedTimer.unref();
      }
    };
    const drain = () => {
      blocked = false;
      if (blockedTimer) clearTimeout(blockedTimer);
      blockedTimer = undefined;
      if (pending !== undefined) { const frame = pending; pending = undefined; write(frame); }
    };
    response.on('drain', drain); response.on('close', close); response.on('error', close); sessions.add(close);
    unsubscribe = service.subscribe((snapshot: PredictionSnapshot) => { write(`event: price\ndata: ${JSON.stringify(snapshot)}\n\n`); });
    if (ended) unsubscribe();
  });
  app.addHook('preClose', async () => { for (const close of sessions) close(); service.close(); });
}
