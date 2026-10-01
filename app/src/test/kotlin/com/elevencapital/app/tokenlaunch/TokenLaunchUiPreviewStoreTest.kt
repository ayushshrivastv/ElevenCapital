package com.elevencapital.app.tokenlaunch

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.cash.paparazzi.Paparazzi
import com.elevencapital.app.screens.tokenLaunchUiPreviewResult
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TokenLaunchUiPreviewStoreTest {
    @get:Rule val paparazzi = Paparazzi()

    private val canonical = StoredTokenLaunchUiPreview(
        name = "SPXTR Test",
        symbol = "CHTMS",
        mintAddress = tokenLaunchUiPreviewResult().mintAddress,
    )

    @Test fun `canonical preview round trips through the codec`() {
        val encoded = encodeTokenLaunchUiPreview(canonical)

        assertNotNull(encoded)
        assertEquals(canonical, decodeTokenLaunchUiPreview(encoded!!))
    }

    @Test fun `version five preview restores chosen artwork`() {
        val withImage = canonical.copy(imageUri = "content://media/external/images/media/123")

        val encoded = encodeTokenLaunchUiPreview(withImage)

        assertNotNull(encoded)
        assertEquals(withImage, decodeTokenLaunchUiPreview(encoded!!))
        assertEquals(withImage.imageUri, decodeTokenLaunchUiPreview(encoded!!)?.toDraft()?.imageUri)
    }

    @Test fun `version five preserves the selected curve and vesting settings`() {
        val advanced = canonical.copy(
            totalSupply = "500000000", feeBps = "250",
            migrationThresholdSol = "25", supplyOnMigrationPercent = "35",
            curvePreset = "Exponential",
            initialMarketCapSol = "10", graduationMarketCapSol = "100",
            launchProtectionEnabled = true, vestCreatorTokens = true,
            vestedSupplyPercent = "15", vestingDurationMonths = "18",
            vestingFrequency = "Weekly",
        )

        val encoded = encodeTokenLaunchUiPreview(advanced)

        assertNotNull(encoded)
        assertEquals(advanced, decodeTokenLaunchUiPreview(encoded!!))
        assertEquals(advanced.toDraft(), decodeTokenLaunchUiPreview(encoded)?.toDraft())
    }

    @Test fun `version five preserves token description and social links`() {
        val profile = canonical.copy(
            description = "A community token for space explorers.",
            xHandle = "spxtr_crew",
            telegramHandle = "spxtrcrew",
            websiteUrl = "https://spxtr.example/token",
        )

        val encoded = encodeTokenLaunchUiPreview(profile)

        assertNotNull(encoded)
        assertEquals(profile, decodeTokenLaunchUiPreview(encoded!!))
        assertEquals(profile.toDraft(), decodeTokenLaunchUiPreview(encoded)?.toDraft())
    }

    @Test fun `market cap curve restores without a separate quote target`() {
        val marketCapCurve = canonical.copy(
            migrationThresholdSol = "",
            initialMarketCapSol = "10",
            graduationMarketCapSol = "100",
        )

        val encoded = encodeTokenLaunchUiPreview(marketCapCurve)

        assertNotNull(encoded)
        assertEquals(marketCapCurve, decodeTokenLaunchUiPreview(encoded!!))
    }

    @Test fun `existing version one preview remains readable without artwork`() {
        val oldRecord = "1|U1BYVFIgVGVzdA|SPXTR|${canonical.mintAddress}"

        assertEquals(canonical, decodeTokenLaunchUiPreview(oldRecord))
    }

    @Test fun `existing version two preview remains readable with default curve settings`() {
        val oldRecord = "2|U1BYVFIgVGVzdA|SPXTR|${canonical.mintAddress}|-"

        assertEquals(canonical, decodeTokenLaunchUiPreview(oldRecord))
    }

    @Test fun `existing version five SPXTR launch retains its settings under CHTMS`() {
        val advanced = canonical.copy(curvePreset = "Exponential", feeBps = "250")
        val oldRecord = encodeTokenLaunchUiPreview(advanced)!!
            .replace("|CHTMS|", "|SPXTR|")

        assertEquals(advanced, decodeTokenLaunchUiPreview(oldRecord))
        assertEquals(tokenLaunchUiPreviewResult().mintAddress,
            decodeTokenLaunchUiPreview(oldRecord)?.mintAddress)
    }

    @Test fun `existing version three preview remains readable with empty profile`() {
        val versionFive = encodeTokenLaunchUiPreview(canonical)!!
        val versionThree = versionFive.split('|').take(16).toMutableList().also { it[0] = "3" }
            .joinToString("|")

        assertEquals(canonical, decodeTokenLaunchUiPreview(versionThree))
    }

    @Test fun `existing version four preview remains readable with classic curve`() {
        val versionFive = encodeTokenLaunchUiPreview(canonical)!!
        val versionFour = versionFive.split('|').dropLast(1).toMutableList().also { it[0] = "4" }
            .joinToString("|")

        assertEquals(canonical, decodeTokenLaunchUiPreview(versionFour))
    }

    @Test fun `version five rejects tampered vesting protection and curve settings`() {
        val encoded = encodeTokenLaunchUiPreview(canonical)!!
        assertNull(decodeTokenLaunchUiPreview(encoded.replace("|0|0|10|12|Monthly", "|yes|0|10|12|Monthly")))
        assertNull(decodeTokenLaunchUiPreview(encoded.replace("|0|0|10|12|Monthly", "|0|1|0|12|Monthly")))
        assertNull(decodeTokenLaunchUiPreview(encoded.replace("|Classic", "|Unknown")))
    }

    @Test fun `preview codec rejects non content artwork`() {
        assertNull(encodeTokenLaunchUiPreview(canonical.copy(imageUri = "https://example.com/image.jpg")))
        assertNull(encodeTokenLaunchUiPreview(canonical.copy(imageUri = "content:///missing-provider")))
    }

    @Test fun `preview codec rejects a wrong ticker`() {
        assertNull(encodeTokenLaunchUiPreview(canonical.copy(symbol = "SPXRT")))

        val encoded = encodeTokenLaunchUiPreview(canonical)
        assertNotNull(encoded)
        assertNull(decodeTokenLaunchUiPreview(encoded!!.replace("|CHTMS|", "|SPXRT|")))
    }

    @Test fun `preview codec rejects a wrong mint`() {
        val wrongMint = "Vote111111111111111111111111111111111111111"
        assertNull(encodeTokenLaunchUiPreview(canonical.copy(mintAddress = wrongMint)))

        val encoded = encodeTokenLaunchUiPreview(canonical)
        assertNotNull(encoded)
        assertNull(decodeTokenLaunchUiPreview(encoded!!.replace(canonical.mintAddress, wrongMint)))
    }

    @Test fun `remove for user clears only wallet scoped previews for that owner`() {
        val context = memoryContext()
        val preferences = context.getSharedPreferences("eleven-token-launch-ui-wallet-v2", Context.MODE_PRIVATE)
        val alice = "did:privy:alice"
        val bob = "did:privy:bob"
        val walletOne = "So11111111111111111111111111111111111111112"
        val walletTwo = "Vote111111111111111111111111111111111111111"
        val encoded = requireNotNull(encodeTokenLaunchUiPreview(canonical))
        assertTrue(preferences.edit()
            .putString("$alice|$walletOne", encoded)
            .putString("$alice|$walletTwo", encoded)
            .putString("$bob|$walletOne", encoded)
            .putString("${alice}2|$walletOne", encoded)
            .putString("$alice|not-a-wallet", encoded)
            .commit())

        assertTrue(TokenLaunchUiPreviewStore.removeForUser(context, alice))

        assertNull(TokenLaunchUiPreviewStore.read(context, alice, walletOne))
        assertNull(TokenLaunchUiPreviewStore.read(context, alice, walletTwo))
        assertNotNull(TokenLaunchUiPreviewStore.read(context, bob, walletOne))
        assertNotNull(TokenLaunchUiPreviewStore.read(context, "${alice}2", walletOne))
        assertTrue(preferences.contains("$alice|not-a-wallet"))
        assertFalse(preferences.contains("$alice|$walletOne"))
        assertFalse(preferences.contains("$alice|$walletTwo"))
    }

    private fun memoryContext(): Context {
        val stores = HashMap<String, SharedPreferences>()
        return object : ContextWrapper(paparazzi.context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                stores.getOrPut(name, ::memoryPreferences)
        }
    }

    private fun memoryPreferences(): SharedPreferences {
        val values = HashMap<String, String>()
        val editor = Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "commit" -> true
                "apply" -> Unit
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> HashMap(values)
                "getString" -> values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }
}
