package com.elevencapital.app.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.elevencapital.app.auth.UserWallet
import com.elevencapital.app.ui.LocalReferenceFont
import com.elevencapital.app.ui.P
import com.elevencapital.app.ui.RefIcon
import com.elevencapital.app.ui.RefText
import com.elevencapital.app.ui.WalletStyle
import com.elevencapital.app.ui.rd
import com.elevencapital.app.ui.rs
import com.elevencapital.app.wallet.PreparedTransfer
import com.elevencapital.app.wallet.TransferDraft
import com.elevencapital.app.wallet.TransferPreparationException
import com.elevencapital.app.wallet.TransferSessionIdentity
import com.elevencapital.app.wallet.TransferLifecycleStatus
import com.elevencapital.app.wallet.TransferStatusObservation
import com.elevencapital.app.wallet.WalletAsset
import com.elevencapital.app.wallet.WalletAssetId
import com.elevencapital.app.wallet.WalletAssetKind
import com.elevencapital.app.wallet.WalletAssetRegistry
import com.elevencapital.app.wallet.WalletNetwork
import com.elevencapital.app.wallet.WalletNetworkId
import com.elevencapital.app.wallet.WalletSubmission
import com.elevencapital.app.wallet.WalletSubmissionException
import com.elevencapital.app.wallet.WalletTransferValidationException
import com.elevencapital.app.wallet.formatBaseUnits
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

private sealed interface SendStage {
    data object Entry : SendStage
    data class Review(val prepared: PreparedTransfer) : SendStage
    data class Submitted(val prepared: PreparedTransfer, val submission: WalletSubmission) : SendStage
    data class Ambiguous(val message: String) : SendStage
}

@Composable
fun SendWalletScreen(
    userId: String,
    wallets: List<UserWallet>,
    onBack: () -> Unit,
    onPrepare: suspend (TransferDraft) -> PreparedTransfer,
    onSubmit: suspend (PreparedTransfer) -> WalletSubmission,
    onCheckStatus: suspend (PreparedTransfer, WalletSubmission) -> TransferStatusObservation,
) {
    val usableWallets = remember(wallets) { wallets.filter { it.walletId.isNotBlank() } }
    val availableNetworks = remember(usableWallets) {
        WalletAssetRegistry.networks.filter { network -> usableWallets.any { it.chain == network.walletChain } }
    }
    if (availableNetworks.isEmpty()) {
        Column(Modifier.fillMaxSize().background(P.Background).padding(horizontal = rd(22f))) {
            WalletPageHeader("Send", onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RefText("Your secure wallet is still connecting.", 16f, P.Muted)
            }
        }
        return
    }

    var selectedNetworkId by remember(userId, usableWallets) {
        mutableStateOf(availableNetworks.first().id)
    }
    val selectedNetwork = WalletAssetRegistry.network(selectedNetworkId)
    val networkWallets = remember(usableWallets, selectedNetworkId) {
        usableWallets.filter { it.chain == selectedNetwork.walletChain }
    }
    var selectedWalletKey by remember(userId, selectedNetworkId, networkWallets) {
        mutableStateOf(networkWallets.first().stableWalletKey())
    }
    val selectedWallet = networkWallets.firstOrNull { it.stableWalletKey() == selectedWalletKey } ?: networkWallets.first()
    // SOL USDC is safe to receive, but outgoing transferChecked/ATA construction deliberately
    // remains disabled until that transaction builder is independently verified.
    val assets = remember(selectedNetworkId) {
        WalletAssetRegistry.assets.filter {
            it.network.id == selectedNetworkId && it.id != WalletAssetId.SOLANA_USDC
        }
    }
    var selectedAssetId by remember(userId, selectedNetworkId) { mutableStateOf(assets.first().id) }
    val selectedAsset = WalletAssetRegistry.asset(selectedAssetId)
    var recipient by remember(userId, selectedNetworkId, selectedWalletKey) { mutableStateOf("") }
    var amount by remember(userId, selectedAssetId, selectedWalletKey) { mutableStateOf("") }
    var stage by remember(userId, selectedWalletKey) { mutableStateOf<SendStage>(SendStage.Entry) }
    var working by remember(userId, selectedWalletKey) { mutableStateOf(false) }
    var error by remember(userId, selectedWalletKey) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun resetForEdit() {
        stage = SendStage.Entry
        working = false
        error = null
    }

    fun handleBack() {
        if (working) return
        when (stage) {
            SendStage.Entry -> onBack()
            is SendStage.Review -> resetForEdit()
            is SendStage.Submitted, is SendStage.Ambiguous -> onBack()
        }
    }

    // The Privy provider call is deliberately one-shot and may finish after cancellation. Keep the
    // screen attached while preparation or submission is active so Android back cannot hide a result.
    BackHandler { handleBack() }

    Column(
        Modifier.fillMaxSize().background(if (stage is SendStage.Review) WalletStyle.Background else P.Background)
            .then(if (stage is SendStage.Review) Modifier else Modifier.verticalScroll(rememberScrollState()))
            .padding(horizontal = rd(22f)),
    ) {
        if (stage is SendStage.Review) {
            TransactionReviewHeader("Review transfer", !working, ::handleBack)
        } else {
            WalletPageHeader(
                title = when (stage) {
                    SendStage.Entry -> "Send"
                    is SendStage.Review -> "Review transfer"
                    is SendStage.Submitted -> "Transfer status"
                    is SendStage.Ambiguous -> "Transfer status"
                },
                onBack = ::handleBack,
                backEnabled = !working,
            )
        }
        when (val current = stage) {
            SendStage.Entry -> SendEntry(
                availableNetworks = availableNetworks,
                selectedNetwork = selectedNetwork,
                onNetwork = { network ->
                    selectedNetworkId = network.id
                    error = null
                },
                networkWallets = networkWallets,
                selectedWallet = selectedWallet,
                onWallet = { selectedWalletKey = it.stableWalletKey(); resetForEdit() },
                assets = assets,
                selectedAsset = selectedAsset,
                onAsset = { selectedAssetId = it.id; error = null },
                recipient = recipient,
                onRecipient = { if (it.length <= 96) recipient = it; error = null },
                onPaste = { clipboard.getText()?.text?.trim()?.let { recipient = it.take(96) }; error = null },
                amount = amount,
                onAmount = { candidate ->
                    if (candidate.length <= 80 && candidate.all { it.isDigit() || it == '.' }) amount = candidate
                    error = null
                },
                error = error,
                working = working,
                onReview = {
                    if (!working) {
                        // Set synchronously before launching so two taps cannot queue two prepares.
                        working = true
                        scope.launch {
                            error = null
                            try {
                                val session = TransferSessionIdentity.create(
                                    userId = userId,
                                    walletId = selectedWallet.walletId,
                                    networkId = selectedNetworkId,
                                    walletAddress = selectedWallet.address,
                                )
                                val draft = TransferDraft.create(session, selectedAssetId, recipient.trim(), amount)
                                stage = SendStage.Review(onPrepare(draft))
                            } catch (failure: WalletTransferValidationException) {
                                error = failure.userMessage
                            } catch (failure: TransferPreparationException) {
                                error = failure.userMessage
                            } catch (_: Exception) {
                                error = "The transfer could not be prepared. Nothing was sent."
                            } finally {
                                working = false
                            }
                        }
                    }
                },
            )
            is SendStage.Review -> SendReview(
                prepared = current.prepared,
                working = working,
                error = error,
                onEdit = ::resetForEdit,
                onConfirm = {
                    if (!working) {
                        // Set synchronously before launching. The controller also enforces one-shot
                        // operation IDs, but the UI must not let a second tap obscure a first success.
                        working = true
                        scope.launch {
                            error = null
                            try {
                                stage = SendStage.Submitted(current.prepared, onSubmit(current.prepared))
                            } catch (failure: WalletSubmissionException) {
                                if (failure.transactionId != null) {
                                    // A valid provider identifier must stay visible even if the
                                    // device's durable journal failed immediately after broadcast.
                                    stage = SendStage.Submitted(
                                        current.prepared,
                                        WalletSubmission(
                                            current.prepared.review.operationId,
                                            current.prepared.review.networkId,
                                            failure.transactionId,
                                            System.currentTimeMillis(),
                                        ),
                                    )
                                } else if (failure.definitelyNotBroadcast) {
                                    stage = SendStage.Entry
                                    error = failure.userMessage
                                } else {
                                    stage = SendStage.Ambiguous(failure.userMessage)
                                }
                            } catch (_: Exception) {
                                stage = SendStage.Ambiguous(
                                    "The transfer result is uncertain. Check wallet history before trying again.",
                                )
                            } finally {
                                working = false
                            }
                        }
                    }
                },
            )
            is SendStage.Submitted -> SendSubmitted(
                current.prepared, current.submission, clipboard, onCheckStatus, onBack,
            )
            is SendStage.Ambiguous -> SendAmbiguous(current.message, onBack)
        }
        if (stage !is SendStage.Review) Spacer(Modifier.height(rd(28f)))
    }
}

@Composable
private fun SendEntry(
    availableNetworks: List<WalletNetwork>,
    selectedNetwork: WalletNetwork,
    onNetwork: (WalletNetwork) -> Unit,
    networkWallets: List<UserWallet>,
    selectedWallet: UserWallet,
    onWallet: (UserWallet) -> Unit,
    assets: List<WalletAsset>,
    selectedAsset: WalletAsset,
    onAsset: (WalletAsset) -> Unit,
    recipient: String,
    onRecipient: (String) -> Unit,
    onPaste: () -> Unit,
    amount: String,
    onAmount: (String) -> Unit,
    error: String?,
    working: Boolean,
    onReview: () -> Unit,
) {
    Spacer(Modifier.height(rd(12f)))
    FieldLabel("Network")
    Spacer(Modifier.height(rd(9f)))
    WalletSegmentedControl(availableNetworks, selectedNetwork, WalletNetwork::displayName, onNetwork)
    Spacer(Modifier.height(rd(18f)))
    FieldLabel("Asset")
    Spacer(Modifier.height(rd(9f)))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(rd(9f)),
    ) {
        assets.forEach { asset -> AssetChip(asset, asset == selectedAsset) { onAsset(asset) } }
    }
    Spacer(Modifier.height(rd(18f)))
    FieldLabel("From")
    Spacer(Modifier.height(rd(9f)))
    if (networkWallets.size > 1) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(rd(8f)),
        ) {
            networkWallets.forEach { wallet ->
                WalletAddressChoice(wallet, wallet == selectedWallet) { onWallet(wallet) }
            }
        }
    } else {
        AddressCard(selectedWallet.address)
    }
    Spacer(Modifier.height(rd(18f)))
    FieldLabel("Recipient address")
    Spacer(Modifier.height(rd(9f)))
    WalletInput(
        value = recipient,
        onValueChange = onRecipient,
        hint = "Enter ${selectedNetwork.displayName} address",
        keyboardType = KeyboardType.Text,
        trailing = "Paste",
        onTrailing = onPaste,
    )
    Spacer(Modifier.height(rd(18f)))
    FieldLabel("Amount")
    Spacer(Modifier.height(rd(9f)))
    WalletInput(
        value = amount,
        onValueChange = onAmount,
        hint = "0.00",
        keyboardType = KeyboardType.Decimal,
        trailing = selectedAsset.symbol,
    )
    Spacer(Modifier.height(rd(15f)))
    SafetyNote(
        "The address, mainnet, amount, balance and maximum network fee are verified before Privy asks you to sign.",
    )
    if (selectedNetwork.id == WalletNetworkId.SOLANA_MAINNET) {
        Spacer(Modifier.height(rd(9f)))
        RefText("SOL sending is available. Solana USDC sending is still disabled for safety.", 12.5f, P.Muted)
    }
    error?.let {
        Spacer(Modifier.height(rd(13f)))
        RefText(it, 13f, Color(0xFFFF7088), modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(rd(22f)))
    WalletPrimaryButton(if (working) "Checking transfer…" else "Review transfer", !working, onReview)
}

@Composable
private fun ColumnScope.SendReview(
    prepared: PreparedTransfer,
    working: Boolean,
    error: String?,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
) {
    val review = prepared.review
    val nativeAsset = when (review.networkId) {
        WalletNetworkId.ETHEREUM_MAINNET -> WalletAssetRegistry.ethereumEth
        WalletNetworkId.SOLANA_MAINNET -> WalletAssetRegistry.solanaSol
    }
    var currentTime by remember(prepared.review.operationId) { mutableStateOf(Instant.now()) }
    LaunchedEffect(prepared.review.operationId) {
        while (true) {
            currentTime = Instant.now()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val seconds = Duration.between(currentTime, prepared.expiresAt).seconds.coerceAtLeast(0)
    val expired = seconds == 0L
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(rd(28f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetworkAssetDot(prepared.asset)
            Spacer(Modifier.width(rd(12f)))
            Column {
                RefText("Send ${review.assetSymbol}", 20f, WalletStyle.White, FontWeight.SemiBold)
                RefText("${prepared.asset.network.displayName} Mainnet", 13f, WalletStyle.Muted)
            }
        }
        Spacer(Modifier.height(rd(26f)))
        RefText("You send", 13f, WalletStyle.Muted)
        Spacer(Modifier.height(rd(4f)))
        RefText("${review.displayAmount} ${review.assetSymbol}", 31f, WalletStyle.White, FontWeight.Bold)
        Spacer(Modifier.height(rd(26f)))
        TransactionReviewPanel {
            TransactionReviewRow("From", review.sender.value, fullAddress = true)
            TransactionReviewRow("Network", "${prepared.asset.network.displayName} Mainnet")
            TransactionReviewDivider()
            TransactionReviewRow("To", review.recipient.value, fullAddress = true)
            TransactionReviewDivider()
            TransactionReviewRow("Available",
                "${formatBaseUnits(prepared.assetBalanceBaseUnits, prepared.asset.decimals)} ${prepared.asset.symbol}")
            TransactionReviewRow("Estimated fee",
                "${formatBaseUnits(prepared.estimatedFeeBaseUnits, nativeAsset.decimals)} ${nativeAsset.symbol}")
            TransactionReviewRow("Maximum fee",
                "${formatBaseUnits(prepared.maxFeeBaseUnits, nativeAsset.decimals)} ${nativeAsset.symbol}")
            TransactionReviewDivider()
            TransactionReviewRow("Approval expires", if (expired) "Expired" else "in ${seconds}s")
        }
        Spacer(Modifier.height(rd(16f)))
        TransactionReviewPanel {
            RefText("Privy wallet approval", 15f, WalletStyle.White, FontWeight.SemiBold)
            TransactionReviewNote(
                "Check the complete recipient and confirm it supports ${prepared.asset.symbol} on " +
                    "${prepared.asset.network.displayName} Mainnet. Privy will ask you to approve this one-time transfer.",
            )
        }
        error?.let {
            Spacer(Modifier.height(rd(12f)))
            RefText(it, 13f, Color(0xFFFF7088), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(rd(22f)))
    }
    Spacer(Modifier.height(rd(10f)))
    TransactionReviewPrimaryButton(
        if (working) "Confirm in Privy…" else if (expired) "Review expired" else "Confirm and send",
        !working && !expired,
        onConfirm,
    )
    TransactionReviewSecondaryButton("Edit details", !working, onEdit)
    Spacer(Modifier.height(rd(12f)))
}

@Composable
private fun SendSubmitted(
    prepared: PreparedTransfer,
    submission: WalletSubmission,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onCheckStatus: suspend (PreparedTransfer, WalletSubmission) -> TransferStatusObservation,
    onDone: () -> Unit,
) {
    var copied by remember(submission.transactionId) { mutableStateOf(false) }
    var observation by remember(submission.transactionId) { mutableStateOf<TransferStatusObservation?>(null) }
    var statusMessage by remember(submission.transactionId) {
        mutableStateOf("Checking network confirmation…")
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }
    LaunchedEffect(submission.transactionId) {
        var attempt = 0
        while (true) {
            try {
                val latest = onCheckStatus(prepared, submission)
                observation = latest
                statusMessage = transferStatusMessage(latest)
                if (latest.status == TransferLifecycleStatus.FINALIZED ||
                    (latest.status == TransferLifecycleStatus.FAILED && latest.isFinalized)) break
            } catch (failure: TransferPreparationException) {
                statusMessage = failure.userMessage
            } catch (_: Exception) {
                statusMessage = "Confirmation is temporarily unavailable. Do not send this transfer again."
            }
            val delays = longArrayOf(1_500, 2_500, 4_000, 7_000, 10_000, 15_000)
            kotlinx.coroutines.delay(delays[attempt.coerceAtMost(delays.lastIndex)])
            attempt++
        }
    }
    Spacer(Modifier.height(rd(72f)))
    Box(Modifier.size(rd(70f)).clip(CircleShape).background(P.Lime), contentAlignment = Alignment.Center) {
        RefText("✓", 34f, P.Background, FontWeight.Bold)
    }
    Spacer(Modifier.height(rd(20f)))
    CenteredWalletText("Transfer submitted", 26f, P.White, FontWeight.Bold)
    Spacer(Modifier.height(rd(8f)))
    CenteredWalletText(
        "${prepared.review.displayAmount} ${prepared.asset.symbol} was broadcast to ${prepared.asset.network.displayName} Mainnet.",
        14f, P.Muted,
    )
    Spacer(Modifier.height(rd(18f)))
    TransferStatusCard(observation, statusMessage, observation?.confirmations?.toString())
    Spacer(Modifier.height(rd(24f)))
    FieldLabel("Transaction identifier")
    Spacer(Modifier.height(rd(8f)))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(15f))).background(P.Card)
            .clickable(role = Role.Button) {
                clipboard.setText(AnnotatedString(submission.transactionId)); copied = true
            }.padding(rd(15f)),
    ) {
        BasicText(
            if (copied) "Copied" else submission.transactionId,
            style = TextStyle(
                color = if (copied) P.Lime else P.White,
                fontSize = rs(12f),
                fontFamily = LocalReferenceFont.current,
                lineHeight = rs(17f),
            ),
        )
    }
    Spacer(Modifier.height(rd(14f)))
    SafetyNote("Eleven Capital will keep checking this exact identifier. Do not send a replacement while its status is pending or unknown.")
    Spacer(Modifier.height(rd(24f)))
    WalletPrimaryButton("Done", true, onDone)
}

@Composable
private fun TransferStatusCard(
    observation: TransferStatusObservation?,
    message: String,
    confirmations: String?,
) {
    val status = observation?.status
    val color = when (status) {
        TransferLifecycleStatus.FINALIZED -> P.Lime
        TransferLifecycleStatus.FAILED -> Color(0xFFFF7088)
        TransferLifecycleStatus.CONFIRMED -> Color(0xFF8CCBFF)
        else -> Color(0xFFFFC457)
    }
    val label = when (status) {
        null -> "Checking"
        TransferLifecycleStatus.UNKNOWN -> "Awaiting network"
        TransferLifecycleStatus.PENDING -> "Pending"
        TransferLifecycleStatus.CONFIRMED -> "Confirmed"
        TransferLifecycleStatus.FINALIZED -> "Finalized"
        TransferLifecycleStatus.FAILED -> if (observation?.isFinalized == true) "Failed on network" else "Failed · confirming"
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(16f))).background(P.Card)
            .padding(horizontal = rd(15f), vertical = rd(14f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(rd(9f)).clip(CircleShape).background(color))
            Spacer(Modifier.width(rd(9f)))
            RefText(label, 14f, color, FontWeight.Bold)
            confirmations?.let {
                Spacer(Modifier.width(rd(8f)))
                RefText("· $it confirmations", 12f, P.Muted)
            }
        }
        Spacer(Modifier.height(rd(7f)))
        BasicText(
            message,
            style = TextStyle(P.Muted, rs(12.5f), fontFamily = LocalReferenceFont.current, lineHeight = rs(17f)),
        )
    }
}

@Composable
private fun SendAmbiguous(message: String, onDone: () -> Unit) {
    Spacer(Modifier.height(rd(72f)))
    Box(Modifier.size(rd(70f)).clip(CircleShape).background(Color(0xFF3A3021)), contentAlignment = Alignment.Center) {
        RefText("!", 34f, Color(0xFFFFC457), FontWeight.Bold)
    }
    Spacer(Modifier.height(rd(20f)))
    CenteredWalletText("Status needs attention", 25f, P.White, FontWeight.Bold)
    Spacer(Modifier.height(rd(10f)))
    CenteredWalletText(message, 14f, P.Muted)
    Spacer(Modifier.height(rd(18f)))
    SafetyNote("Do not repeat this transfer. A timeout can happen after broadcast, so check Privy wallet history and the recipient before taking another action.")
    Spacer(Modifier.height(rd(24f)))
    WalletPrimaryButton("Done", true, onDone)
}

@Composable
private fun WalletInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    keyboardType: KeyboardType,
    trailing: String,
    onTrailing: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().height(rd(58f)).clip(RoundedCornerShape(rd(15f))).background(P.Card)
            .padding(horizontal = rd(15f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(P.White, rs(15f), fontFamily = LocalReferenceFont.current),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            decorationBox = { inner -> if (value.isEmpty()) RefText(hint, 15f, P.Muted) else inner() },
        )
        Spacer(Modifier.width(rd(10f)))
        Box(
            Modifier.clip(CircleShape).background(P.Chip)
                .then(if (onTrailing != null) Modifier.clickable(role = Role.Button, onClick = onTrailing) else Modifier)
                .padding(horizontal = rd(11f), vertical = rd(7f)),
            contentAlignment = Alignment.Center,
        ) { RefText(trailing, 12f, if (onTrailing != null) P.Lime else P.White, FontWeight.Bold) }
    }
}

@Composable
internal fun WalletAddressChoice(wallet: UserWallet, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(rd(43f)).clip(CircleShape).background(if (selected) P.Chip else P.Card)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = rd(13f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(rd(7f)).clip(CircleShape).background(if (selected) P.Lime else P.Muted))
        Spacer(Modifier.width(rd(8f)))
        RefText(compactAddress(wallet.address), 13f, if (selected) P.White else P.Muted)
    }
}

@Composable
private fun AddressCard(address: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(15f))).background(P.Card)
            .padding(horizontal = rd(15f), vertical = rd(15f)),
    ) { RefText(compactAddress(address), 14f, P.White) }
}

@Composable
private fun SafetyNote(message: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(rd(14f))).background(P.Card)
            .padding(horizontal = rd(14f), vertical = rd(13f)),
        verticalAlignment = Alignment.Top,
    ) {
        RefIcon("verified", 17f, P.Lime)
        Spacer(Modifier.width(rd(9f)))
        BasicText(
            message,
            Modifier.weight(1f),
            style = TextStyle(P.Muted, rs(12.5f), fontFamily = LocalReferenceFont.current, lineHeight = rs(17f)),
        )
    }
}

@Composable
private fun FieldLabel(label: String) {
    RefText(label, 13f, P.Muted, weight = FontWeight.Medium)
}

@Composable
private fun CenteredWalletText(
    value: String,
    size: Float,
    color: Color,
    weight: FontWeight = FontWeight.Normal,
) {
    BasicText(
        value,
        Modifier.fillMaxWidth(),
        style = TextStyle(
            color = color,
            fontSize = rs(size),
            fontFamily = LocalReferenceFont.current,
            fontWeight = weight,
            textAlign = TextAlign.Center,
            lineHeight = rs(size * 1.35f),
        ),
    )
}

private fun transferStatusMessage(observation: TransferStatusObservation): String = when (observation.status) {
    TransferLifecycleStatus.UNKNOWN ->
        "The node has not found this identifier yet. It may still be propagating; this does not mean the transfer failed."
    TransferLifecycleStatus.PENDING -> "The network has seen the transfer and is processing it."
    TransferLifecycleStatus.CONFIRMED -> "The transfer is included and waiting for finality."
    TransferLifecycleStatus.FINALIZED -> "The network finalized this transfer."
    TransferLifecycleStatus.FAILED -> if (observation.isFinalized) {
        "The network finalized this failed execution. Review it before taking another action."
    } else {
        "Execution failed in a non-final block. Confirmation checks will continue; do not resend."
    }
}

internal fun UserWallet.stableWalletKey(): String = walletId.ifBlank { "${chain.name}:$address" }
