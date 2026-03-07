<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Destination App Icon" width="128" height="128">
</p>

# Destination 🛡️
> *Un-bypassable Digital Wellbeing & App Blocking for Android.*

**Destination** is an advanced, high-friction digital wellbeing application engineered to provide strict, system-level app blocking. Unlike standard blockers that rely on easily bypassed accessibility overlays, Destination operates as a **Device Owner**, utilizing Android's `DevicePolicyManager` to physically suspend packages. 

Once a rule is active, apps are locked out at the OS level—making restrictions robust, persistent, and incredibly difficult to circumvent.

---

## ✨ Core Features

*   **System-Level Package Suspension**: Enforces blocks natively via `DevicePolicyManager.setPackagesSuspended()`. When an app is blocked, it is completely inaccessible.
*   **Granular Usage Budgets**: Set precise daily caps, hourly caps, or limit the number of app opens.
*   **Advanced Scheduling**: Create custom, recurring schedules for deep work or focus sessions.
*   **Strict Install Protection**: When a "Strict Schedule" is active, side-loading or installing new apps is actively blocked to prevent workarounds (`STRICT_INSTALL` staging).
*   **Emergency Safety Nets**: Exempt critical apps (like the default Dialer or SMS) and manage "Emergency Apps" to ensure the device remains safe and usable during total lockdowns.
*   **Deep Diagnostics & Tracking**: Built-in verifiable state architectures and usage snapshots track exactly why an app is suspended, resolving conflicting rules deterministically.

---

## 🚀 Installation & Setup

Because Destination requires system-level privileges to suspend packages, it **must** be provisioned as the Device Owner via ADB. 

> [!IMPORTANT]
> **No device reset or factory wipe is needed!** Standard Device Owner provisioning strictly requires a factory reset, but by temporarily removing all accounts, you can bypass this requirement.

### Step-by-Step ADB Provisioning

1.  **Install the Application**: Build the project and install the APK onto your Android device.
    ```install latest apk in release
    ```

2.  **Remove All Accounts Temporarily**: Go to your device's **Settings > Passwords & Accounts** and remove all existing accounts (like Google Accounts). *You will add these back immediately after setup.*
    *   *Verify via ADB:*
        ```bash
        adb shell dumpsys account
        ```
    *   Ensure the output shows no active accounts, if it shows any apps and their accounts, uninstall those apps temporarily.

3.  **Set Device Owner**: Run the following ADB command to grant Destination Device Owner privileges:
    ```bash
    adb shell dpm set-device-owner com.ankit.destination/.admin.FocusDeviceAdminReceiver
    ```

4.  **Grant Usage Access**: Allow the app to read usage statistics to enforce budgets and schedules:
    ```bash
    adb shell cmd appops set com.ankit.destination GET_USAGE_STATS allow
    ```

5.  **Complete Setup**: Open the Destination app. You can now safely re-add your Google and device accounts in your system settings.

---

## 🛠️ Architecture & Tech Stack

Destination is built entirely with modern Android development practices:

*   **Language**: 100% Kotlin
*   **UI Framework**: Jetpack Compose (Material 3)
*   **Concurrency**: Kotlin Coroutines & Flow
*   **Local Storage**: Room Database (SQLite) for deterministic policy storage (`PolicyStore`)
*   **Privilege Escalation**: `DevicePolicyManager` (Device Owner) & Shizuku API
*   **Architecture**: Multi-stage evaluation pipeline prioritizing canonical union-of-reasons (`PolicyEngine` -> `EffectivePolicyEvaluator` -> `PolicyApplier`).

---

## 📝 License

This project is licensed under the terms specified in the [LICENSE](LICENSE) file.
