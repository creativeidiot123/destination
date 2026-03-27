<p align="center">
  <img src="idea/ic_launcher.png" alt="Destination App Icon" width="120" height="120">
</p>

<h1 align="center">Destination 🛡️</h1>

<p align="center">
  <strong>Un-bypassable app blocking for Android. No loopholes. No excuses.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white" />
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" />
  <img src="https://img.shields.io/badge/Privilege-Device%20Owner-FF6B35?style=flat-square" />
</p>

---

Most app blockers are a joke — tap twice, switch launchers, go to adb/ safe mode done. **Destination is different.**

It runs as a **Device Owner**, using Android's `DevicePolicyManager` to *physically suspend packages at the OS level*. When an app is blocked, it doesn't show up. It doesn't launch. It's gone — until you say otherwise.

No accessibility service nonsense. No overlay tricks. The OS itself is doing the blocking.

---

## 🧱 Bypass Prevention

This is what makes Destination actually serious. Every known escape hatch is closed:

| Attack Vector | How Destination handles it |
|---|---|
| **Fast App Switching** | Irrelevant — suspension is OS-level, not overlay-based |
| **Changing System Time** | Blocked. Clock manipulation doesn't fool the scheduler |
| **ADB Commands** | Detected and blocked during active strict sessions |
| **Safe Mode** | Blocked. Safe mode boot is prevented while rules are active |
| **Creating New Users** | User creation is disabled — no "work profile" workarounds |
| **App Cloners** | Cloned package detection prevents duplicate-app bypasses |
| **Sideloading New Apps** | Install blocked entirely during strict schedules |

> If you're the type to find loopholes, you're also the type who needs this app most.

---

## ✨ Features

| Feature | What it does |
|---|---|
| ⏱️ **Usage Budgets** | Daily caps, hourly caps, or max open counts per app |
| 📅 **Advanced Scheduling** | Custom recurring schedules for focus sessions or deep work |
| 🚫 **Strict Install Protection** | Blocks sideloading new apps while a strict schedule is active |
| 🆘 **Emergency Exemptions** | Always keep your dialer, SMS, and critical apps accessible |
| 🌐 **VPN & DNS Lock** | Pornography addicts hate this. Locks VPN and Private DNS settings — pair with any content-filtering DNS and it stays locked, no switching around it |
| ⏳ **Individual App Time Limits** | Set a hard daily screen time cap per app — Instagram gets 15 minutes, it gets 15 minutes, not a second more |
| 👥 **Group Time Limits** | Bundle apps (e.g. all social media) under a shared time budget — open Instagram or TikTok or Twitter, it all counts against the same pool |
| 🔒 **Self-Protection Lock** | Destination protects itself and other blocking/parental control apps from being uninstalled, force-stopped, or tampered with — your past self can't sabotage your future self |

---

## 🚀 Setup (ADB Required): Takes 3 minutes max

> Device Owner access requires ADB. No factory reset needed — just temporarily remove your accounts.

### Step 1 — Install the APK

Download and install the [latest release APK](../../releases/latest).

### Step 2 — Remove Accounts Temporarily

Go to **Settings → Passwords & Accounts** and remove all accounts (Google, etc.). You'll re-add them right after.

Verify no accounts remain:
```bash
adb shell dumpsys account
```

> If any apps still show accounts, uninstall them temporarily.

### Step 3 — Set Device Owner

```bash
adb shell dpm set-device-owner com.ankit.destination/.admin.FocusDeviceAdminReceiver
```

### Step 4 — Allow Restricted Settings

```bash
adb shell cmd appops set com.ankit.destination ACCESS_RESTRICTED_SETTINGS allow
```

### Step 5 — Grant Usage Access

```bash
adb shell cmd appops set com.ankit.destination GET_USAGE_STATS allow
```

### Step 6 — Grant Accessibility

```bash
adb shell settings put secure enabled_accessibility_services com.ankit.destination/.enforce.FocusEnforcementService
```

```bash
adb shell settings put secure accessibility_enabled 1
```

### Step 7 — Grant Notification access

```bash
adb shell cmd notification allow_listener com.ankit.destination/com.ankit.destination.music.MusicPlaybackNotificationListenerService
```

### Step 8 — You're done

Open Destination, then re-add your Google and device accounts. That's it.

---


### Secure and "Forget" the Password

- **The specified locker App:**  
  https://github.com/creativeidiot123/Lockey/releases  
  This will be used to store your emergency password.


Generate a highly complex and random password. It should be impossible to memorize.

Example: b$ca{76r>t<3.w7yrqrt6dab?rtfg3iqrdasa

**Crucial Step:** You must not have easy access to this password.

Entrust it to a reliable person (like a family member or trusted friend) and put it in lockey app. Send it to them and then delete all records of it from your own devices and message history. The goal is that you cannot retrieve it on your own.

Put it in the Lockey app provided above, and put a 450+ character protection minimum.

Visit https://github.com/creativeidiot123/Completely-kill-your-phone-addiction for in app setup.

## 🛠️ Tech Stack

```
Language       → Kotlin (100%)
UI             → Jetpack Compose + Material 3
Async          → Coroutines & Flow
Storage        → Room (SQLite) via PolicyStore
Privilege      → DevicePolicyManager (Device Owner) + Shizuku API
Architecture   → PolicyEngine → EffectivePolicyEvaluator → PolicyApplier
```

---

## ⚠️ Important Notes

- This app **requires Device Owner provisioning** — standard accessibility-based blockers are not used by design.
- Removing Device Owner status will disable all blocking. That's the point — it's a commitment, not a suggestion.
- Keep a backup of your ADB setup in case you need to remove Device Owner later via `adb shell dpm remove-active-admin`.

---

## 📄 License

Licensed under the terms in [LICENSE](LICENSE).
