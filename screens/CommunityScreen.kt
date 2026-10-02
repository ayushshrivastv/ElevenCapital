package com.elevencapital.app.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.elevencapital.app.BuildConfig
import com.elevencapital.app.ui.BrandLogo
import com.elevencapital.app.ui.LocalReferenceFont
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.SolanaLogo
import com.elevencapital.app.ui.SquareLogo
import com.elevencapital.app.ui.WalletStyle as W
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.rs
import com.elevencapital.app.wallet.QrCodeMatrix
import com.elevencapital.app.communitybuy.CommunityPurchasedToken
import com.elevencapital.app.tokenlaunch.isPublicTokenHttpsUrl
import com.elevencapital.app.tokenlaunch.TokenLaunchArtworkStore
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User-authored values. The coordinator must validate them again before preparing a transaction. */
data class LaunchTokenDraft(
    val imageUri: String? = null,
    val name: String = "",
    val symbol: String = "",
    val description: String = "",
    val xHandle: String = "",
    val telegramHandle: String = "",
    val websiteUrl: String = "",
    val totalSupply: String = "1000000000",
    val feeBps: String = "100",
    val migrationThresholdSol: String = "10",
    val supplyOnMigrationPercent: String = "20",
    val curvePreset: String = "Classic",
    val initialMarketCapSol: String = "",
    val graduationMarketCapSol: String = "",
    val launchProtectionEnabled: Boolean = false,
    val vestCreatorTokens: Boolean = false,
    val vestedSupplyPercent: String = "10",
    val vestingDurationMonths: String = "12",
    val vestingFrequency: String = "Monthly",
) {
    val pairedAsset: String get() = LAUNCH_PAIRED_ASSET
    val network: String get() = LAUNCH_NETWORK
}

internal const val LAUNCH_PAIRED_ASSET = "SOL"
internal const val LAUNCH_NETWORK = "Solana Mainnet"
internal const val LAUNCH_MIGRATION_TARGET = "Meteora DAMM v2"
internal const val LAUNCH_PROGRAM = "Meteora Dynamic Bonding Curve"
internal val LAUNCH_CURVE_PRESETS = listOf("Classic", "Flat", "Exponential", "Long")

internal fun launchUsesMarketCapCurve(draft: LaunchTokenDraft): Boolean =
    draft.initialMarketCapSol.isNotBlank() && draft.graduationMarketCapSol.isNotBlank()

internal enum class LaunchTokenStep { IMAGE, IDENTITY, DESCRIPTION, DETAILS, DBC }

/** Lifecycle owned by the launch coordinator. Compose never performs a network or wallet call. */
enum class LaunchTokenExecutionPhase {
    EDITING,
    PREPARING,
    SIGNING,
    CONFIRMING,
    CONFIRMED,
    FAILED,
}

enum class CommunityTokenOrigin {
    CONFIRMED_LAUNCH,
    LINKED_SCAN,
    LOCAL_UI,
}

data class ConfirmedTokenLaunch(
    val mintAddress: String,
    val signature: String,
    val poolAddress: String = "",
)

data class LaunchTokenExecutionState(
    val phase: LaunchTokenExecutionPhase = LaunchTokenExecutionPhase.EDITING,
    val confirmed: ConfirmedTokenLaunch? = null,
    val message: String? = null,
)

sealed interface LaunchTokenAction {
    data class Create(val draft: LaunchTokenDraft) : LaunchTokenAction
    data object Retry : LaunchTokenAction
    data object Edit : LaunchTokenAction
    data object Cancel : LaunchTokenAction
}

internal fun mainnetSolscanTokenUrl(mint: String): String? =
    parseSolanaTokenQrPayload(mint)?.let { "https://solscan.io/token/$it" }

internal data class TokenLaunchConfirmationPresentation(
    val ticker: String,
    val tokenLink: String?,
)

internal fun tokenLaunchConfirmationPresentation(
    draft: LaunchTokenDraft,
    result: ConfirmedTokenLaunch?,
): TokenLaunchConfirmationPresentation = TokenLaunchConfirmationPresentation(
    ticker = draft.symbol.trim().uppercase(Locale.US).let { if (it.isBlank()) "$" else "\$$it" },
    tokenLink = result?.mintAddress?.let { mint ->
        if (isTokenLaunchUiPreview(draft.symbol) && mint == tokenLaunchUiPreviewResult().mintAddress) {
            TOKEN_LAUNCH_UI_PREVIEW_LINK
        } else mainnetSolscanTokenUrl(mint)
    },
)

/** Symbols are displayed without a leading dollar sign and stored in canonical uppercase form. */
internal fun normalizeLaunchTokenSymbol(value: String): String = value
    .trim()
    .removePrefix("$")
    .uppercase(Locale.US)
    .filter { it in 'A'..'Z' || it in '0'..'9' }
    .take(10)

internal fun truncateLaunchTokenName(value: String, maxUtf8Bytes: Int = 32): String = buildString {
    var byteCount = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val text = String(Character.toChars(codePoint))
        val nextBytes = text.toByteArray(Charsets.UTF_8).size
        if (byteCount + nextBytes > maxUtf8Bytes) break
        append(text)
        byteCount += nextBytes
        index += Character.charCount(codePoint)
    }
}

internal fun normalizeLaunchSocialHandle(value: String, host: String): String = value
    .trim()
    .removePrefix("https://")
    .removePrefix("http://")
    .removePrefix("www.")
    .removePrefix(host)
    .trimStart('/', '@')
    .take(32)

/** Returns one actionable inline message for the current step, or null when it can advance. */
internal fun launchTokenValidationMessage(step: LaunchTokenStep, draft: LaunchTokenDraft): String? = when (step) {
    LaunchTokenStep.IMAGE -> if (draft.imageUri.isNullOrBlank()) "Choose an image for your token." else null
    LaunchTokenStep.IDENTITY -> when {
        draft.name.trim().isEmpty() -> "Enter a token name."
        draft.name != draft.name.trim() -> "Token name cannot start or end with spaces."
        draft.name.any { it.code < 0x20 || it.code == 0x7F } -> "Token name contains an unsupported character."
        draft.name.toByteArray(Charsets.UTF_8).size > 32 -> "Token name must be 32 UTF-8 bytes or fewer."
        draft.symbol.length !in 1..10 -> "Symbol must be 1 to 10 letters or numbers."
        !draft.symbol.matches(Regex("[A-Z0-9]{1,10}")) -> "Symbol can only contain A–Z letters and numbers."
        else -> null
    }
    LaunchTokenStep.DESCRIPTION -> when {
        draft.description.trim().length < 10 -> "Description must be at least 10 characters."
        draft.description.trim().length > 280 -> "Description must be 280 characters or fewer."
        else -> null
    }
    LaunchTokenStep.DETAILS -> when {
        draft.xHandle.isNotEmpty() && !draft.xHandle.matches(Regex("[A-Za-z0-9_]{1,15}")) ->
            "Enter a valid X handle."
        draft.telegramHandle.isNotEmpty() &&
            !draft.telegramHandle.matches(Regex("[A-Za-z0-9_]{5,32}")) ->
            "Enter a valid Telegram handle."
        draft.websiteUrl.isNotEmpty() && !isValidLaunchWebsite(draft.websiteUrl) ->
            "Website must be a public https:// URL."
        else -> null
    }
    LaunchTokenStep.DBC -> when {
        !draft.totalSupply.matches(Regex("[1-9]\\d*")) ||
            draft.totalSupply.toBigIntegerOrNull()?.let { it < MIN_LAUNCH_SUPPLY || it > MAX_LAUNCH_SUPPLY } != false ->
            "Total supply must be a whole number from 1,000,000 to 1,000,000,000,000."
        draft.feeBps.toIntOrNull() !in 25..1_000 -> "Trading fee must be from 25 to 1,000 bps."
        !launchUsesMarketCapCurve(draft) &&
            (!draft.migrationThresholdSol.matches(Regex("(?:0|[1-9]\\d*)(?:\\.\\d{1,9})?")) ||
                draft.migrationThresholdSol.toBigDecimalOrNull()?.let {
                    it < MIN_MIGRATION_THRESHOLD_SOL || it > MAX_MIGRATION_THRESHOLD_SOL
                } != false) -> "Graduation target must be from 0.01 to 10,000 SOL."
        draft.supplyOnMigrationPercent.toIntOrNull() !in 1..90 ->
            "Supply on migration must be from 1% to 90%."
        draft.curvePreset !in LAUNCH_CURVE_PRESETS -> "Choose a curve shape."
        (draft.initialMarketCapSol.isNotBlank() || draft.graduationMarketCapSol.isNotBlank()) &&
            !isLaunchMarketCap(draft.initialMarketCapSol) ->
            "Starting market cap must be from 0.01 to 10,000,000 SOL."
        (draft.initialMarketCapSol.isNotBlank() || draft.graduationMarketCapSol.isNotBlank()) &&
            !isLaunchMarketCap(draft.graduationMarketCapSol) ->
            "Graduation market cap must be from 0.01 to 10,000,000 SOL."
        draft.initialMarketCapSol.isNotBlank() && draft.graduationMarketCapSol.isNotBlank() &&
            draft.initialMarketCapSol.toBigDecimal() >= draft.graduationMarketCapSol.toBigDecimal() ->
            "Graduation market cap must be higher than starting market cap."
        draft.vestCreatorTokens && draft.vestedSupplyPercent.toIntOrNull() !in 1..100 ->
            "Vested supply must be from 1% to 100%."
        draft.vestCreatorTokens &&
            (draft.vestedSupplyPercent.toIntOrNull() ?: 0) +
            (draft.supplyOnMigrationPercent.toIntOrNull() ?: 0) >= 100 ->
            "Leave some supply for bonding-curve trading."
        draft.vestCreatorTokens && draft.vestingDurationMonths.toIntOrNull() !in 1..120 ->
            "Vesting duration must be from 1 to 120 months."
        draft.vestCreatorTokens && draft.vestingFrequency !in LAUNCH_VESTING_FREQUENCIES ->
            "Choose a vesting release schedule."
        else -> null
    }
}

internal fun launchTokenDraftValidationMessage(draft: LaunchTokenDraft): String? =
    LaunchTokenStep.entries.firstNotNullOfOrNull { launchTokenValidationMessage(it, draft) }

internal fun nextLaunchTokenStep(step: LaunchTokenStep): LaunchTokenStep = when (step) {
    LaunchTokenStep.IMAGE -> LaunchTokenStep.IDENTITY
    LaunchTokenStep.IDENTITY -> LaunchTokenStep.DESCRIPTION
    LaunchTokenStep.DESCRIPTION -> LaunchTokenStep.DETAILS
    LaunchTokenStep.DETAILS -> LaunchTokenStep.DBC
    LaunchTokenStep.DBC -> LaunchTokenStep.DBC
}

internal fun previousLaunchTokenStep(step: LaunchTokenStep): LaunchTokenStep? = when (step) {
    LaunchTokenStep.IMAGE -> null
    LaunchTokenStep.IDENTITY -> LaunchTokenStep.IMAGE
    LaunchTokenStep.DESCRIPTION -> LaunchTokenStep.IDENTITY
    LaunchTokenStep.DETAILS -> LaunchTokenStep.DESCRIPTION
    LaunchTokenStep.DBC -> LaunchTokenStep.DETAILS
}

/** Keeps untouched required steps visibly inactive while still allowing invalid input to explain itself. */
internal fun launchTokenFooterEnabled(step: LaunchTokenStep, draft: LaunchTokenDraft): Boolean = when (step) {
    LaunchTokenStep.IMAGE -> !draft.imageUri.isNullOrBlank()
    LaunchTokenStep.IDENTITY -> draft.name.isNotBlank() && draft.symbol.isNotBlank()
    LaunchTokenStep.DESCRIPTION -> draft.description.isNotBlank()
    LaunchTokenStep.DETAILS -> true
    LaunchTokenStep.DBC -> draft.totalSupply.isNotBlank() && draft.feeBps.isNotBlank() &&
        (launchUsesMarketCapCurve(draft) || draft.migrationThresholdSol.isNotBlank()) &&
        draft.supplyOnMigrationPercent.isNotBlank()
}

private val MIN_LAUNCH_SUPPLY = BigInteger("1000000")
private val MAX_LAUNCH_SUPPLY = BigInteger("1000000000000")
private val MIN_MIGRATION_THRESHOLD_SOL = BigDecimal("0.01")
private val MAX_MIGRATION_THRESHOLD_SOL = BigDecimal("10000")
private val MIN_MARKET_CAP_SOL = BigDecimal("0.01")
private val MAX_MARKET_CAP_SOL = BigDecimal("10000000")
internal val LAUNCH_VESTING_FREQUENCIES = listOf("Daily", "Weekly", "Monthly")

private fun isLaunchMarketCap(value: String): Boolean =
    value.matches(Regex("(?:0|[1-9]\\d*)(?:\\.\\d{1,3})?")) &&
        value.toBigDecimalOrNull()?.let { it in MIN_MARKET_CAP_SOL..MAX_MARKET_CAP_SOL } == true

private fun isValidLaunchWebsite(value: String): Boolean =
    value == value.trim() && isPublicTokenHttpsUrl(value)

internal fun restoreLaunchTokenDraft(values: List<String>): LaunchTokenDraft? {
    if (values.size < 11) return null
    return LaunchTokenDraft(
        imageUri = values[0].ifBlank { null },
        name = values[1], symbol = values[2], description = values[3],
        xHandle = values[4], telegramHandle = values[5], websiteUrl = values[6],
        totalSupply = values[7], feeBps = values[8], migrationThresholdSol = values[9],
        supplyOnMigrationPercent = values[10],
        curvePreset = values.getOrNull(18) ?: "Classic",
        initialMarketCapSol = values.getOrNull(11).orEmpty(),
        graduationMarketCapSol = values.getOrNull(12).orEmpty(),
        launchProtectionEnabled = values.getOrNull(13) == "true",
        vestCreatorTokens = values.getOrNull(14) == "true",
        vestedSupplyPercent = values.getOrNull(15) ?: "10",
        vestingDurationMonths = values.getOrNull(16) ?: "12",
        vestingFrequency = values.getOrNull(17) ?: "Monthly",
    )
}

private val LaunchTokenDraftSaver = listSaver<LaunchTokenDraft, String>(
    save = {
        listOf(
            it.imageUri.orEmpty(), it.name, it.symbol, it.description,
            it.xHandle, it.telegramHandle, it.websiteUrl,
            it.totalSupply, it.feeBps, it.migrationThresholdSol, it.supplyOnMigrationPercent,
            it.initialMarketCapSol, it.graduationMarketCapSol,
            it.launchProtectionEnabled.toString(), it.vestCreatorTokens.toString(),
            it.vestedSupplyPercent, it.vestingDurationMonths, it.vestingFrequency,
            it.curvePreset,
        )
    },
    restore = ::restoreLaunchTokenDraft,
)

/** Community tab landing. Launching is routed separately so the dock can disappear. */
@Composable
fun CommunityScreen(
    onLaunchToken: () -> Unit,
    onOpenTokenDetail: () -> Unit = {},
    localDraft: LaunchTokenDraft? = null,
    linkedMint: String? = null,
    tokenOrigin: CommunityTokenOrigin? = null,
    purchasedTokens: List<CommunityPurchasedToken> = emptyList(),
    scannedTokens: List<CommunityScannedTokenPurchase> = emptyList(),
    pendingMessage: String? = null,
    onTokenLinked: (String) -> Unit = {},
    onOpenScannedToken: (String) -> Unit = {},
    onScannedTokenRemove: (String) -> Unit = {},
    onLocalTokenRemove: () -> Unit = {},
    onLocalTokenImageChosen: (String) -> Boolean = { false },
    modifier: Modifier = Modifier,
) {
    var scanInProgress by rememberSaveable { mutableStateOf(false) }
    var scanMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var scanMessageIsError by rememberSaveable { mutableStateOf(false) }
    val launchScanner = rememberGoogleQrCodeScanner { result ->
        scanInProgress = false
        when (result) {
            QrCodeScanResult.Canceled -> scanMessage = null
            is QrCodeScanResult.Failed -> {
                scanMessageIsError = true
                scanMessage = "Couldn't open the QR scanner. Check Google Play services and try again."
            }
            is QrCodeScanResult.Scanned -> {
                val mint = parseSolanaTokenQrPayload(result.rawValue)
                if (mint == null) {
                    scanMessageIsError = true
                    scanMessage = "That QR code doesn't contain a valid Solana address. Try another code."
                } else {
                    onTokenLinked(mint)
                    scanMessageIsError = false
                    scanMessage = null
                }
            }
        }
    }
    fun scanToken() {
        if (scanInProgress) return
        scanMessage = null
        scanMessageIsError = false
        scanInProgress = true
        launchScanner()
    }

    Column(modifier.fillMaxSize().background(W.Background)) {
        Row(
            Modifier.fillMaxWidth().height(rd(56f))
                .padding(start = rd(32f), end = rd(12f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandLogo(Modifier.size(rd(36f)))
            Spacer(Modifier.weight(1f))
            CommunityButton(
                label = "Create Token",
                background = W.Cream,
                foreground = W.Ink,
                contentDescription = "Create a Solana token",
                onClick = onLaunchToken,
            )
            Spacer(Modifier.width(rd(8f)))
            CommunityScanButton(enabled = !scanInProgress, onClick = ::scanToken)
        }
        Spacer(Modifier.height(rd(16f)))

        when {
            scanInProgress -> RefText(
                "Opening secure QR scanner…", 12f, W.Muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = rd(22f), vertical = rd(8f)),
            )
            scanMessage != null -> RefText(
                requireNotNull(scanMessage), 12f,
                if (scanMessageIsError) P.Pink else P.Lime,
                modifier = Modifier.fillMaxWidth().padding(horizontal = rd(22f), vertical = rd(8f))
                    .then(if (scanMessageIsError) {
                        Modifier.semantics { error(requireNotNull(scanMessage)) }
                    } else Modifier),
                maxLines = 2,
            )
        }

        pendingMessage?.let { message ->
            RefText(message, 12f, W.Muted, FontWeight.Medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = rd(22f), vertical = rd(8f)),
                maxLines = 3)
        }

        if (linkedMint == null && purchasedTokens.isEmpty() && scannedTokens.isEmpty()) {
            CommunityEmptyState(Modifier.weight(1f))
        } else {
            val origin = tokenOrigin ?: if (localDraft != null) {
                CommunityTokenOrigin.CONFIRMED_LAUNCH
            } else CommunityTokenOrigin.LINKED_SCAN
            val launchedMint = linkedMint?.takeIf { origin != CommunityTokenOrigin.LINKED_SCAN }
            val linkedOnlyMint = linkedMint?.takeIf { origin == CommunityTokenOrigin.LINKED_SCAN }
            val purchasedRows = purchasedTokens.filterNot { it.mintAddress == linkedMint }
            val purchasedMints = purchasedRows.mapTo(HashSet<String>()) { it.mintAddress }
            val scannedRows = scannedTokens.filterNot {
                it.mintAddress == linkedMint || it.mintAddress in purchasedMints
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = rd(22f), end = rd(22f), bottom = rd(120f)),
            ) {
                if (launchedMint != null) {
                    Spacer(Modifier.height(rd(12f)))
                    RefText(
                        "Launched token", 15f, P.Muted, FontWeight.Normal,
                        Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(rd(9f)))
                    CommunityLaunchedTokenRow(
                        mint = launchedMint,
                        draft = localDraft,
                        origin = origin,
                        onOpenTokenDetail = onOpenTokenDetail,
                        onLocalTokenRemove = onLocalTokenRemove,
                        onLocalTokenImageChosen = onLocalTokenImageChosen,
                    )
                }
                if (linkedOnlyMint != null) {
                    RefText("Linked token", 15f, P.Muted, FontWeight.Normal,
                        Modifier.semantics { heading() })
                    Spacer(Modifier.height(rd(9f)))
                    CommunityLinkedMintCard(
                        mint = linkedOnlyMint,
                        draft = localDraft,
                        origin = origin,
                        onOpenTokenDetail = onOpenTokenDetail,
                        onRescan = ::scanToken,
                        onLocalTokenRemove = onLocalTokenRemove,
                        onLocalTokenImageChosen = onLocalTokenImageChosen,
                    )
                }
                if (purchasedRows.isNotEmpty() || scannedRows.isNotEmpty()) {
                    if (linkedMint != null) Spacer(Modifier.height(rd(28f)))
                    RefText("Community tokens", 15f, P.Muted, FontWeight.Normal,
                        Modifier.semantics { heading() })
                    Spacer(Modifier.height(rd(9f)))
                    purchasedRows.forEach { token ->
                        CommunityPurchasedTokenCard(token) { onOpenScannedToken(token.mintAddress) }
                    }
                    scannedRows.forEach { token ->
                        CommunityScannedTokenCard(
                            token = token,
                            onOpen = { onOpenScannedToken(token.mintAddress) },
                            onRemove = { onScannedTokenRemove(token.mintAddress) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen Solana-only launch flow. All side effects are emitted as [LaunchTokenAction];
 * transaction preparation, signing, confirmation, and persistence stay outside Compose.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun LaunchTokenFlow(
    onClose: () -> Unit,
    onFinished: (LaunchTokenDraft) -> Unit = {},
    initialDraft: LaunchTokenDraft = LaunchTokenDraft(),
    executionState: LaunchTokenExecutionState = LaunchTokenExecutionState(),
    onAction: (LaunchTokenAction) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var stepOrdinal by rememberSaveable { mutableStateOf(LaunchTokenStep.IMAGE.ordinal) }
    var draft by rememberSaveable(stateSaver = LaunchTokenDraftSaver) { mutableStateOf(initialDraft) }
    var validationMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val step = LaunchTokenStep.entries[stepOrdinal.coerceIn(0, LaunchTokenStep.entries.lastIndex)]
    val keyboard = LocalSoftwareKeyboardController.current
    val isEditing = executionState.phase == LaunchTokenExecutionPhase.EDITING

    fun changeStep(next: LaunchTokenStep) {
        keyboard?.hide()
        validationMessage = null
        stepOrdinal = next.ordinal
    }
    fun closeFlow() {
        if (executionState.phase in setOf(
                LaunchTokenExecutionPhase.EDITING,
                LaunchTokenExecutionPhase.PREPARING,
                LaunchTokenExecutionPhase.SIGNING,
                LaunchTokenExecutionPhase.FAILED,
            )
        ) onAction(LaunchTokenAction.Cancel)
        onClose()
    }
    fun goBack() {
        when (executionState.phase) {
            LaunchTokenExecutionPhase.EDITING -> previousLaunchTokenStep(step)?.let(::changeStep) ?: closeFlow()
            LaunchTokenExecutionPhase.FAILED -> onAction(LaunchTokenAction.Edit)
            else -> closeFlow()
        }
    }

    BackHandler(onBack = ::goBack)

    BoxWithConstraints(modifier.fillMaxSize().background(W.Background)) {
        val compact = maxHeight < rd(700f)
        val waitingForLaunch = executionState.phase in setOf(
            LaunchTokenExecutionPhase.PREPARING,
            LaunchTokenExecutionPhase.SIGNING,
            LaunchTokenExecutionPhase.CONFIRMING,
        )
        Column(Modifier.fillMaxSize().imePadding()) {
            LaunchFlowHeader(
                title = launchFlowTitle(executionState.phase),
                showBack = isEditing || executionState.phase == LaunchTokenExecutionPhase.FAILED,
                onBack = ::goBack,
                onClose = ::closeFlow,
            )
            if (waitingForLaunch) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = rd(24f)),
                    contentAlignment = Alignment.Center,
                ) {
                    when (executionState.phase) {
                        LaunchTokenExecutionPhase.PREPARING -> LaunchWaitingStep(
                            title = "Preparing launch",
                            message = executionState.message ?: "Building your Solana transaction.",
                        )
                        LaunchTokenExecutionPhase.SIGNING -> LaunchWaitingStep(
                            title = "Approve in Privy",
                            message = executionState.message ?: "Review and approve the transaction.",
                        )
                        LaunchTokenExecutionPhase.CONFIRMING -> LaunchWaitingStep(
                            title = "Confirming launch",
                            message = executionState.message ?: "Waiting for Solana confirmation.",
                        )
                        else -> Unit
                    }
                }
            } else Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = rd(24f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(rd(if (compact) 14f else 32f)))
                when (executionState.phase) {
                    LaunchTokenExecutionPhase.EDITING -> when (step) {
                        LaunchTokenStep.IMAGE -> LaunchImageStep(
                            draft = draft,
                            onImage = { draft = draft.copy(imageUri = it); validationMessage = null },
                            validationMessage = validationMessage,
                            compact = compact,
                        )
                        LaunchTokenStep.IDENTITY -> LaunchIdentityStep(
                            draft = draft,
                            onDraftChange = { draft = it; validationMessage = null },
                            validationMessage = validationMessage,
                        )
                        LaunchTokenStep.DESCRIPTION -> LaunchDescriptionStep(
                            draft = draft,
                            onDraftChange = { draft = it; validationMessage = null },
                            validationMessage = validationMessage,
                        )
                        LaunchTokenStep.DETAILS -> LaunchDetailsStep(
                            draft = draft,
                            onDraftChange = { draft = it; validationMessage = null },
                            validationMessage = validationMessage,
                        )
                        LaunchTokenStep.DBC -> LaunchDbcStep(
                            draft = draft,
                            onDraftChange = { draft = it; validationMessage = null },
                            validationMessage = validationMessage,
                        )
                    }
                    LaunchTokenExecutionPhase.PREPARING,
                    LaunchTokenExecutionPhase.SIGNING,
                    LaunchTokenExecutionPhase.CONFIRMING -> Unit
                    LaunchTokenExecutionPhase.CONFIRMED -> LaunchConfirmedStep(
                        draft = draft,
                        result = executionState.confirmed,
                        compact = compact,
                    )
                    LaunchTokenExecutionPhase.FAILED -> LaunchFailedStep(
                        message = executionState.message
                            ?: "The launch could not be completed. Your form values are still here.",
                        onEdit = { onAction(LaunchTokenAction.Edit) },
                    )
                }
                Spacer(Modifier.height(rd(24f)))
            }
            when (executionState.phase) {
                LaunchTokenExecutionPhase.EDITING -> LaunchFlowFooter(
                    step = step,
                    enabled = launchTokenFooterEnabled(step, draft),
                    onAction = {
                        val issue = if (step == LaunchTokenStep.DBC) {
                            launchTokenDraftValidationMessage(draft)
                        } else launchTokenValidationMessage(step, draft)
                        if (issue != null) validationMessage = issue
                        else if (step == LaunchTokenStep.DBC) {
                            keyboard?.hide()
                            onAction(LaunchTokenAction.Create(draft))
                        } else changeStep(nextLaunchTokenStep(step))
                    },
                )
                LaunchTokenExecutionPhase.PREPARING -> LaunchExecutionFooter(
                    label = "Preparing…",
                    contentDescription = "Preparing token launch",
                    enabled = false,
                ) {}
                LaunchTokenExecutionPhase.SIGNING -> LaunchExecutionFooter(
                    label = "Waiting for wallet…",
                    contentDescription = "Waiting for wallet approval",
                    enabled = false,
                ) {}
                LaunchTokenExecutionPhase.CONFIRMING -> LaunchExecutionFooter(
                    label = "Confirming…",
                    contentDescription = "Confirming token launch on Solana Mainnet",
                    enabled = false,
                ) {}
                LaunchTokenExecutionPhase.CONFIRMED -> Unit
                LaunchTokenExecutionPhase.FAILED -> LaunchExecutionFooter(
                    label = "Try again",
                    contentDescription = "Retry token launch preparation",
                    enabled = true,
                ) { onAction(LaunchTokenAction.Retry) }
            }
        }
    }
}

@Composable
private fun CommunityEmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(bottom = rd(120f)), contentAlignment = Alignment.Center) {
        SquareLogo(136f, .18f)
    }
}

/** A launched token reads as one tappable market row, with no separate detail card. */
@Composable
private fun CommunityLaunchedTokenRow(
    mint: String,
    draft: LaunchTokenDraft?,
    origin: CommunityTokenOrigin,
    onOpenTokenDetail: () -> Unit,
    onLocalTokenRemove: () -> Unit,
    onLocalTokenImageChosen: (String) -> Boolean,
) {
    val removable = origin == CommunityTokenOrigin.LOCAL_UI
    val isSpxtr = mint == tokenLaunchUiPreviewResult().mintAddress
    val displayName = if (isSpxtr) TOKEN_LAUNCH_UI_PREVIEW_NAME
        else draft?.name?.takeIf(String::isNotBlank) ?: "Creator token"
    val displaySymbol = if (isSpxtr) TOKEN_LAUNCH_UI_PREVIEW_SYMBOL
        else draft?.symbol?.takeIf(String::isNotBlank) ?: shortCommunityAddress(mint)
    val currentOpen by rememberUpdatedState(onOpenTokenDetail)
    val currentRemove by rememberUpdatedState(onLocalTokenRemove)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageError by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            imageError = null
            try {
                val savedUri = TokenLaunchArtworkStore.importPickedImage(context, uri)
                if (!onLocalTokenImageChosen(savedUri)) imageError = "Couldn't save image. Try again."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                imageError = "Couldn't use that image. Choose another."
            } finally {
                importing = false
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(rd(70f)), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).height(rd(70f))
                    .then(if (removable) Modifier.pointerInput(mint) {
                        detectPreviewRowPress(onTap = { currentOpen() }, onHold = { currentRemove() })
                    } else Modifier.clickable(role = Role.Button, onClick = onOpenTokenDetail))
                    .semantics {
                        contentDescription = "Open $displayName token details"
                        if (removable) {
                            onClick(label = "Open token details") { onOpenTokenDetail(); true }
                            onLongClick(label = "Clear local UI-flow data") {
                                onLocalTokenRemove()
                                true
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSpxtr) Box(
                    Modifier.size(rd(44f)).clip(RoundedCornerShape(rd(11f))),
                ) { SpxtrArtwork(Modifier.fillMaxSize()) }
                else TokenArtwork(draft?.imageUri, displayName, 44f)
                Spacer(Modifier.width(rd(12f)))
                Column {
                    RefText(displaySymbol, 18f, W.White, FontWeight.SemiBold, maxLines = 1)
                    RefText(displayName, 15f, W.Muted, maxLines = 1)
                }
            }
            if (removable && !isSpxtr) {
                Box(
                    Modifier.size(rd(38f)).clip(CircleShape).background(W.Circle)
                        .clickable(enabled = !importing, role = Role.Button) {
                            imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        .semantics { contentDescription = "Change $displayName artwork" },
                    contentAlignment = Alignment.Center,
                ) { RefIcon("edit", 16f, W.Muted) }
            }
        }
        imageError?.let {
            RefText(it, 12f, P.Pink, FontWeight.Medium, Modifier.padding(bottom = rd(8f)))
        }
    }
}

@Composable
private fun CommunityPurchasedTokenCard(token: CommunityPurchasedToken, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(rd(70f))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Open token ${shortCommunityAddress(token.mintAddress)} details" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(rd(44f)).clip(RoundedCornerShape(rd(11f))).background(W.Cream),
            contentAlignment = Alignment.Center) { SolanaLogo(34f) }
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText("Meteora token", 18f, W.White, FontWeight.SemiBold, maxLines = 1)
            RefText(shortCommunityAddress(token.mintAddress), 15f, W.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun CommunityScannedTokenCard(
    token: CommunityScannedTokenPurchase,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val isSpxtr = token.mintAddress == tokenLaunchUiPreviewResult().mintAddress
    val displayName = if (isSpxtr) TOKEN_LAUNCH_UI_PREVIEW_NAME
        else token.name ?: "Creator token"
    val displaySymbol = if (isSpxtr) TOKEN_LAUNCH_UI_PREVIEW_SYMBOL else token.symbol
    val currentOpen by rememberUpdatedState(onOpen)
    val currentRemove by rememberUpdatedState(onRemove)
    Row(
        Modifier.fillMaxWidth().height(rd(70f))
            .pointerInput(token.mintAddress) {
                detectPreviewRowPress(onTap = { currentOpen() }, onHold = { currentRemove() })
            }
            .semantics {
                contentDescription = "Open $displayName details"
                onClick(label = "Open token details") { currentOpen(); true }
                onLongClick(label = "Clear local UI-flow data") { currentRemove(); true }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var artworkLoaded by remember(token.imageUrl) { mutableStateOf(false) }
        Box(Modifier.size(rd(44f)).clip(RoundedCornerShape(rd(11f))).background(W.Cream),
            contentAlignment = Alignment.Center) {
            if (isSpxtr) SpxtrArtwork(Modifier.fillMaxSize())
            else if (!artworkLoaded) SolanaLogo(34f)
            token.imageUrl?.takeUnless { isSpxtr }?.let { imageUrl ->
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "$displayName artwork",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onSuccess = { artworkLoaded = true },
                    onError = { artworkLoaded = false },
                )
            }
        }
        Spacer(Modifier.width(rd(12f)))
        Column(Modifier.weight(1f)) {
            RefText(displaySymbol ?: shortCommunityAddress(token.mintAddress),
                18f, W.White, FontWeight.SemiBold, maxLines = 1)
            RefText(displayName, 15f, W.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun CommunityLinkedMintCard(
    mint: String,
    draft: LaunchTokenDraft?,
    origin: CommunityTokenOrigin,
    onOpenTokenDetail: () -> Unit,
    onRescan: () -> Unit,
    onLocalTokenRemove: () -> Unit,
    onLocalTokenImageChosen: (String) -> Boolean,
) {
    val removable = origin == CommunityTokenOrigin.LOCAL_UI
    val isSpxtr = mint == tokenLaunchUiPreviewResult().mintAddress
    val opensDetail = isSpxtr || origin != CommunityTokenOrigin.LINKED_SCAN
    val currentOpen by rememberUpdatedState(onOpenTokenDetail)
    val currentRescan by rememberUpdatedState(onRescan)
    val currentRemove by rememberUpdatedState(onLocalTokenRemove)
    val context = LocalContext.current
    var imageError by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val permissionGranted = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            imageError = when {
                !permissionGranted -> "Couldn't access image. Choose another."
                !onLocalTokenImageChosen(uri.toString()) -> "Couldn't save image. Try again."
                else -> null
            }
        }
    }
    val displayName = when {
        isSpxtr -> TOKEN_LAUNCH_UI_PREVIEW_NAME
        origin != CommunityTokenOrigin.LINKED_SCAN && !draft?.name.isNullOrBlank() ->
            requireNotNull(draft).name
        origin == CommunityTokenOrigin.CONFIRMED_LAUNCH -> "Confirmed Solana token"
        else -> "Linked token"
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(rd(70f))
                .then(if (removable) {
                    Modifier.pointerInput(mint) {
                        detectPreviewRowPress(
                            onTap = { if (opensDetail) currentOpen() else currentRescan() },
                            onHold = { currentRemove() },
                        )
                    }.semantics {
                        onClick(label = if (opensDetail) "Open token details" else "Scan token QR code") {
                            if (opensDetail) onOpenTokenDetail() else onRescan()
                            true
                        }
                        onLongClick(label = "Clear local UI-flow data") {
                            onLocalTokenRemove()
                            true
                        }
                    }
                } else Modifier.clickable(role = Role.Button) {
                    if (opensDetail) onOpenTokenDetail() else onRescan()
                })
                .semantics { contentDescription = if (opensDetail)
                    "Open $displayName details" else "Scan another Solana token QR code" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSpxtr) {
                Box(Modifier.size(rd(44f)).clip(RoundedCornerShape(rd(11f)))) {
                    SpxtrArtwork(Modifier.fillMaxSize())
                }
            } else if (origin == CommunityTokenOrigin.LINKED_SCAN || draft == null) {
                Box(
                    Modifier.size(rd(44f)).clip(RoundedCornerShape(rd(11f))).background(W.Cream),
                    contentAlignment = Alignment.Center,
                ) { SolanaLogo(34f) }
            } else {
                Box(
                    Modifier.size(rd(44f))
                        .then(if (removable) Modifier.clickable(role = Role.Button) {
                            imageError = null
                            imagePicker.launch(arrayOf("image/*"))
                        }.semantics {
                            contentDescription = if (draft.imageUri.isNullOrBlank())
                                "Add image for $displayName" else "Change image for $displayName"
                        } else Modifier),
                ) {
                    TokenArtwork(draft.imageUri, displayName, 44f)
                    if (removable) {
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(rd(20f))
                                .background(W.Cream, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { RefIcon(if (draft.imageUri.isNullOrBlank()) "plus" else "edit", 12f, W.Ink) }
                    }
                }
            }
            Spacer(Modifier.width(rd(12f)))
            Column(Modifier.weight(1f)) {
                RefText(
                    if (isSpxtr) {
                        TOKEN_LAUNCH_UI_PREVIEW_SYMBOL
                    } else if (origin != CommunityTokenOrigin.LINKED_SCAN && !draft?.symbol.isNullOrBlank()) {
                        requireNotNull(draft).symbol
                    } else shortCommunityAddress(mint),
                    18f, W.White, FontWeight.SemiBold, maxLines = 1,
                )
                RefText(displayName, 15f, W.Muted, maxLines = 1)
            }
            if (!opensDetail) {
                Box(
                    Modifier.size(rd(34f)).clickable(role = Role.Button, onClick = onRescan)
                        .semantics { contentDescription = "Scan another Solana token QR code" },
                    contentAlignment = Alignment.Center,
                ) { RefIcon("scan", 20f, W.White) }
            }
        }
        if (removable) imageError?.let {
            RefText(
                it, 12f, P.Pink, FontWeight.Medium,
                Modifier.padding(bottom = rd(12f)),
            )
        }
    }
}

private fun shortCommunityAddress(address: String): String =
    if (address.length <= 11) address else "${address.take(4)}...${address.takeLast(4)}"

private fun launchFlowTitle(phase: LaunchTokenExecutionPhase): String = when (phase) {
    LaunchTokenExecutionPhase.EDITING -> "Launch a token"
    LaunchTokenExecutionPhase.PREPARING -> "Preparing launch"
    LaunchTokenExecutionPhase.SIGNING -> "Wallet signature"
    LaunchTokenExecutionPhase.CONFIRMING -> "Confirming launch"
    LaunchTokenExecutionPhase.CONFIRMED -> "Token launched"
    LaunchTokenExecutionPhase.FAILED -> "Launch interrupted"
}

@Composable
private fun LaunchFlowHeader(
    title: String,
    showBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(rd(72f)).padding(horizontal = rd(22f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) RoundIconButton("Back", onBack) { RefIcon("back", 22f, W.White) }
        else Spacer(Modifier.size(rd(46f)))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            RefText(title, 20f, W.White, FontWeight.SemiBold, Modifier.semantics { heading() })
        }
        RoundIconButton("Close token launch", onClose) {
            RefText("×", 30f, W.White, FontWeight.Normal)
        }
    }
}

@Composable
private fun RoundIconButton(
    description: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(rd(46f)).clip(CircleShape).background(W.Panel)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun LaunchImageStep(
    draft: LaunchTokenDraft,
    onImage: (String) -> Unit,
    validationMessage: String?,
    compact: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                try {
                    onImage(TokenLaunchArtworkStore.importPickedImage(context, uri))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(context, "Couldn't use that image. Choose another.", Toast.LENGTH_SHORT).show()
                } finally {
                    importing = false
                }
            }
        }
    }
    val chooseImage = {
        if (!importing) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    RefText("Choose token artwork", 26f, W.White, FontWeight.SemiBold)
    Spacer(Modifier.height(rd(if (compact) 20f else 44f)))
    val dashLength = rd(9f)
    val dashGap = rd(7f)
    val outlineRadius = rd(34f)
    val outlineWidth = rd(1.5f)
    Box(
        Modifier.size(rd(if (compact) 210f else 246f)).clip(RoundedCornerShape(rd(34f)))
            .background(W.Panel)
            .drawBehind {
                if (draft.imageUri == null) drawRoundRect(
                    color = W.CardMuted,
                    cornerRadius = CornerRadius(outlineRadius.toPx(), outlineRadius.toPx()),
                    style = Stroke(
                        width = outlineWidth.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength.toPx(), dashGap.toPx())),
                    ),
                )
            }
            .clickable(role = Role.Button, onClick = chooseImage)
            .semantics { contentDescription = if (draft.imageUri == null) "Choose token image" else "Change token image" },
        contentAlignment = Alignment.Center,
    ) {
        if (draft.imageUri == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RefIcon("plus", 38f, W.White)
                Spacer(Modifier.height(rd(10f)))
                RefText("Add image", 15f, W.Muted, FontWeight.Medium)
            }
        } else {
            SavedTokenArtwork(
                uri = draft.imageUri,
                contentDescription = "Selected token artwork",
                modifier = Modifier.fillMaxSize(),
                onMissing = {
                    RefText("Couldn't display artwork", 14f, W.Muted, FontWeight.Medium)
                },
            )
            Box(
                Modifier.align(Alignment.BottomEnd).padding(rd(12f)).size(rd(42f))
                    .background(W.Background.copy(alpha = .72f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { RefIcon("edit", 18f, W.White) }
        }
    }
    validationMessage?.let { InlineLaunchError(it) }
    Spacer(Modifier.height(rd(16f)))
    CommunityButton(
        label = if (importing) "Adding image…" else if (draft.imageUri == null) "Choose from photos" else "Choose another image",
        background = W.Panel,
        foreground = W.White,
        contentDescription = if (draft.imageUri == null) "Choose token image from photos" else "Replace token image",
    ) { chooseImage() }
}

@Composable
private fun LaunchIdentityStep(
    draft: LaunchTokenDraft,
    onDraftChange: (LaunchTokenDraft) -> Unit,
    validationMessage: String?,
) {
    TokenStepArtwork(draft)
    Spacer(Modifier.height(rd(30f)))
    RefText("Name your token", 26f, W.White, FontWeight.SemiBold, Modifier.fillMaxWidth())
    Spacer(Modifier.height(rd(20f)))
    LaunchTextField(
        label = "Token name",
        value = draft.name,
        placeholder = "Race",
        helper = "${draft.name.toByteArray(Charsets.UTF_8).size}/32 bytes",
        error = validationMessage?.takeIf { it.contains("name", ignoreCase = true) },
        capitalization = KeyboardCapitalization.Words,
        imeAction = ImeAction.Next,
        onValueChange = { onDraftChange(draft.copy(name = truncateLaunchTokenName(it))) },
    )
    Spacer(Modifier.height(rd(18f)))
    LaunchTextField(
        label = "Symbol",
        value = draft.symbol,
        placeholder = "RACE",
        prefix = "$",
        helper = "1–10 letters or numbers",
        error = validationMessage?.takeIf { it.contains("Symbol", ignoreCase = true) },
        capitalization = KeyboardCapitalization.Characters,
        imeAction = ImeAction.Done,
        onValueChange = { onDraftChange(draft.copy(symbol = normalizeLaunchTokenSymbol(it))) },
    )
}

@Composable
private fun LaunchDescriptionStep(
    draft: LaunchTokenDraft,
    onDraftChange: (LaunchTokenDraft) -> Unit,
    validationMessage: String?,
) {
    TokenStepArtwork(draft)
    Spacer(Modifier.height(rd(30f)))
    RefText("Tell people about it", 26f, W.White, FontWeight.SemiBold, Modifier.fillMaxWidth())
    Spacer(Modifier.height(rd(20f)))
    LaunchTextField(
        label = "Description",
        value = draft.description,
        placeholder = "A community token for…",
        helper = "${draft.description.length}/280",
        error = validationMessage,
        singleLine = false,
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = ImeAction.Default,
        onValueChange = { onDraftChange(draft.copy(description = it.take(280))) },
    )
}

@Composable
private fun LaunchDetailsStep(
    draft: LaunchTokenDraft,
    onDraftChange: (LaunchTokenDraft) -> Unit,
    validationMessage: String?,
) {
    TokenStepArtwork(draft)
    Spacer(Modifier.height(rd(24f)))
    RefText("Launch details", 26f, W.White, FontWeight.SemiBold, Modifier.fillMaxWidth())
    Spacer(Modifier.height(rd(18f)))
    FixedSolanaRoute()
    Spacer(Modifier.height(rd(22f)))
    LaunchTextField(
        label = "X profile · optional",
        value = draft.xHandle,
        placeholder = "handle",
        prefix = "x.com/",
        error = validationMessage?.takeIf { it.contains("X handle") },
        imeAction = ImeAction.Next,
        onValueChange = { onDraftChange(draft.copy(xHandle = normalizeLaunchSocialHandle(it, "x.com"))) },
    )
    Spacer(Modifier.height(rd(16f)))
    LaunchTextField(
        label = "Telegram · optional",
        value = draft.telegramHandle,
        placeholder = "handle",
        prefix = "t.me/",
        error = validationMessage?.takeIf { it.contains("Telegram") },
        imeAction = ImeAction.Next,
        onValueChange = { onDraftChange(draft.copy(telegramHandle = normalizeLaunchSocialHandle(it, "t.me"))) },
    )
    Spacer(Modifier.height(rd(16f)))
    LaunchTextField(
        label = "Website · optional",
        value = draft.websiteUrl,
        placeholder = "https://website.com",
        error = validationMessage?.takeIf { it.contains("Website") },
        keyboardType = KeyboardType.Uri,
        imeAction = ImeAction.Done,
        onValueChange = { onDraftChange(draft.copy(websiteUrl = it.trim().take(200))) },
    )
}

@Composable
private fun LaunchDbcStep(
    draft: LaunchTokenDraft,
    onDraftChange: (LaunchTokenDraft) -> Unit,
    validationMessage: String?,
) {
    var customizeCurve by rememberSaveable { mutableStateOf(false) }
    val marketCapCurve = launchUsesMarketCapCurve(draft)
    TokenStepArtwork(draft)
    Spacer(Modifier.height(rd(24f)))
    RefText("Review your launch", 26f, W.White, FontWeight.SemiBold, Modifier.fillMaxWidth())
    Spacer(Modifier.height(rd(18f)))
    LaunchDbcReview(draft, marketCapCurve)
    Spacer(Modifier.height(rd(16f)))
    CommunityButton(
        label = if (customizeCurve) "Hide settings" else "Curve & token settings",
        background = W.Panel,
        foreground = W.White,
        contentDescription = if (customizeCurve) "Hide Meteora curve and token settings"
            else "Edit Meteora curve and token settings",
        modifier = Modifier.fillMaxWidth(),
    ) { customizeCurve = !customizeCurve }
    if (customizeCurve) {
        LaunchCurveSettings(draft, marketCapCurve, validationMessage, onDraftChange)
        LaunchFeeSettings(draft, validationMessage, onDraftChange)
        LaunchTokenVestingSettings(draft, validationMessage, onDraftChange)
    }
    validationMessage?.takeIf { message ->
        !customizeCurve || listOf("Total supply", "Trading fee", "Graduation target", "Supply on migration",
            "market cap", "Vested supply", "Vesting duration", "curve shape", "bonding-curve trading")
            .none(message::contains)
    }?.let { InlineLaunchError(it) }
}

@Composable
private fun LaunchDbcReview(draft: LaunchTokenDraft, marketCapCurve: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        ReviewRow("Network", LAUNCH_NETWORK)
        ReviewDivider()
        ReviewRow("Curve", "Meteora DBC")
        ReviewDivider()
        ReviewRow("Price path", draft.curvePreset)
        ReviewDivider()
        ReviewRow("Total supply", launchTokenSupplyReview(draft.totalSupply))
        ReviewDivider()
        ReviewRow("Trading fee", "${draft.feeBps} bps")
        ReviewDivider()
        if (marketCapCurve) {
            ReviewRow("Market cap", "${draft.initialMarketCapSol} → ${draft.graduationMarketCapSol} SOL")
        } else if (draft.initialMarketCapSol.isNotBlank() || draft.graduationMarketCapSol.isNotBlank()) {
            ReviewRow("Market cap", "Set both values")
        } else {
            ReviewRow("Quote target", "${draft.migrationThresholdSol} SOL")
        }
        ReviewDivider()
        ReviewRow("Supply to pool", "${draft.supplyOnMigrationPercent}%")
        ReviewDivider()
        ReviewRow("Migration", "DAMM v2 after curve completion", maxLines = 2)
        if (draft.launchProtectionEnabled) {
            ReviewDivider()
            ReviewRow("Early fees", "Linear · 10% → ${launchFeePercent(draft.feeBps)} · 10 min", maxLines = 2)
        }
        if (draft.vestCreatorTokens) {
            ReviewDivider()
            ReviewRow("Vesting", "${draft.vestedSupplyPercent}% · ${draft.vestingDurationMonths} mo · ${draft.vestingFrequency.lowercase(Locale.US)}", maxLines = 2)
        }
    }
}

@Composable
private fun LaunchCurveSettings(
    draft: LaunchTokenDraft,
    marketCapCurve: Boolean,
    validationMessage: String?,
    onDraftChange: (LaunchTokenDraft) -> Unit,
) {
    Spacer(Modifier.height(rd(20f)))
    RefText("Curve", 18f, W.White, FontWeight.SemiBold, Modifier.fillMaxWidth())
    Spacer(Modifier.height(rd(14f)))
    RefText("Price path", 14f, W.Muted, FontWeight.Medium, Modifier.fillMaxWidth())
    Spacer(Modifier.height(rd(8f)))
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        LAUNCH_CURVE_PRESETS.chunked(2).forEachIndexed { index, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(8f))) {
                pair.forEach { preset ->
                    LaunchCurvePresetCard(
                        preset = preset,
                        selected = draft.curvePreset == preset,
                        modifier = Modifier.weight(1f),
                        onSelect = { onDraftChange(draft.copy(curvePreset = preset)) },
                    )
                }
            }
            if (index < 1) Spacer(Modifier.height(rd(8f)))
        }
    }
    Spacer(Modifier.height(rd(16f)))
    LaunchTextField(
        label = "Starting market cap",
        value = draft.initialMarketCapSol,
        placeholder = "e.g. 10",
        suffix = "SOL",
        helper = "Set both caps for a range; leave both empty for the SOL target.",
        error = validationMessage?.takeIf { it.contains("Starting market cap") },
        keyboardType = KeyboardType.Decimal,
        onValueChange = { value ->
            onDraftChange(draft.copy(initialMarketCapSol = normalizeLaunchDecimal(value, 8, 3)))
        },
    )
    Spacer(Modifier.height(rd(16f)))
    LaunchTextField(
        label = "Graduation market cap",
        value = draft.graduationMarketCapSol,
        placeholder = "e.g. 100",
        suffix = "SOL",
        error = validationMessage?.takeIf { it.contains("Graduation market cap") },
        keyboardType = KeyboardType.Decimal,
        onValueChange = { value ->
            onDraftChange(draft.copy(graduationMarketCapSol = normalizeLaunchDecimal(value, 8, 3)))
        },
    )
    if (!marketCapCurve) {
        Spacer(Modifier.height(rd(16f)))
        LaunchTextField(
            label = "Graduation target",
            value = draft.migrationThresholdSol,
            placeholder = "10",
            suffix = "SOL",
            helper = "SOL in pool · 10 SOL uses Meteora migration keepers",
            error = validationMessage?.takeIf { it.contains("Graduation target") },
            keyboardType = KeyboardType.Decimal,
            onValueChange = { value ->
                onDraftChange(draft.copy(migrationThresholdSol = normalizeLaunchDecimal(value, 6, 6)))
            },
        )
    }
}

@Composable
private fun LaunchCurvePresetCard(
    preset: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
) {
    val shape = RoundedCornerShape(rd(17f))
    val description = when (preset) {
        "Flat" -> "Gradual"
        "Exponential" -> "Accelerating"
        "Long" -> "Extended"
        else -> "Smooth"
    }
    Column(
        modifier.height(rd(108f))
            .clip(shape)
            .background(if (selected) W.Panel else W.Panel.copy(alpha = .72f))
            .border(rd(1f), if (selected) P.Lime else W.Circle, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .semantics {
                contentDescription = "$preset price path"
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .padding(horizontal = rd(14f), vertical = rd(11f)),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        RefText(preset, 15f, W.White, FontWeight.SemiBold, Modifier.fillMaxWidth())
        CurveShapeMark(preset, selected, Modifier.fillMaxWidth().height(rd(29f)))
        RefText(description, 12f, W.Muted, modifier = Modifier.fillMaxWidth())
    }
}

/** A qualitative shape cue, not a price projection or an exact DBC curve plot. */
@Composable
private fun CurveShapeMark(preset: String, selected: Boolean, modifier: Modifier = Modifier) {
    val strokeWidth = rd(2.4f)
    Canvas(modifier) {
        val left = size.width * .04f
        val right = size.width * .96f
        val width = right - left
        val height = size.height
        val path = Path().apply {
            when (preset) {
                "Flat" -> {
                    moveTo(left, height * .67f)
                    cubicTo(left + width * .3f, height * .66f, left + width * .7f, height * .54f, right, height * .52f)
                }
                "Exponential" -> {
                    moveTo(left, height * .84f)
                    cubicTo(left + width * .6f, height * .84f, left + width * .82f, height * .64f, right, height * .13f)
                }
                "Long" -> {
                    moveTo(left, height * .76f)
                    cubicTo(left + width * .48f, height * .74f, left + width * .78f, height * .48f, right, height * .26f)
                }
                else -> {
                    moveTo(left, height * .79f)
                    cubicTo(left + width * .28f, height * .7f, left + width * .7f, height * .28f, right, height * .2f)
                }
            }
        }
        drawPath(
            path,
            color = if (selected) P.Lime else W.Muted,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun LaunchFeeSettings(
    draft: LaunchTokenDraft,
    validationMessage: String?,
    onDraftChange: (LaunchTokenDraft) -> Unit,
) {
    Spacer(Modifier.height(rd(24f)))
    RefText("Fees", 18f, W.White, FontWeight.SemiBold, Modifier.fillMaxWidth())
    Spacer(Modifier.height(rd(16f)))
    LaunchTextField(
        label = "Trading fee",
        value = draft.feeBps,
        placeholder = "100",
        suffix = "bps",
        helper = "25–1,000 bps · 100 = 1%",
        error = validationMessage?.takeIf { it.contains("Trading fee") },
        keyboardType = KeyboardType.Number,
        onValueChange = { value ->
            onDraftChange(draft.copy(feeBps = value.filter(Char::isDigit).take(4)))
        },
    )
    Spacer(Modifier.height(rd(16f)))
    LaunchSettingToggle(
        label = "Early fee schedule",
        subtitle = "Linear · 10% to trading fee over 10 min",
        checked = draft.launchProtectionEnabled,
        onCheckedChange = { onDraftChange(draft.copy(launchProtectionEnabled = it)) },
    )
}

@Composable
private fun LaunchTokenVestingSettings(
    draft: LaunchTokenDraft,
    validationMessage: String?,
    onDraftChange: (LaunchTokenDraft) -> Unit,
) {
    Spacer(Modifier.height(rd(24f)))
    RefText("Tokens", 18f, W.White, FontWeight.SemiBold, Modifier.fillMaxWidth())
    Spacer(Modifier.height(rd(16f)))
    LaunchTextField(
        label = "Total supply",
        value = draft.totalSupply,
        placeholder = "1000000000",
        helper = "1M–1T tokens",
        error = validationMessage?.takeIf { it.contains("Total supply") },
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Next,
        onValueChange = { value ->
            onDraftChange(draft.copy(totalSupply = value.filter(Char::isDigit).take(13)))
        },
    )
    Spacer(Modifier.height(rd(16f)))
    LaunchTextField(
        label = "Supply to DAMM v2",
        value = draft.supplyOnMigrationPercent,
        placeholder = "20",
        suffix = "%",
        helper = "1–90%",
        error = validationMessage?.takeIf { it.contains("Supply on migration") },
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done,
        onValueChange = { value ->
            onDraftChange(draft.copy(supplyOnMigrationPercent = value.filter(Char::isDigit).take(2)))
        },
    )
    Spacer(Modifier.height(rd(16f)))
    LaunchSettingToggle(
        label = "Lock creator tokens",
        checked = draft.vestCreatorTokens,
        onCheckedChange = { onDraftChange(draft.copy(vestCreatorTokens = it)) },
    )
    if (draft.vestCreatorTokens) {
        Spacer(Modifier.height(rd(16f)))
        LaunchTextField(
            label = "Vested supply",
            value = draft.vestedSupplyPercent,
            placeholder = "10",
            suffix = "%",
            helper = "Of total supply",
            error = validationMessage?.takeIf { it.contains("Vested supply") },
            keyboardType = KeyboardType.Number,
            onValueChange = { value ->
                onDraftChange(draft.copy(vestedSupplyPercent = value.filter(Char::isDigit).take(3)))
            },
        )
        Spacer(Modifier.height(rd(16f)))
        LaunchTextField(
            label = "Vesting duration",
            value = draft.vestingDurationMonths,
            placeholder = "12",
            suffix = "months",
            helper = "1–120 months",
            error = validationMessage?.takeIf { it.contains("Vesting duration") },
            keyboardType = KeyboardType.Number,
            onValueChange = { value ->
                onDraftChange(draft.copy(vestingDurationMonths = value.filter(Char::isDigit).take(3)))
            },
        )
        Spacer(Modifier.height(rd(16f)))
        RefText("Release frequency", 14f, W.Muted, FontWeight.Medium, Modifier.fillMaxWidth())
        Spacer(Modifier.height(rd(8f)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(rd(8f))) {
            LAUNCH_VESTING_FREQUENCIES.forEach { frequency ->
                val selected = draft.vestingFrequency == frequency
                CommunityButton(
                    label = frequency,
                    background = if (selected) W.White else W.Panel,
                    foreground = if (selected) W.Ink else W.White,
                    contentDescription = "Release $frequency",
                    modifier = Modifier.weight(1f),
                ) { onDraftChange(draft.copy(vestingFrequency = frequency)) }
            }
        }
    }
}

internal fun launchTokenSupplyReview(value: String): String =
    if (value == "1000000000") "1B tokens" else "$value tokens"

internal fun normalizeLaunchDecimal(value: String, integerDigits: Int, fractionDigits: Int): String {
    val filtered = buildString {
        var hasDot = false
        value.forEach { character ->
            when {
                character.isDigit() -> append(character)
                character == '.' && !hasDot -> {
                    append(character)
                    hasDot = true
                }
            }
        }
    }
    val parts = filtered.split('.', limit = 2)
    val integerPart = parts[0].take(integerDigits)
    return if (parts.size == 1) integerPart else "$integerPart.${parts[1].take(fractionDigits)}"
}

@Composable
private fun LaunchWaitingStep(title: String, message: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(rd(88f)).semantics { contentDescription = title },
        ) {
            val stroke = 6.dp.toPx()
            drawCircle(W.Panel, style = Stroke(stroke))
            drawArc(
                color = P.Lime,
                startAngle = -90f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Spacer(Modifier.height(rd(26f)))
        RefText(title, 28f, W.White, FontWeight.Bold)
        Spacer(Modifier.height(rd(10f)))
        BasicText(
            message,
            modifier = Modifier.fillMaxWidth(),
            style = TextStyle(
                color = W.Muted,
                fontSize = rs(15f),
                fontFamily = LocalReferenceFont.current,
                lineHeight = rs(19f),
                textAlign = TextAlign.Center,
            ),
            maxLines = 4,
        )
    }
}

@Composable
private fun LaunchFailedStep(message: String, onEdit: () -> Unit) {
    Spacer(Modifier.height(rd(44f)))
    Box(
        Modifier.size(rd(88f)).background(P.Pink.copy(alpha = .14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { RefText("!", 44f, P.Pink, FontWeight.Bold) }
    Spacer(Modifier.height(rd(24f)))
    RefText("Launch not completed", 28f, W.White, FontWeight.Bold)
    Spacer(Modifier.height(rd(10f)))
    RefText(message, 15f, W.Muted, modifier = Modifier.fillMaxWidth(), maxLines = 5)
    Spacer(Modifier.height(rd(24f)))
    CommunityButton(
        label = "Edit launch",
        background = W.Panel,
        foreground = W.White,
        contentDescription = "Edit token launch values",
        onClick = onEdit,
    )
}

@Composable
private fun LaunchConfirmedStep(
    draft: LaunchTokenDraft,
    result: ConfirmedTokenLaunch?,
    compact: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by rememberSaveable(result?.mintAddress) { mutableStateOf(false) }
    var copying by remember(result?.mintAddress) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_800)
            copied = false
        }
    }
    val mint = result?.mintAddress.orEmpty()
    val presentation = remember(draft.symbol, mint) {
        tokenLaunchConfirmationPresentation(draft, result)
    }
    val explorerDestination = remember(draft.symbol, mint, result?.signature, BuildConfig.MARKET_DATA_URL) {
        tokenLaunchExplorerDestination(draft, result, BuildConfig.MARKET_DATA_URL)
    }
    val qr = remember(mint, presentation.tokenLink) {
        runCatching {
            QrCodeMatrix.encodeAddress(
                if (presentation.tokenLink == TOKEN_LAUNCH_UI_PREVIEW_LINK) presentation.tokenLink else mint,
            )
        }.getOrNull()
    }
    val qrSize = if (compact) 216f else 254f

    Spacer(Modifier.height(rd(if (compact) 30f else 48f)))
    if (presentation.tokenLink == TOKEN_LAUNCH_UI_PREVIEW_LINK) {
        val artworkSize = if (compact) 82f else 102f
        Box(
            Modifier.size(rd(artworkSize))
                .clip(RoundedCornerShape(rd(artworkSize * .24f))),
        ) { SpxtrArtwork(Modifier.fillMaxSize()) }
    } else TokenArtwork(draft.imageUri, draft.name, if (compact) 82f else 102f)
    Spacer(Modifier.height(rd(14f)))
    RefText(presentation.ticker, 27f, W.White, FontWeight.Bold)
    Spacer(Modifier.height(rd(if (compact) 18f else 24f)))
    if (qr != null) {
        TokenMintQrCode(qr, draft.symbol, qrSize)
    } else {
        InlineLaunchError("QR code unavailable.")
    }
    Spacer(Modifier.height(rd(if (compact) 18f else 24f)))
    CommunityButton(
        label = when {
            copying -> "Copying…"
            copied -> "Copied"
            else -> "Copy QR Code"
        },
        background = if (qr != null && !copying) W.White else W.White.copy(alpha = .4f),
        foreground = W.Ink,
        contentDescription = when {
            copying -> "Copying QR code for ${draft.symbol} token"
            copied -> "QR code copied"
            else -> "Copy QR code for ${draft.symbol} token"
        },
        modifier = Modifier.width(rd(qrSize)),
        enabled = qr != null && !copying,
        large = true,
    ) {
        qr?.let { matrix ->
            scope.launch {
                copying = true
                try {
                    copyTokenLaunchQrCode(context, matrix)
                    copied = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(context, "Couldn't copy QR code", Toast.LENGTH_SHORT).show()
                } finally {
                    copying = false
                }
            }
        }
    }
    explorerDestination?.let { destination ->
        Spacer(Modifier.height(rd(12f)))
        Row(
            Modifier.width(rd(qrSize)).height(rd(46f))
                .clip(RoundedCornerShape(rd(23f)))
                .background(W.Panel)
                .clickable(role = Role.Button) {
                    if (!openTokenLaunchExplorer(context, destination)) {
                        Toast.makeText(context, "Token page unavailable", Toast.LENGTH_SHORT).show()
                    }
                }
                .semantics { contentDescription = destination.contentDescription },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefText(destination.contentDescription, if (compact) 12.5f else 14f,
                W.White, FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TokenMintQrCode(matrix: QrCodeMatrix, symbol: String, qrSize: Float) {
    val quiet = 4
    Box(
        Modifier.size(rd(qrSize)).clip(RoundedCornerShape(rd(28f))).background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = "QR code for ${symbol.ifBlank { "token" }} token"
        }) {
            val cells = matrix.size + quiet * 2
            val cell = size.minDimension / cells
            val drawnSize = cell * cells
            val origin = Offset((size.width - drawnSize) / 2f, (size.height - drawnSize) / 2f)
            for (y in 0 until matrix.size) for (x in 0 until matrix.size) {
                val finder = (x < 7 && y < 7) || (x >= matrix.size - 7 && y < 7) ||
                    (x < 7 && y >= matrix.size - 7)
                if (matrix[x, y] && !finder) {
                    if (x == 6 || y == 6 || (x in 32..36 && y in 32..36)) {
                        drawRect(
                            Color.Black,
                            origin + Offset((x + quiet) * cell, (y + quiet) * cell),
                            Size(cell, cell),
                        )
                    } else {
                        drawCircle(
                            Color.Black,
                            cell * .47f,
                            origin + Offset((x + quiet + .5f) * cell, (y + quiet + .5f) * cell),
                        )
                    }
                }
            }
            for ((x, y) in listOf(0 to 0, matrix.size - 7 to 0, 0 to matrix.size - 7)) {
                val corner = origin + Offset((x + quiet) * cell, (y + quiet) * cell)
                drawRoundRect(Color.Black, corner, Size(cell * 7, cell * 7), CornerRadius(cell * 3.5f))
                drawRoundRect(
                    Color.White,
                    corner + Offset(cell, cell),
                    Size(cell * 5, cell * 5),
                    CornerRadius(cell * 2.5f),
                )
                drawRoundRect(
                    Color.Black,
                    corner + Offset(cell * 2, cell * 2),
                    Size(cell * 3, cell * 3),
                    CornerRadius(cell * 1.5f),
                )
            }
        }
        Box(Modifier.size(rd(qrSize * .165f)).background(Color.White), contentAlignment = Alignment.Center) {
            BrandLogo(Modifier.size(rd(qrSize * .124f)), onLightBackground = true)
        }
    }
}

@Composable
private fun FixedSolanaRoute() {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(22f))).background(W.Panel)
            .padding(horizontal = rd(18f), vertical = rd(15f)),
    ) {
        RefText("Paired asset", 13f, W.Muted, FontWeight.Medium)
        Spacer(Modifier.height(rd(9f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SolanaLogo(38f)
            Spacer(Modifier.width(rd(12f)))
            RefText("SOL", 19f, W.White, FontWeight.SemiBold, Modifier.weight(1f))
            RefIcon("check", 19f, P.Lime)
        }
        Spacer(Modifier.height(rd(16f)))
        ReviewDivider()
        Spacer(Modifier.height(rd(16f)))
        RefText("Network", 13f, W.Muted, FontWeight.Medium)
        Spacer(Modifier.height(rd(9f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SolanaLogo(38f)
            Spacer(Modifier.width(rd(12f)))
            RefText("Solana Mainnet", 19f, W.White, FontWeight.SemiBold, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TokenStepArtwork(draft: LaunchTokenDraft) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TokenArtwork(draft.imageUri, draft.name, 58f)
        Spacer(Modifier.width(rd(13f)))
        Column(Modifier.weight(1f)) {
            RefText(draft.name.ifBlank { "New token" }, 17f, W.White, FontWeight.SemiBold)
            RefText(draft.symbol.takeIf(String::isNotBlank)?.let { "$$it" } ?: "Solana launch", 13f, W.Muted)
        }
    }
}

@Composable
private fun TokenArtwork(uri: String?, name: String, size: Float) {
    Box(
        Modifier.size(rd(size)).clip(RoundedCornerShape(rd(size * .24f))).background(W.Panel),
        contentAlignment = Alignment.Center,
    ) {
        val fallback: @Composable () -> Unit = {
            RefText(name.trim().take(1).uppercase().ifEmpty { "+" }, size * .36f, W.White, FontWeight.Bold)
        }
        if (uri == null) fallback() else SavedTokenArtwork(
            uri = uri,
            contentDescription = "${name.ifBlank { "Token" }} artwork",
            modifier = Modifier.fillMaxSize(),
            onMissing = fallback,
        )
    }
}

private data class SavedArtworkLoad(val image: ImageBitmap? = null, val complete: Boolean = false)

@Composable
private fun SavedTokenArtwork(
    uri: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onMissing: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val localFile = remember(context, uri) { TokenLaunchArtworkStore.localArtworkFile(context, uri) }
    if (localFile == null) {
        var imageReady by remember(uri) { mutableStateOf(false) }
        Box(modifier, contentAlignment = Alignment.Center) {
            if (!imageReady) onMissing()
            AsyncImage(
                model = uri,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { imageReady = true },
                onError = { imageReady = false },
            )
        }
        return
    }
    val load by produceState(initialValue = SavedArtworkLoad(), localFile) {
        value = withContext(Dispatchers.IO) {
            SavedArtworkLoad(
                image = runCatching { BitmapFactory.decodeFile(localFile.absolutePath)?.asImageBitmap() }.getOrNull(),
                complete = true,
            )
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            load.image != null -> Image(
                bitmap = requireNotNull(load.image),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            load.complete -> onMissing()
            else -> RefText("Loading artwork…", 12f, W.Muted)
        }
    }
}

@Composable
private fun LaunchTextField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    prefix: String? = null,
    suffix: String? = null,
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.fillMaxWidth()) {
        RefText(label, 14f, W.Muted, FontWeight.Medium)
        Spacer(Modifier.height(rd(7f)))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 5,
            maxLines = if (singleLine) 1 else 7,
            textStyle = TextStyle(
                color = W.White,
                fontSize = rs(if (singleLine) 18f else 17f),
                fontFamily = LocalReferenceFont.current,
                fontWeight = FontWeight.Medium,
                lineHeight = rs(23f),
            ),
            cursorBrush = SolidColor(P.Lime),
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                keyboardType = keyboardType,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = rd(if (singleLine) 58f else 142f))
                .clip(RoundedCornerShape(rd(18f))).background(W.Panel)
                .semantics {
                    contentDescription = label
                    if (error != null) this.error(error)
                }
                .padding(horizontal = rd(17f), vertical = rd(if (singleLine) 16f else 15f)),
            decorationBox = { field ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    prefix?.let {
                        RefText(it, 18f, W.White, FontWeight.SemiBold)
                        Spacer(Modifier.width(rd(7f)))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) RefText(placeholder, if (singleLine) 18f else 17f, W.CardMuted)
                        field()
                    }
                    suffix?.let {
                        Spacer(Modifier.width(rd(8f)))
                        RefText(it, 15f, W.Muted, FontWeight.Medium)
                    }
                }
            },
        )
        if (error != null) {
            RefText(error, 12f, P.Pink, modifier = Modifier.fillMaxWidth().padding(top = rd(6f)), maxLines = 2)
        }
        if (helper != null) {
            RefText(helper, 12f, W.Muted, modifier = Modifier.fillMaxWidth().padding(top = rd(if (error == null) 6f else 3f)), maxLines = 2)
        }
    }
}

@Composable
private fun LaunchFlowFooter(step: LaunchTokenStep, enabled: Boolean, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(W.Background)
            .padding(start = rd(24f), end = rd(24f), top = rd(12f), bottom = rd(18f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LaunchProgress(step)
        Spacer(Modifier.weight(1f))
        if (step == LaunchTokenStep.DBC) {
            Column(horizontalAlignment = Alignment.End) {
                RefText("0.05 SOL max", 12f, W.White, FontWeight.SemiBold)
                RefText("No service fee", 11f, W.Muted)
            }
            Spacer(Modifier.width(rd(10f)))
        }
        val label = when (step) {
            LaunchTokenStep.DBC -> "Create"
            else -> "Continue"
        }
        CommunityButton(
            label = label,
            background = if (enabled) P.Lime else P.Lime.copy(alpha = .2f),
            foreground = if (enabled) W.Ink else P.Lime.copy(alpha = .62f),
            contentDescription = when (step) {
                LaunchTokenStep.DBC -> "Create token with Meteora on Solana Mainnet"
                else -> "Continue to the next launch step"
            },
            enabled = enabled,
            large = true,
            onClick = onAction,
        )
    }
}

@Composable
private fun LaunchProgress(step: LaunchTokenStep) {
    val total = LaunchTokenStep.entries.size
    val current = step.ordinal + 1
    Canvas(
        Modifier.size(rd(52f)).semantics {
            contentDescription = if (step == LaunchTokenStep.DBC) "Final launch step"
            else "Step $current of $total"
        },
    ) {
        val width = 4.dp.toPx()
        drawCircle(W.Panel, style = Stroke(width))
        drawArc(
            color = P.Lime,
            startAngle = -90f,
            sweepAngle = 360f * current / total,
            useCenter = false,
            style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

@Composable
private fun LaunchExecutionFooter(
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(W.Background)
            .padding(start = rd(24f), end = rd(24f), top = rd(12f), bottom = rd(18f)),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommunityButton(
            label = label,
            background = if (enabled) P.Lime else P.Lime.copy(alpha = .2f),
            foreground = if (enabled) W.Ink else P.Lime.copy(alpha = .62f),
            contentDescription = contentDescription,
            enabled = enabled,
            large = true,
            onClick = onAction,
        )
    }
}

@Composable
private fun ReviewRow(label: String, value: String, maxLines: Int = 1) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = rd(13f)),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(rd(14f)),
    ) {
        RefText(label, 14f, W.Muted, FontWeight.Medium, Modifier.width(rd(100f)))
        RefText(value, 14f, W.White, FontWeight.Medium, Modifier.weight(1f), maxLines = maxLines)
    }
}

@Composable
private fun ReviewDivider() {
    Box(Modifier.fillMaxWidth().height(rd(1f)).background(W.Circle))
}

@Composable
private fun LaunchSettingToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(rd(if (subtitle == null) 58f else 66f))
            .clip(RoundedCornerShape(rd(18f))).background(W.Panel)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .semantics {
                contentDescription = label
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(horizontal = rd(17f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            RefText(label, 16f, W.White, FontWeight.Medium)
            subtitle?.let { RefText(it, 12f, W.Muted, FontWeight.Medium) }
        }
        Box(
            Modifier.size(width = rd(42f), height = rd(25f)).clip(CircleShape)
                .background(if (checked) P.Lime else W.Circle),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(horizontal = rd(3f)).size(rd(19f)).clip(CircleShape).background(W.White))
        }
    }
}

@Composable
private fun InlineLaunchError(message: String) {
    RefText(
        message, 12f, P.Pink,
        modifier = Modifier.fillMaxWidth().padding(top = rd(9f)).semantics { error(message) },
        maxLines = 2,
    )
}

@Composable
private fun CommunityButton(
    label: String,
    background: Color,
    foreground: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    large: Boolean = false,
    onClick: () -> Unit,
) {
    val buttonModifier = modifier.height(rd(if (large) 56f else 46f)).clip(CircleShape)
        .background(background)
        .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics {
            this.contentDescription = contentDescription
            if (!enabled) disabled()
        }
        .padding(horizontal = rd(if (large) 30f else 18f))
    Box(buttonModifier, contentAlignment = Alignment.Center) {
        RefText(label, if (large) 18f else 14f, foreground, FontWeight.SemiBold)
    }
}

@Composable
private fun CommunityScanButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(rd(46f)).clip(CircleShape)
            .background(if (enabled) W.Panel else W.Panel.copy(alpha = .55f))
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics {
                contentDescription = "Scan Solana token QR code"
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        RefIcon("scan", 21f, if (enabled) W.White else W.Muted)
    }
}
