<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Destination App Icon" width="128" height="128">
</p>

<h1 align="center">Destination 🛡️</h1>

<p align="center">
  <strong>System-level digital wellbeing and app blocking for Android.</strong>
</p>

<p align="center">
  Built for people who want restrictions that are durable, enforceable, and hard to bypass.
</p>

---

## Overview

**Destination** is a high-friction digital wellbeing app for Android that blocks apps at the **operating system level**.

Unlike typical app blockers that depend on accessibility overlays, soft warnings, or easy-to-disable permissions, Destination is designed to run as a **Device Owner** and enforce restrictions using Android’s `DevicePolicyManager`.

That means when a rule becomes active, the target app is not just covered or discouraged — it is **actually suspended by the OS**.

This makes blocking far more reliable, persistent, and resistant to common bypass methods.

---

## Why Destination?

Most digital wellbeing apps are easy to break:

- disable accessibility
- force stop the app
- revoke permissions
- install alternatives
- exploit timing gaps or state drift

Destination is built to avoid that pattern.

It focuses on **deterministic enforcement**, **strict rule evaluation**, and **real device-level control** so that blocking remains effective when it actually matters.

---

## Features

### OS-level app blocking
Blocks apps using `DevicePolicyManager.setPackagesSuspended()` so restricted apps become inaccessible at the system level.

### Usage budgets
Set limits based on:

- daily usage time
- hourly usage time
- app open count

### Scheduling
Create recurring schedules for focus time, work sessions, sleep windows, or custom blocking periods.

### Strict install protection
During strict schedules, Destination can prevent workaround behavior by blocking or controlling newly installed apps.

### Emergency safeguards
Protect critical device usability with emergency-safe handling for apps like the default dialer, SMS, or other essential tools.

### Deterministic policy evaluation
Rules are evaluated through a structured policy pipeline so overlapping schedules, limits, and app/group rules resolve consistently.

### Diagnostics and state tracking
Track why an app is blocked, what rule triggered it, and how the current enforcement state was derived.

---

## Installation & Setup

Because Destination uses system-level Android management APIs, it must be provisioned as a **Device Owner** through ADB.

> [!IMPORTANT]
> Standard Android Device Owner provisioning usually requires a factory reset.  
> In many cases, you can provision without wiping the device by temporarily removing all accounts first.

---

## ADB Provisioning Steps

### 1. Install the app
Build and install the latest APK on your Android device.

```bash
# install your latest release APK
adb install app-release.apk
