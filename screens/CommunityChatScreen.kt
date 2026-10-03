package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import coil3.compose.AsyncImage
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.R
import com.elevencapital.app.tokenlaunch.TokenLaunchArtworkStore
import com.elevencapital.app.ui.ProfileAvatar
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.rs
import com.elevencapital.app.ui.LocalReferenceFont
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Chat data is provided by the caller; local room starters never enter the backend. */
data class CommunityChatMessage(
    val id: String,
    val author: String,
    val body: String,
    val sentAtMillis: Long,
    val isMine: Boolean,
)

@Composable
fun CommunityChatScreen(
    tokenName: String,
    symbol: String,
    artworkUri: String?,
    messages: List<CommunityChatMessage>,
    canPost: Boolean,
    onClose: () -> Unit,
    onSend: suspend (String) -> Boolean,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    error: String? = null,
    sending: Boolean = false,
    chatReady: Boolean = canPost,
    onRetry: () -> Unit = {},
    isSpxtrRoom: Boolean = false,
    avatarVariant: Int = 0,
) {
    BackHandler(onBack = onClose)
    val sendScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var initialPositionSet by remember(tokenName, symbol) { mutableStateOf(false) }
    var hadOtherLiveMessages by remember(tokenName, symbol) { mutableStateOf(false) }
    var scrollToSentMessage by remember(tokenName, symbol) { mutableStateOf(false) }
    var draft by rememberSaveable(tokenName, symbol) { mutableStateOf("") }
    var sendingLocally by remember(tokenName, symbol) { mutableStateOf(false) }
    val sendText = draft.trim()
    val canSend = canPost && !sending && !sendingLocally && sendText.isNotEmpty()
    val showChristmasStarters = isSpxtrRoom && BuildConfig.DEBUG
    val visibleMessages = messages

    fun send() {
        if (!canSend) return
        val submitted = sendText
        sendingLocally = true
        sendScope.launch {
            try {
                if (onSend(submitted)) {
                    scrollToSentMessage = true
                    if (draft.trim() == submitted) draft = ""
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep the text so it can be retried after a local storage failure.
            } finally {
                sendingLocally = false
            }
        }
    }

    // Open the Christmas room on its starter conversation. Older live history stays
    // available below it; only a newly sent post or a reader already at the bottom
    // should move the list down to a live message.
    LaunchedEffect(visibleMessages.lastOrNull()?.id, tokenName, symbol, showChristmasStarters,
        scrollToSentMessage) {
        val lastIndex = visibleMessages.lastIndex +
            if (showChristmasStarters) CHRISTMAS_CHAT_STARTERS.size else 0
        val hasOtherLiveMessages = visibleMessages.any { !it.isMine }
        if (!initialPositionSet && showChristmasStarters) {
            listState.scrollToItem(0)
        } else if (lastIndex >= 0) {
            val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val wasAtBottom = scrollToSentMessage ||
                (!initialPositionSet && !showChristmasStarters) ||
                (!showChristmasStarters && !hadOtherLiveMessages && hasOtherLiveMessages) ||
                visibleLast >= lastIndex - 1
            if (wasAtBottom) listState.animateScrollToItem(lastIndex)
        }
        initialPositionSet = true
        hadOtherLiveMessages = hasOtherLiveMessages
        if (scrollToSentMessage) scrollToSentMessage = false
    }

    Column(modifier.fillMaxSize().background(W.Background).imePadding()) {
        CommunityChatHeader(tokenName, symbol, artworkUri, showChristmasStarters, onClose)

        if ((visibleMessages.isNotEmpty() || showChristmasStarters) &&
            (loading || error != null || (canPost && !chatReady))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = rd(22f), vertical = rd(8f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RefText(
                    if (error != null) "Other messages may be delayed"
                        else if (canPost && !chatReady) "Connecting to Live Chat…"
                        else "Updating chat…",
                    13f, W.Muted, modifier = Modifier.weight(1f), maxLines = 2,
                )
                if (error != null || (canPost && !chatReady && !loading)) ChatRetryButton(onRetry)
            }
        }

        if (visibleMessages.isEmpty() && !showChristmasStarters) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = rd(28f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        error != null -> {
                            RefText("Other messages may be delayed", 19f, W.White, FontWeight.SemiBold)
                            Spacer(Modifier.height(rd(8f)))
                            RefText(error, 14f, W.Muted, maxLines = 3)
                            Spacer(Modifier.height(rd(14f)))
                            ChatRetryButton(onRetry)
                        }
                        loading -> RefText("Loading chat…", 16f, W.Muted)
                        else -> {
                            RefText("No messages yet", 19f, W.White, FontWeight.SemiBold)
                            Spacer(Modifier.height(rd(8f)))
                            RefText(
                                if (canPost) "Start the conversation."
                                else "Messages will appear here.",
                                14f, W.Muted, maxLines = 2,
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(rd(22f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = rd(22f), end = rd(22f), top = rd(20f), bottom = rd(20f),
                ),
            ) {
                if (showChristmasStarters) {
                    items(CHRISTMAS_CHAT_STARTERS, key = { it.id }) { starter ->
                        CommunityChatMessageRow(starter, avatarVariant)
                    }
                }
                items(visibleMessages, key = { it.id }) { message ->
                    CommunityChatMessageRow(message, avatarVariant)
                }
            }
        }

        if (canPost) {
            Row(
                Modifier.fillMaxWidth().background(W.Background)
                    .padding(start = rd(20f), end = rd(20f), top = rd(10f), bottom = rd(16f)),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(500) },
                    readOnly = sending,
                    modifier = Modifier.weight(1f).heightIn(min = rd(50f))
                        .clip(RoundedCornerShape(rd(25f))).background(W.Panel)
                        .padding(horizontal = rd(18f), vertical = rd(14f))
                        .semantics { contentDescription = "Write a chat message" },
                    textStyle = TextStyle(
                        color = W.White,
                        fontSize = rs(16f),
                        lineHeight = rs(20f),
                        fontFamily = LocalReferenceFont.current,
                    ),
                    cursorBrush = SolidColor(W.White),
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) RefText("Write a message", 16f, W.Muted)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(rd(10f)))
                Box(
                    Modifier.height(rd(50f)).width(rd(66f)).clip(CircleShape)
                        .background(if (canSend) W.Cream else W.Panel)
                        .then(if (canSend) Modifier.clickable(role = Role.Button, onClick = ::send) else Modifier)
                        .semantics { contentDescription = "Send message" },
                    contentAlignment = Alignment.Center,
                ) {
                    RefText(
                        if (sending || sendingLocally) "Sending" else "Send", 14f,
                        if (canSend) W.Ink else W.Muted,
                        FontWeight.SemiBold,
                    )
                }
            }
        } else {
            Box(
                Modifier.fillMaxWidth().padding(start = rd(20f), end = rd(20f),
                    top = rd(10f), bottom = rd(16f))
                    .height(rd(52f)).clip(RoundedCornerShape(rd(18f)))
                    .background(W.Panel),
                contentAlignment = Alignment.Center,
            ) {
                RefText("Buy to send messages", 14f, W.Muted, FontWeight.Medium)
            }
        }
    }
}

// Display-only conversation starters for the Christmas debug room.
private val CHRISTMAS_CHAT_STARTERS = listOf(
    CommunityChatMessage("christmas-starter-1", "@snowcap", "Okay, who put the candy-cane sweater on the token? 🎄", 0L, false),
    CommunityChatMessage("christmas-starter-2", "@mintcocoa", "I saw the artwork and immediately wanted hot chocolate 😂", 0L, false),
    CommunityChatMessage("christmas-starter-3", "@pineglow", "The name is Christmas and the ticker is CHTMS. I love it.", 0L, false),
    CommunityChatMessage("christmas-starter-4", "@sleighbell", "Can we get this pattern on an actual sweater?", 0L, false),
    CommunityChatMessage("christmas-starter-5", "@snowcap", "Only if we all wear it for the holiday photo.", 0L, false),
    CommunityChatMessage("christmas-starter-6", "@wintermint", "I'm in. Who's making the playlist?", 0L, false),
    CommunityChatMessage("christmas-starter-7", "@gingerbyte", "I volunteer. Expect one Mariah song and several terrible remixes.", 0L, false),
    CommunityChatMessage("christmas-starter-8", "@wintermint", "Perfect. I'll bring the cocoa ☕", 0L, false),
)

@Composable
private fun ChatRetryButton(onRetry: () -> Unit) {
    Box(
        Modifier.height(rd(44f)).clip(CircleShape).background(W.Panel)
            .clickable(role = Role.Button, onClick = onRetry)
            .semantics { contentDescription = "Retry loading chat" }
            .padding(horizontal = rd(18f)),
        contentAlignment = Alignment.Center,
    ) { RefText("Retry", 13f, W.White, FontWeight.SemiBold) }
}

@Composable
private fun CommunityChatHeader(
    tokenName: String,
    symbol: String,
    artworkUri: String?,
    useChristmasArtworkFallback: Boolean,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(rd(80f))
            .padding(start = rd(20f), end = rd(22f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(rd(44f)).clip(CircleShape).background(W.Panel)
                .clickable(role = Role.Button, onClick = onClose)
                .semantics { contentDescription = "Close live chat" },
            contentAlignment = Alignment.Center,
        ) { RefText("×", 28f, W.White) }
        Spacer(Modifier.width(rd(14f)))
        ChatTokenArtwork(tokenName, artworkUri, useChristmasArtworkFallback)
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(tokenName.ifBlank { "Creator token" }, 19f, W.White, FontWeight.SemiBold,
                Modifier.semantics { heading() }, maxLines = 1)
            RefText(
                symbol.trim().removePrefix("$").takeIf(String::isNotBlank)?.let { "\$$it · Live chat" }
                    ?: "Live chat",
                13f, W.Muted, maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChatTokenArtwork(
    tokenName: String,
    artworkUri: String?,
    useChristmasArtworkFallback: Boolean,
) {
    val context = LocalContext.current
    val model = remember(context, artworkUri) {
        artworkUri?.takeIf(String::isNotBlank)?.let { uri ->
            TokenLaunchArtworkStore.localArtworkFile(context, uri) ?: uri
        }
    }
    var artworkReady by remember(model) { mutableStateOf(false) }
    Box(
        Modifier.size(rd(44f)).clip(CircleShape).background(W.Circle),
        contentAlignment = Alignment.Center,
    ) {
        if (!artworkReady) {
            if (useChristmasArtworkFallback) {
                Image(
                    painter = painterResource(R.drawable.token_spxtr_christmas),
                    contentDescription = "${tokenName.ifBlank { "Token" }} artwork",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                RefText(tokenName.trim().take(1).uppercase().ifBlank { "$" },
                    18f, W.White, FontWeight.Bold)
            }
        }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = "${tokenName.ifBlank { "Token" }} artwork",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onLoading = { artworkReady = false },
                onError = { artworkReady = false },
                onSuccess = { artworkReady = true },
            )
        }
    }
}

@Composable
private fun CommunityChatMessageRow(message: CommunityChatMessage, avatarVariant: Int) {
    val starterAvatar = if (message.id.startsWith("christmas-starter-")) {
        when (message.author) {
            "@snowcap" -> 2 to "🎅"
            "@mintcocoa" -> 3 to "☕"
            "@pineglow" -> 4 to "🎄"
            "@sleighbell" -> 1 to "🔔"
            "@wintermint" -> 0 to "☃️"
            else -> null
        }
    } else null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(rd(34f)).clip(CircleShape)
                .background(if (message.isMine) W.Circle else W.Panel),
            contentAlignment = Alignment.Center,
        ) {
            if (message.isMine) {
                ProfileAvatar(avatarVariant, Modifier.fillMaxSize())
            } else if (starterAvatar != null) {
                ProfileAvatar(starterAvatar.first, Modifier.fillMaxSize())
                RefText(starterAvatar.second, 18f, W.White)
            } else {
                RefText(message.author.trim().removePrefix("@").take(1).uppercase().ifBlank { "?" },
                    14f, W.White, FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RefText(
                    if (message.isMine) "You" else message.author.ifBlank { "Holder" },
                    15f, W.White, FontWeight.Medium,
                    Modifier.weight(1f, fill = false), maxLines = 1,
                )
                if (message.sentAtMillis > 0L) {
                    Spacer(Modifier.width(rd(9f)))
                    val time = remember(message.sentAtMillis) {
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.sentAtMillis))
                    }
                    RefText(time, 12f, W.Muted)
                }
            }
            Spacer(Modifier.height(rd(6f)))
            RefText(message.body, 16f, W.White, modifier = Modifier.fillMaxWidth(),
                maxLines = Int.MAX_VALUE)
        }
    }
}
