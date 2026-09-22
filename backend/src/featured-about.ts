/** Brief descriptions of the businesses underlying the featured stock listings. */
const FeaturedCompanySummaries: Readonly<Record<string, string>> = {
  MSFT: 'Microsoft develops software, cloud services, devices and AI products.',
  SPCX: 'SpaceX designs reusable rockets and spacecraft and operates the Starlink satellite broadband network.',
  TSLA: 'Tesla makes electric vehicles, energy storage systems and solar products.',
  GOOGL: 'Alphabet is the parent of Google, whose businesses include Search, YouTube, Android and cloud computing.',
  META: 'Meta operates Facebook, Instagram and WhatsApp and develops virtual and augmented reality products.',
  NFLX: 'Netflix provides streaming films, series and other entertainment to subscribers.',
  AAPL: 'Apple designs iPhone, Mac, iPad and wearables alongside related software and services.',
  NVDA: 'NVIDIA designs graphics processors and accelerated computing systems used in AI, gaming and other markets.',
  AMZN: 'Amazon operates online retail businesses, AWS cloud computing and digital services.',
  'BRK.B': 'Berkshire Hathaway owns businesses in insurance, railroads, energy, manufacturing, retail and services.',
  JPM: 'JPMorgan Chase provides banking, payments, investment and wealth management services.',
  V: 'Visa operates a global digital payments network connecting financial institutions, merchants and consumers.',
  MA: 'Mastercard operates a global payments network and provides payment technology and services.',
  LLY: 'Eli Lilly develops and sells medicines for diabetes, obesity, cancer and other conditions.',
  WMT: 'Walmart operates retail stores, membership clubs and e-commerce services.',
  KO: 'The Coca-Cola Company markets nonalcoholic beverages through a global bottling system.',
  DIS: 'Disney operates entertainment studios, streaming and television services, and theme parks and resorts.',
  COIN: 'Coinbase provides crypto trading, custody and developer platform services.',
  AMD: 'AMD designs processors, graphics chips and adaptive computing products for data centers, PCs and embedded devices.',
  AVGO: 'Broadcom develops semiconductor and infrastructure software products.',
};

/** Accepts a bare ticker, a Backpack .US security, or a Backed xStock symbol. */
export function featuredCompanySummary(symbol: string): string | null {
  const ticker = symbol.trim().replace(/\.US$/i, '').replace(/x$/, '').toUpperCase();
  return FeaturedCompanySummaries[ticker] ?? null;
}
