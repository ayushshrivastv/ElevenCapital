package com.elevencapital.app.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WalletProvisionerTest {
    private class Account(override val id: String = "account-a") : WalletAccountSession {
        override var wallets = emptyList<UserWallet>()
        var refreshes = 0
        val created = mutableListOf<WalletChain>()
        var refreshFailure: Exception? = null
        var refreshLimit = Int.MAX_VALUE
        var loseResponse = false
        var afterRefresh: () -> Unit = {}
        var afterCreate: () -> Unit = {}
        override suspend fun refresh() {
            refreshes++
            check(refreshes <= refreshLimit) { "Account refresh rate limit" }
            refreshFailure?.let { throw it }
            afterRefresh()
        }
        override suspend fun create(chain: WalletChain): UserWallet {
            check(wallets.none { it.chain == chain })
            val wallet = UserWallet(chain, "address-$id-$chain")
            created += chain
            wallets += wallet
            afterCreate()
            if (loseResponse) { loseResponse = false; throw IllegalStateException("Response lost") }
            return wallet
        }
    }

    @Test fun `new account gets one wallet per chain`() = runBlocking {
        val account = Account()
        var published = emptyList<UserWallet>()
        provisionWallets(account, { it == account.id }) { published = it }
        assertEquals(WalletChain.entries.toSet(), account.created.toSet())
        assertEquals(1, account.refreshes)
        assertEquals(2, published.size)
    }

    @Test fun `returning account reuses both wallets`() = runBlocking {
        val account = Account().apply {
            wallets = WalletChain.entries.map { UserWallet(it, "existing-$it") }
        }
        provisionWallets(account, { true }) {}
        assertTrue(account.created.isEmpty())
        assertEquals(2, account.wallets.size)
        assertEquals(1, account.refreshes)
    }

    @Test fun `partially provisioned account creates only missing chain`() = runBlocking {
        val account = Account().apply {
            wallets = listOf(UserWallet(WalletChain.SOLANA, "existing-solana"))
            refreshLimit = 1
        }
        provisionWallets(account, { true }) {}
        assertEquals(listOf(WalletChain.ETHEREUM), account.created)
        assertEquals("existing-solana", account.wallets.first().address)
        assertEquals(1, account.refreshes)
    }

    @Test fun `SDK refreshed wallet list is reread before creating next chain`() = runBlocking {
        val account = Account().apply {
            afterCreate = { wallets += UserWallet(WalletChain.ETHEREUM, "server-linked-ethereum") }
        }
        var published = emptyList<UserWallet>()
        provisionWallets(account, { true }) { published = it }
        assertEquals(listOf(WalletChain.SOLANA), account.created)
        assertEquals(1, account.refreshes)
        assertEquals(2, published.size)
    }

    @Test fun `existing multiple wallets never triggers additional wallets`() = runBlocking {
        val account = Account().apply {
            wallets = listOf(UserWallet(WalletChain.SOLANA, "s1"), UserWallet(WalletChain.SOLANA, "s2"), UserWallet(WalletChain.ETHEREUM, "e1"))
        }
        provisionWallets(account, { true }) {}
        assertTrue(account.created.isEmpty())
        assertEquals(3, account.wallets.size)
    }

    @Test fun `failed refresh cannot create a duplicate using stale local absence`() = runBlocking {
        val account = Account().apply { refreshFailure = IllegalStateException("Offline") }
        assertTrue(runCatching { provisionWallets(account, { true }) {} }.isFailure)
        assertTrue(account.created.isEmpty())
    }

    @Test fun `ambiguous creation is reconciled before retry`() = runBlocking {
        val account = Account().apply { loseResponse = true }
        assertTrue(runCatching { provisionWallets(account, { true }) {} }.isFailure)
        provisionWallets(account, { true }) {}
        assertEquals(listOf(WalletChain.SOLANA, WalletChain.ETHEREUM), account.created)
        assertEquals(2, account.wallets.size)
        assertEquals(2, account.refreshes)
    }

    @Test fun `retry refresh recovers remotely created wallet missing from local cache`() = runBlocking {
        var remoteWallets = emptyList<UserWallet>()
        val created = mutableListOf<WalletChain>()
        var refreshes = 0
        var loseResponse = true
        val account = object : WalletAccountSession {
            override val id = "account-a"
            override var wallets = emptyList<UserWallet>()
            override suspend fun refresh() { refreshes++; wallets = remoteWallets }
            override suspend fun create(chain: WalletChain): UserWallet {
                check(remoteWallets.none { it.chain == chain })
                val wallet = UserWallet(chain, "remote-$chain")
                remoteWallets += wallet
                created += chain
                if (loseResponse) { loseResponse = false; throw IllegalStateException("Response lost") }
                wallets = remoteWallets
                return wallet
            }
        }
        assertTrue(runCatching { provisionWallets(account, { true }) {} }.isFailure)
        assertTrue(account.wallets.isEmpty())
        provisionWallets(account, { true }) {}
        assertEquals(listOf(WalletChain.SOLANA, WalletChain.ETHEREUM), created)
        assertEquals(2, refreshes)
        assertEquals(2, account.wallets.size)
    }

    @Test fun `account change during initial refresh prevents wallet publication and creation`() = runBlocking {
        var current = "account-a"
        val account = Account().apply {
            wallets = listOf(UserWallet(WalletChain.SOLANA, "existing-solana"))
            afterRefresh = { current = "account-b" }
        }
        var published = emptyList<UserWallet>()
        val failure = runCatching { provisionWallets(account, { it == current }) { published = it } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(account.created.isEmpty())
        assertTrue(published.isEmpty())
    }

    @Test fun `signed out session creates no wallets`() = runBlocking {
        val account = Account()
        val failure = runCatching { provisionWallets(account, { false }) {} }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(account.created.isEmpty())
    }

    @Test fun `account change during request cannot publish old wallet or create next chain`() = runBlocking {
        var current = "account-a"
        val account = Account().apply { afterCreate = { current = "account-b" } }
        var published = emptyList<UserWallet>()
        val failure = runCatching { provisionWallets(account, { it == current }) { published = it } }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(listOf(WalletChain.SOLANA), account.created)
        assertTrue(published.isEmpty())
    }

    @Test fun `unverified session is never authenticated or wallet ready`() {
        val state = ElevenAuthState(true, AuthPhase.SESSION_UNVERIFIED)
        assertFalse(state.authenticated)
        assertFalse(state.walletsReady)
    }

    @Test fun `ready requires verified account and both chains`() {
        val base = ElevenAuthState(true, AuthPhase.AUTHENTICATED, userId = "account-a")
        assertFalse(base.walletsReady)
        assertTrue(base.copy(wallets = WalletChain.entries.map { UserWallet(it, "public-$it") }).walletsReady)
    }
}
