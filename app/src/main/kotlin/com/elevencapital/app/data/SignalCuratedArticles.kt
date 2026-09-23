package com.elevencapital.app.data

import com.elevencapital.app.screens.SignalNewsArticle
import com.elevencapital.app.screens.SignalProfile
import com.elevencapital.app.screens.SignalTrade

/**
 * Editorially selected links to original reporting and company releases. These are dated articles,
 * not a live feed; the dates stay visible so an older story is never presented as breaking news.
 */
private fun article(
    id: String,
    title: String,
    publisher: String,
    date: String,
    url: String,
    summary: String,
    symbol: String? = null,
) = SignalNewsArticle(
    id = "curated-$id",
    title = title,
    publisher = publisher,
    publishedAt = date,
    url = url,
    relatedSymbol = symbol,
    summary = summary,
)

private val congressArticles = listOf(
    article(
        "house-vote-2026",
        "House votes to limit stock trading by members of Congress, elevating a campaign issue",
        "Associated Press", "22 Jul 2026",
        "https://apnews.com/article/3400cf5d498cc9934c0f16be1326bfe9",
        "The House advanced a bill restricting purchases of individual stocks by lawmakers. The measure still faced an uncertain path in the Senate.",
    ),
    article(
        "senate-ban-2026",
        "Senators launch a cross-party effort to end stock trading by lawmakers",
        "Associated Press", "15 Jan 2026",
        "https://apnews.com/article/b25f05f409738ced1269f1c171420b76",
        "Senators from both parties proposed limits on lawmakers and immediate family members owning and trading individual stocks.",
    ),
    article(
        "stock-debate-2026",
        "Democrats feud over stock trading as they sharpen anti-corruption case against Trump",
        "Associated Press", "25 May 2026",
        "https://apnews.com/article/97c1dc2f144cb3b7457ca062741a6e78",
        "Candidates and lawmakers debated congressional stock trading and competing proposals to restrict it.",
    ),
    article(
        "bipartisan-ban-2025",
        "Left and right are joining forces to ban lawmakers from trading stock",
        "Associated Press", "3 Sep 2025",
        "https://apnews.com/article/7467604e26652de32687442bcb277774",
        "A bipartisan House coalition introduced a proposal to bar members and their families from owning or trading individual stocks.",
    ),
)

private val profileArticles = mapOf(
    "nancy-pelosi" to listOf(
        article(
            "pelosi-trading-ban",
            "Trump calls GOP's Hawley 'second tier' senator after bill to ban stock trades in government advances",
            "Associated Press", "30 Jul 2025",
            "https://apnews.com/article/cf6200d0ec71fe012bb3b9a916696b37",
            "The report examines the proposed trading ban, scrutiny of the Pelosi family's disclosures, and Pelosi's response.",
        ),
    ),
    "gilbert-cisneros" to listOf(
        article(
            "cisneros-defense-trades",
            "A Seat on Armed Services, and 41 Defense Trades",
            "Capitol Markets", "7 Sep 2026",
            "https://capitolmarkets.org/news/gilbert-ray-cisneros-avav-defense-2026-09-07",
            "An analysis of Cisneros's disclosed defense-sector transactions and their filing dates.",
        ),
    ),
    "dan-newhouse" to listOf(
        article(
            "newhouse-july-trades",
            "Dan Newhouse of Washington’s 4th District Makes Multiple Stock Trades",
            "Investing.com", "20 Jul 2026",
            "https://www.investing.com/news/company-news/dan-newhouse-of-washingtons-4th-district-makes-multiple-stock-trades-93CH-4801082",
            "The article reviews a group of stock transactions Newhouse reported to the House clerk.",
        ),
    ),
    "david-j-taylor" to listOf(
        article(
            "taylor-stock-activity",
            "Freshman Ohio Republican emerges as one of the biggest stock traders in Congress",
            "Signal Ohio", "19 Mar 2026",
            "https://signalohio.org/freshman-ohio-republican-david-taylor-emerges-as-one-of-the-biggest-stock-traders-in-congress/",
            "A reported look at Rep. David Taylor's disclosures during his first 14 months in Congress.",
        ),
    ),
    "josh-gottheimer" to listOf(
        article(
            "gottheimer-microsoft",
            "Josh Gottheimer’s recent trading activity in Microsoft, Air Products, and other companies",
            "Investing.com", "9 Apr 2026",
            "https://www.investing.com/news/company-news/josh-gottheimers-recent-trading-activity-in-microsoft-air-products-and-other-companies-93CH-4606980",
            "A report on Gottheimer's disclosed stock and options transactions, including Microsoft and several other companies.",
        ),
    ),
    "thomas-h-kean-jr" to listOf(
        article(
            "kean-stock-ban",
            "Kean Votes to Ban Members of Congress from Trading Stocks",
            "Rep. Thomas Kean Jr.", "22 Jul 2026",
            "https://kean.house.gov/media/press-releases/kean-votes-ban-members-congress-trading-stocks",
            "Kean's official statement on the House vote to restrict individual stock purchases by members and their families.",
        ),
    ),
    "maria-elvira-salazar" to listOf(
        article(
            "salazar-trading-record",
            "The Reporter Who Beat the Market",
            "Capitol Markets", "9 Jul 2026",
            "https://capitolmarkets.org/news/maria-salazar-top-trader-2026-07-09",
            "An analysis of Salazar's disclosed trades and the gap between trade dates and public filing dates.",
        ),
    ),
    "byron-donalds" to listOf(
        article(
            "donalds-stock-ban",
            "Donalds Votes to Ban Congressional Insider Trading and Secure the Ballot Box",
            "Rep. Byron Donalds", "23 Jul 2026",
            "https://donalds.house.gov/news/documentsingle.aspx?DocumentID=2636",
            "Donalds's official statement on legislation restricting new individual stock purchases by lawmakers.",
        ),
    ),
)

private val stockArticles = mapOf(
    "MSFT" to article(
        "msft-fy26-q4", "Earnings Release FY26 Q4", "Microsoft", "29 Jul 2026",
        "https://www.microsoft.com/en-us/investor/earnings/fy-2026-q4/press-release-webcast",
        "Microsoft's official quarterly results and business update.", "MSFT",
    ),
    "AAPL" to article(
        "aapl-fy26-q3", "Apple reports third quarter results", "Apple", "30 Jul 2026",
        "https://www.apple.com/newsroom/2026/07/apple-reports-third-quarter-results/",
        "Apple's official report on its fiscal third quarter and product revenue.", "AAPL",
    ),
    "AMZN" to article(
        "amzn-2026-q2", "Amazon.com announces second quarter results", "Amazon", "30 Jul 2026",
        "https://www.aboutamazon.com/news/company-news/amazon-earnings-q2-2026-report",
        "Amazon's official second-quarter results, including its retail and AWS businesses.", "AMZN",
    ),
    "NVDA" to article(
        "nvda-fy27-q2", "NVIDIA Announces Financial Results for Second Quarter Fiscal 2027",
        "NVIDIA", "26 Aug 2026",
        "https://nvidianews.nvidia.com/news/nvidia-announces-financial-results-for-second-quarter-fiscal-2027",
        "NVIDIA's official earnings release covering its data-center and other businesses.", "NVDA",
    ),
    "META" to article(
        "meta-2026-q2", "Meta Reports Second Quarter 2026 Results", "Meta", "29 Jul 2026",
        "https://investor.atmeta.com/investor-news/press-release-details/2026/Meta-Reports-Second-Quarter-2026-Results/",
        "Meta's official second-quarter financial results and outlook.", "META",
    ),
    "GOOGL" to article(
        "googl-2026-q2", "Alphabet Announces Second Quarter 2026 Results", "Alphabet", "22 Jul 2026",
        "https://www.sec.gov/Archives/edgar/data/1652044/000165204426000066/googexhibit991q22026.htm",
        "Alphabet's earnings release filed with the U.S. Securities and Exchange Commission.", "GOOGL",
    ),
    "AMD" to article(
        "amd-2026-q2", "AMD Reports Second Quarter 2026 Financial Results", "AMD", "4 Aug 2026",
        "https://ir.amd.com/news-events/press-releases/detail/1295/amd-reports-second-quarter-2026-financial-results",
        "AMD's official second-quarter earnings release and outlook.", "AMD",
    ),
    "TSLA" to article(
        "tsla-2026-q2", "Tesla Second Quarter 2026 Production, Deliveries & Deployments",
        "Tesla", "2 Jul 2026",
        "https://ir.tesla.com/press-release/tesla-second-quarter-2026-production-deliveries-and-deployments",
        "Tesla's official update on vehicle production, deliveries and energy storage deployments.", "TSLA",
    ),
    "PLTR" to article(
        "pltr-2026-q2", "Palantir Reports Q2 2026 U.S. Comm Revenue Growth of 149% Y/Y and Revenue Growth of 93% Y/Y; Raises FY 2026 Revenue Guidance to 82% Y/Y Growth and U.S. Comm Revenue Guidance to 134% Y/Y, Crushing Consensus Expectations",
        "Palantir / SEC", "3 Aug 2026",
        "https://www.sec.gov/Archives/edgar/data/1321655/000132165526000039/a2026q2ex991pressrelease.htm",
        "Palantir's second-quarter earnings release as filed with the U.S. Securities and Exchange Commission.", "PLTR",
    ),
    "MU" to article(
        "mu-fy26-q3", "Micron Technology Reports Record Results for the Third Quarter of Fiscal 2026",
        "Micron", "24 Jun 2026",
        "https://investors.micron.com/news/press-release/2026/Micron-Technology-Inc--Reports-Record-Results-for-the-Third-Quarter-of-Fiscal-2026/default.aspx",
        "Micron's official quarterly results and memory-market outlook.", "MU",
    ),
    "AMAT" to article(
        "amat-fy26-q3", "Applied Materials Announces Third Quarter 2026 Results",
        "Applied Materials", "13 Aug 2026",
        "https://ir.appliedmaterials.com/news-releases/news-release-details/applied-materials-announces-third-quarter-2026-results",
        "Applied Materials' official results and update on its semiconductor equipment business.", "AMAT",
    ),
    "NOW" to article(
        "now-2026-q2", "ServiceNow Reports Second Quarter 2026 Financial Results",
        "ServiceNow", "22 Jul 2026",
        "https://newsroom.servicenow.com/press-releases/details/2026/ServiceNow-Reports-Second-Quarter-2026-Financial-Results/default.aspx",
        "ServiceNow's official second-quarter results and subscription-revenue update.", "NOW",
    ),
)

/** Four distinct, verified links: person coverage first, then articles about recently disclosed stocks. */
fun curatedArticlesForProfile(profileId: String, recentTradeTickers: List<String>): List<SignalNewsArticle> {
    val member = profileArticles[profileId].orEmpty()
    val stocks = recentTradeTickers.asSequence()
        .map { it.trim().uppercase() }.distinct()
        .mapNotNull { stockArticles[it] }.take(3).toList()
    val start = (profileId.hashCode() and Int.MAX_VALUE) % congressArticles.size
    val coverage = congressArticles.indices.map { congressArticles[(start + it) % congressArticles.size] }
    return (member + stocks + coverage).distinctBy { it.url }.take(4)
}

object SignalCuratedArticles {
    fun forProfile(profile: SignalProfile, trades: List<SignalTrade>): List<SignalNewsArticle> {
        val personKey = profile.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return curatedArticlesForProfile(personKey, trades.map(SignalTrade::symbol))
    }
}
