import Fastify, { type FastifyReply } from 'fastify';
import { constants, existsSync } from 'node:fs';
import { open } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { z } from 'zod';
import { CatalogSchema, ChartSchema, ErrorSchema, Range } from './schema.js';
import { MarketDataService, UnknownStockError } from './service.js';
import { MarketStreams, RealtimeCatalog, snapshotAt } from './realtime.js';
import { featuredRobinhoodStocks } from './robinhood.js';
import { WalletPortfolioService, PortfolioBusyError } from './portfolio.js';
import { PortfolioRequestSchema, PortfolioSchema, type PortfolioRequest } from './portfolio-schema.js';
import { PublicPortfolioUpstream } from './portfolio-upstream.js';
import { WalletActivityRequestSchema, WalletActivityResponseSchema, WalletActivityService,
  type WalletActivityRequest } from './wallet-activity.js';
import { SolanaDevnetRequestSchema, SolanaDevnetResponseSchema, SolanaDevnetWalletService,
  type SolanaDevnetRequest } from './solana-devnet-wallet.js';
import { WalletTransactionsRequestSchema, WalletTransactionsResponseSchema, WalletTransactionsService,
  type WalletTransactionsRequest } from './wallet-transactions.js';
import { PurchaseActivityResponseSchema } from './purchase-activity.js';
import { LiveMarketService } from './live-service.js';
import { MarketSocket, type SocketMarketService } from './market-socket.js';
import type { CatalogMutation } from './market-update.js';
import { TransferPreparationSchema, TransferRequestSchema, type TransferRequest } from './transfer-schema.js';
import { TransferPreparationError, TransferPreparationService, TransferRateLimiter } from './transfer.js';
import { TransferStatusRequestSchema, TransferStatusSchema, type TransferStatusRequest } from './transfer-status-schema.js';
import { TransferStatusService } from './transfer-status.js';
import { configuredPrivyVerifier, SubjectRateLimiter, SubjectRateLimitError, WalletAuthenticationError,
  type AccessTokenVerifier, type PrivyPrincipal } from './privy-auth.js';
import { TransferIntentLedger, TransferIntentError } from './transfer-intent-ledger.js';
import { TransferIntentCommitSchema, TransferIntentMutationResponseSchema, TransferIntentReleaseSchema,
  type TransferIntentCommit, type TransferIntentRelease } from './transfer-intent-schema.js';
import { PurchaseActionParamsSchema, PurchaseCommitRequestSchema, PurchaseCommitResponseSchema, PurchaseIdParamsSchema,
  PurchaseOptionsRequestSchema, PurchaseOptionsResponseSchema, PurchaseQuoteRequestSchema, PurchaseQuoteResponseSchema,
  PurchaseStatusSchema, PurchaseSubmittedRequestSchema, PurchaseInvokingRequestSchema, PurchaseInvokingResponseSchema,
  PurchaseInvocationReleaseRequestSchema, type PurchaseCommitRequest, type PurchaseOptionsRequest,
  type PurchaseQuoteRequest, type PurchaseSubmittedRequest, type PurchaseInvokingRequest,
  type PurchaseInvocationReleaseRequest } from './purchase-schema.js';
import { PublicPurchaseChainReader } from './purchase-chain.js';
import { PurchaseRouteError } from './purchase-router.js';
import { JupiterPurchaseRouter } from './jupiter-purchase-router.js';
import { PurchaseError, PurchaseService, type PurchaseApi } from './purchase.js';
import { PurchaseLedgerError } from './purchase-ledger.js';
import { PythPredictionService, type PredictionPriceService } from './prediction-pyth.js';
import { registerPredictionRoutes } from './prediction-routes.js';
import { createPantaClientFromEnv } from './panta-client.js';
import { registerPantaRoutes, type PantaApi } from './panta-routes.js';
import { TokenLaunchService, type TokenLaunchApi } from './token-launch.js';
import { TokenLaunchError } from './token-launch-error.js';
import { TokenLaunchLedgerError } from './token-launch-ledger.js';
import { CommunityBuyService, type CommunityBuyApi } from './community-buy.js';
import { CommunityBuyError } from './community-buy-error.js';
import { CommunityBuyLedgerError } from './community-buy-ledger.js';
import { CommunityBuyIdentitySchema, CommunityBuyMutationResponseSchema, CommunityBuyPreparationSchema,
  CommunityBuyPrepareRequestSchema, CommunityBuyReleaseRequestSchema, CommunityBuyStatusSchema,
  CommunityBuySubmitRequestSchema, CommunityBuySubmitResponseSchema,
  type CommunityBuyIdentity, type CommunityBuyPrepareRequest, type CommunityBuyReleaseRequest,
  type CommunityBuySubmitRequest } from './community-buy-schema.js';
import { TokenLaunchCommitRequestSchema, TokenLaunchMutationResponseSchema, TokenLaunchPreparationSchema,
  TokenLaunchPrepareRequestSchema, TokenLaunchReleaseRequestSchema, TokenLaunchStatusRequestSchema,
  TokenLaunchStatusSchema, TokenLaunchSubmitRequestSchema, TokenLaunchSubmitResponseSchema,
  type TokenLaunchCommitRequest, type TokenLaunchPrepareRequest, type TokenLaunchReleaseRequest,
  type TokenLaunchStatusRequest, type TokenLaunchSubmitRequest } from './token-launch-schema.js';
import { TokenMetadataAdmissionLimiter, TokenMetadataError, TokenMetadataService,
  type TokenMetadataApi } from './token-metadata.js';
import { TOKEN_METADATA_BODY_LIMIT, TokenMetadataDocumentParamsSchema, TokenMetadataImageParamsSchema,
  TokenMetadataPublicationSchema, TokenMetadataRequestSchema, type TokenMetadataRequest } from './token-metadata-schema.js';
import { ChatCursorSchema, ChatMessageRequestSchema, CommunityChatAccessError, CommunityChatStorageError, CommunityChatStore,
  SPXTR_CHAT_ROOM } from './community-chat.js';
import { CommunityMintParamsSchema, CommunityMintValidationError, CommunityMintValidationSchema,
  CommunityMintValidationService, type CommunityMintValidationApi } from './community-mint-validation.js';

type MarketService = Pick<MarketDataService, 'catalog' | 'chart'> & SocketMarketService & {
  onUpdate?(listener: (catalog: import('./schema.js').Catalog, mutation?: CatalogMutation) => void): () => void;
  diagnostics?(): unknown;
  prewarm?(): Promise<void>;
  featuredCatalog?(provider: 'robinhood'): Promise<import('./schema.js').Catalog>;
  stop?(): void;
};
export const WALLET_LOG_REDACTION_PATHS = ['req.headers.authorization', 'request.headers.authorization', 'headers.authorization'] as const;

// The explorer is a single self-contained project file, not a static directory or file-server route.
function explorerHtmlPath(): string | null {
  let directory = resolve(process.cwd());
  for (let level = 0; level < 5; level++) {
    if (existsSync(join(directory, 'backend', 'package.json'))) return join(directory, 'index.html');
    const parent = dirname(directory);
    if (parent === directory) break;
    directory = parent;
  }
  return null;
}

async function sendExplorerHtml(reply: FastifyReply, filename: string | null) {
  if (filename === null) return reply.code(503).send({ error: 'explorer_unavailable', message: 'Explorer page is unavailable.' });
  let file;
  try {
    file = await open(filename, constants.O_RDONLY | constants.O_NOFOLLOW);
    const details = await file.stat();
    if (!details.isFile()) {
      await file.close();
      return reply.code(503).send({ error: 'explorer_unavailable', message: 'Explorer page is unavailable.' });
    }
  } catch {
    await file?.close();
    return reply.code(503).send({ error: 'explorer_unavailable', message: 'Explorer page is unavailable.' });
  }
  return reply.type('text/html; charset=utf-8').header('Cache-Control', 'no-store')
    .header('X-Frame-Options', 'DENY').header('Referrer-Policy', 'no-referrer')
    .send(file.createReadStream());
}

export async function buildApp(service: MarketService = new LiveMarketService(), logger = false,
  options: { autoRefresh?: boolean; now?: () => number; refreshMs?: number; retryMs?: number; heartbeatMs?: number;
    portfolio?: Pick<WalletPortfolioService, 'portfolio'>;
    activity?: Pick<WalletActivityService, 'activity'>;
    devnet?: Pick<SolanaDevnetWalletService, 'wallet'>;
    transactions?: Pick<WalletTransactionsService, 'activity'>;
    transfer?: Pick<TransferPreparationService, 'prepare'>; transferRateLimiter?: Pick<TransferRateLimiter, 'take'>;
    transferStatus?: Pick<TransferStatusService, 'status'>; transferStatusRateLimiter?: Pick<TransferRateLimiter, 'take'>;
    accessTokenVerifier?: AccessTokenVerifier; transferIntentLedger?: Pick<TransferIntentLedger,
      'registerPrepared' | 'commit' | 'release' | 'recordTransaction' | 'recordObservation'>;
    transferSubjectRateLimiter?: Pick<SubjectRateLimiter, 'take'>;
    purchase?: PurchaseApi; purchaseSubjectRateLimiter?: Pick<SubjectRateLimiter, 'take'>;
    tokenLaunch?: TokenLaunchApi; tokenMetadata?: TokenMetadataApi; communityBuy?: CommunityBuyApi;
    communityChat?: Pick<CommunityChatStore, 'list' | 'join' | 'post' | 'clearMine'>;
    communityChatReadRateLimiter?: Pick<SubjectRateLimiter, 'take'>;
    communityChatPostRateLimiter?: Pick<SubjectRateLimiter, 'take'>;
    communityMintValidation?: CommunityMintValidationApi;
    communityMintValidationRateLimiter?: Pick<SubjectRateLimiter, 'take'>;
    tokenMetadataAdmissionRateLimiter?: Pick<TokenMetadataAdmissionLimiter, 'take'>;
    tokenLaunchSubjectRateLimiter?: Pick<SubjectRateLimiter, 'take'>;
    tokenLaunchStatusRateLimiter?: Pick<SubjectRateLimiter, 'take'>;
    communityBuySubjectRateLimiter?: Pick<SubjectRateLimiter, 'take'>;
    communityBuyStatusRateLimiter?: Pick<SubjectRateLimiter, 'take'>;
    prediction?: PredictionPriceService; predictionBtc?: PredictionPriceService;
    panta?: PantaApi | null } = {}) {
  const app = Fastify({ logger: logger ? { redact: { paths: [...WALLET_LOG_REDACTION_PATHS], censor: '[Redacted]' } } : false,
    bodyLimit: 8_192, pluginTimeout: 30_000, requestTimeout: 30_000, connectionTimeout: 30_000, ajv: { customOptions: { removeAdditional: false } } });
  const feed = new RealtimeCatalog(() => service.catalog(), options.now, options.refreshMs, options.retryMs);
  const streams = new MarketStreams(feed, options.now, options.heartbeatMs);
  const marketSocket = new MarketSocket(app.server, feed, service, options.now);
  const unsubscribe = service.onUpdate?.((catalog, mutation) => feed.publish(catalog, mutation));
  const portfolio = options.portfolio ?? new WalletPortfolioService(() => service.catalog(),
    new PublicPortfolioUpstream(undefined, options.now, event => app.log.warn(event, 'Wallet balance read unavailable')), options.now);
  const activity = options.activity ?? new WalletActivityService(undefined, options.now);
  const devnet = options.devnet ?? new SolanaDevnetWalletService(undefined, options.now);
  const transactions = options.transactions ?? new WalletTransactionsService(activity, options.now);
  const transfer = options.transfer ?? new TransferPreparationService(undefined, options.now);
  const transferRateLimiter = options.transferRateLimiter ?? new TransferRateLimiter(options.now, 6_000);
  const transferStatus = options.transferStatus ?? new TransferStatusService(undefined, options.now);
  const transferStatusRateLimiter = options.transferStatusRateLimiter ?? new TransferRateLimiter(options.now, 12_000);
  const accessTokenVerifier = options.accessTokenVerifier ?? configuredPrivyVerifier();
  const transferIntentLedger = options.transferIntentLedger ?? new TransferIntentLedger(undefined, options.now);
  const transferSubjectRateLimiter = options.transferSubjectRateLimiter ?? new SubjectRateLimiter(options.now);
  // Only independently validated same-wallet Solana swaps can enable a signature.
  const purchase = options.purchase ?? new PurchaseService(() => service.catalog(), new PublicPurchaseChainReader(),
    new JupiterPurchaseRouter(), undefined, options.now, true);
  const purchaseSubjectRateLimiter = options.purchaseSubjectRateLimiter ?? new SubjectRateLimiter(options.now, 120);
  const tokenLaunch = options.tokenLaunch ?? new TokenLaunchService();
  const communityBuy = options.communityBuy ?? new CommunityBuyService();
  const communityChat = options.communityChat ?? new CommunityChatStore();
  const communityChatReadRateLimiter = options.communityChatReadRateLimiter ?? new SubjectRateLimiter(options.now, 120);
  const communityChatPostRateLimiter = options.communityChatPostRateLimiter ?? new SubjectRateLimiter(options.now, 8);
  let communityMintValidation = options.communityMintValidation;
  const communityMintValidationRateLimiter = options.communityMintValidationRateLimiter ?? new SubjectRateLimiter(options.now, 30);
  const tokenMetadata = options.tokenMetadata ?? new TokenMetadataService();
  const tokenMetadataAdmissionRateLimiter = options.tokenMetadataAdmissionRateLimiter ?? new TokenMetadataAdmissionLimiter(options.now);
  const tokenLaunchSubjectRateLimiter = options.tokenLaunchSubjectRateLimiter ?? new SubjectRateLimiter(options.now, 12);
  const tokenLaunchStatusRateLimiter = options.tokenLaunchStatusRateLimiter ?? new SubjectRateLimiter(options.now, 60);
  const communityBuySubjectRateLimiter = options.communityBuySubjectRateLimiter ?? new SubjectRateLimiter(options.now, 12);
  const communityBuyStatusRateLimiter = options.communityBuyStatusRateLimiter ?? new SubjectRateLimiter(options.now, 60);
  const tokenMetadataPrincipals = new WeakMap<object, PrivyPrincipal>();
  if (options.autoRefresh) app.addHook('onReady', async () => { await service.prewarm?.(); feed.start(); });
  // Close streaming responses before Fastify waits for active requests during shutdown.
  app.addHook('preClose', async () => { unsubscribe?.(); marketSocket.close(); streams.close(); feed.stop(); service.stop?.(); });
  const schema = (value: z.ZodType) => z.toJSONSchema(value, { target: 'draft-7' });
  app.addHook('onSend', async (_request, reply) => {
    if (!reply.hasHeader('Cache-Control')) reply.header('Cache-Control', 'no-store');
    reply.header('X-Content-Type-Options', 'nosniff');
  });
  app.setErrorHandler((error, request, reply) => {
    if (error instanceof TransferPreparationError) {
      if (error.statusCode === 429) reply.header('Retry-After', '60');
      return reply.code(error.statusCode).send({ error: error.code, message: error.message });
    }
    if (error instanceof WalletAuthenticationError) {
      request.log.warn({ errorCode: error.code, statusCode: error.statusCode }, 'wallet authentication request failed safely');
      return reply.code(error.statusCode).send({ error: error.code, message: error.message });
    }
    if (error instanceof SubjectRateLimitError) return reply.code(429).header('Retry-After', '60')
      .send({ error: 'rate_limited', message: error.message });
    if (error instanceof TransferIntentError) return reply.code(error.statusCode).send({ error: error.code, message: error.message });
    if (error instanceof PurchaseError || error instanceof PurchaseLedgerError) {
      request.log.warn({ errorCode: error.code, statusCode: error.statusCode }, 'purchase request failed safely');
      if (error.statusCode === 429) reply.header('Retry-After', '90');
      if (error.statusCode === 503) reply.header('Retry-After', '3');
      return reply.code(error.statusCode).send({ error: error.code, message: error.message });
    }
    if (error instanceof PurchaseRouteError) {
      const status = error.code === 'route_unavailable' ? 422 : 503;
      if (status === 503) reply.header('Retry-After', '3');
      return reply.code(status).send({ error: error.code, message: error.message });
    }
    if (error instanceof TokenLaunchError || error instanceof TokenLaunchLedgerError) {
      request.log.warn({ errorCode: error.code, statusCode: error.statusCode }, 'token launch request failed safely');
      if (error.statusCode === 503) reply.header('Retry-After', '3');
      return reply.code(error.statusCode).send({ error: error.code, message: error.message });
    }
    if (error instanceof CommunityBuyError || error instanceof CommunityBuyLedgerError) {
      request.log.warn({ errorCode: error.code, statusCode: error.statusCode }, 'community buy request failed safely');
      if (error.statusCode === 503) reply.header('Retry-After', '3');
      return reply.code(error.statusCode).send({ error: error.code, message: error.message });
    }
    if (error instanceof TokenMetadataError) {
      request.log.warn({ errorCode: error.code, statusCode: error.statusCode }, 'token metadata request failed safely');
      if (error.statusCode === 429) reply.header('Retry-After', '60');
      if (error.statusCode === 503) reply.header('Retry-After', '3');
      return reply.code(error.statusCode).send({ error: error.code, message: error.message });
    }
    if (error instanceof CommunityChatStorageError) return reply.code(503).header('Retry-After', '3')
      .send({ error: error.code, message: error.message });
    if (error instanceof CommunityChatAccessError) return reply.code(403)
      .send({ error: error.code, message: error.message });
    if (error instanceof CommunityMintValidationError) {
      if (error.statusCode === 503) reply.header('Retry-After', '3');
      return reply.code(error.statusCode).send({ error: error.code, message: error.message });
    }
    if (error instanceof PortfolioBusyError) return reply.code(503).header('Retry-After', '5').send({ error: 'busy', message: 'Wallet balances are refreshing. Try again shortly.' });
    if (error instanceof UnknownStockError) return reply.code(404).send({ error: 'not_found', message: 'Stock is not in the current verified catalog.' });
    if (typeof error === 'object' && error !== null && 'statusCode' in error && error.statusCode === 413) {
      return reply.code(413).send({ error: 'request_too_large', message: 'Request body exceeds the server limit.' });
    }
    if (typeof error === 'object' && error !== null && 'validation' in error) return reply.code(400).send({ error: 'invalid_request', message: 'Request parameters are invalid.' });
    return reply.code(500).send({ error: 'internal_error', message: 'Market data is temporarily unavailable.' });
  });
  app.setNotFoundHandler((_request, reply) => reply.code(404).send({ error: 'not_found', message: 'Read-only endpoint not found.' }));
  const explorerFile = explorerHtmlPath();
  const explorerHandler = (_request: unknown, reply: FastifyReply) => sendExplorerHtml(reply, explorerFile);
  app.get('/', explorerHandler);
  app.get('/explorer', explorerHandler);
  app.get('/index.html', explorerHandler);
  app.get<{ Params: { mint: string } }>('/v1/community-tokens/:mint/validate', { schema: {
    params: schema(CommunityMintParamsSchema),
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(CommunityMintValidationSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      404: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    communityMintValidationRateLimiter.take(principal.subject);
    const params = CommunityMintParamsSchema.safeParse(request.params);
    if (!params.success) return reply.code(400).send({ error: 'invalid_mint', message: 'Token address is invalid.' });
    communityMintValidation ??= new CommunityMintValidationService();
    return reply.header('Cache-Control', 'no-store').send(await communityMintValidation.validate(params.data.mint));
  });
  const chatMessageSchema = z.object({ id: z.string().regex(/^[1-9]\d*$/), authorName: z.string(), text: z.string(),
    createdAt: z.string().datetime(), isMine: z.boolean() }).strict();
  app.get<{ Querystring: { after?: string } }>('/v1/community-chat/spxtr/messages', { schema: {
    querystring: schema(ChatCursorSchema),
    response: { 200: schema(z.object({ schemaVersion: z.literal(1), roomId: z.literal(SPXTR_CHAT_ROOM),
      messages: z.array(chatMessageSchema), nextCursor: z.string(), canPost: z.boolean(),
      deletionVersion: z.number().int().nonnegative() }).strict()),
      400: schema(ErrorSchema), 401: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    const parsed = ChatCursorSchema.safeParse(request.query);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Chat cursor is invalid.' });
    communityChatReadRateLimiter.take(principal.subject);
    return reply.header('Cache-Control', 'no-store').send(communityChat.list(principal, Number(parsed.data.after ?? 0)));
  });
  app.post('/v1/community-chat/spxtr/join', { schema: {
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(z.object({ schemaVersion: z.literal(1), roomId: z.literal(SPXTR_CHAT_ROOM),
      canPost: z.literal(true) }).strict()),
      401: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    communityChatPostRateLimiter.take(principal.subject);
    return reply.header('Cache-Control', 'no-store').send(communityChat.join(principal));
  });
  app.post<{ Body: { text: string } }>('/v1/community-chat/spxtr/messages', { schema: {
    body: schema(ChatMessageRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(z.object({ schemaVersion: z.literal(1), roomId: z.literal(SPXTR_CHAT_ROOM),
      message: chatMessageSchema }).strict()),
      400: schema(ErrorSchema), 401: schema(ErrorSchema), 403: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    const parsed = ChatMessageRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Chat message is invalid.' });
    communityChatPostRateLimiter.take(principal.subject);
    return reply.header('Cache-Control', 'no-store').send(communityChat.post(principal, parsed.data.text));
  });
  app.delete('/v1/community-chat/spxtr/messages/mine', { schema: {
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(z.object({ schemaVersion: z.literal(1), roomId: z.literal(SPXTR_CHAT_ROOM),
      deletedCount: z.number().int().nonnegative() }).strict()),
      400: schema(ErrorSchema), 401: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    if (request.body !== undefined) return reply.code(400).send({ error: 'invalid_request', message: 'Chat reset takes no body.' });
    communityChatReadRateLimiter.take(principal.subject);
    return reply.header('Cache-Control', 'no-store').send(communityChat.clearMine(principal));
  });
  registerPredictionRoutes(app, options.prediction ?? new PythPredictionService({ asset: 'eth', now: options.now }), 'eth');
  registerPredictionRoutes(app, options.predictionBtc ?? new PythPredictionService({ asset: 'btc', now: options.now }), 'btc');
  registerPantaRoutes(app, options.panta === undefined ? createPantaClientFromEnv() : options.panta,
    accessTokenVerifier, new SubjectRateLimiter(options.now, 30), new SubjectRateLimiter(options.now, 8),
    new SubjectRateLimiter(options.now, 90), new SubjectRateLimiter(options.now, 25));
  app.get('/health', { schema: { response: { 200: schema(z.object({ status: z.literal('ok'), schemaVersion: z.literal(1), mode: z.literal('live-read-only') })) } } },
    async () => ({ status: 'ok', schemaVersion: 1, mode: 'live-read-only' }));
  app.post<{ Body: PortfolioRequest }>('/v1/wallet/portfolio', { schema: {
    body: schema(PortfolioRequestSchema),
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(PortfolioSchema), 400: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, (request, reply) => {
    const parsed = PortfolioRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Wallet addresses are invalid or duplicated.' });
    return portfolio.portfolio(parsed.data);
  });
  app.post<{ Body: WalletActivityRequest }>('/v1/wallet/activity', { schema: {
    body: schema(WalletActivityRequestSchema),
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(WalletActivityResponseSchema), 400: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, (request, reply) => {
    const parsed = WalletActivityRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Solana wallet address is invalid.' });
    return activity.activity(parsed.data.walletAddress);
  });
  app.post<{ Body: SolanaDevnetRequest }>('/v1/wallet/devnet', { schema: {
    body: schema(SolanaDevnetRequestSchema),
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(SolanaDevnetResponseSchema), 400: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, (request, reply) => {
    const parsed = SolanaDevnetRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Solana wallet address is invalid.' });
    return devnet.wallet(parsed.data.walletAddress);
  });
  app.post<{ Body: WalletTransactionsRequest }>('/v1/wallet/transactions', { schema: {
    body: schema(WalletTransactionsRequestSchema),
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(WalletTransactionsResponseSchema), 400: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, (request, reply) => {
    const parsed = WalletTransactionsRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Wallet addresses are invalid or duplicated.' });
    return transactions.activity(parsed.data);
  });
  app.get('/v1/purchase/activity', { schema: {
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(PurchaseActivityResponseSchema), 401: schema(ErrorSchema),
      429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request) => {
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    purchaseSubjectRateLimiter.take(principal.subject);
    return purchase.activity(principal);
  });
  app.post<{ Body: TransferRequest }>('/v1/wallet/transfer/prepare', { schema: {
    body: schema(TransferRequestSchema),
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TransferPreparationSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TransferRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Transfer preparation request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    transferSubjectRateLimiter.take(principal.subject);
    transferRateLimiter.take();
    const prepared = await transfer.prepare(parsed.data);
    transferIntentLedger.registerPrepared(principal, parsed.data, prepared);
    return prepared;
  });
  app.post<{ Body: TransferIntentCommit }>('/v1/wallet/transfer/commit', { schema: {
    body: schema(TransferIntentCommitSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TransferIntentMutationResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TransferIntentCommitSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Transfer commit request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    transferSubjectRateLimiter.take(principal.subject); transferRateLimiter.take();
    return transferIntentLedger.commit(principal, parsed.data);
  });
  app.post<{ Body: TransferIntentRelease }>('/v1/wallet/transfer/release', { schema: {
    body: schema(TransferIntentReleaseSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TransferIntentMutationResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TransferIntentReleaseSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Transfer release request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    transferSubjectRateLimiter.take(principal.subject); transferRateLimiter.take();
    return transferIntentLedger.release(principal, parsed.data);
  });
  app.post<{ Body: TransferStatusRequest }>('/v1/wallet/transfer/status', { schema: {
    body: schema(TransferStatusRequestSchema),
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TransferStatusSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TransferStatusRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Transfer status request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    transferSubjectRateLimiter.take(principal.subject);
    transferStatusRateLimiter.take();
    transferIntentLedger.recordTransaction(principal, parsed.data);
    const observation = await transferStatus.status(parsed.data);
    transferIntentLedger.recordObservation(principal, parsed.data, observation);
    return observation;
  });
  app.post<{ Body: TokenLaunchPrepareRequest }>('/v1/token-launches/prepare', { schema: {
    body: schema(TokenLaunchPrepareRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TokenLaunchPreparationSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TokenLaunchPrepareRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Token launch request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    tokenLaunchSubjectRateLimiter.take(principal.subject);
    return tokenLaunch.prepare(principal, parsed.data);
  });
  app.post<{ Body: TokenMetadataRequest }>('/v1/token-launches/metadata', { bodyLimit: TOKEN_METADATA_BODY_LIMIT,
    onRequest: async request => {
      const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
      tokenLaunchSubjectRateLimiter.take(principal.subject);
      tokenMetadataAdmissionRateLimiter.take();
      tokenMetadataPrincipals.set(request, principal);
    }, schema: {
    body: schema(TokenMetadataRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TokenMetadataPublicationSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      413: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    if (!tokenMetadataPrincipals.has(request)) {
      throw new WalletAuthenticationError('authentication_required', 401, 'A verified Privy session is required.');
    }
    const parsed = TokenMetadataRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Token metadata is invalid.' });
    return tokenMetadata.publish(parsed.data);
  });
  app.get<{ Params: { filename: string } }>('/v1/token-launches/metadata/images/:filename', { schema: {
    params: schema(TokenMetadataImageParamsSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 404: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TokenMetadataImageParamsSchema.safeParse(request.params);
    if (!parsed.success) return reply.code(404).send({ error: 'not_found', message: 'Token metadata image was not found.' });
    const asset = await tokenMetadata.image(parsed.data.filename);
    if (!asset) return reply.code(404).send({ error: 'not_found', message: 'Token metadata image was not found.' });
    return reply.header('Cache-Control', 'public, max-age=31536000, immutable').header('ETag', `"${asset.hash}"`)
      .type(asset.contentType).send(asset.bytes);
  });
  app.get<{ Params: { filename: string } }>('/v1/token-launches/metadata/documents/:filename', { schema: {
    params: schema(TokenMetadataDocumentParamsSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 404: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TokenMetadataDocumentParamsSchema.safeParse(request.params);
    if (!parsed.success) return reply.code(404).send({ error: 'not_found', message: 'Token metadata document was not found.' });
    const asset = await tokenMetadata.document(parsed.data.filename);
    if (!asset) return reply.code(404).send({ error: 'not_found', message: 'Token metadata document was not found.' });
    return reply.header('Cache-Control', 'public, max-age=31536000, immutable').header('ETag', `"${asset.hash}"`)
      .type(asset.contentType).send(asset.bytes);
  });
  app.post<{ Body: TokenLaunchCommitRequest }>('/v1/token-launches/commit', { schema: {
    body: schema(TokenLaunchCommitRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TokenLaunchMutationResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TokenLaunchCommitRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Token launch commit request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    tokenLaunchSubjectRateLimiter.take(principal.subject);
    return tokenLaunch.commit(principal, parsed.data);
  });
  app.post<{ Body: TokenLaunchReleaseRequest }>('/v1/token-launches/release', { schema: {
    body: schema(TokenLaunchReleaseRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TokenLaunchMutationResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TokenLaunchReleaseRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Token launch release request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    tokenLaunchSubjectRateLimiter.take(principal.subject);
    return tokenLaunch.release(principal, parsed.data);
  });
  app.post<{ Body: TokenLaunchSubmitRequest }>('/v1/token-launches/submit', { schema: {
    body: schema(TokenLaunchSubmitRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TokenLaunchSubmitResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TokenLaunchSubmitRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Signed token launch is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    tokenLaunchSubjectRateLimiter.take(principal.subject);
    return tokenLaunch.submit(principal, parsed.data);
  });
  app.post<{ Body: TokenLaunchStatusRequest }>('/v1/token-launches/status', { schema: {
    body: schema(TokenLaunchStatusRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(TokenLaunchStatusSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = TokenLaunchStatusRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Token launch status request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    tokenLaunchStatusRateLimiter.take(principal.subject);
    return tokenLaunch.status(principal, parsed.data);
  });
  app.post<{ Body: CommunityBuyPrepareRequest }>('/v1/community-buys/prepare', { schema: {
    body: schema(CommunityBuyPrepareRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(CommunityBuyPreparationSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = CommunityBuyPrepareRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Community buy request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    communityBuySubjectRateLimiter.take(principal.subject);
    return communityBuy.prepare(principal, parsed.data);
  });
  app.post<{ Body: CommunityBuyIdentity }>('/v1/community-buys/commit', { schema: {
    body: schema(CommunityBuyIdentitySchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(CommunityBuyMutationResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = CommunityBuyIdentitySchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Community buy commit is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    communityBuySubjectRateLimiter.take(principal.subject);
    return communityBuy.commit(principal, parsed.data);
  });
  app.post<{ Body: CommunityBuyReleaseRequest }>('/v1/community-buys/release', { schema: {
    body: schema(CommunityBuyReleaseRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(CommunityBuyMutationResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = CommunityBuyReleaseRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Community buy release is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    communityBuySubjectRateLimiter.take(principal.subject);
    return communityBuy.release(principal, parsed.data);
  });
  app.post<{ Body: CommunityBuySubmitRequest }>('/v1/community-buys/submit', { schema: {
    body: schema(CommunityBuySubmitRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(CommunityBuySubmitResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = CommunityBuySubmitRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Signed community buy is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    communityBuySubjectRateLimiter.take(principal.subject);
    return communityBuy.submit(principal, parsed.data);
  });
  app.post<{ Body: CommunityBuyIdentity }>('/v1/community-buys/status', { schema: {
    body: schema(CommunityBuyIdentitySchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(CommunityBuyStatusSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      409: schema(ErrorSchema), 422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = CommunityBuyIdentitySchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Community buy status request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    communityBuyStatusRateLimiter.take(principal.subject);
    return communityBuy.status(principal, parsed.data);
  });
  app.post<{ Body: PurchaseOptionsRequest }>('/v1/purchases/options', { schema: {
    body: schema(PurchaseOptionsRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(PurchaseOptionsResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema),
      422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = PurchaseOptionsRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Purchase options request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    purchaseSubjectRateLimiter.take(principal.subject);
    return purchase.options(principal, parsed.data);
  });
  app.post<{ Body: PurchaseQuoteRequest }>('/v1/purchases/quote', { schema: {
    body: schema(PurchaseQuoteRequestSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(PurchaseQuoteResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema), 409: schema(ErrorSchema),
      422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const parsed = PurchaseQuoteRequestSchema.safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: 'invalid_request', message: 'Purchase quote request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    purchaseSubjectRateLimiter.take(principal.subject);
    return purchase.quote(principal, parsed.data);
  });
  app.post<{ Params: { quoteId: string }; Body: PurchaseCommitRequest }>('/v1/purchases/:quoteId/commit', { schema: {
    params: schema(PurchaseIdParamsSchema), body: schema(PurchaseCommitRequestSchema),
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(PurchaseCommitResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema), 409: schema(ErrorSchema),
      422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const params = PurchaseIdParamsSchema.safeParse(request.params); const body = PurchaseCommitRequestSchema.safeParse(request.body);
    if (!params.success || !body.success) return reply.code(400).send({ error: 'invalid_request', message: 'Purchase commit request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    purchaseSubjectRateLimiter.take(principal.subject);
    return purchase.commit(principal, params.data.quoteId);
  });
  app.post<{ Params: { quoteId: string; actionId: string }; Body: PurchaseSubmittedRequest }>(
    '/v1/purchases/:quoteId/actions/:actionId/submitted', { schema: {
      params: schema(PurchaseActionParamsSchema), body: schema(PurchaseSubmittedRequestSchema),
      querystring: { type: 'object', additionalProperties: false, properties: {} },
      response: { 200: schema(PurchaseStatusSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema), 409: schema(ErrorSchema),
        422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
    } }, async (request, reply) => {
      const params = PurchaseActionParamsSchema.safeParse(request.params); const body = PurchaseSubmittedRequestSchema.safeParse(request.body);
      if (!params.success || !body.success) return reply.code(400).send({ error: 'invalid_request', message: 'Purchase transaction report is invalid.' });
      const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
      purchaseSubjectRateLimiter.take(principal.subject);
      return purchase.submitted(principal, params.data.quoteId, params.data.actionId, body.data);
    });
  app.post<{ Params: { quoteId: string; actionId: string }; Body: PurchaseInvokingRequest }>(
    '/v1/purchases/:quoteId/actions/:actionId/invoking', { schema: {
      params: schema(PurchaseActionParamsSchema), body: schema(PurchaseInvokingRequestSchema),
      querystring: { type: 'object', additionalProperties: false, properties: {} },
      response: { 200: schema(PurchaseInvokingResponseSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema), 409: schema(ErrorSchema),
        422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
    } }, async (request, reply) => {
      const params = PurchaseActionParamsSchema.safeParse(request.params); const body = PurchaseInvokingRequestSchema.safeParse(request.body);
      if (!params.success || !body.success) return reply.code(400).send({ error: 'invalid_request', message: 'Purchase invocation request is invalid.' });
      const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
      purchaseSubjectRateLimiter.take(principal.subject);
      return purchase.invoking(principal, params.data.quoteId, params.data.actionId);
    });
  app.post<{ Params: { quoteId: string; actionId: string }; Body: PurchaseInvocationReleaseRequest }>(
    '/v1/purchases/:quoteId/actions/:actionId/release', { schema: {
      params: schema(PurchaseActionParamsSchema), body: schema(PurchaseInvocationReleaseRequestSchema),
      querystring: { type: 'object', additionalProperties: false, properties: {} },
      response: { 200: schema(PurchaseStatusSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema), 409: schema(ErrorSchema),
        422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
    } }, async (request, reply) => {
      const params = PurchaseActionParamsSchema.safeParse(request.params); const body = PurchaseInvocationReleaseRequestSchema.safeParse(request.body);
      if (!params.success || !body.success) return reply.code(400).send({ error: 'invalid_request', message: 'Purchase invocation release is invalid.' });
      const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
      purchaseSubjectRateLimiter.take(principal.subject);
      return purchase.releaseInvocation(principal, params.data.quoteId, params.data.actionId);
    });
  app.get<{ Params: { quoteId: string } }>('/v1/purchases/:quoteId/status', { schema: {
    params: schema(PurchaseIdParamsSchema), querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(PurchaseStatusSchema), 400: schema(ErrorSchema), 401: schema(ErrorSchema), 409: schema(ErrorSchema),
      422: schema(ErrorSchema), 429: schema(ErrorSchema), 503: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async (request, reply) => {
    const params = PurchaseIdParamsSchema.safeParse(request.params);
    if (!params.success) return reply.code(400).send({ error: 'invalid_request', message: 'Purchase status request is invalid.' });
    const principal = await accessTokenVerifier.verifyAuthorization(request.headers.authorization);
    purchaseSubjectRateLimiter.take(principal.subject);
    return purchase.status(principal, params.data.quoteId);
  });
  app.get('/v1/stocks', { schema: {
    querystring: { type: 'object', additionalProperties: false, properties: {} },
    response: { 200: schema(CatalogSchema), 500: schema(ErrorSchema) },
  } }, () => { feed.start(); return feed.snapshot(); });
  app.get<{ Querystring: { provider: 'robinhood' } }>('/v1/stocks/featured', { schema: {
    querystring: { type: 'object', required: ['provider'], additionalProperties: false,
      properties: { provider: { type: 'string', enum: ['robinhood'] } } },
    response: { 200: schema(CatalogSchema), 400: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, async request => {
    if (service.featuredCatalog) return service.featuredCatalog(request.query.provider);
    const catalog = snapshotAt(await service.catalog(), (options.now ?? Date.now)());
    return { ...catalog, stocks: featuredRobinhoodStocks(catalog.stocks) };
  });
  app.get('/v1/market/diagnostics', async () => ({ ...(service.diagnostics?.() as object ?? {}), websocket: marketSocket.metrics }));
  app.get('/v1/stocks/stream', { schema: {
    querystring: { type: 'object', additionalProperties: false, properties: {} },
  } }, async (_request, reply) => {
    if (streams.size >= 64) return reply.code(503).header('Retry-After', '2').send({ error: 'busy', message: 'Reconnect automatically in two seconds.' });
    reply.hijack();
    streams.attach(reply.raw);
  });
  app.get<{ Params: { id: string }; Querystring: { range: z.infer<typeof Range> } }>('/v1/stocks/:id/charts', { schema: {
    params: { type: 'object', required: ['id'], additionalProperties: false, properties: { id: { type: 'string', minLength: 3, maxLength: 100, pattern: '^(backed|backpack|prestocks|robinhood):[A-Za-z0-9.-]+$' } } },
    querystring: { type: 'object', additionalProperties: false, required: ['range'], properties: { range: { type: 'string', enum: Range.options } } },
    response: { 200: schema(ChartSchema), 400: schema(ErrorSchema), 404: schema(ErrorSchema), 500: schema(ErrorSchema) },
  } }, request => service.chart(request.params.id, request.query.range));
  return app;
}
