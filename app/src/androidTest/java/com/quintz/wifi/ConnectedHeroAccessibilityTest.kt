package com.quintz.wifi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quintz.wifi.model.*
import com.quintz.wifi.ui.components.CliConnectedHeroPanel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectedHeroAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    private fun verifyControls(width: Int, fontScale: Float) {
        var passwordOpened = false
        var policyOpened = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Column(Modifier.width(width.dp).verticalScroll(rememberScrollState())) {
                    CliConnectedHeroPanel(
                        status = WifiStatus(isConnected = true, ssid = "Test", securityType = "2",
                            profileInspectionKnown = true, configuredMacPolicy = MacAddressPolicy.DEVICE),
                        isOperating = false, recoveryThresholdRssi = -75, onToggleLock = {},
                        onGetMacPolicy = { MacAddressPolicy.DEVICE }, onChangeMacPolicy = { policyOpened = true },
                        onManagePassword = { passwordOpened = true }, savedCredentialState = SavedCredentialState.SAVED
                    )
                }
            }
        }
        compose.onNodeWithText("DEVICE MAC ⇄").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("MANAGE PASSWORD ↗").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(passwordOpened); assertTrue(policyOpened) }
    }

    @Test fun largeTextInPortraitKeepsBothActionsReachable() = verifyControls(280, 1.8f)
    @Test fun compactWindowWithMaximumTextKeepsBothActionsReachable() = verifyControls(200, 2f)
}
