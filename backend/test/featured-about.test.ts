import assert from 'node:assert/strict';
import test from 'node:test';
import { featuredCompanySummary } from '../src/featured-about.js';

test('every featured Backpack and xStocks company has a business summary', () => {
  const featured = [
    'MSFT', 'SPCX', 'TSLA', 'GOOGL', 'META', 'NFLX', 'AAPL', 'NVDA', 'AMZN', 'BRK.B',
    'JPM', 'V', 'MA', 'LLY', 'WMT', 'KO', 'DIS', 'COIN', 'AMD', 'AVGO',
  ];
  for (const symbol of featured) {
    const summary = featuredCompanySummary(symbol);
    assert.ok(summary && summary.length > 45, `Missing company context for ${symbol}`);
    assert.equal(featuredCompanySummary(`${symbol}.US`), summary);
    assert.equal(featuredCompanySummary(`${symbol}x`), summary);
  }
  assert.equal(featuredCompanySummary('UNKNOWN'), null);
});
