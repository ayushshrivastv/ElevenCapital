import assert from 'node:assert/strict';
import test from 'node:test';
import { featuredCompanySummary } from '../src/featured-about.js';
import { ROBINHOOD_FEATURED_SYMBOLS } from '../src/robinhood.js';

test('every featured Backpack and xStocks company has a business summary', () => {
  const featured = [
    'MSFT', 'SPCX', 'TSLA', 'GOOGL', 'META', 'NFLX', 'AAPL', 'NVDA', 'AMZN', 'BRK.B',
    'JPM', 'V', 'MA', 'LLY', 'WMT', 'KO', 'DIS', 'COIN', 'AMD', 'NKE',
  ];
  for (const symbol of featured) {
    const summary = featuredCompanySummary(symbol);
    assert.ok(summary && summary.length > 45, `Missing company context for ${symbol}`);
    assert.equal(featuredCompanySummary(`${symbol}.US`), summary);
    assert.equal(featuredCompanySummary(`${symbol}x`), summary);
  }
  assert.equal(featuredCompanySummary('UNKNOWN'), null);
});
test('all20 official Robinhood featured companies have business descriptions, including the replacement listings', () => {
  for (const symbol of ROBINHOOD_FEATURED_SYMBOLS) assert.ok(featuredCompanySummary(symbol)!.length > 45, symbol);
  assert.match(featuredCompanySummary('AVGO')!, /semiconductor and infrastructure software/);
  assert.match(featuredCompanySummary('CRM')!, /customer relationships/);
  assert.match(featuredCompanySummary('TSM')!, /manufactures integrated circuits/);
});
