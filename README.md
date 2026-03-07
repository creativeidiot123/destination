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

> [!WARNING]
> Setting a Device Owner requires that **no accounts** (e.g., Google Accounts) are present on the device during provisioning. You can add your accounts back normally after setup is complete.

### Step-by-Step ADB Provisioning

1.  **Install the Application**: Build the project and install the APK onto your Android device.
    ```bash
    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ```

2.  **Remove All Accounts**: Go to your device's **Settings > Passwords & Accounts** and remove all existing accounts temporarily.
    *   *Verify via ADB:*
        ```bash
        adb shell dumpsys account
        ```
    *   Ensure the output shows no active accounts.

3.  **Set Device Owner**: Run the following ADB command to grant Destination Device Owner privileges:
    ```bash
    adb shell dpm set-device-owner com.ankit.destination/.admin.FocusDeviceAdminReceiver
    ```

4.  **Grant Usage Access**: Allow the app to read usage statistics to enforce budgets and schedules:
    ```bash
    adb shell cmd appops set com.ankit.destination GET_USAGE_STATS allow
    ```

5.  **Complete Setup**: Open the Destination app. You can now safely re-add your Google/device accounts.

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
