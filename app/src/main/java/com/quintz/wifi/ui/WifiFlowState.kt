package com.quintz.wifi.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.quintz.wifi.core.WifiActionContext
import com.quintz.wifi.model.AccessPointRadio

enum class WifiRequestKind { BIND, PREFER }
data class PendingWifiRequest(val kind: WifiRequestKind, val ssid: String,
    val radio: AccessPointRadio?, val password: String, val source: String, val correlationId: String,
    val context: WifiActionContext = WifiActionContext(false, ssid, "", null))

/** ViewModel memory survives rotation. None of these secrets are put in SavedState or disk. */
internal class WifiFlowState {
    var showCredentialDialog by mutableStateOf(false)
    var replacementPassword by mutableStateOf("")
    var credentialTarget by mutableStateOf<WifiActionContext?>(null)
    var credentialBssid by mutableStateOf("")
    var showPasswordDialog by mutableStateOf(false)
    var showMacPolicyDialog by mutableStateOf(false)
    var targetSsidForMacPolicy by mutableStateOf("")
    var macRequestContext by mutableStateOf<WifiActionContext?>(null)
    var pendingLockAction by mutableStateOf<PendingWifiRequest?>(null)
    var passwordInput by mutableStateOf("")
    var isPasswordVisible by mutableStateOf(false)
    var targetRadioForPassword by mutableStateOf<AccessPointRadio?>(null)
    var pendingBindRadio by mutableStateOf<AccessPointRadio?>(null)
    var pendingBindSource by mutableStateOf("")
    var pendingBindContext by mutableStateOf<WifiActionContext?>(null)
    var pendingPreferRadio by mutableStateOf<AccessPointRadio?>(null)
    var pendingPreferSource by mutableStateOf("")
    var pendingPreferContext by mutableStateOf<WifiActionContext?>(null)
    var preferRadioForPassword by mutableStateOf<AccessPointRadio?>(null)
    var passwordRequestContext by mutableStateOf<WifiActionContext?>(null)
    var isPreparingPrefer by mutableStateOf(false)

    fun clearSecrets() {
        replacementPassword = ""; passwordInput = ""; isPasswordVisible = false
        pendingLockAction = null; showCredentialDialog = false; showPasswordDialog = false
        showMacPolicyDialog = false; targetRadioForPassword = null; preferRadioForPassword = null
        credentialTarget = null; passwordRequestContext = null; macRequestContext = null
        pendingBindRadio = null; pendingPreferRadio = null
        pendingBindContext = null; pendingPreferContext = null
    }
}
