import assert from 'node:assert/strict';
import test from 'node:test';
import { reviewedRobinhoodCompanyLogos } from '../src/robinhood-company-logo-map.js';
import { reviewedRobinhoodCompanyLogo } from '../src/robinhood-company-logos.js';
import { loadRobinhood, type RobinhoodAsset } from '../src/robinhood.js';

const reviewed = Object.entries(reviewedRobinhoodCompanyLogos).find(([, row]) => row.symbol === 'MSFT')!;
const asset: RobinhoodAsset = {
  id: reviewed[0], tokenSymbol: reviewed[1].symbol, isin: reviewed[1].isin,
  tokenName: 'Microsoft • Robinhood Token', tokenDecimals: 18, currentMultiplier: '1',
  status: 'ASSET_STATUS_ACTIVE', logoUrl: reviewed[1].issuerLogoUrl,
  deployments: [{ chainId: 4663, contractAddress: reviewed[1].contract }],
};

test('catalog uses the company artwork when the exact issuer instrument supplies a reviewed generic feather', async () => {
  const catalog = await loadRobinhood(async (_provider, path) => path === '/rhj/assets' ? { assets: [asset] } : { quotes: [] });
  assert.equal(catalog.stocks.length, 1);
  assert.equal(catalog.stocks[0]!.logoUrl, reviewed[1].companyLogoUrl);
  assert.equal(reviewedRobinhoodCompanyLogo({ ...asset, logoUrl: undefined }), reviewed[1].companyLogoUrl);
});

test('a new issuer company logo remains authoritative', async () => {
  const issuerLogo = 'https://cdn.robinhood.com/ncw_assets/company/msft-new.png';
  const changed = { ...asset, logoUrl: issuerLogo };
  assert.equal(reviewedRobinhoodCompanyLogo(changed), null);
  const catalog = await loadRobinhood(async (_provider, path) => path === '/rhj/assets' ? { assets: [changed] } : { quotes: [] });
  assert.equal(catalog.stocks[0]!.logoUrl, issuerLogo);
});

test('company fallback cannot cross issuer ids, ISINs, tickers, chains or contracts', () => {
  for (const changed of [
    { ...asset, id: `0x${'f'.repeat(64)}` }, { ...asset, isin: 'US0378331005' },
    { ...asset, tokenSymbol: 'AAPL' },
    { ...asset, deployments: [{ chainId: 1, contractAddress: reviewed[1].contract }] },
    { ...asset, deployments: [{ chainId: 4663, contractAddress: '0x1111111111111111111111111111111111111111' }] },
    { ...asset, deployments: [...asset.deployments, ...asset.deployments] },
  ]) assert.equal(reviewedRobinhoodCompanyLogo(changed), null);
});
