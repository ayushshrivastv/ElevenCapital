import assert from 'node:assert/strict';
import test from 'node:test';
import { getOrderId, type Order } from '@relay-protocol/settlement-sdk';
import { encodeFunctionData, hexToBytes, parseAbi, recoverMessageAddress } from 'viem';
import { LiFiError } from '../src/lifi.js';
import { type JsonRpc } from '../src/purchase-chain.js';
import { MAINNET_GENESIS } from '../src/portfolio-upstream.js';
import { RELAY_ANTHROPIC_MINT, RELAY_ANTHROPIC_TOOL, RelayClient } from '../src/relay.js';
import { encodeBase58 } from '../src/transfer.js';

const NOW = Date.UTC(2026, 8, 24, 0, 0, 0);
const USER = '0x1111111111111111111111111111111111111111';
const RECIPIENT = '8wkJRHpXPLhApKTBEpy1kf9Kejid7SDBGtoqvLRvDMmM';
const USDC = '0xaf88d065e77c8cc2239327c5edb3a432268e5831';
const ETH = '0x0000000000000000000000000000000000000000';
const PYUSD = '2b1kV6DkPAnxd5ixfnxCpjxmKwqjjaYmCZfHsFu24GXo';
const ETH_AMOUNT = '500000000000000';
const DEPOSITORY = '0x4cd00e387622c35bddb9b4c962c136462338bc31';
const REQUEST_ID = '0x' + 'a'.repeat(64);
const SIGNATURE = '0x' + 'd'.repeat(130);
const SOLVER = '0xf70da97812cb96acdf810712aa562db8dfa3dbef';
const TX_HASH = '0x' + 'b'.repeat(64);
const SIG = encodeBase58(Buffer.alloc(64, 7));
// Captured from a read-only Relay quote with includeProtocolData: true; no signing key is held by the test.
const LIVE_ORDER_ID = '0xa45cd093166bb92f6c3398a8ae1ed453b4ae4d7f82b5347f98297d7123a8ed27';
const LIVE_ORDER_SIGNATURE = '0x71ba981e85913f7bae4c20d8fb61a34f78803a368536586c9644fa7f67854ec7401e3dffa01bbbe066caf888faab0cd7c69b69f215d12a3feb8879fb154b892e1c';
const MINIMUM = '9950000';
const VM = { arbitrum: 'ethereum-vm', base: 'ethereum-vm', solana: 'solana-vm' } as const;
const DEPOSIT_ABI = parseAbi(['function depositErc20(address depositor, address token, uint256 amount, bytes32 id)']);
const DEPOSIT_NATIVE_ABI = parseAbi(['function depositNative(address depositor, bytes32 id)']);
const APPROVAL_ABI = parseAbi(['function approve(address spender, uint256 amount)']);
const request = { fromAddress: USER, recipientAddress: RECIPIENT, amountBaseUnits: '10000000', slippageBps: 50 };

function quoteBody() {
  const deadline = Math.floor(NOW / 1000) + 86_400;
  const orderData: Order = {
    version: 'v1', solverChainId: 'base', solver: '0xf70da97812cb96acdf810712aa562db8dfa3dbef',
    salt: '0x' + 'c'.repeat(64),
    inputs: [{ payment: { chainId: 'arbitrum', currency: USDC, amount: request.amountBaseUnits, weight: '1' },
      refunds: [
        { chainId: 'arbitrum', recipient: USER, currency: USDC, minimumAmount: '0', deadline,
          extraData: '0x' },
        { chainId: 'solana', recipient: RECIPIENT,
          currency: 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v', minimumAmount: '0',
          deadline, extraData: '0x' },
      ] }],
    output: { chainId: 'solana', payments: [{ recipient: RECIPIENT, currency: RELAY_ANTHROPIC_MINT,
      minimumAmount: MINIMUM, expectedAmount: '10000000' }], calls: [], deadline, extraData: '0x' },
    fees: [],
  };
  const orderId = getOrderId(orderData, VM);
  const tx = (to: string, data: string, gas: string) => ({
    from: USER, to, data, value: '0', chainId: 42161, gas,
    maxFeePerGas: '22044000', maxPriorityFeePerGas: '0',
  });
  const approve = tx(USDC, encodeFunctionData({ abi: APPROVAL_ABI, functionName: 'approve',
    args: [DEPOSITORY, 10_000_000n] }), '74009');
  const deposit = tx(DEPOSITORY, encodeFunctionData({ abi: DEPOSIT_ABI, functionName: 'depositErc20',
    args: [USER, USDC, 10_000_000n, orderId] }), '77372');
  const currency = (chainId: number, address: string, decimals: number) => ({ chainId, address, decimals });
  return {
    requestId: REQUEST_ID,
    steps: [
      { id: 'approve', kind: 'transaction', requestId: REQUEST_ID,
        items: [{ status: 'incomplete', data: approve }] },
      { id: 'deposit', kind: 'transaction', requestId: REQUEST_ID,
        items: [{ status: 'incomplete', data: deposit,
          check: { method: 'GET', endpoint: '/intents/status/v3?requestId=' + REQUEST_ID } }] },
    ],
    details: { operation: 'swap', sender: USER, recipient: RECIPIENT,
      currencyIn: { currency: currency(42161, USDC, 6), amount: '10000000',
        minimumAmount: '10000000', amountUsd: '10' },
      currencyOut: { currency: currency(792703809, RELAY_ANTHROPIC_MINT, 9), amount: '10000000',
        minimumAmount: MINIMUM, amountUsd: '9.7' } },
    fees: { gas: { currency: currency(42161, '0x0000000000000000000000000000000000000000', 18),
        amount: '2000000000000', amountUsd: '0.004' },
      relayer: { currency: currency(42161, USDC, 6), amount: '260000', amountUsd: '0.26' },
      app: { currency: currency(42161, USDC, 6), amount: '0', amountUsd: '0' } },
    protocol: { v2: { orderId, hubType: 'onchain', orderData, orderSignature: SIGNATURE,
      paymentDetails: { chainId: 'arbitrum', depository: DEPOSITORY, currency: USDC,
        amount: request.amountBaseUnits } } },
  };
}

function rebindDeposit(body: ReturnType<typeof quoteBody>, native = false): void {
  const orderId = getOrderId(body.protocol.v2.orderData, VM);
  body.protocol.v2.orderId = orderId;
  const deposit = body.steps.at(-1)!.items[0]!.data;
  deposit.data = native
    ? encodeFunctionData({ abi: DEPOSIT_NATIVE_ABI, functionName: 'depositNative', args: [USER, orderId] })
    : encodeFunctionData({ abi: DEPOSIT_ABI, functionName: 'depositErc20',
      args: [USER, USDC, BigInt(request.amountBaseUnits), orderId] });
}

function nativeQuoteBody() {
  const body = quoteBody();
  const order = body.protocol.v2.orderData;
  order.inputs[0]!.payment.currency = ETH;
  order.inputs[0]!.payment.amount = ETH_AMOUNT;
  order.inputs[0]!.refunds[0]!.currency = ETH;
  order.inputs[0]!.refunds[1]!.currency = PYUSD;
  order.output.payments[0]!.minimumAmount = '995000';
  order.output.payments[0]!.expectedAmount = '1000000';
  body.protocol.v2.paymentDetails.currency = ETH;
  body.protocol.v2.paymentDetails.amount = ETH_AMOUNT;
  body.details.currencyIn.currency.address = ETH;
  body.details.currencyIn.currency.decimals = 18;
  body.details.currencyIn.amount = ETH_AMOUNT;
  body.details.currencyIn.minimumAmount = ETH_AMOUNT;
  body.details.currencyIn.amountUsd = '1.33';
  body.details.currencyOut.amount = '1000000';
  body.details.currencyOut.minimumAmount = '995000';
  body.details.currencyOut.amountUsd = '1.03';
  body.fees.gas.currency.address = ETH;
  body.fees.relayer.currency.address = ETH;
  body.fees.relayer.currency.decimals = 18;
  body.fees.relayer.amount = '100000000000000';
  body.fees.relayer.amountUsd = '0.267';
  body.fees.app.currency.address = ETH;
  body.fees.app.currency.decimals = 18;
  body.steps = [body.steps[1]!];
  body.steps[0]!.items[0]!.data.value = ETH_AMOUNT;
  body.steps[0]!.items[0]!.data.gas = '33911';
  rebindDeposit(body, true);
  return body;
}

function fetcher(body: unknown, inspect?: (url: URL, init?: RequestInit) => void): typeof fetch {
  return (async (input: URL | RequestInfo, init?: RequestInit) => {
    const url = new URL(typeof input === 'string' ? input : input instanceof URL ? input.href : input.url);
    inspect?.(url, init);
    return new Response(JSON.stringify(body), { status: 200, headers: { 'content-type': 'application/json' } });
  }) as typeof fetch;
}
const recoverTestSigner = async (_orderId: `0x${string}`, signature: `0x${string}`) =>
  signature === SIGNATURE ? SOLVER : USER;
function client(body: unknown): RelayClient {
  return new RelayClient(fetcher(body), undefined, () => NOW, undefined, recoverTestSigner);
}
function isUnsafe(error: unknown): boolean {
  return error instanceof LiFiError && error.code === 'unsafe_router_response';
}

test('Relay quote sends pinned exact-input request and returns only a verified route action', async () => {
  const source = quoteBody();
  const relay = new RelayClient(fetcher(source, (url, init) => {
    assert.equal(url.origin, 'https://api.relay.link');
    assert.equal(url.pathname, '/quote/v2');
    assert.equal(init?.method, 'POST');
    assert.equal(init?.redirect, 'error');
    const sent = JSON.parse(String(init?.body));
    assert.deepEqual({ chain: sent.originChainId, destination: sent.destinationChainId,
      input: sent.originCurrency, output: sent.destinationCurrency, amount: sent.amount,
      slippage: sent.slippageTolerance, ttl: sent.ttl, protocol: sent.includeProtocolData },
    { chain: 42161, destination: 792703809, input: USDC, output: RELAY_ANTHROPIC_MINT,
      amount: '10000000', slippage: '50', ttl: 300, protocol: true });
    assert.equal(sent.user, USER);
    assert.equal(sent.recipient, RECIPIENT);
    assert.equal(sent.tradeType, 'EXACT_INPUT');
  }), undefined, () => NOW, undefined, recoverTestSigner);
  const quote = await relay.quote(request);
  assert.equal(quote.requestId, REQUEST_ID);
  assert.equal(quote.routeId, REQUEST_ID);
  assert.equal(quote.tool, RELAY_ANTHROPIC_TOOL);
  assert.equal(quote.executionValidated, true);
  assert.equal(quote.approvalAddress, DEPOSITORY);
  assert.equal(quote.toAmountMin, MINIMUM);
  assert.equal(quote.transaction.to, DEPOSITORY);
  assert.equal(quote.transaction.value, '0x0');
  assert.equal(quote.expiresAt, new Date(NOW + 240_000).toISOString());
});

test('Relay solver signs the raw order ID under EIP-191; altered order IDs cannot recover the pinned signer', async () => {
  const signer = await recoverMessageAddress({ message: { raw: hexToBytes(LIVE_ORDER_ID) },
    signature: LIVE_ORDER_SIGNATURE });
  assert.equal(signer.toLowerCase(), SOLVER);
  const alteredOrder = LIVE_ORDER_ID.slice(0, -1) + '6';
  const alteredSigner = await recoverMessageAddress({ message: { raw: hexToBytes(alteredOrder) },
    signature: LIVE_ORDER_SIGNATURE });
  assert.notEqual(alteredSigner.toLowerCase(), SOLVER);
  const alteredSignature = '0x70' + LIVE_ORDER_SIGNATURE.slice(4);
  await assert.rejects(recoverMessageAddress({ message: { raw: hexToBytes(LIVE_ORDER_ID) },
    signature: alteredSignature }));
});

test('Relay quote rejects altered recipient, mint, order commitment, deposit, approval, chain, fees and extra steps', async () => {
  const cases: Array<(body: ReturnType<typeof quoteBody>) => void> = [
    q => { q.details.recipient = '11111111111111111111111111111111'; },
    q => { q.details.currencyOut.currency.address = 'So11111111111111111111111111111111111111112'; },
    q => { q.protocol.v2.orderData.output.payments[0]!.recipient = '11111111111111111111111111111111'; },
    q => { q.protocol.v2.orderId = '0x' + 'f'.repeat(64); },
    q => { q.protocol.v2.orderSignature = '0x' + 'e'.repeat(130); },
    q => { q.protocol.v2.paymentDetails.depository = USER; },
    q => { q.steps[1]!.items[0]!.data.to = USER; },
    q => { q.steps[1]!.items[0]!.data.chainId = 1; },
    q => { q.steps[1]!.items[0]!.data.value = '1'; },
    q => { q.steps[1]!.items[0]!.data.gas = '1000001'; },
    q => { q.steps[1]!.items[0]!.data.data = '0xe8017952'; },
    q => { q.steps[0]!.items[0]!.data.data = encodeFunctionData({ abi: APPROVAL_ABI,
      functionName: 'approve', args: [DEPOSITORY, 10_000_001n] }); },
    q => { q.steps[0]!.items[0]!.data.data = encodeFunctionData({ abi: APPROVAL_ABI,
      functionName: 'approve', args: [USER, 10_000_000n] }); },
    q => { q.steps.push(structuredClone(q.steps[1]!)); },
    q => { q.fees.gas.amount = '100000000000000001'; },
  ];
  for (const mutate of cases) {
    const body = quoteBody(); mutate(body);
    await assert.rejects(client(body).quote(request), isUnsafe);
  }
});

test('Relay enforces reviewed minimum slippage and bounded demo fees', async () => {
  const slippage = quoteBody();
  slippage.details.currencyOut.minimumAmount = '9800000';
  slippage.protocol.v2.orderData.output.payments[0]!.minimumAmount = '9800000';
  await assert.rejects(client(slippage).quote(request),
    error => error instanceof LiFiError && error.code === 'route_unavailable');

  const fees = quoteBody();
  fees.fees.relayer.amountUsd = '5.1';
  await assert.rejects(client(fees).quote(request),
    error => error instanceof LiFiError && error.code === 'route_unavailable');
  await assert.rejects(client(quoteBody()).quote({ ...request, amountBaseUnits: '10000001' }),
    error => error instanceof LiFiError && error.code === 'route_unavailable');

  const rounded = quoteBody();
  rounded.details.currencyOut.minimumAmount = '9949999';
  rounded.protocol.v2.orderData.output.payments[0]!.minimumAmount = '9949999';
  rebindDeposit(rounded);
  assert.equal((await client(rounded).quote(request)).toAmountMin, '9949999');
  rounded.details.currencyOut.minimumAmount = '9949998';
  rounded.protocol.v2.orderData.output.payments[0]!.minimumAmount = '9949998';
  rebindDeposit(rounded);
  await assert.rejects(client(rounded).quote(request),
    error => error instanceof LiFiError && error.code === 'route_unavailable');
});

test('Relay verifies native Arbitrum ETH as one exact-value deposit with no approval', async () => {
  const body = nativeQuoteBody();
  const relay = new RelayClient(fetcher(body, (_url, init) => {
    const sent = JSON.parse(String(init?.body));
    assert.equal(sent.originCurrency, ETH);
    assert.equal(sent.amount, ETH_AMOUNT);
    assert.equal(sent.slippageTolerance, '50');
    assert.equal(sent.includeProtocolData, true);
  }), undefined, () => NOW, undefined, recoverTestSigner);
  const quote = await relay.quote({ ...request, sourceAssetId: 'ARBITRUM:ETH', amountBaseUnits: ETH_AMOUNT });
  assert.equal(quote.executionValidated, true);
  assert.equal(quote.fromTokenAddress, ETH);
  assert.equal(quote.fromAmountUsd, '1.33');
  assert.equal(quote.approvalAddress, null);
  assert.equal(quote.transaction.to, DEPOSITORY);
  assert.equal(quote.transaction.value, '0x1c6bf52634000');
  assert.equal(quote.toAmountMin, '995000');
  assert.equal(quote.expiresAt, new Date(NOW + 240_000).toISOString());
});

test('Relay rejects native ETH value, calldata, refund, fee and source substitution', async () => {
  const nativeRequest = { ...request, sourceAssetId: 'ARBITRUM:ETH' as const, amountBaseUnits: ETH_AMOUNT };
  const cases: Array<(body: ReturnType<typeof nativeQuoteBody>) => void> = [
    q => { q.steps[0]!.items[0]!.data.value = '500000000000001'; },
    q => { q.steps[0]!.items[0]!.data.data = encodeFunctionData({ abi: DEPOSIT_NATIVE_ABI,
      functionName: 'depositNative', args: [DEPOSITORY, q.protocol.v2.orderId] }); },
    q => { q.steps[0]!.items[0]!.data.data = encodeFunctionData({ abi: DEPOSIT_ABI,
      functionName: 'depositErc20', args: [USER, ETH, BigInt(ETH_AMOUNT), q.protocol.v2.orderId] }); },
    q => { q.steps.unshift(structuredClone(q.steps[0]!)); },
    q => { q.protocol.v2.orderData.inputs[0]!.refunds[0]!.currency = USDC; rebindDeposit(q, true); },
    q => { q.protocol.v2.orderData.inputs[0]!.refunds[1]!.currency = RELAY_ANTHROPIC_MINT; rebindDeposit(q, true); },
    q => { q.protocol.v2.orderData.inputs[0]!.refunds[1]!.recipient = '11111111111111111111111111111111'; rebindDeposit(q, true); },
    q => { q.fees.relayer.currency.address = USDC; },
    q => { q.details.currencyIn.currency.decimals = 6; },
    q => { q.details.currencyIn.amountUsd = '10.01'; },
  ];
  for (const mutate of cases) {
    const body = nativeQuoteBody(); mutate(body);
    await assert.rejects(client(body).quote(nativeRequest),
      error => error instanceof LiFiError && ['unsafe_router_response', 'route_unavailable'].includes(error.code));
  }
  await assert.rejects(client(nativeQuoteBody()).quote({ ...nativeRequest, amountBaseUnits: '3000000000000001' }),
    error => error instanceof LiFiError && error.code === 'route_unavailable');
});

function statusBody(status: string) {
  return { status, inTxHashes: [TX_HASH], txHashes: [SIG],
    originChainId: 42161, destinationChainId: 792703809 };
}
function destinationTx(amount = '9950100') {
  const balance = (value: string) => ({ accountIndex: 2, owner: RECIPIENT, mint: RELAY_ANTHROPIC_MINT,
    uiTokenAmount: { amount: value, decimals: 9 } });
  const usdc = (value: string) => ({ accountIndex: 3, owner: RECIPIENT,
    mint: 'EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v',
    uiTokenAmount: { amount: value, decimals: 6 } });
  return { transaction: { signatures: [SIG] }, meta: { err: null,
    preTokenBalances: [balance('100'), usdc('2000')],
    postTokenBalances: [balance(amount), usdc('1500')] } };
}
function rpc(destination: unknown = destinationTx(), receipt: unknown = { transactionHash: TX_HASH, status: '0x1' }): JsonRpc {
  return { async call(network, method, params) {
    if (network === 'ARBITRUM' && method === 'eth_chainId') return '0xa4b1';
    if (network === 'ARBITRUM' && method === 'eth_getTransactionReceipt') {
      assert.deepEqual(params, [TX_HASH]); return receipt;
    }
    if (network === 'SOLANA' && method === 'getGenesisHash') return MAINNET_GENESIS;
    if (network === 'SOLANA' && method === 'getTransaction') {
      assert.equal(params[0], SIG); return destination;
    }
    assert.fail('Unexpected RPC read');
  } };
}
const statusRequest = { requestId: REQUEST_ID, transactionId: TX_HASH, recipientAddress: RECIPIENT,
  destinationMint: RELAY_ANTHROPIC_MINT, minimumReceivedBaseUnits: MINIMUM };

test('Relay status proves source receipt and exact recipient Anthropic token delta before DONE', async () => {
  const relay = new RelayClient(fetcher(statusBody('success'), (url, init) => {
    assert.equal(url.origin, 'https://api.relay.link');
    assert.equal(url.pathname, '/intents/status/v3');
    assert.equal(url.searchParams.get('requestId'), REQUEST_ID);
    assert.equal(init?.method, 'GET');
  }), rpc(), () => NOW);
  assert.deepEqual(await relay.status(statusRequest), {
    state: 'DONE', receivedBaseUnits: '9950000', destinationTransactionId: SIG, message: null,
  });
});

test('Relay status stays pending until both receipts exist and fails closed on refunds and reverts', async () => {
  assert.equal((await new RelayClient(fetcher(statusBody('pending')), rpc(null), () => NOW)
    .status(statusRequest)).state, 'PENDING');
  assert.equal((await new RelayClient(fetcher(statusBody('success')), rpc(null), () => NOW)
    .status(statusRequest)).state, 'PENDING');
  assert.equal((await new RelayClient(fetcher(statusBody('refund')), rpc(), () => NOW)
    .status(statusRequest)).state, 'FAILED');
  assert.equal((await new RelayClient(fetcher(statusBody('waiting')),
    rpc(destinationTx(), { transactionHash: TX_HASH, status: '0x0' }), () => NOW)
    .status(statusRequest)).state, 'FAILED');
});

test('Relay status rejects wrong source hash, destination signature, mint and short receipt', async () => {
  await assert.rejects(new RelayClient(fetcher({ ...statusBody('success'), inTxHashes: ['0x' + 'c'.repeat(64)] }),
    rpc(), () => NOW).status(statusRequest), isUnsafe);
  await assert.rejects(new RelayClient(fetcher({ ...statusBody('success'), txHashes: ['11111111111111111111111111111111'] }),
    rpc(), () => NOW).status(statusRequest), isUnsafe);
  await assert.rejects(new RelayClient(fetcher(statusBody('success')), rpc(destinationTx('9949000')), () => NOW)
    .status(statusRequest), isUnsafe);
  const internalMove = destinationTx();
  internalMove.meta.preTokenBalances.push({ accountIndex: 4, owner: RECIPIENT, mint: RELAY_ANTHROPIC_MINT,
    uiTokenAmount: { amount: '1000', decimals: 9 } });
  await assert.rejects(new RelayClient(fetcher(statusBody('success')), rpc(internalMove), () => NOW)
    .status(statusRequest), isUnsafe);
  await assert.rejects(new RelayClient(fetcher(statusBody('success')), rpc(), () => NOW)
    .status({ ...statusRequest, destinationMint: 'So11111111111111111111111111111111111111112' }), isUnsafe);
});
