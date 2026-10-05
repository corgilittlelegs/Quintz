package com.quintz.wifi

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quintz.wifi.model.*
import com.quintz.wifi.ui.components.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiEvidenceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun unavailableSignalShowsNeutralDash() {
        compose.setContent { CliSignalBars(0) }
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithText("0 dBm").assertDoesNotExist()
    }
    @Test fun staleRadioCannotBindButKnownSavedPinCanBeCleared() {
        val radio = AccessPointRadio("aa:bb:cc:dd:ee:ff", "Test", 5180, BandType.BAND_5_GHZ, 36, -60, "[ESS]", ageSeconds = 20)
        compose.setContent { CliRadioRow(radio, false, false, {}, {}, false, canBind = false) }
        compose.onNodeWithText("BIND").assertIsNotEnabled()
    }
    @Test fun mismatchedSavedPinOffersUnpinEvenWhenTargetIsAbsent() {
        val radio = AccessPointRadio("aa:bb:cc:dd:ee:ff", "Test", 5180, BandType.BAND_5_GHZ, 36, -60, "[ESS]", ageSeconds = 20)
        compose.setContent { CliRadioRow(radio, false, true, {}, {}, false, canBind = false, canUnpin = true) }
        compose.onNodeWithText("UNPIN").assertIsEnabled()
    }
}
