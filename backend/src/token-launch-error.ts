export class TokenLaunchError extends Error {
  constructor(readonly code: 'invalid_launch' | 'insufficient_mainnet_balance' | 'launch_cost_too_high' |
    'simulation_failed' | 'signed_transaction_invalid' | 'blockhash_expired' | 'mainnet_rpc_unavailable',
  readonly statusCode: 422 | 503, message: string) { super(message); }
}
