import type { PaymentAssetDefinition } from './purchase-chain.js';
import type { PurchaseDestination } from './purchase-schema.js';

export interface PurchaseRouteQuoteRequest {
  source: PaymentAssetDefinition;
  destination: PurchaseDestination;
  amountBaseUnits: string;
  fromAddress: string;
  toAddress: string;
  slippageBps: number;
}

export interface SolanaRouteTransaction {
  kind: 'SOLANA';
  transactionBase64: string;
  minContextSlot: string | null;
  lastValidBlockHeight: string | null;
}

export interface PurchaseRouteQuote {
  routeId: string;
  tool: string;
  sourceChainId: string;
  destinationChainId: string;
  executionValidated?: boolean;
  expiresAt?: string;
  fromTokenAddress: string;
  toTokenAddress: string;
  fromAddress: string;
  toAddress: string;
  fromAmount: string;
  toAmount: string;
  toAmountMin: string;
  toDecimals: number;
  fromAmountUsd: string | null;
  toAmountUsd: string | null;
  feesUsd: string | null;
  priceImpactPercent: string | null;
  sourceGasBaseUnits: string | null;
  approvalAddress: null;
  transaction: SolanaRouteTransaction;
}

export type PurchaseExecutionStatus = {
  state: 'PENDING' | 'DONE' | 'FAILED';
  receivedBaseUnits: string | null;
  message: string | null;
  destinationTransactionId?: string | null;
};

export interface PurchaseRouteStatusRequest {
  transactionId: string;
  tool: string;
  fromChainId: string;
  toChainId: string;
  routeId?: string;
  expectedTransactionBase64?: string;
  destinationAddress?: string;
  recipientAddress?: string;
  minimumReceivedBaseUnits?: string;
}

export interface PurchaseRouter {
  quote(request: PurchaseRouteQuoteRequest): Promise<PurchaseRouteQuote>;
  status(request: PurchaseRouteStatusRequest): Promise<PurchaseExecutionStatus>;
}

export class PurchaseRouteError extends Error {
  constructor(readonly code: 'route_unavailable' | 'router_unavailable' | 'unsafe_router_response', message: string) {
    super(message);
  }
}
