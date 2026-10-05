package com.quintz.wifi.shizuku;
import android.os.Bundle;

// Only reachable through the permission-gated Shizuku binding, never exported as an Android service.
interface IWifiProfileService {
    Bundle call(String operation, in Bundle request) = 0;
    void destroy() = 16777114;
}
