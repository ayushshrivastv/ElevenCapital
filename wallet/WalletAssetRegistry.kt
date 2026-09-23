package com.elevencapital.app.wallet

import java.util.Collections

enum class WalletNetworkId {
    ETHEREUM_MAINNET,
    SOLANA_MAINNET,
}

enum class WalletAssetId {
    ETHEREUM_ETH,
    ETHEREUM_USDC,
    SOLANA_SOL,
    SOLANA_USDC,
}

enum class WalletAssetKind { NATIVE, TOKEN }

/** Immutable chain identity used for validation and transaction preparation. */
class WalletNetwork internal constructor(
    val id: WalletNetworkId,
    val displayName: String,
    val caip2: String,
    val eip155ChainId: Long?,
) {
    override fun equals(other: Any?): Boolean = other is WalletNetwork && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id.name
}

/** Token identity is a contract/mint, never a ticker. Native assets have no contract or mint. */
class WalletAsset internal constructor(
    val id: WalletAssetId,
    val symbol: String,
    val displayName: String,
    val network: WalletNetwork,
    val kind: WalletAssetKind,
    val contractOrMint: String?,
    val decimals: Int,
    val baseUnitBits: Int,
) {
    init {
        require(symbol.isNotBlank() && displayName.isNotBlank())
        require(decimals in 0..36)
        require(baseUnitBits in setOf(64, 256))
        require((kind == WalletAssetKind.NATIVE) == (contractOrMint == null))
    }

    override fun equals(other: Any?): Boolean = other is WalletAsset && id == other.id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id.name
}

/**
 * Mainnet-only allowlist for the first send/receive release. Adding an asset requires a source
 * change and review; a server response or user-entered symbol can never change these identities.
 */
object WalletAssetRegistry {
    val ethereumMainnet = WalletNetwork(
        id = WalletNetworkId.ETHEREUM_MAINNET,
        displayName = "Ethereum",
        caip2 = "eip155:1",
        eip155ChainId = 1L,
    )
    val solanaMainnet = WalletNetwork(
        id = WalletNetworkId.SOLANA_MAINNET,
        displayName = "Solana",
        caip2 = "solana:5eykt4UsFv8P8NJdTREpY1vzqKqZKvdp",
        eip155ChainId = null,
    )

    val ethereumEth = WalletAsset(
        id = WalletAssetId.ETHEREUM_ETH,
        symbol = "ETH",
        displayName = "Ether",
        network = ethereumMainnet,
        kind = WalletAssetKind.NATIVE,
        contractOrMint = null,
        decimals = 18,
        baseUnitBits = 256,
    )
    val ethereumUsdc = WalletAsset(
        id = WalletAssetId.ETHEREUM_USDC,
        symbol = "USDC",
        displayName = "USD Coin",
        network = ethereumMainnet,
        kind = WalletAssetKind.TOKEN,
        contractOrMint = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
        decimals = 6,
        baseUnitBits = 256,
    )
    val solanaSol = WalletAsset(
        id = WalletAssetId.SOLANA_SOL,
        symbol = "SOL",
        displayName = "Solana",
        network = solanaMainnet,
        kind = WalletAssetKind.NATIVE,
        contractOrMint = null,
        decimals = 9,
        baseUnitBits = 64,
    )
    val solanaUsdc = WalletAsset(
        id = WalletAssetId.SOLANA_USDC,
        symbol = "USDC",
        displayName = "USD Coin",
        network = solanaMainnet,
        kind = WalletAssetKind.TOKEN,
        contractOrMint = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",
        decimals = 6,
        baseUnitBits = 64,
    )

    private val immutableNetworks = Collections.unmodifiableList(listOf(ethereumMainnet, solanaMainnet))
    private val immutableAssets = Collections.unmodifiableList(
        listOf(ethereumEth, ethereumUsdc, solanaSol, solanaUsdc),
    )
    private val assetsById = immutableAssets.associateBy(WalletAsset::id)
    private val networksById = immutableNetworks.associateBy(WalletNetwork::id)

    val networks: List<WalletNetwork> get() = immutableNetworks
    val assets: List<WalletAsset> get() = immutableAssets

    fun asset(id: WalletAssetId): WalletAsset = assetsById[id]
        ?: transferValidationFailure(WalletTransferErrorCode.UNSUPPORTED_ASSET, "This asset is not supported.")

    fun network(id: WalletNetworkId): WalletNetwork = networksById[id]
        ?: transferValidationFailure(WalletTransferErrorCode.WRONG_NETWORK, "This network is not supported.")
}
