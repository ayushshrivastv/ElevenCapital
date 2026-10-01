package com.elevencapital.app.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTokenFlowTest {
    private val validDraft = LaunchTokenDraft(
        imageUri = "content://token/race",
        name = "Race",
        symbol = "RACE",
        description = "A community token for people who love racing.",
        xHandle = "race_token",
        telegramHandle = "race_token",
        websiteUrl = "https://race.example/token",
        totalSupply = "1000000000",
        feeBps = "100",
        migrationThresholdSol = "10",
        supplyOnMigrationPercent = "20",
    )

    @Test fun `launch draft route is fixed to Solana and SOL`() {
        val draft = LaunchTokenDraft()

        assertEquals("Solana Mainnet", draft.network)
        assertEquals("SOL", draft.pairedAsset)
        assertEquals("Solana Mainnet", LAUNCH_NETWORK)
        assertEquals("SOL", LAUNCH_PAIRED_ASSET)
    }

    @Test fun `valid draft advances directly to final DBC create step`() {
        val steps = listOf(
            LaunchTokenStep.IMAGE,
            LaunchTokenStep.IDENTITY,
            LaunchTokenStep.DESCRIPTION,
            LaunchTokenStep.DETAILS,
            LaunchTokenStep.DBC,
        )

        assertTrue(steps.all { launchTokenValidationMessage(it, validDraft) == null })
        assertEquals(
            listOf(
                LaunchTokenStep.IDENTITY,
                LaunchTokenStep.DESCRIPTION,
                LaunchTokenStep.DETAILS,
                LaunchTokenStep.DBC,
                LaunchTokenStep.DBC,
            ),
            steps.map(::nextLaunchTokenStep),
        )
        assertEquals(LaunchTokenStep.DETAILS, previousLaunchTokenStep(LaunchTokenStep.DBC))
        assertNull(previousLaunchTokenStep(LaunchTokenStep.IMAGE))
    }

    @Test fun `required steps stay inactive until the user enters something`() {
        val empty = LaunchTokenDraft()

        assertFalse(launchTokenFooterEnabled(LaunchTokenStep.IMAGE, empty))
        assertFalse(launchTokenFooterEnabled(LaunchTokenStep.IDENTITY, empty))
        assertFalse(launchTokenFooterEnabled(LaunchTokenStep.DESCRIPTION, empty))
        assertTrue(launchTokenFooterEnabled(LaunchTokenStep.IDENTITY, empty.copy(name = "R", symbol = "R")))
        assertTrue(launchTokenFooterEnabled(LaunchTokenStep.DESCRIPTION, empty.copy(description = "Short")))
        assertTrue(launchTokenFooterEnabled(LaunchTokenStep.DETAILS, empty))
        assertTrue(launchTokenFooterEnabled(LaunchTokenStep.DBC, empty))
    }

    @Test fun `final create validation refuses drafts missing required earlier fields`() {
        assertEquals(
            "Choose an image for your token.",
            launchTokenDraftValidationMessage(LaunchTokenDraft()),
        )
        assertEquals(
            "Enter a token name.",
            launchTokenDraftValidationMessage(validDraft.copy(name = "")),
        )
        assertEquals(
            "Symbol must be 1 to 10 letters or numbers.",
            launchTokenValidationMessage(
                LaunchTokenStep.IDENTITY,
                validDraft.copy(symbol = ""),
            ),
        )
        assertEquals(
            "Description must be at least 10 characters.",
            launchTokenDraftValidationMessage(validDraft.copy(description = "Too short")),
        )
    }

    @Test fun `symbol and social input are normalized for display`() {
        assertEquals("RACE2026", normalizeLaunchTokenSymbol("  \$ra-ce_2026!!!  "))
        assertEquals("1234567890", normalizeLaunchTokenSymbol("123456789012345"))
        assertEquals("RC", normalizeLaunchTokenSymbol("râcé"))
        assertEquals(
            "eleven_wallet",
            normalizeLaunchSocialHandle("https://x.com/@eleven_wallet", "x.com"),
        )
        assertEquals(
            "eleven_group",
            normalizeLaunchSocialHandle("http://www.t.me/eleven_group", "t.me"),
        )
    }

    @Test fun `token name uses the backend UTF-8 byte limit without splitting emoji`() {
        val emojiName = "😀".repeat(9)
        assertEquals(
            "Token name must be 32 UTF-8 bytes or fewer.",
            launchTokenValidationMessage(LaunchTokenStep.IDENTITY, validDraft.copy(name = emojiName)),
        )
        assertEquals("😀".repeat(8), truncateLaunchTokenName(emojiName))
        assertEquals(
            "Token name cannot start or end with spaces.",
            launchTokenValidationMessage(LaunchTokenStep.IDENTITY, validDraft.copy(name = " Race")),
        )
    }

    @Test fun `optional website accepts only complete secure URLs`() {
        assertNull(launchTokenValidationMessage(LaunchTokenStep.DETAILS, validDraft))
        assertEquals(
            "Enter a valid X handle.",
            launchTokenValidationMessage(
                LaunchTokenStep.DETAILS,
                validDraft.copy(xHandle = "invalid handle"),
            ),
        )
        assertEquals(
            "Enter a valid Telegram handle.",
            launchTokenValidationMessage(
                LaunchTokenStep.DETAILS,
                validDraft.copy(telegramHandle = "bad"),
            ),
        )
        assertEquals(
            "Website must be a public https:// URL.",
            launchTokenValidationMessage(
                LaunchTokenStep.DETAILS,
                validDraft.copy(websiteUrl = "http://race.example"),
            ),
        )
        assertEquals(
            "Website must be a public https:// URL.",
            launchTokenValidationMessage(
                LaunchTokenStep.DETAILS,
                validDraft.copy(websiteUrl = "https://user@race.example"),
            ),
        )
    }

    @Test fun `DBC economics are defaulted but remain bounded when customized`() {
        val defaults = LaunchTokenDraft()
        assertEquals("1000000000", defaults.totalSupply)
        assertEquals("100", defaults.feeBps)
        assertEquals("10", defaults.migrationThresholdSol)
        assertEquals("20", defaults.supplyOnMigrationPercent)
        assertEquals("", defaults.initialMarketCapSol)
        assertEquals("", defaults.graduationMarketCapSol)
        assertEquals("Classic", defaults.curvePreset)
        assertFalse(defaults.launchProtectionEnabled)
        assertFalse(defaults.vestCreatorTokens)
        assertEquals("10", defaults.vestedSupplyPercent)
        assertEquals("12", defaults.vestingDurationMonths)
        assertEquals("Monthly", defaults.vestingFrequency)
        assertEquals("1B tokens", launchTokenSupplyReview(defaults.totalSupply))
        assertEquals(
            "Trading fee must be from 25 to 1,000 bps.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(feeBps = "1001")),
        )
        assertEquals(
            "Trading fee must be from 25 to 1,000 bps.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(feeBps = "24")),
        )
        assertEquals(
            "Total supply must be a whole number from 1,000,000 to 1,000,000,000,000.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(totalSupply = "1000000000001")),
        )
        assertEquals(
            "Total supply must be a whole number from 1,000,000 to 1,000,000,000,000.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(totalSupply = "999999")),
        )
        assertEquals(
            "Graduation target must be from 0.01 to 10,000 SOL.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(migrationThresholdSol = "0.009")),
        )
        assertEquals(
            "Supply on migration must be from 1% to 90%.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(supplyOnMigrationPercent = "0")),
        )
        assertNull(launchTokenValidationMessage(LaunchTokenStep.DETAILS, validDraft.copy(
            xHandle = "", telegramHandle = "", websiteUrl = "",
        )))
    }

    @Test fun `optional market cap targets require a valid increasing pair`() {
        assertNull(launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft))
        assertFalse(launchUsesMarketCapCurve(validDraft))
        assertEquals(
            "Graduation market cap must be from 0.01 to 10,000,000 SOL.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(initialMarketCapSol = "10")),
        )
        assertEquals(
            "Starting market cap must be from 0.01 to 10,000,000 SOL.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(
                initialMarketCapSol = "0.009", graduationMarketCapSol = "100",
            )),
        )
        assertEquals(
            "Graduation market cap must be higher than starting market cap.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(
                initialMarketCapSol = "100", graduationMarketCapSol = "100",
            )),
        )
        assertNull(launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(
            initialMarketCapSol = "10", graduationMarketCapSol = "100",
        )))
        val marketCapDraft = validDraft.copy(
            initialMarketCapSol = "10", graduationMarketCapSol = "100",
            migrationThresholdSol = "",
        )
        assertTrue(launchUsesMarketCapCurve(marketCapDraft))
        assertNull(launchTokenValidationMessage(LaunchTokenStep.DBC, marketCapDraft))
        assertTrue(launchTokenFooterEnabled(LaunchTokenStep.DBC, marketCapDraft))
        assertEquals("Choose a curve shape.", launchTokenValidationMessage(
            LaunchTokenStep.DBC, validDraft.copy(curvePreset = "Unknown"),
        ))
    }

    @Test fun `vesting fields validate only while creator vesting is selected`() {
        val invalid = validDraft.copy(vestedSupplyPercent = "0", vestingDurationMonths = "0",
            vestingFrequency = "Never")
        assertNull(launchTokenValidationMessage(LaunchTokenStep.DBC, invalid))
        assertEquals(
            "Vested supply must be from 1% to 100%.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, invalid.copy(vestCreatorTokens = true)),
        )
        assertEquals(
            "Vesting duration must be from 1 to 120 months.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, invalid.copy(
                vestCreatorTokens = true, vestedSupplyPercent = "10",
            )),
        )
        assertEquals(
            "Choose a vesting release schedule.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, invalid.copy(
                vestCreatorTokens = true, vestedSupplyPercent = "10", vestingDurationMonths = "12",
            )),
        )
        assertNull(launchTokenValidationMessage(LaunchTokenStep.DBC, invalid.copy(
            vestCreatorTokens = true, vestedSupplyPercent = "10", vestingDurationMonths = "12",
            vestingFrequency = "Weekly", launchProtectionEnabled = true,
        )))
        assertEquals("Leave some supply for bonding-curve trading.",
            launchTokenValidationMessage(LaunchTokenStep.DBC, validDraft.copy(
                vestCreatorTokens = true, vestedSupplyPercent = "80",
                supplyOnMigrationPercent = "20",
            )))
    }

    @Test fun `older saved launch forms restore with advanced defaults`() {
        val legacy = listOf(
            "content://token/race", "Race", "RACE", "A community token for racing.",
            "race_token", "race_token", "https://race.example/token",
            "1000000000", "100", "10", "20",
        )
        val restored = restoreLaunchTokenDraft(legacy)
        assertEquals("Race", restored?.name)
        assertEquals("", restored?.initialMarketCapSol)
        assertFalse(restored?.launchProtectionEnabled ?: true)
        assertFalse(restored?.vestCreatorTokens ?: true)
        assertEquals("Monthly", restored?.vestingFrequency)
        assertEquals("Classic", restored?.curvePreset)
        assertNull(restoreLaunchTokenDraft(legacy.dropLast(1)))
    }

    @Test fun `token detail formats the reviewed launch fee without rounding`() {
        assertEquals("1%", launchFeePercent("100"))
        assertEquals("0.25%", launchFeePercent("25"))
        assertEquals("10%", launchFeePercent("1000"))
    }

    @Test fun `decimal input remains bounded and deterministic`() {
        assertEquals("10.123456", normalizeLaunchDecimal("1x0..12345678", 6, 6))
        assertEquals("123456", normalizeLaunchDecimal("123456789", 6, 6))
    }

    @Test fun `confirmed token link always targets mainnet Solscan`() {
        val mint = "Vote111111111111111111111111111111111111111"
        assertEquals("https://solscan.io/token/$mint", mainnetSolscanTokenUrl(mint))
        assertNull(mainnetSolscanTokenUrl("not a mint"))
    }
}
