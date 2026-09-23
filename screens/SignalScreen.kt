package com.elevencapital.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import java.net.URI
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.elevencapital.app.R
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd

/** Display models. The provider keeps source and disclosure dates attached to every trade. */
data class SignalProfile(
    val id: String,
    val name: String,
    val role: String,
    val state: String,
    val portraitUrl: String? = null,
    val sourceUrl: String? = null,
)

data class SignalTrade(
    val id: String,
    val symbol: String,
    val companyName: String?,
    val action: String,
    val transactionDate: String,
    val disclosureDate: String?,
    val amountRange: String?,
    val sourceUrl: String?,
)

data class SignalNewsArticle(
    val id: String,
    val title: String,
    val publisher: String,
    val publishedAt: String,
    val url: String,
    val relatedSymbol: String? = null,
    val imageUrl: String? = null,
    val summary: String? = null,
)

/** The shared top panel and dock are supplied by ElevenApp, as on Wallet and Stocks. */
@Composable
fun SignalScreen(
    profiles: List<SignalProfile>,
    selectedProfileId: String?,
    trades: List<SignalTrade>,
    news: List<SignalNewsArticle>,
    onProfile: (String) -> Unit,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    bargoTrades: Boolean = false,
    notice: String? = null,
    onRefresh: () -> Unit = {},
    canExploreStock: (String) -> Boolean = { false },
    onExploreStock: (String) -> Unit = {},
) {
    val selected = profiles.firstOrNull { it.id == selectedProfileId }
    Box(Modifier.fillMaxSize().background(W.Background)) {
        if (selected == null) {
            SignalDirectory(profiles, notice, onProfile, onRefresh)
        } else {
            SignalDetail(selected, trades, news, bargoTrades, notice, onBack, onOpenLink,
                canExploreStock, onExploreStock)
        }
    }
}

@Composable
private fun SignalDirectory(
    profiles: List<SignalProfile>,
    notice: String?,
    onProfile: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(W.Background),
        contentPadding = PaddingValues(start = rd(22f), end = rd(22f), bottom = rd(114f)),
    ) {
        item(key = "intro") {
            Spacer(Modifier.height(rd(4f)))
            RefText("Congress trading", 23f, W.White, FontWeight.SemiBold)
            Spacer(Modifier.height(rd(5f)))
            RefText(
                "Follow stock trades disclosed by members of the US Congress.",
                14f, W.Muted, maxLines = 2,
            )
            Spacer(Modifier.height(rd(24f)))
            RefText("Profiles", 16f, W.White, FontWeight.SemiBold)
            Spacer(Modifier.height(rd(9f)))
        }
        items(profiles, key = { it.id }) { profile ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = rd(76f))
                    .clickable(role = Role.Button) { onProfile(profile.id) }
                    .semantics { contentDescription = "View ${profile.name} disclosed trades" }
                    .padding(vertical = rd(8f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SignalPortrait(profile, rd(44f))
                Spacer(Modifier.width(rd(12f)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(rd(1f))) {
                    RefText(profile.name, 18f, W.White, FontWeight.Medium)
                    RefText(profile.chamberName(), 14f, W.Muted)
                    RefText(profile.fullStateName(), 13f, W.Muted)
                }
            }
        }
        if (profiles.isEmpty()) {
            item(key = "empty") {
                Spacer(Modifier.height(rd(26f)))
                RefText("Profiles are unavailable right now.", 15f, W.Muted, maxLines = 2)
                SignalRetry(onRefresh)
            }
        }
    }
}

@Composable
private fun SignalDetail(
    profile: SignalProfile,
    trades: List<SignalTrade>,
    news: List<SignalNewsArticle>,
    bargoTrades: Boolean,
    notice: String?,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    canExploreStock: (String) -> Boolean,
    onExploreStock: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(W.Background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = rd(24f), end = rd(24f), bottom = rd(16f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(rd(42f)).clip(CircleShape).background(W.Circle)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = "Back to Signal profiles" },
                contentAlignment = Alignment.Center,
            ) { RefIcon("back", 19f, W.Cream) }
            Spacer(Modifier.width(rd(13f)))
            SignalPortrait(profile, rd(45f))
            Spacer(Modifier.width(rd(12f)))
            Column(Modifier.weight(1f)) {
                RefText(profile.name, 17f, W.White, FontWeight.SemiBold)
                RefText(profile.chamberName(), 12f, W.Muted)
                RefText(profile.fullStateName(), 12f, W.Muted)
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = rd(24f))) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RefText("Trades", 18f, W.White, FontWeight.SemiBold, Modifier.weight(1f))
                Row(
                    Modifier.clip(CircleShape).background(W.Cream)
                        .clickable(role = Role.Button) { onOpenLink("https://www.bargo.ai") }
                        .semantics { contentDescription = "${if (bargoTrades) "Data via" else "View"} Bargo, open provider website" }
                        .padding(horizontal = rd(13f), vertical = rd(9f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(rd(5f)),
                ) {
                    RefText(if (bargoTrades) "Data via Bargo" else "View Bargo",
                        12f, W.Ink, FontWeight.Medium)
                    RefIcon("arrowUpRight", 11f, W.Ink)
                }
            }
            Spacer(Modifier.height(rd(3f)))
            RefText("Reported after the transaction date", 12f, W.Muted)
            Spacer(Modifier.height(rd(10f)))
            SignalLine()
            LazyColumn(Modifier.fillMaxSize()) {
                items(trades, key = { it.id }) { trade ->
                    SignalTradeRow(trade, onOpenLink, canExploreStock, onExploreStock)
                    SignalLine()
                }
                if (trades.isEmpty() && notice?.startsWith("Checking") != true) {
                    item(key = "no-trades") {
                        Spacer(Modifier.height(rd(18f)))
                        RefText("No verified trades are available for this profile right now.",
                            14f, W.Muted, maxLines = 3)
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .clip(RoundedCornerShape(topStart = rd(28f), topEnd = rd(28f)))
                .background(W.Panel)
                .padding(top = rd(17f)),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = rd(24f)),
                verticalAlignment = Alignment.CenterVertically) {
                RefText("In the news", 18f, W.White, FontWeight.SemiBold, Modifier.weight(1f))
            }
            Spacer(Modifier.height(rd(8f)))
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = rd(24f), end = rd(24f), bottom = rd(114f)),
            ) {
                items(news, key = { it.id }) { article ->
                    SignalArticleRow(article, onOpenLink)
                    SignalLine()
                }
                if (news.isEmpty()) {
                    item(key = "no-news") {
                        Spacer(Modifier.height(rd(16f)))
                        RefText("Articles are unavailable right now.", 14f, W.Muted,
                            maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalTradeRow(
    trade: SignalTrade,
    onOpenLink: (String) -> Unit,
    canExploreStock: (String) -> Boolean,
    onExploreStock: (String) -> Unit,
) {
    val isPurchase = trade.action.contains("purchase", ignoreCase = true) ||
        trade.action.contains("buy", ignoreCase = true)
    val isSale = trade.action.contains("sale", ignoreCase = true) ||
        trade.action.contains("sell", ignoreCase = true)
    Column(
        Modifier.fillMaxWidth().clickable(enabled = trade.sourceUrl?.startsWith("https://") == true,
            role = Role.Button) { trade.sourceUrl?.let(onOpenLink) }
            .padding(vertical = rd(11f)),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RefText(trade.symbol.ifBlank { "Stock" }, 16f, W.White, FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            RefText(trade.action, 13f,
                when {
                    isPurchase -> P.Lime
                    isSale -> Color(0xFFFF6B6B)
                    else -> W.Cream
                }, FontWeight.SemiBold, Modifier.padding(start = rd(8f)))
        }
        if (!trade.companyName.isNullOrBlank()) {
            Spacer(Modifier.height(rd(2f)))
            RefText(trade.companyName, 13f, W.Muted)
        }
        Spacer(Modifier.height(rd(5f)))
        val dateLine = buildList {
            if (trade.transactionDate.isNotBlank()) add("Traded ${trade.transactionDate}")
            if (!trade.disclosureDate.isNullOrBlank()) add("Filed ${trade.disclosureDate}")
        }.joinToString("  ·  ")
        if (dateLine.isNotBlank()) RefText(dateLine, 12f, W.Muted, maxLines = 2)
        if (!trade.amountRange.isNullOrBlank()) {
            Spacer(Modifier.height(rd(2f)))
            RefText(trade.amountRange, 12f, W.Muted)
        }
        if (trade.symbol.isNotBlank() && canExploreStock(trade.symbol)) {
            Spacer(Modifier.height(rd(8f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Row(
                    Modifier.clip(CircleShape).background(W.Cream)
                        .clickable(role = Role.Button) { onExploreStock(trade.symbol) }
                        .semantics { contentDescription = "View ${trade.symbol} in Stocks" }
                        .padding(horizontal = rd(13f), vertical = rd(9f)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(rd(5f)),
                ) {
                    RefText("View in Stocks", 12f, W.Ink, FontWeight.Medium)
                    RefIcon("arrowUpRight", 12f, W.Ink)
                }
            }
        }
    }
}

@Composable
private fun SignalArticleRow(article: SignalNewsArticle, onOpenLink: (String) -> Unit) {
    val host = runCatching { URI(article.url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    val publisherLogo = signalPublisherLogo(article.publisher, host)
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = article.url.startsWith("https://"), role = Role.Button) {
                onOpenLink(article.url)
            }
            .semantics { contentDescription = "Read ${article.title} in browser" }
            .padding(vertical = rd(14f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(30f)).clip(CircleShape)
                .background(if (publisherLogo == null) W.Cream else Color.White),
                contentAlignment = Alignment.Center) {
                if (publisherLogo != null) {
                    Image(painterResource(publisherLogo), null,
                        Modifier.fillMaxSize().padding(rd(2f)), contentScale = ContentScale.Fit)
                } else {
                    RefText(article.publisher.take(1).uppercase(), 16f, W.Background, FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(rd(10f)))
            Column {
                RefText(article.publisher, 13f, W.White, FontWeight.Medium)
                if (host.isNotBlank()) RefText(host, 11f, W.Muted)
            }
        }
        Spacer(Modifier.height(rd(9f)))
        RefText(article.title, 16f, Color(0xFFB7D0FF), FontWeight.Medium, maxLines = 3)
        Spacer(Modifier.height(rd(6f)))
        if (!article.summary.isNullOrBlank()) {
            RefText(listOf(article.publishedAt, article.summary).filter { it.isNotBlank() }
                .joinToString(" — "), 12f, W.Muted, maxLines = 3)
        } else {
            RefText(article.publishedAt, 12f, W.Muted)
        }
    }
}

private fun signalPublisherLogo(publisher: String, host: String): Int? {
    val name = publisher.lowercase()
    fun from(domain: String) = host == domain || host.endsWith(".$domain")
    return when {
        name == "associated press" || from("apnews.com") -> R.drawable.publisher_ap
        name == "capitol markets" || from("capitolmarkets.org") -> R.drawable.publisher_capitol_markets
        name == "investing.com" || from("investing.com") -> R.drawable.publisher_investing
        name == "signal ohio" || from("signalohio.org") -> R.drawable.publisher_signal_ohio
        name.startsWith("rep. ") || from("house.gov") -> R.drawable.publisher_house
        name == "microsoft" || from("microsoft.com") -> R.drawable.publisher_microsoft
        name == "apple" || from("apple.com") -> R.drawable.publisher_apple
        name == "amazon" || from("aboutamazon.com") -> R.drawable.publisher_amazon
        name == "nvidia" || from("nvidia.com") -> R.drawable.publisher_nvidia
        name == "meta" || from("atmeta.com") -> R.drawable.publisher_meta
        name == "alphabet" || from("abc.xyz") -> R.drawable.publisher_alphabet
        name == "amd" || from("amd.com") -> R.drawable.publisher_amd
        name == "tesla" || from("tesla.com") -> R.drawable.publisher_tesla
        name.startsWith("palantir") || from("palantir.com") -> R.drawable.publisher_palantir
        name == "micron" || from("micron.com") -> R.drawable.publisher_micron
        name == "applied materials" || from("appliedmaterials.com") -> R.drawable.publisher_applied_materials
        name == "servicenow" || from("servicenow.com") -> R.drawable.publisher_servicenow
        else -> null
    }
}

private fun SignalProfile.chamberName(): String = when (role.trim().lowercase()) {
    "house" -> "U.S. House of Representatives"
    "senate" -> "U.S. Senate"
    else -> role
}

private fun SignalProfile.fullStateName(): String {
    val code = state.trim().take(2).uppercase()
    val name = stateNames[code] ?: state
    val district = state.trim().drop(2).trim().toIntOrNull()
    return if (district != null && role.equals("House", ignoreCase = true))
        "$name · District $district" else name
}

private val stateNames = mapOf(
    "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
    "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
    "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
    "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
    "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
    "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
    "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
    "NH" to "New Hampshire", "NJ" to "New Jersey", "NM" to "New Mexico", "NY" to "New York",
    "NC" to "North Carolina", "ND" to "North Dakota", "OH" to "Ohio", "OK" to "Oklahoma",
    "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "Rhode Island", "SC" to "South Carolina",
    "SD" to "South Dakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah",
    "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "West Virginia",
    "WI" to "Wisconsin", "WY" to "Wyoming", "DC" to "District of Columbia",
)

@Composable
private fun SignalPortrait(profile: SignalProfile, size: androidx.compose.ui.unit.Dp) {
    val palette = listOf(
        Color(0xFF465E63), Color(0xFF5E536A), Color(0xFF5E5A4D), Color(0xFF4E6358),
    )
    val fallback = palette[(profile.id.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(Modifier.size(size).clip(CircleShape).background(fallback), contentAlignment = Alignment.Center) {
        if (profile.portraitUrl?.startsWith("https://") == true) {
            SubcomposeAsyncImage(
                model = profile.portraitUrl,
                contentDescription = "${profile.name} portrait",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { SignalInitials(profile.name) },
                error = { SignalInitials(profile.name) },
                success = { SubcomposeAsyncImageContent() },
            )
        } else {
            SignalInitials(profile.name)
        }
    }
}

@Composable
private fun SignalInitials(name: String) {
    val initials = name.split(' ').filter(String::isNotBlank).take(2).joinToString("") {
        it.first().uppercaseChar().toString()
    }
    RefText(initials, 15f, W.White, FontWeight.SemiBold)
}

@Composable
private fun SignalRetry(onRefresh: () -> Unit) {
    Spacer(Modifier.height(rd(14f)))
    Box(
        Modifier.clip(CircleShape).background(W.Cream)
            .clickable(role = Role.Button, onClick = onRefresh)
            .padding(horizontal = rd(16f), vertical = rd(9f)),
    ) { RefText("Refresh", 13f, W.Ink, FontWeight.Medium) }
}

@Composable
private fun SignalLine() {
    Spacer(Modifier.fillMaxWidth().height(rd(1f)).background(W.Circle.copy(alpha = .65f)))
}
