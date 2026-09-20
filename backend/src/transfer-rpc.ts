import { z } from 'zod';
import { limitConcurrency } from './cache.js';
import { parseProviderJson } from './http.js';
import { rpcEndpoint } from './portfolio-upstream.js';
import type { TransferChain } from './transfer-schema.js';

export interface TransferRpc {
  call(chain: TransferChain, method: string, params: unknown[], signal: AbortSignal): Promise<unknown>;
}

const Allowed: Record<TransferChain, ReadonlySet<string>> = {
  ETHEREUM: new Set(['eth_chainId', 'eth_getBlockByNumber', 'eth_getTransactionCount', 'eth_getBalance', 'eth_getCode',
    'eth_call', 'eth_estimateGas', 'eth_maxPriorityFeePerGas', 'eth_gasPrice', 'eth_blockNumber',
    'eth_getTransactionByHash', 'eth_getTransactionReceipt']),
  SOLANA: new Set(['getGenesisHash', 'getBalance', 'getAccountInfo', 'getLatestBlockhash', 'getFeeForMessage', 'simulateTransaction',
    'getSignatureStatuses', 'getTransaction']),
};
const runRpc = limitConcurrency(4);

class SafeRpcFailure extends Error {
  constructor() { super('Mainnet RPC read unavailable'); }
}

async function readBounded(response: Response): Promise<unknown> {
  if (!response.ok) throw new SafeRpcFailure();
  const reader = response.body?.getReader();
  if (!reader) throw new SafeRpcFailure();
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      size += chunk.value.length;
      if (size > 1_000_000) throw new SafeRpcFailure();
      chunks.push(chunk.value);
    }
  } finally { await reader.cancel(); }
  try { return parseProviderJson(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new SafeRpcFailure(); }
}

/** Fixed at construction from server environment. Requests can never select or modify an RPC origin. */
export function publicTransferRpc(env: NodeJS.ProcessEnv = process.env): TransferRpc {
  const endpoints = {
    SOLANA: rpcEndpoint(env.SOLANA_RPC_URL ?? 'https://api.mainnet.solana.com'),
    ETHEREUM: rpcEndpoint(env.ETHEREUM_RPC_URL ?? 'https://ethereum-rpc.publicnode.com'),
  };
  return {
    async call(chain, method, params, signal) {
      if (!Allowed[chain].has(method) || params.length > 4) throw new Error('Only bounded transfer-preparation reads are permitted');
      return runRpc(async () => {
        signal.throwIfAborted();
        const body = { jsonrpc: '2.0' as const, id: '1', method, params };
        let raw: unknown;
        try {
          const response = await fetch(endpoints[chain], {
            method: 'POST', redirect: 'error', signal: AbortSignal.any([signal, AbortSignal.timeout(6_000)]),
            headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, body: JSON.stringify(body),
          });
          raw = await readBounded(response);
        } catch (error) {
          if (error instanceof SafeRpcFailure || (error instanceof Error && ['AbortError', 'TimeoutError'].includes(error.name))) throw error;
          throw new SafeRpcFailure();
        }
        const parsed = z.object({ jsonrpc: z.literal('2.0'), id: z.union([z.literal('1'), z.literal(1)]),
          result: z.unknown().optional(), error: z.unknown().optional() }).strict().safeParse(raw);
        if (!parsed.success || parsed.data.error !== undefined || parsed.data.result === undefined) throw new SafeRpcFailure();
        return parsed.data.result;
      });
    },
  };
}
