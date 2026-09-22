/** Reviewed issuer/filing evidence for descriptions that the exchange directory alone cannot resolve. */
export const ReviewedEquityFunds: Record<string, { name: string; reason: string; source: string }> = {
  YLDE: { name: 'Franklin ClearBridge Enhanced Income ETF', reason: 'Issuer classifies the fund as Equity; dividend-paying companies with an equity-index options overlay.', source: 'https://www.franklintempleton.com/clearbridge/etfs/91629/SINGLCLASS/franklin-clearbridge-enhanced-income-etf/YLDE' },
  URA: { name: 'Global X Uranium ETF', reason: 'Issuer describes an equity-industry basket of uranium-mining and nuclear-component companies.', source: 'https://www.globalxetfs.com/funds/ura' },
  PGJ: { name: 'Invesco Golden Dragon China ETF', reason: 'Issuer prospectus tracks the Nasdaq Golden Dragon China Index of listed company equities.', source: 'https://www.sec.gov/Archives/edgar/data/1209466/000119312526372141/d177078d497k.htm' },
};
/** Applied only when the symbol is absent from the current directory; never substitute successor-company quotes. */
export const InactiveListings: Record<string, { reason: string; source: string }> = {
  AVB: { reason: 'Inactive underlying listing: merger completed 2026-08-17; legacy AVB shares ceased trading. Provider identity needs updating.', source: 'https://investors.avalonbay.com/sec-filings/all-sec-filings/content/0001104659-26-097833/tm2623381d1_8k.htm' },
  EQR: { reason: 'Inactive underlying listing: merger completed 2026-08-17; combined company trades as VMRK. Provider identity needs updating.', source: 'https://investors.avalonbay.com/sec-filings/all-sec-filings/content/0001104659-26-097833/tm2623381d1_ex99-1.htm' },
  EA: { reason: 'Inactive underlying listing: acquisition completed 2026-08-04 and shares delisted.', source: 'https://www.ea.com/amp/news/ea-announces-completion-of-acquisition' },
  SATS: { reason: 'Inactive underlying ticker: EchoStar changed SATS to ECHO on 2026-06-24. Provider identity needs updating.', source: 'https://ir.echostar.com/news-releases/news-release-details/echostar-changing-stocker-ticker-sats-echo-marking-companys-next' },
  GTLS: { reason: 'Inactive underlying listing: Baker Hughes completed the Chart Industries acquisition on 2026-07-16.', source: 'https://investors.bakerhughes.com/news/press-releases/news-details/2026/Baker-Hughes-Completes-Acquisition-of-Chart-Industries/default.aspx' },
  BLD: { reason: 'Inactive underlying listing: QXO completed the TopBuild acquisition on 2026-07-01; shares converted to merger consideration.', source: 'https://www.sec.gov/Archives/edgar/data/1633931/000110465926079876/tm2618991d10_8k.htm' },
  WBS: { reason: 'Inactive underlying listing: Santander completed the Webster acquisition on 2026-08-20.', source: 'https://www.websterbank.com/about/newsroom/santander-expands-u-s-presence-with-completion-of-webster-acquisition/' },
  MASI: { reason: 'Inactive underlying listing: Danaher completed the Masimo acquisition on 2026-06-10; common shares acquired for cash.', source: 'https://www.sec.gov/Archives/edgar/data/937556/000110465926072151/tm2617395d1_8k.htm' },
  APGE: { reason: 'Inactive underlying listing: AbbVie completed the Apogee acquisition on 2026-09-03.', source: 'https://www.sec.gov/Archives/edgar/data/1551152/000110465926104940/tm2624674d1_ex99-1.htm' },
};
