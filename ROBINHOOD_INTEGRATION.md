# Robinhood Stock Tokens integration

Eleven lists Robinhood Stock Tokens as a separate provider, `robinhood`. Robinhood's official public catalog establishes token identity and deployments; verified same-token markets supply price changes and trading activity when available. It does not connect a Robinhood brokerage account or authorize trades.

## Network and listing identity

The issuer's live catalog was checked on September 29, 2026: all 195 assets had deployments on Robinhood Chain, chain ID **4663**. Robinhood Chain is a separate, non-Solana network. Its entries remain reference-only in Eleven: they do not provision another wallet or participate in wallet balances, sends, receives, or purchases.

The live loader included 188 stocks and equity ETFs, all with valid indicative prices. Seven assets were excluded by Eleven's existing stock/equity-ETF scope; none had unresolved identities.

Only issuer-reported deployment addresses are used. The app links a Robinhood Chain address to `https://robin.etherscan.io/address/{address}` on RobinScan, Etherscan's Robinhood Chain explorer. A hexadecimal address is retained as Robinhood Chain reference data and is never relabeled as a Solana address. Being listed by the issuer and having verified source code on an explorer are separate facts; the UI's explorer action makes no claim about source verification.

For example, the live issuer catalog reports AAPL at [`0xaF3D76f1834A1d425780943C99Ea8A608f8a93f9`](https://robin.etherscan.io/address/0xaF3D76f1834A1d425780943C99Ea8A608f8a93f9). A read-only check against the official mainnet RPC returned chain ID 4663 and nonempty bytecode for that address. Explorer source-code verification could not be confirmed because its API returned a browser challenge.

Network configuration: <https://docs.robinhood.com/chain/connecting/>

Canonical contract discovery: <https://docs.robinhood.com/chain/contracts/>

## Catalog and prices

- `GET https://api.robinhood.com/rhj/assets` supplies asset identities, symbols, canonical deployments, active status and corporate-action multipliers.
- `GET https://api.robinhood.com/rhj/prices` supplies the bounded price snapshot for the catalog.
- The underlying bid/ask midpoint is multiplied by the asset's `currentMultiplier` to obtain a token-equivalent indicative fallback price. Decimal arithmetic preserves precision. That reference is not an executable swap quote. Verified onchain USD market prices are a different basis and must retain their own change and activity observations.
- Dividends and splits can change the multiplier without changing raw token balances. Do not treat raw underlying prices as token prices or apply the multiplier twice.
- Underlying daily trading volume is retained in shares internally, separately from token statistics. Zero or absent volume is unavailable, not invented token turnover. The current API's daily high/low fields do not have documented units/session semantics, so those values are withheld.
- The public model retains the original-share bid/ask and shares-per-token multiplier for an indicative fallback. Source timestamps are retained. Robinhood says its Stock Tokens are backed 1:1 by custodied underlying equities and provide economic exposure without legal or beneficial ownership of those equities. The current multiplier determines the share-equivalent amount per token; one raw token need not remain equal to one raw share. [Official backing and ownership FAQ](https://docs.robinhood.com/rhj/faq/).

## Verified trading activity

GeckoTerminal supports Robinhood Chain as network `robinhood`. Price and 24-hour percentage change come together from its exact-contract USD token-price endpoint (`include_24hr_price_change=true`). Pool percentage fields are not used: their documentation does not explicitly establish a USD return for stock/stock pairs. Trading activity is obtained independently from a verified pool for the same issuer-listed contract. The pool can have a 20-byte contract address or a 32-byte Uniswap v4 pool ID; neither is substituted for the token's canonical deployment.

With `include_volume_breakdown=true`, a pool reports `volume_usd`, `buy_volume_usd`, `sell_volume_usd` and `net_buy_volume_usd`. Net is calculated with decimal arithmetic as buy USD minus sell USD only when the complete side aggregates reconcile to the reported total and reported net. Transaction counts, underlying-share volume, transfers and mint/burn amounts never substitute for traded USD volume.

Default pool trade direction is relative to the base token. If the stock is the quote token in an exact two-token Uniswap v3/v4 pair, Eleven validates the base aggregates first and reverses their Net to describe buying versus selling the stock. Pool USD turnover is unchanged. Unknown or multi-token quote-side pools are rejected. [Official trade direction and inversion](https://docs.coingecko.com/reference/pool-trades-contract-address).

Vol and Net describe the selected verified pool, not all Robinhood trading venues or original-stock trading. The USD token-price feed may select a different pricing pool; the activity pool is not claimed to be the quote's pricing pool. An available token-market quote uses `onchain_token_market`; verified pool activity uses source `GeckoTerminal` with its own receipt clock. An unavailable or expired market quote falls back to the official issuer price with unavailable market return fields while independently fresh, identity-matched pool activity remains usable. Missing activity remains unavailable. Indicative NAV fields are not blended into a market quote.

Public market responses have no source-observation clock. Price and activity retain their respective actual HTTP receipt times, and cached observations expire after five minutes; `asOf` remains null rather than claiming the fetch time was the last trade time. Pool identity and the selected activity scope are retained in backend diagnostics. Startup and background refresh are independent of the viewport and use the shared, paced GeckoTerminal request budget.

The September 29 compiled HTTP check made all four featured metrics available for all 20 in 4.327 seconds. The full 188-row catalog had 188 prices (77 token-market prices and 111 issuer fallbacks), 77 daily changes, 20 volumes and 20 signed net values during that bounded cold check. Fifteen later requests returned HTTP 429. Those missing fields remain unavailable, and are never presented as zero or claimed to be complete. The refresh path now gives the featured 20 quote and activity data first, then processes two rotating non-featured batches per pass. Background cursors advance before requests; bounded discovery fallbacks and a negative-result cache prevent unknown pools from repeatedly blocking the remaining catalog. Each pass has a 90-second budget, canceled queued work releases its slot, and cooldown honors Retry-After. Pool turnover is not discarded merely because current liquidity is below $1,000; exact deployment and pool relationships, nonnegative liquidity, creation time, and matching USD side aggregates remain required.

[Official pool metrics and volume breakdown](https://docs.coingecko.com/reference/pools-addresses), [token-oriented USD prices and 24-hour change](https://docs.coingecko.com/reference/onchain-simple-price).

The current activity feed is GeckoTerminal, not an explorer-derived trade index. RobinScan and Blockscout do not provide a ready-made complete per-stock 24-hour USD Vol/Net feed. A direct explorer implementation needs an authenticated logs API, incremental swap indexing, exact pool identity, complete time-window pagination and historical USD valuation. Token transfers alone cannot establish purchases or total traded volume. Until that index exists, unavailable activity is not presented as zero or labeled as explorer data.

## Featured stocks and startup loading

The featured list is a stable, curated set of 20 issuer-listed tokens, displayed first when Robinhood is selected. It is not a live market-cap ranking. The remaining supported Robinhood catalog remains searchable.

`GET /v1/stocks/featured?provider=robinhood` returns these 20 with the same identities, original quote timestamps, About descriptions and deployments as the full catalog. Backend readiness waits up to 25 seconds for Robinhood catalog and the first featured-market batch. The service also warms the featured one-day histories, with three concurrent requests, independently of a user's screen.

That bounded wait applies to cold startup. After the first featured market attempt completes, an existing featured snapshot returns immediately while later market and metadata refreshes run independently. Cached observations keep their original timestamps and expiry; a provider cooldown cannot make a usable cached response wait for the current refresh.

Android starts a public-market warmup in `Application.onCreate`, before account restoration and before the Stocks screen opens. A dated public catalog and market snapshot is bundled for the first install. Later launches restore an atomic, endpoint-specific public cache, then refresh from the backend. Quotes retained for up to 24 hours have a saved-price indicator; older quotes and their bid/ask reference are cleared. Market activity has its separate five-minute bound. Metadata, descriptions and explorer links can remain cached for seven days. No account, balance, wallet or order data enters this cache.

Fresh featured quote and activity observations merge independently into a matching full-catalog identity. The composite catalog uses the latest contributing source snapshot and its matching monotonic arrival anchor; each quote and activity keeps its original upstream receipt. This prevents a newly received volume observation from being rejected as future data against an older full snapshot.

The app warms all 20 one-day histories with two concurrent requests and shares them with the ViewModel. Original upstream chart receipt times survive serialization; responses older than five minutes or from the future are rejected rather than retimed. This avoids duplicate requests and preserves freshness bounds. Android can start this work once its process runs; it cannot run before the OS launches the app or guarantee a live response while offline.

## Original-stock history

All 20 featured tokens resolve to actual USD original-share histories. Historical points remain original-share closes, not historical token prices. Their basis and source are retained internally; the public interface omits the feed, currency and history-reference captions at the user's request. A token quote is never appended to that series, and today's dividend/split multiplier is never applied to an entire historical series.

ISIN lookup establishes 18 mappings. Two explicit fallbacks require exact Robinhood provider, symbol, ISIN and chain 4663, followed by a USD/EQUITY/exchange check:

- GOOGL / US02079K3059 is Alphabet **Class A** on Nasdaq. It is not GOOG Class C. [Alphabet share-class filing](https://www.sec.gov/Archives/edgar/data/1288776/000119312515336550/d56649d8k.htm).
- TSM / US8740391003 is the NYSE American depositary share, representing five Taiwan ordinary shares. It is not a Taiwan-dollar ordinary-share series. [TSMC depositary-share announcement](https://investor.tsmc.com/system/files/2026-07/Final%20Dividend%20Announcement_4Q25.pdf).

Original-share history uses Yahoo's public finance endpoints. Robinhood's official APIs remain the source of current token catalog, deployments, multipliers and token-equivalent references.

## Featured deployment links

Each About page uses its own issuer-listed contract on Robinhood Chain (4663). Checked against the live issuer catalog on September 29, 2026.

| Order | Symbol | Underlying ISIN | Listing contract |
| --- | --- | --- | --- |
| 1 | MSFT | US5949181045 | [0xe93237C50D904957Cf27E7B1133b510C669c2e74](https://robin.etherscan.io/address/0xe93237C50D904957Cf27E7B1133b510C669c2e74) |
| 2 | AVGO | US11135F1012 | [0x156E175DD063a8cE274C50654eF40e0032b3fbcF](https://robin.etherscan.io/address/0x156E175DD063a8cE274C50654eF40e0032b3fbcF) |
| 3 | TSLA | US88160R1014 | [0x322F0929c4625eD5bAd873c95208D54E1c003b2d](https://robin.etherscan.io/address/0x322F0929c4625eD5bAd873c95208D54E1c003b2d) |
| 4 | GOOGL | US02079K3059 | [0x2e0847E8910a9732eB3fb1bb4b70a580ADAD4FE3](https://robin.etherscan.io/address/0x2e0847E8910a9732eB3fb1bb4b70a580ADAD4FE3) |
| 5 | META | US30303M1027 | [0xc0D6457C16Cc70d6790Dd43521C899C87ce02f35](https://robin.etherscan.io/address/0xc0D6457C16Cc70d6790Dd43521C899C87ce02f35) |
| 6 | NFLX | US64110L1061 | [0xE0444EF8BF4eD74f74FD73686e2ddF4C1c5591E8](https://robin.etherscan.io/address/0xE0444EF8BF4eD74f74FD73686e2ddF4C1c5591E8) |
| 7 | AAPL | US0378331005 | [0xaF3D76f1834A1d425780943C99Ea8A608f8a93f9](https://robin.etherscan.io/address/0xaF3D76f1834A1d425780943C99Ea8A608f8a93f9) |
| 8 | NVDA | US67066G1040 | [0xd0601CE157Db5bdC3162BbaC2a2C8aF5320D9EEC](https://robin.etherscan.io/address/0xd0601CE157Db5bdC3162BbaC2a2C8aF5320D9EEC) |
| 9 | AMZN | US0231351067 | [0x12f190a9F9d7D37a250758b26824B97CE941bF54](https://robin.etherscan.io/address/0x12f190a9F9d7D37a250758b26824B97CE941bF54) |
| 10 | LLY | US5324571083 | [0x8005d266423c7ea827372c9c864491e5786600ea](https://robin.etherscan.io/address/0x8005d266423c7ea827372c9c864491e5786600ea) |
| 11 | COIN | US19260Q1076 | [0x6330D8C3178a418788dF01a47479c0ce7CCF450b](https://robin.etherscan.io/address/0x6330D8C3178a418788dF01a47479c0ce7CCF450b) |
| 12 | AMD | US0079031078 | [0x86923f96303D656E4aa86D9d42D1e57ad2023fdC](https://robin.etherscan.io/address/0x86923f96303D656E4aa86D9d42D1e57ad2023fdC) |
| 13 | PLTR | US69608A1088 | [0x894E1EC2D74FFE5AEF8Dc8A9e84686acCB964F2A](https://robin.etherscan.io/address/0x894E1EC2D74FFE5AEF8Dc8A9e84686acCB964F2A) |
| 14 | ORCL | US68389X1054 | [0xb0992820E760d836549ba69BC7598b4af75dEE03](https://robin.etherscan.io/address/0xb0992820E760d836549ba69BC7598b4af75dEE03) |
| 15 | ADBE | US00724F1012 | [0x232B8ed6377BE97813853B0Ac104c4Cda8378d1B](https://robin.etherscan.io/address/0x232B8ed6377BE97813853B0Ac104c4Cda8378d1B) |
| 16 | CRM | US79466L3024 | [0xd95B44124e475743a7589e68F3D74008A5536D44](https://robin.etherscan.io/address/0xd95B44124e475743a7589e68F3D74008A5536D44) |
| 17 | INTC | US4581401001 | [0xc72b96e0E48ecd4DC75E1e45396e26300BC39681](https://robin.etherscan.io/address/0xc72b96e0E48ecd4DC75E1e45396e26300BC39681) |
| 18 | COST | US22160K1051 | [0x4EA005168D7F09a7A0Ba9D1DEf21a479950E44C2](https://robin.etherscan.io/address/0x4EA005168D7F09a7A0Ba9D1DEf21a479950E44C2) |
| 19 | TSM | US8740391003 | [0x58FfE4a942d3885bAa22D7520691F611EF09e7AA](https://robin.etherscan.io/address/0x58FfE4a942d3885bAa22D7520691F611EF09e7AA) |
| 20 | CSCO | US17275R1023 | [0xF543967EEBB6f1917992eF0E68De63ab07a5a0dA](https://robin.etherscan.io/address/0xF543967EEBB6f1917992eF0E68De63ab07a5a0dA) |

## Artwork provenance

The provider avatar is copied unmodified from the [official Robinhood Chain brand archive](https://cdn.robinhood.com/robinhood_chain/brand_assets/robinhood-chain-brand-assets-v1.zip). All 20 asset-logo URLs in the issuer catalog currently return the same Robinhood feather, so they are not presented as company logos.

The reviewed featured issuer-image URLs currently return the same generic feather. At the user's request, those 20 rows and detail pages display the underlying company artwork instead, bundled for immediate loading. Images use the existing project publisher marks or original company-hosted artwork; TSMC uses the same company-image CDN pattern as Backpack because company-hosted image requests were blocked. [Artwork provenance and hashes](ROBINHOOD_COMPANY_LOGOS.json) records the source boundary without implying that every image was retrieved from a company host. The Robinhood provider/avatar remains on Listing source. A provider image URL outside the reviewed generic set can still supply its own artwork, with the company image as its loading/offline fallback.

All 188 supported listings now have reviewed company-image fallbacks tied to the exact issuer asset ID, ticker, ISIN, chain and contract. Their original issuer images were independently checked against the generic feather hash. The API replaces those reviewed generic URLs with usable company PNG URLs, preserving a new issuer image URL. The app uses the same mapping for rendering and image prefetch, with appropriate light or dark avatar backgrounds. The 20 featured marks remain bundled for immediate display. Other marks use the same FinancialModelingPrep image endpoint as Backpack; TSEM uses a company-hosted PNG favicon because the image endpoint returned a building photograph. Provenance distinguishes company-hosted artwork from third-party copies of company and fund marks.

Robinhood Overview shows only About and the exact Listing source link. Tapping Listing source opens `https://robin.etherscan.io/address/{contract}` on RobinScan, Etherscan's Robinhood Chain explorer, for that token's validated deployment. About includes the company description and a company-specific explanation of 1:1 underlying backing, US custody, economic exposure without direct share ownership, and changing share entitlement after dividends or splits. The section uses its natural content height so the Listing source card ends above the fixed Buy/Sell area without a blank scroll tail. Provider suffixes and feed/reference/currency captions are hidden from the stock row and detail toolbar. Data-source identity and units remain in the public wire model, cache and integration documentation.

API documentation: <https://docs.robinhood.com/chain/stock-token-apis/>

Product and ownership semantics: <https://docs.robinhood.com/chain/stock-tokens/>

## Solana wallet boundary

The integration adds catalog browsing, provider filtering, indicative prices and issuer explorer links. Eleven's wallet, portfolio, transfer, and purchase services support Solana only. Robinhood Stock Token buying and selling therefore stay disabled; a Robinhood Chain listing never enables a wallet action.

Trading integration patterns: <https://docs.robinhood.com/chain/building-with-stock-tokens/>
