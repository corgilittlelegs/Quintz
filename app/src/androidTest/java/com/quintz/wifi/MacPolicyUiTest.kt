package com.quintz.wifi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quintz.wifi.model.*
import com.quintz.wifi.ui.components.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MacPolicyUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun freshInstallShowsAndroidPolicyWithoutAQuintzPreferenceOrPassword() {
        var opened: String? = null
        compose.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CliConnectedHeroPanel(
                    status = WifiStatus(isConnected = true, ssid = "Test", securityType = "2",
                        profileInspectionKnown = true, configuredMacPolicy = MacAddressPolicy.DEVICE),
                    isOperating = false, recoveryThresholdRssi = -75, onToggleLock = {},
                    onGetMacPolicy = { null }, onChangeMacPolicy = { opened = it }, onManagePassword = {},
                    savedCredentialState = SavedCredentialState.NOT_SAVED
                )
            }
        }
        compose.onNodeWithText("DEVICE MAC ▾").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Test", opened) }
        compose.onNodeWithText("Quintz preference: Not set").assertExists()
        compose.onNodeWithText("No · Separate from Android's saved network").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ADD PASSWORD ↗").assertExists()
        compose.onNodeWithText("UNKNOWN ▾").assertDoesNotExist()
    }
    @Test fun userChoosesAnExplicitPolicy() {
        var selected: MacAddressPolicy? = null
        compose.setContent {
            MacPolicyDialog("Test", { selected = it }, {}, currentPolicy = MacAddressPolicy.DEVICE,
                selectionDescription = "Save for the next connection without reconnecting.")
        }
        compose.onNodeWithText("Android configured: Device MAC").assertIsDisplayed()
        compose.onNodeWithText("USE RANDOMIZED MAC (PRIVACY)").performClick()
        compose.runOnIdle { assertEquals(MacAddressPolicy.RANDOMIZED, selected) }
    }
}
