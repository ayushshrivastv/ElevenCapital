import type { Stock } from './schema.js';

// Reviewed against the issuer's exact chain4663 identities and live Gecko pool relationships on 2026-09-29.
// These are discovery hints only: every live response must still prove its base/quote, liquidity and metrics.
// No market values or observation clocks are seeded here.
export const ReviewedRobinhoodMarketPools = [
  ["MSFT", "US5949181045", "0xe93237c50d904957cf27e7b1133b510c669c2e74", "0xeb60bcd1d920ad6e102690ccfc6fb488899e1510"],
  ["AVGO", "US11135F1012", "0x156e175dd063a8ce274c50654ef40e0032b3fbcf", "0x5b7c404f1d7d77f9f3885ab13d7764f8a173028c"],
  ["TSLA", "US88160R1014", "0x322f0929c4625ed5bad873c95208d54e1c003b2d", "0x8517f8071ae5b831b738052f12125e8e3d6c158b78728aa44ce3b25e5104d32e"],
  ["GOOGL", "US02079K3059", "0x2e0847e8910a9732eb3fb1bb4b70a580adad4fe3", "0x34d0dc122cf9a8eb296fc5e0d3a233625d7d19b7"],
  ["META", "US30303M1027", "0xc0d6457c16cc70d6790dd43521c899c87ce02f35", "0x5875d407a42965b0e768c8925cea290e06fa50603ef34fc99eb92a1050e6ae36"],
  ["NFLX", "US64110L1061", "0xe0444ef8bf4ed74f74fd73686e2ddf4c1c5591e8", "0x59895c0302f41aeaa129d2fa2442cec01e7ef45e"],
  ["AAPL", "US0378331005", "0xaf3d76f1834a1d425780943c99ea8a608f8a93f9", "0xc748f4671a867db48b552f6b7650bf3255e05f80f00e3f7aad1b17ccb7898fdb"],
  ["NVDA", "US67066G1040", "0xd0601ce157db5bdc3162bbac2a2c8af5320d9eec", "0xd4eb21209c4d6093f80b5b84f5c45cc093ea14a3"],
  ["AMZN", "US0231351067", "0x12f190a9f9d7d37a250758b26824b97ce941bf54", "0x8ac92da74ab5f3b1d024dc1943ad7e15dc4179ef"],
  ["LLY", "US5324571083", "0x8005d266423c7ea827372c9c864491e5786600ea", "0xf212d02146a897f5f686e9d629f6a73da534324a"],
  ["COIN", "US19260Q1076", "0x6330d8c3178a418788df01a47479c0ce7ccf450b", "0x007a13fa152f6dc383cad20a8eaab4e1e2538b606936eae2a424f8aa47d6db31"],
  ["AMD", "US0079031078", "0x86923f96303d656e4aa86d9d42d1e57ad2023fdc", "0xde9f85fdd9e05a943a52f2c69ffafe3064a3287df03d02c9b431bc92d4781274"],
  ["PLTR", "US69608A1088", "0x894e1ec2d74ffe5aef8dc8a9e84686accb964f2a", "0xee430ee1003e1985e1828a01b9a20dad67ad4302994fe2abb4a173de4ac54623"],
  ["ORCL", "US68389X1054", "0xb0992820e760d836549ba69bc7598b4af75dee03", "0xc2ce4784e1e72cf0221b51b30c8aaadf6c0b12863cf03ab3687854fb7b1d850a"],
  ["ADBE", "US00724F1012", "0x232b8ed6377be97813853b0ac104c4cda8378d1b", "0x92a8b42d686c4be5444f3ea927ae29e04ada9205127f36c7e5a8a98d37c65a8b"],
  ["CRM", "US79466L3024", "0xd95b44124e475743a7589e68f3d74008a5536d44", "0x5d0434f1dcf45ff9450c1a63b5c9e088f90cfebf483458164e81be6fb96e2b2a"],
  ["INTC", "US4581401001", "0xc72b96e0e48ecd4dc75e1e45396e26300bc39681", "0xf2e329e631d0fb315a5c563ee3a9120f24822b5ef6c502a91cb56b174d5d8c22"],
  ["COST", "US22160K1051", "0x4ea005168d7f09a7a0ba9d1def21a479950e44c2", "0x0a2121a50a09ed0796ae81f9c53ff9398355a398"],
  ["TSM", "US8740391003", "0x58ffe4a942d3885baa22d7520691f611ef09e7aa", "0x0ba5d53d2f6255f334b7c8ead4f56b6aef5af3402c5e4d11180afd38c6b85fb1"],
  ["CSCO", "US17275R1023", "0xf543967eebb6f1917992ef0e68de63ab07a5a0da", "0x62b64b49f9505efa26ddde50e7203fed2a7b0543b9697695285b6fe580091e81"],
] as const;

export function reviewedRobinhoodMarketPool(stock: Stock): string | null {
  const deployment = stock.deployments[0];
  if (stock.provider !== 'robinhood' || stock.deployments.length !== 1 || deployment?.chainId !== 4663 ||
    deployment.network !== 'Robinhood Chain' || stock.underlying.symbol !== stock.symbol ||
    stock.underlying.listingCountry !== 'US' || stock.underlying.currency !== 'USD') return null;
  return ReviewedRobinhoodMarketPools.find(([symbol, isin, address]) => stock.symbol === symbol &&
    stock.underlying.isin === isin && deployment.address.toLowerCase() === address)?.[3] ?? null;
}
