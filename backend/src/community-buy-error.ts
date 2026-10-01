export class CommunityBuyError extends Error {
  constructor(readonly code: 'unsupported_token' | 'pool_complete' | 'quote_unavailable' | 'insufficient_mainnet_balance' |
    'buy_cost_too_high' | 'simulation_failed' | 'signed_transaction_invalid' | 'blockhash_expired' |
    'mainnet_rpc_unavailable', readonly statusCode: 422 | 503, message: string) { super(message); }
}
