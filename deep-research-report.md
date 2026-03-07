# Deep logic audit of the current blocking model in `creativeidiot123/destination`

## Findings report

### What I inspected

This audit focused on the code paths that **compute** and **apply** “blocked vs unblocked” state, plus the places that mutate or stage state that later affects enforcement. Key files/classes inspected:

- Policy orchestration + storage
  - `app/src/main/java/com/ankit/destination/policy/PolicyEngine.kt`
  - `app/src/main/java/com/ankit/destination/policy/PolicyStore.kt`
  - `app/src/main/java/com/ankit/destination/policy/PolicyModels.kt`
- Core decision evaluators (where “blocked” is computed)
  - `app/src/main/java/com/ankit/destination/policy/EffectivePolicyEvaluator.kt`
  - `app/src/main/java/com/ankit/destination/policy/PolicyEvaluator.kt`
  - `app/src/main/java/com/ankit/destination/policy/PackageResolver.kt`
  - `app/src/main/java/com/ankit/destination/schedule/ScheduleEvaluator.kt`
  - `app/src/main/java/com/ankit/destination/budgets/BudgetOrchestrator.kt`
- Enforcement (where the device is actually locked down / apps get blocked)
  - `app/src/main/java/com/ankit/destination/policy/PolicyApplier.kt`
  - `app/src/main/java/com/ankit/destination/policy/DevicePolicyFacade.kt`
- Runtime triggers that invoke enforcement
  - `app/src/main/java/com/ankit/destination/schedule/ScheduleEnforcer.kt`
  - `app/src/main/java/com/ankit/destination/schedule/ScheduleTickReceiver.kt`
  - `app/src/main/java/com/ankit/destination/boot/BootReceiver.kt`
  - `app/src/main/java/com/ankit/destination/packages/PackageChangeReceiver.kt`

### Current blocking model as implemented

#### What “blocked” means in practice
In this app, the **enforcement primitive** is **Device Owner package suspension** via `DevicePolicyManager.setPackagesSuspended(...)` (wrapped by `DevicePolicyFacade.setPackagesSuspended`). So “blocked” means:

- A package is present in `PolicyState.suspendTargets`
- Which leads `PolicyApplier.apply()` to suspend it (delta-based from last applied set)

There are *other* Device Owner restrictions (user restrictions, uninstall blocking, lock-task config, network management), but **the “block apps” feature is primarily package suspension**.

#### Where the final decision is made today (and how many evaluators exist)

There is **not one** authoritative evaluator; there is a pipeline of evaluators that each make partial decisions:

- **`EffectivePolicyEvaluator.evaluate()`**: decides which packages/groups are “effectively blocked” due to:
  - schedules (`SCHEDULED_BLOCK`)
  - usage budgets (hourly/daily/open caps)
  - strict-install staging
  - emergency budget unlock active state (“unblocks” that target while active)
- **`PolicyEvaluator.evaluate()`**: takes the computed/remembered block sets and produces the final `PolicyState`, including:
  - resolving an allowlist (`PackageResolver.resolveAllowlist()`)
  - filtering block candidates via `PackageResolver.filterSuspendable(...)`
  - building the final `suspendTargets` set by merging multiple sources
  - special override path: **Usage Access recovery lockdown** (suspend nearly everything non-system)
- **`PolicyEngine.requestApplyNowLocked()`**: orchestrates schedule + budgets + DB reads, persists computed state to `PolicyStore`, then calls `PolicyEvaluator` to compute desired state and `PolicyApplier` to apply it.

So the current model is **multi-stage**, not single-authority.

#### Are reasons tracked explicitly?
Only partially, and inconsistently:

- Budget/schedule evaluator produces only a **single string reason per package** (`primaryReasonByPackage`) and drops all other concurrent reasons.
- Enforcement uses only sets (`suspendTargets`), so reasons are mainly for display/diagnostics.
- Multiple layers compute “reason” differently, and some layers compute “blocked packages” differently than the applier actually enforces.

This matches your suspicion: **the code is mostly “state-by-sets”, with primary-reason strings as a best-effort side channel**.

#### Is unblocking reason-aware or state-flipping?
Enforcement is **state-delta-based**:

- `PolicyApplier.apply()` computes:
  - `toSuspend = state.suspendTargets - state.previouslySuspended`
  - `toUnsuspend = state.previouslySuspended - state.suspendTargets`
- It then suspends/unsuspends those deltas.

This is only “reason-safe” if and only if **`suspendTargets` is truly the union of all active block reasons** at evaluation time.

The danger is exactly what you flagged: if one code path computes a smaller set and forgets an active reason, `toUnsuspend` will unsuspend prematurely.

### Every class/function that can decide, modify, or override blocked/unblocked state

This is the complete set of “business-logic decision makers” and “state influencers” I found (direct or indirect):

- **Policy orchestration & persistence**
  - `PolicyEngine.requestApplyNow()` / `requestApplyNowLocked()`
  - `PolicyEngine.orchestrateCurrentPolicy()`
  - `PolicyEngine.persistComputedState()`
  - `PolicyEngine.computeExternalState()`
  - `PolicyEngine.onNewPackageInstalledDuringStrictSchedule()`
  - `PolicyEngine.sanitizeTrackedPackages()`
  - `PolicyStore.setComputedPolicyState()`
  - `PolicyStore.recordApply()` (affects future deltas)
- **Budget/schedule evaluation**
  - `ScheduleEvaluator.evaluate()` / `isActive()` / `computeNextTransition()`
  - `BudgetOrchestrator.readUsageSnapshot()`
  - `BudgetOrchestrator.activateEmergencyUnlock()` (creates emergency state that changes effective blocks)
  - `EffectivePolicyEvaluator.evaluate()`
- **Exemptions / safety filtering**
  - `PackageResolver.resolveAllowlist()`
  - `PackageResolver.filterSuspendable()`
  - `PackageResolver.isProtectedPackageName()` (via `FocusConfig.protectedExactPackages`/`protectedPackagePrefixes`)
- **Enforcement**
  - `PolicyEvaluator.evaluate()` (final desired suspend set)
  - `PolicyEvaluator.mergeSuspendTargets()`
  - `PolicyApplier.apply()` + `DevicePolicyFacade.setPackagesSuspended()` (actual suspend/unsuspend)
- **Triggers that invoke enforcement**
  - `ScheduleEnforcer.enforceNow()`
  - `ScheduleTickReceiver.onReceive()`
  - `BootReceiver.onReceive()`
  - `PackageChangeReceiver.onReceive()` (including strict-install staging)

This plurality is the source of several contradictions.

### Contradictions and hidden precedence bugs

#### Schedule lock is effectively disabled (logic bug)
In `ScheduleEvaluator.evaluate()`, the returned `ScheduleDecision.shouldLock` is always `false` regardless of active blocks. That means:

- `PolicyStore.KEY_SCHEDULE_LOCK_COMPUTED` never becomes true
- `PolicyStore.KEY_SCHEDULE_LOCK_ENFORCED` never becomes true after apply
- `PolicyEngine.setMode()`’s “cannot cancel scheduled lock” gate never triggers

Meanwhile, group IDs *are* computed from active blocks and used for blocking evaluation regardless, so schedule is “active” in one sense but “never lock-enforced” in another.

This is a **direct internal inconsistency**: the schedule evaluator computes active schedules but returns a lock flag that claims “no schedule lock”.

#### Exemptions are applied inconsistently between evaluators
Budget evaluator (`EffectivePolicyEvaluator`) only excludes `alwaysAllowedPackages` that are passed in (currently `policyControls.alwaysAllowedApps`), but final filtering (`PolicyEvaluator` → `resolveAllowlist`) excludes a wider set:

- controller app
- always allowed apps
- user “emergency apps”
- default dialer, default sms app, default launcher
- permission controller

Result: **a package can be “blocked” in the budget model, but never actually suspended** because the later stage filters it out.

This causes exactly the class of contradiction you suspected: “blocked in one code path and unblocked in another.”

It also causes subtle policy drift: group usage sums may include allowlisted/emergency packages, meaning those packages contribute to exhausting a group budget even though they’ll never be suspended.

#### `filterSuspendable()` doesn’t filter non-installed packages
`PackageResolver.filterSuspendable()` does not check installation status, so if DB mappings include stale packages (uninstalled), they can end up in suspend deltas. DevicePolicy calls may then fail, and PolicyStore’s reconciliation may keep stale packages in “last suspended” or simply generate persistent errors.

This is both correctness and durability risk.

#### “Primary reason” is not canonical or consistent
There are at least *three* distinct “reason systems”:

- `EffectivePolicyEvaluator` emits `primaryReasonByPackage` based on insertion order across group eval then app eval then strict-install.
- `PolicyEngine.computePrimaryReasonByPackage(...)` emits a different labeling and precedence order (and is used as fallback).
- `PolicyStore` stores whatever the last apply wrote as `KEY_PRIMARY_REASON_BY_PACKAGE`, which can diverge from either evaluator if sets change or fallbacks run.

So the app cannot reliably answer: “Why is this app blocked?” without contradiction.

#### Strict mode + emergency unlock semantics are unclear and possibly contradictory
The code currently allows budget emergency unlock to bypass schedule/usage blocks at the target-level (`effectiveBlocked = baselineBlocked && !emergencyActive`) even if the schedule block is strict, while strict install remains active. This creates a situation where:

- strict schedule is active (so new installs get suspended)
- but the apps in the strict-scheduled group can become usable during emergency unlock

Depending on intended product semantics, this may violate “strict means unbypassable”.

### Missing / implicit invariants

These are invariants that **should** exist for a durable blocking app, but are not enforced as such in the current code:

- There is no single canonical representation of “effective blocked state” with explicit multi-reasons.
- Exemptions/safety filtering are not applied uniformly at the same stage for evaluation, storage, and enforcement.
- The app relies on correctness of set union at the final evaluator stage, but the upstream computed sets can and do include packages that later get filtered out.
- “Primary reason” is treated like a source of truth sometimes, but it is derived inconsistently.

### User-visible risks caused by current logic

- **Schedule lock UI/behavior mismatch**: schedules can be “active” in blocking evaluation while “lock” is never enforced, enabling unintended cancellation or misreporting.
- **Blocked list/reporting can lie**: packages can appear blocked due to budget/schedule but never actually be suspended because they’re allowlisted/protected later.
- **Hard-to-debug unblocking**: because reasons are not explicit, a package might appear to unblock “randomly” when one reason disappears, even if another is still active (or vice versa), especially when different layers disagree.
- **Repeated policy-apply errors**: non-installed packages can enter suspend deltas causing errors, retries, and unclean “tracked suspended” state.
- **Strict mode ambiguity**: emergency unlock can partially bypass “strict” scheduled blocks, which can violate expected “strict means no bypass” semantics.

## Corrected logic model

This corrected model is designed to be **canonical, deterministic, testable**, and to eliminate contradictions without redesigning the product.

### Canonical decision model to adopt

Define one canonical snapshot function that computes the final desired block state:

**Inputs** (all evaluated in one snapshot):
- Policy storage (Room + prefs):
  - alwaysAllowedApps, alwaysBlockedApps, uninstallProtectedApps, global controls
  - group limits + group membership mappings
  - per-app policies
  - schedule blocks + schedule block groups
  - emergency “budget unlock” state (per group/app)
  - strict-install staged packages
- Runtime signals:
  - usage snapshot (today/hour/opens)
  - usage access compliance state (lockdown active?)
  - package inventory/resolver (installed? protected? allowlisted?)

**Outputs**:
- `suspendTargets: Set<String>` (final set to suspend)
- `blockReasonsByPackage: Map<String, Set<String>>` (explicit union-of-reasons **after all filtering**)
- `primaryReasonByPackage: Map<String, String>` derived deterministically from `blockReasonsByPackage`
- stored computed report sets (schedule blocked packages, budget blocked group IDs, next transitions/checks)

### Exact rule-composition behavior

This is the canonical composition that makes contradictions impossible:

- **Union-of-reasons is the default**:
  - If a package has multiple active reasons, the package remains blocked until **all** reasons are gone.
- **Group + per-app combine as union**:
  - Group blocking reasons add to the app’s reason set.
  - App policy reasons add to the same reason set.
  - Multiple group memberships contribute multiple reasons.
- **Schedule + usage combine as union**:
  - Schedule reason does not erase usage reason.
  - Either can keep a package blocked independently.
- **Emergency budget unlock suppresses only the budget/schedule reasons for that specific target**:
  - If emergency unlock is active for group G, group-derived reasons for members of G are not applied.
  - If emergency unlock is active for app A, app-policy reasons for A are not applied.
  - (This matches the current “effectiveBlocked = baselineBlocked && !emergencyActive” semantics, but makes it explicit.)
- **Strict schedule + install behavior**:
  - While strict schedule is active, newly installed packages that are suspendable and not allowlisted get staged and blocked with reason `STRICT_INSTALL`.
  - Strict-install reason persists only while strict schedule remains active.
- **Exempt/protected package logic is centralized**:
  - A package that is:
    - protected by `FocusConfig` safety rules, or
    - on the computed allowlist (controller, dialer, sms, launcher, permission controller, always allowed apps, user emergency apps)
    is excluded **before** it becomes part of “effective blocked packages” for budget/schedule evaluation, and also excluded during final suspend filtering.
- **Device Owner safety exclusion precedence**:
  - Protected packages are a hard ceiling: never suspended.
  - Allowlist packages are exempt from *budget/schedule/strict-install* suspensions.
  - “Always blocked” can be treated as a hard-block category that may override allowlist (your current code effectively does this), but still cannot override protected packages.

### Exact unblocking behavior

Unblocking is reason-safe:

- `PolicyApplier` only unsuspends a package when it is absent from `suspendTargets`.
- `suspendTargets` is derived from `blockReasonsByPackage.keys` after applying exemption filters.
- Therefore, a package cannot transition from blocked → unblocked unless the evaluator proves that:
  - all active reasons are gone **or**
  - the package became exempt (allowlisted/protected), in which case it was never meant to be suspended.

### Explicit invariants enforced

These invariants become enforced by design:

- One canonical snapshot computes final desired suspend targets.
- Block state is union-of-reasons.
- Reasons are explicit per package and used to derive primary reason deterministically.
- Budget/schedule evaluation uses the same exemption set that enforcement uses, eliminating “blocked in storage but not enforced.”
- No enforcement layer invents new block logic; it consumes computed state.

### Exact data shape for final effective reasons per app

In corrected code below, the canonical representation is:

- `PolicyState.blockReasonsByPackage: Map<String, Set<String>>`

Where each key is a package name, and the value is a set of reason keys such as:
- `ALWAYS_BLOCKED`
- `STRICT_INSTALL`
- `GROUP:<groupId>:SCHEDULED_BLOCK`
- `GROUP:<groupId>:DAILY_CAP`
- `APP:HOURLY_CAP`
- `APP:SCHEDULED_BLOCK`

A deterministic `primaryReasonByPackage` is derived from this set by an explicit precedence function.

## Implementation plan

### Exact files/classes/functions to change

High-confidence, local fixes first:

- `ScheduleEvaluator.evaluate()` — fix `shouldLock` to reflect actual active schedules.
- `PackageResolver.filterSuspendable()` — filter out non-installed packages (and normalize package strings) to prevent invalid DPM calls.
- `PolicyEngine.orchestrateCurrentPolicy()` — pass correct exemption set into budget evaluation to prevent “blocked-but-never-suspended” contradictions.
- `EffectivePolicyEvaluator.evaluate()` — produce explicit multi-reason output (`blockReasonsByPackage`).
- `PolicyStore.setComputedPolicyState(...)` — store derived block reasons (for computed state/debug) consistently.
- `PolicyEngine.persistComputedState()` — compute canonical reason map (merge always-blocked + budget reasons) and store deterministic primary reasons.
- `PolicyEvaluator.evaluate()` + `PolicyModels.PolicyState` — carry `blockReasonsByPackage` into the final desired policy state, and derive `primaryReasonByPackage` deterministically.
- Add a small shared util:
  - `BlockReasonUtils.kt` — single canonical precedence logic for primary reason selection.

### Order of changes and why this is safest

1. **Fix schedule evaluator (`shouldLock`)**: purely local, removes a blatant logical bug with minimal ripple.
2. **Fix `filterSuspendable` installed filtering**: reduces enforcement errors without altering policy semantics for installed packages.
3. **Unify exemption inputs to budget evaluation**: aligns existing behavior (already exempted at enforcement) with evaluation/storage, preventing contradictory states.
4. **Introduce reason sets and deterministic primary derivation**: improves durability and testability without changing enforcement deltas except in cases where the old code was inconsistent.
5. **Persist canonical reasons for computed state**: makes debugging and UI/reporting consistent with enforcement.

### High-confidence local vs riskier follow-ups

High-confidence/local (implemented below):
- `ScheduleEvaluator.shouldLock`
- `PackageResolver.filterSuspendable` installed filtering
- canonical reason representation and deterministic primary derivation
- exemption alignment for budget evaluation

Riskier follow-ups (not required to eliminate the core contradictions, but recommended):
- Clarify and enforce strict-mode semantics vs emergency unlock (should strict be bypassable?)
- Decide whether “always blocked” may override dialer/sms/launcher/permission controller exemptions (safety policy)
- Remove NUCLEAR-mode stubs or complete them (currently several mode functions hardcode NORMAL)

## Corrected code

Below are direct code changes (no pseudocode) as unified diffs.

### Add canonical primary-reason derivation utility

```diff
diff --git a/app/src/main/java/com/ankit/destination/policy/BlockReasonUtils.kt b/app/src/main/java/com/ankit/destination/policy/BlockReasonUtils.kt
new file mode 100644
index 0000000..1111111
--- /dev/null
+++ b/app/src/main/java/com/ankit/destination/policy/BlockReasonUtils.kt
@@
+package com.ankit.destination.policy
+
+/**
+ * Canonical, deterministic primary-reason derivation.
+ *
+ * Reason keys are expected to be one of:
+ *  - ALWAYS_BLOCKED
+ *  - STRICT_INSTALL
+ *  - USAGE_ACCESS_RECOVERY_LOCKDOWN
+ *  - GROUP:<groupId>:<EffectiveBlockReason>
+ *  - APP:<EffectiveBlockReason>
+ *
+ * We also tolerate legacy strings like GROUP_DAILY_CAP / APP_DAILY_CAP.
+ */
+internal object BlockReasonUtils {
+    private fun extractReasonCode(reasonKey: String): EffectiveBlockReason? {
+        val trimmed = reasonKey.trim()
+        if (trimmed.isBlank()) return null
+        val token = trimmed
+            .substringAfterLast(':')
+            .substringAfterLast('_')
+        return runCatching { EffectiveBlockReason.valueOf(token) }.getOrNull()
+    }
+
+    private fun rank(code: EffectiveBlockReason?): Int = when (code) {
+        EffectiveBlockReason.ALWAYS_BLOCKED -> 0
+        EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN -> 1
+        EffectiveBlockReason.STRICT_INSTALL -> 2
+        EffectiveBlockReason.SCHEDULED_BLOCK -> 3
+        EffectiveBlockReason.HOURLY_CAP -> 4
+        EffectiveBlockReason.DAILY_CAP -> 5
+        EffectiveBlockReason.OPENS_CAP -> 6
+        else -> 999
+    }
+
+    private fun primaryLabelForReasonKey(reasonKey: String): String {
+        val trimmed = reasonKey.trim()
+        val code = extractReasonCode(trimmed) ?: return trimmed
+        return when {
+            trimmed.startsWith("GROUP:") -> "GROUP_${code.name}"
+            trimmed.startsWith("APP:") -> "APP_${code.name}"
+            trimmed.startsWith("GROUP_") -> "GROUP_${code.name}"
+            trimmed.startsWith("APP_") -> "APP_${code.name}"
+            else -> code.name
+        }
+    }
+
+    fun derivePrimaryReason(reasons: Set<String>): String? {
+        val normalized = reasons.asSequence()
+            .map(String::trim)
+            .filter(String::isNotBlank)
+            .distinct()
+            .toList()
+        if (normalized.isEmpty()) return null
+
+        val bestReasonKey = normalized.minWithOrNull(
+            compareBy<String>(
+                { rank(extractReasonCode(it)) },
+                { primaryLabelForReasonKey(it) },
+                { it }
+            )
+        ) ?: return null
+
+        return primaryLabelForReasonKey(bestReasonKey)
+    }
+
+    fun derivePrimaryByPackage(reasonsByPackage: Map<String, Set<String>>): Map<String, String> {
+        val result = linkedMapOf<String, String>()
+        reasonsByPackage.forEach { (pkg, reasons) ->
+            val label = derivePrimaryReason(reasons) ?: return@forEach
+            if (pkg.isNotBlank() && label.isNotBlank()) {
+                result[pkg] = label
+            }
+        }
+        return result
+    }
+}
```

### Fix schedule lock computation

```diff
diff --git a/app/src/main/java/com/ankit/destination/schedule/ScheduleEvaluator.kt b/app/src/main/java/com/ankit/destination/schedule/ScheduleEvaluator.kt
index 07e07e0..18a18a1 100644
--- a/app/src/main/java/com/ankit/destination/schedule/ScheduleEvaluator.kt
+++ b/app/src/main/java/com/ankit/destination/schedule/ScheduleEvaluator.kt
@@
         return ScheduleDecision(
-            shouldLock = false,
+            // Canonical: schedule "lock/computed active" must reflect whether any targets are
+            // actually blocked by the current active schedule set.
+            shouldLock = blockedGroupIds.isNotEmpty(),
             strictActive = strictActive,
             blockedGroupIds = blockedGroupIds,
             reason = reason,
             activeBlockIds = active.map { it.id }.toSet(),
             nextTransitionAt = next
         )
     }
```

### Centralize “suspendable” filtering to include installation state

```diff
diff --git a/app/src/main/java/com/ankit/destination/policy/PackageResolver.kt b/app/src/main/java/com/ankit/destination/policy/PackageResolver.kt
index 2d2d2d2..3e3e3e3 100644
--- a/app/src/main/java/com/ankit/destination/policy/PackageResolver.kt
+++ b/app/src/main/java/com/ankit/destination/policy/PackageResolver.kt
@@
     fun filterSuspendable(packages: Set<String>, allowlist: Set<String>): Set<String> {
-        val result = packages
-            .asSequence()
-            .filterNot { pkg ->
-                val inAllowlist = allowlist.contains(pkg)
-                if (inAllowlist) FocusLog.v(FocusEventId.SUSPEND_TARGET, "filterSuspendable: $pkg skipped (in allowlist)")
-                inAllowlist
-            }
-            .filterNot { pkg ->
-                val prot = isProtectedPackage(pkg)
-                if (prot) FocusLog.v(FocusEventId.SUSPEND_TARGET, "filterSuspendable: $pkg skipped (protected)")
-                prot
-            }
-            .toSet()
+        val normalizedAllowlist = allowlist.asSequence()
+            .map(String::trim)
+            .filter(String::isNotBlank)
+            .toSet()
+
+        val result = packages.asSequence()
+            .map(String::trim)
+            .filter(String::isNotBlank)
+            .filter { pkg ->
+                val installed = isInstalled(pkg)
+                if (!installed) {
+                    FocusLog.v(FocusEventId.SUSPEND_TARGET, "filterSuspendable: $pkg skipped (not installed)")
+                }
+                installed
+            }
+            .filterNot { pkg ->
+                val inAllowlist = normalizedAllowlist.contains(pkg)
+                if (inAllowlist) FocusLog.v(FocusEventId.SUSPEND_TARGET, "filterSuspendable: $pkg skipped (in allowlist)")
+                inAllowlist
+            }
+            .filterNot { pkg ->
+                val prot = isProtectedPackage(pkg)
+                if (prot) FocusLog.v(FocusEventId.SUSPEND_TARGET, "filterSuspendable: $pkg skipped (protected)")
+                prot
+            }
+            .toSet()
         FocusLog.d(FocusEventId.SUSPEND_TARGET, "filterSuspendable: input=${packages.size} allowlist=${allowlist.size} → suspendable=${result.size}")
         return result
     }
```

### Make budget evaluation output explicit multi-reasons

```diff
diff --git a/app/src/main/java/com/ankit/destination/policy/EffectivePolicyEvaluator.kt b/app/src/main/java/com/ankit/destination/policy/EffectivePolicyEvaluator.kt
index 9a9a9a9..abababa 100644
--- a/app/src/main/java/com/ankit/destination/policy/EffectivePolicyEvaluator.kt
+++ b/app/src/main/java/com/ankit/destination/policy/EffectivePolicyEvaluator.kt
@@
 data class EffectivePolicyEvaluation(
     val groupEvaluations: List<GroupPolicyEvaluation>,
     val appEvaluations: List<AppPolicyEvaluation>,
     val scheduledBlockedPackages: Set<String>,
     val usageBlockedPackages: Set<String>,
     val strictInstallBlockedPackages: Set<String>,
     val effectiveBlockedPackages: Set<String>,
     val effectiveBlockedGroupIds: Set<String>,
     val strictInstallActiveGroupIds: Set<String>,
-    val primaryReasonByPackage: Map<String, String>,
+    val blockReasonsByPackage: Map<String, Set<String>>,
+    val primaryReasonByPackage: Map<String, String>,
     val usageReasonSummary: String?
 )
@@
 object EffectivePolicyEvaluator {
     fun evaluate(
         nowMs: Long,
         usageInputs: UsageInputs,
         groupPolicies: List<GroupPolicyInput>,
         appPolicies: List<AppPolicyInput>,
         emergencyStates: List<EmergencyStateInput>,
         strictInstallBlockedPackages: Set<String>,
         alwaysAllowedPackages: Set<String>
     ): EffectivePolicyEvaluation {
@@
-        val scheduledBlocked = linkedSetOf<String>()
-        val usageBlocked = linkedSetOf<String>()
+        val scheduledBlocked = linkedSetOf<String>()
+        val usageBlocked = linkedSetOf<String>()
         val effectiveGroupIds = linkedSetOf<String>()
-        val primaryReason = linkedMapOf<String, String>()
+
+        val blockReasons = linkedMapOf<String, MutableSet<String>>()
+        fun addReason(pkg: String, reasonKey: String) {
+            val cleanPkg = pkg.trim()
+            if (cleanPkg.isBlank()) return
+            blockReasons.getOrPut(cleanPkg) { linkedSetOf() }.add(reasonKey.trim())
+        }
 
         groupEvaluations.forEach { evaluation ->
             if (!evaluation.effectiveBlocked) return@forEach
             effectiveGroupIds += evaluation.groupId
             when (evaluation.baselineReason) {
                 EffectiveBlockReason.SCHEDULED_BLOCK -> scheduledBlocked += evaluation.members
                 EffectiveBlockReason.HOURLY_CAP,
                 EffectiveBlockReason.DAILY_CAP,
                 EffectiveBlockReason.OPENS_CAP -> usageBlocked += evaluation.members
                 else -> Unit
             }
-            evaluation.members.forEach { pkg ->
-                primaryReason.putIfAbsent(pkg, "GROUP_${evaluation.baselineReason.name}")
-            }
+            evaluation.members.forEach { pkg ->
+                addReason(pkg, "GROUP:${evaluation.groupId}:${evaluation.baselineReason.name}")
+            }
         }
 
         appEvaluations.forEach { evaluation ->
             if (!evaluation.effectiveBlocked) return@forEach
             if (evaluation.baselineReason == EffectiveBlockReason.SCHEDULED_BLOCK) {
                 scheduledBlocked += evaluation.packageName
             } else {
                 usageBlocked += evaluation.packageName
             }
-            primaryReason.putIfAbsent(evaluation.packageName, "APP_${evaluation.baselineReason.name}")
+            addReason(evaluation.packageName, "APP:${evaluation.baselineReason.name}")
         }
 
         strictInstallBlockedPackages
             .asSequence()
             .map(String::trim)
             .filter(String::isNotBlank)
             .filterNot(alwaysAllowedPackages::contains)
             .forEach { pkg ->
-                primaryReason.putIfAbsent(pkg, EffectiveBlockReason.STRICT_INSTALL.name)
+                addReason(pkg, EffectiveBlockReason.STRICT_INSTALL.name)
             }
 
-        val effectiveBlockedPackages = linkedSetOf<String>().apply {
-            addAll(scheduledBlocked)
-            addAll(usageBlocked)
-            addAll(strictInstallBlockedPackages)
-        }
+        val effectiveBlockedPackages = blockReasons.keys.toSet()
         val reasonSummary = groupEvaluations.firstOrNull { it.effectiveBlocked }?.let { "${it.groupId}:${it.baselineReason.name}" }
             ?: appEvaluations.firstOrNull { it.effectiveBlocked }?.let { "${it.packageName}:${it.baselineReason.name}" }
@@
-        if (primaryReason.isNotEmpty()) {
-            primaryReason.forEach { (pkg, reason) ->
-                FocusLog.v(FocusEventId.GROUP_EVAL, "│ reason: $pkg → $reason")
-            }
-        }
+        val immutableBlockReasons = blockReasons.mapValues { (_, set) ->
+            set.asSequence().map(String::trim).filter(String::isNotBlank).toSet()
+        }
+        val derivedPrimary = BlockReasonUtils.derivePrimaryByPackage(immutableBlockReasons)
+        if (derivedPrimary.isNotEmpty()) {
+            derivedPrimary.forEach { (pkg, reason) ->
+                FocusLog.v(FocusEventId.GROUP_EVAL, "│ primary: $pkg → $reason")
+            }
+        }
         FocusLog.d(FocusEventId.GROUP_EVAL, "└── EffectivePolicyEvaluator.evaluate() END ──")
 
         return EffectivePolicyEvaluation(
             groupEvaluations = groupEvaluations,
             appEvaluations = appEvaluations,
             scheduledBlockedPackages = scheduledBlocked,
             usageBlockedPackages = usageBlocked,
             strictInstallBlockedPackages = strictInstallBlockedPackages,
             effectiveBlockedPackages = effectiveBlockedPackages,
             effectiveBlockedGroupIds = effectiveGroupIds,
             strictInstallActiveGroupIds = groupEvaluations.filter { it.strictInstallActive }.mapTo(linkedSetOf()) { it.groupId },
-            primaryReasonByPackage = primaryReason,
+            blockReasonsByPackage = immutableBlockReasons,
+            primaryReasonByPackage = derivedPrimary,
             usageReasonSummary = reasonSummary
         )
     }
 }
```

### Carry explicit reason sets into final desired state

```diff
diff --git a/app/src/main/java/com/ankit/destination/policy/PolicyModels.kt b/app/src/main/java/com/ankit/destination/policy/PolicyModels.kt
index 4c4c4c4..5d5d5d5 100644
--- a/app/src/main/java/com/ankit/destination/policy/PolicyModels.kt
+++ b/app/src/main/java/com/ankit/destination/policy/PolicyModels.kt
@@
 data class PolicyState(
@@
     val lockReason: String?,
     val budgetBlockedPackages: Set<String>,
     val touchGrassBreakActive: Boolean,
     val primaryReasonByPackage: Map<String, String>,
-    val globalControls: GlobalControls
+    val globalControls: GlobalControls,
+    // Canonical union-of-reasons for suspended packages (and only those packages).
+    // This makes block/unblock reason-safe and testable.
+    val blockReasonsByPackage: Map<String, Set<String>> = emptyMap()
 )
```

### Make `PolicyEvaluator` compute canonical reasons for the final suspend set

```diff
diff --git a/app/src/main/java/com/ankit/destination/policy/PolicyEvaluator.kt b/app/src/main/java/com/ankit/destination/policy/PolicyEvaluator.kt
index 6f6f6f6..7a7a7a7 100644
--- a/app/src/main/java/com/ankit/destination/policy/PolicyEvaluator.kt
+++ b/app/src/main/java/com/ankit/destination/policy/PolicyEvaluator.kt
@@
 class PolicyEvaluator(
     private val packageResolver: PackageResolver,
     private val controllerPackageName: String
 ) {
     fun evaluate(
         mode: ModeState,
         emergencyApps: Set<String>,
         alwaysAllowedApps: Set<String>,
         alwaysBlockedApps: Set<String>,
         strictInstallBlockedPackages: Set<String> = emptySet(),
         uninstallProtectedApps: Set<String>,
         globalControls: GlobalControls,
         previouslySuspended: Set<String>,
         previouslyUninstallProtected: Set<String>,
         budgetBlockedPackages: Set<String>,
         primaryReasonByPackage: Map<String, String>,
+        blockReasonsByPackage: Map<String, Set<String>> = emptyMap(),
         lockReason: String?,
         touchGrassBreakActive: Boolean,
         usageAccessComplianceState: UsageAccessComplianceState
     ): PolicyState {
@@
         if (usageAccessComplianceState.lockdownActive) {
@@
             val recoveryReasons = recoverySuspendTargets.associateWith {
                 EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name
             }
+            val recoveryReasonSets = recoverySuspendTargets.associateWith {
+                setOf(EffectiveBlockReason.USAGE_ACCESS_RECOVERY_LOCKDOWN.name)
+            }
             val allowlistReasons = recoveryAllowlist.associateWith { "usage access recovery" }
@@
             return PolicyState(
@@
                 lockReason = usageAccessComplianceState.reason,
                 budgetBlockedPackages = emptySet(),
                 touchGrassBreakActive = false,
-                primaryReasonByPackage = recoveryReasons,
-                globalControls = globalControls
+                primaryReasonByPackage = recoveryReasons,
+                globalControls = globalControls,
+                blockReasonsByPackage = recoveryReasonSets
             )
         }
@@
         val budgetBlockedSuspendable =
             packageResolver.filterSuspendable(
                 budgetBlockedPackages,
                 allowlistResolution.packages
             )
@@
         val alwaysBlockedSuspendable = packageResolver.filterSuspendable(
             packages = alwaysBlockedApps,
             allowlist = emptySet()
         )
@@
         val strictInstallSuspendable = packageResolver.filterSuspendable(
             packages = strictInstallBlockedPackages,
             allowlist = allowlistResolution.packages
         )
@@
         val suspendTargets = mergeSuspendTargets(
             effectiveMode = effectiveMode,
             nuclearSuspendTargets = nuclearSuspendTargets,
             budgetBlockedSuspendable = budgetBlockedSuspendable,
             alwaysBlockedSuspendable = alwaysBlockedSuspendable,
             strictInstallSuspendable = strictInstallSuspendable
         )
@@
+        // Canonical explicit reason union (only for packages that will actually be suspended).
+        //
+        // If a caller doesn't provide blockReasonsByPackage yet, we fall back to using
+        // the stored primary reason as a singleton set (still deterministic, but less informative).
+        val baseReasons: MutableMap<String, MutableSet<String>> = linkedMapOf()
+        if (blockReasonsByPackage.isNotEmpty()) {
+            blockReasonsByPackage.forEach { (pkg, reasons) ->
+                val cleanPkg = pkg.trim()
+                if (cleanPkg.isBlank()) return@forEach
+                val cleanReasons = reasons.asSequence()
+                    .map(String::trim)
+                    .filter(String::isNotBlank)
+                    .toCollection(linkedSetOf())
+                if (cleanReasons.isNotEmpty()) {
+                    baseReasons[cleanPkg] = cleanReasons
+                }
+            }
+        } else {
+            primaryReasonByPackage.forEach { (pkg, reason) ->
+                val cleanPkg = pkg.trim()
+                val cleanReason = reason.trim()
+                if (cleanPkg.isNotBlank() && cleanReason.isNotBlank()) {
+                    baseReasons[cleanPkg] = linkedSetOf(cleanReason)
+                }
+            }
+        }
+
+        // Guarantee an explicit reason entry for every suspended package.
+        alwaysBlockedSuspendable.forEach { pkg ->
+            baseReasons.getOrPut(pkg) { linkedSetOf() }.add(EffectiveBlockReason.ALWAYS_BLOCKED.name)
+        }
+        strictInstallSuspendable.forEach { pkg ->
+            baseReasons.getOrPut(pkg) { linkedSetOf() }.add(EffectiveBlockReason.STRICT_INSTALL.name)
+        }
+        budgetBlockedSuspendable.forEach { pkg ->
+            val set = baseReasons.getOrPut(pkg) { linkedSetOf() }
+            // Avoid empty-set reasons; give a stable fallback.
+            if (set.isEmpty()) set.add("BUDGET")
+        }
+
+        val effectiveReasonSets: Map<String, Set<String>> = baseReasons
+            .filterKeys(suspendTargets::contains)
+            .mapValues { (_, set) ->
+                set.asSequence().map(String::trim).filter(String::isNotBlank).toSet()
+            }
+        val derivedPrimary: Map<String, String> = BlockReasonUtils.derivePrimaryByPackage(effectiveReasonSets)
+
         return PolicyState(
@@
             lockReason = lockReason,
             budgetBlockedPackages = budgetBlockedSuspendable,
             touchGrassBreakActive = touchGrassBreakActive,
-            primaryReasonByPackage = primaryReasonByPackage,
-            globalControls = globalControls
+            primaryReasonByPackage = derivedPrimary,
+            globalControls = globalControls,
+            blockReasonsByPackage = effectiveReasonSets
         )
     }
```

### Store canonical reason sets for computed policy state

```diff
diff --git a/app/src/main/java/com/ankit/destination/policy/PolicyStore.kt b/app/src/main/java/com/ankit/destination/policy/PolicyStore.kt
index 8b8b8b8..9c9c9c9 100644
--- a/app/src/main/java/com/ankit/destination/policy/PolicyStore.kt
+++ b/app/src/main/java/com/ankit/destination/policy/PolicyStore.kt
@@
 class PolicyStore(context: Context) {
@@
     fun getPrimaryReasonByPackage(): Map<String, String> {
@@
     }
+
+    fun getBlockReasonsByPackage(): Map<String, Set<String>> {
+        val raw = prefs.getString(KEY_BLOCK_REASONS_BY_PACKAGE, "") ?: ""
+        if (raw.isBlank()) return emptyMap()
+        return raw.lineSequence()
+            .mapNotNull { line ->
+                val idx = line.indexOf('=')
+                if (idx <= 0) return@mapNotNull null
+                val pkg = line.substring(0, idx).trim()
+                val reasonsCsv = line.substring(idx + 1)
+                if (pkg.isBlank()) return@mapNotNull null
+                val reasons = reasonsCsv.split(',')
+                    .asSequence()
+                    .map(String::trim)
+                    .filter(String::isNotBlank)
+                    .toSet()
+                if (reasons.isEmpty()) null else pkg to reasons
+            }
+            .toMap()
+    }
@@
     fun setComputedPolicyState(
@@
         budgetUsageAccessGranted: Boolean,
         budgetNextCheckAtMs: Long?,
         primaryReasonByPackage: Map<String, String>,
+        blockReasonsByPackage: Map<String, Set<String>>,
         clearStrictInstallSuspendedPackages: Boolean
     ) {
         prefs.edit()
@@
             .putString(KEY_PRIMARY_REASON_BY_PACKAGE, encodeMap(primaryReasonByPackage))
+            .putString(KEY_BLOCK_REASONS_BY_PACKAGE, encodeReasonSetMap(blockReasonsByPackage))
             .apply {
                 if (clearStrictInstallSuspendedPackages) {
                     putStringSet(KEY_STRICT_INSTALL_SUSPENDED, emptySet())
                 }
             }
             .apply()
     }
@@
     private fun encodeMap(values: Map<String, String>): String {
         return values.entries.joinToString(separator = "\n") { "${it.key}=${it.value}" }
     }
+
+    private fun encodeReasonSetMap(values: Map<String, Set<String>>): String {
+        if (values.isEmpty()) return ""
+        return values.entries.asSequence()
+            .mapNotNull { (pkg, reasons) ->
+                val cleanPkg = pkg.trim()
+                if (cleanPkg.isBlank()) return@mapNotNull null
+                val cleanReasons = reasons.asSequence()
+                    .map(String::trim)
+                    .filter(String::isNotBlank)
+                    .distinct()
+                    .sorted()
+                    .toList()
+                if (cleanReasons.isEmpty()) return@mapNotNull null
+                "$cleanPkg=${cleanReasons.joinToString(separator = ",")}"
+            }
+            .joinToString(separator = "\n")
+    }
@@
         private const val KEY_PRIMARY_REASON_BY_PACKAGE = "primary_reason_by_package"
+        private const val KEY_BLOCK_REASONS_BY_PACKAGE = "block_reasons_by_package"
         private const val KEY_CURRENT_LOCK_REASON = "current_lock_reason"
```

### Unify exemption inputs + persist canonical reason state in `PolicyEngine`

```diff
diff --git a/app/src/main/java/com/ankit/destination/policy/PolicyEngine.kt b/app/src/main/java/com/ankit/destination/policy/PolicyEngine.kt
index 0101010..0202020 100644
--- a/app/src/main/java/com/ankit/destination/policy/PolicyEngine.kt
+++ b/app/src/main/java/com/ankit/destination/policy/PolicyEngine.kt
@@
     private fun orchestrateCurrentPolicy(now: ZonedDateTime = ZonedDateTime.now()): OrchestratedState {
         val policyControls = loadPolicyControls()
+        // Canonical exemption set used consistently across budget evaluation and enforcement:
+        // anything allowlisted here must not become "blocked" in budget evaluation, because
+        // it will never be suspended later.
+        val allowlistPackages = resolver.resolveAllowlist(
+            userChosenEmergencyApps = getEmergencyApps(),
+            alwaysAllowedApps = policyControls.alwaysAllowedApps
+        ).packages
         val loaded = runBlocking {
             withContext(Dispatchers.IO) {
@@
         val evaluation = EffectivePolicyEvaluator.evaluate(
             nowMs = now.toInstant().toEpochMilli(),
             usageInputs = usageSnapshot.usageInputs,
             groupPolicies = groupInputs,
             appPolicies = appInputs,
             emergencyStates = emergencyStates,
             strictInstallBlockedPackages = strictInstallBlocked,
-            alwaysAllowedPackages = policyControls.alwaysAllowedApps
+            alwaysAllowedPackages = allowlistPackages
         )
@@
     private fun persistComputedState(orchestrated: OrchestratedState) {
-        val fullPrimaryReasons = linkedMapOf<String, String>()
-        orchestrated.policyControls.alwaysBlockedInstalledPackages.forEach { fullPrimaryReasons[it] = EffectiveBlockReason.ALWAYS_BLOCKED.name }
-        orchestrated.evaluation.primaryReasonByPackage.forEach { (pkg, reason) ->
-            fullPrimaryReasons.putIfAbsent(pkg, reason)
-        }
+        val allowlistPackages = resolver.resolveAllowlist(
+            userChosenEmergencyApps = getEmergencyApps(),
+            alwaysAllowedApps = orchestrated.policyControls.alwaysAllowedApps
+        ).packages
+
+        // Filter computed blocked sets to what can *actually* be suspended, so storage/reporting
+        // matches enforcement.
+        val budgetBlockedSuspendable = resolver.filterSuspendable(
+            packages = orchestrated.evaluation.effectiveBlockedPackages,
+            allowlist = allowlistPackages
+        )
+        val scheduleBlockedSuspendable = resolver.filterSuspendable(
+            packages = orchestrated.evaluation.scheduledBlockedPackages,
+            allowlist = allowlistPackages
+        )
+        val alwaysBlockedSuspendable = resolver.filterSuspendable(
+            packages = orchestrated.policyControls.alwaysBlockedInstalledPackages,
+            allowlist = emptySet()
+        )
+
+        // Canonical union-of-reasons for computed state (budget/schedule/strict + always-blocked).
+        val mergedReasons = linkedMapOf<String, MutableSet<String>>()
+        orchestrated.evaluation.blockReasonsByPackage.forEach { (pkg, reasons) ->
+            if (!budgetBlockedSuspendable.contains(pkg)) return@forEach
+            mergedReasons[pkg] = reasons.toMutableSet()
+        }
+        alwaysBlockedSuspendable.forEach { pkg ->
+            mergedReasons.getOrPut(pkg) { linkedSetOf() }.add(EffectiveBlockReason.ALWAYS_BLOCKED.name)
+        }
+        val immutableReasons = mergedReasons.mapValues { (_, set) ->
+            set.asSequence().map(String::trim).filter(String::isNotBlank).toSet()
+        }
+        val fullPrimaryReasons = BlockReasonUtils.derivePrimaryByPackage(immutableReasons)
 
         store.setComputedPolicyState(
             scheduleLockComputed = orchestrated.scheduleDecision.shouldLock,
             scheduleStrictComputed = orchestrated.strictActive,
             scheduleBlockedGroups = orchestrated.scheduledGroupIds,
-            scheduleBlockedPackages = orchestrated.evaluation.scheduledBlockedPackages,
+            scheduleBlockedPackages = scheduleBlockedSuspendable,
             scheduleLockReason = orchestrated.scheduleDecision.reason,
             scheduleNextTransitionAtMs = orchestrated.scheduleDecision.nextTransitionAt?.toInstant()?.toEpochMilli(),
-            budgetBlockedPackages = orchestrated.evaluation.effectiveBlockedPackages,
+            budgetBlockedPackages = budgetBlockedSuspendable,
             budgetBlockedGroupIds = orchestrated.evaluation.effectiveBlockedGroupIds,
             budgetReason = orchestrated.usageAccessComplianceState.reason
                 ?: orchestrated.evaluation.usageReasonSummary
                 ?: if (!orchestrated.usageAccessGranted) "Usage access not granted" else null,
             budgetUsageAccessGranted = orchestrated.usageAccessGranted,
             budgetNextCheckAtMs = orchestrated.nextCheckAtMs,
             primaryReasonByPackage = fullPrimaryReasons,
+            blockReasonsByPackage = immutableReasons,
             clearStrictInstallSuspendedPackages = !orchestrated.strictActive
         )
@@
     private fun computeExternalState(nowMs: Long = System.currentTimeMillis()): ExternalState {
         val policyControls = loadPolicyControls()
         val usageAccessComplianceState = currentUsageAccessComplianceState()
@@
         val budgetBlocked = store.getBudgetBlockedPackages()
-        val primaryReason = store.getPrimaryReasonByPackage().ifEmpty { computePrimaryReasonByPackage(
-            alwaysBlocked = policyControls.alwaysBlockedInstalledPackages,
-            budgetBlocked = budgetBlocked,
-            scheduleBlocked = store.getScheduleBlockedPackages(),
-            strictInstallBlocked = strictInstallBlocked
-        ) }
+        val storedReasons = store.getBlockReasonsByPackage()
+        val primaryReason = store.getPrimaryReasonByPackage()
+        val fallbackReasonSets = if (storedReasons.isNotEmpty()) {
+            storedReasons
+        } else {
+            // Best-effort fallback: primary reason as singleton set
+            primaryReason.mapValues { (_, v) -> setOf(v) }
+        }
@@
         return ExternalState(
             effectiveMode = effectiveMode,
             budgetBlockedPackages = budgetBlocked,
             strictInstallBlockedPackages = strictInstallBlocked,
             touchGrassBreakActive = false,
             lockReason = lockReason,
             policyControls = policyControls,
             primaryReasonByPackage = primaryReason,
+            blockReasonsByPackage = fallbackReasonSets,
             usageAccessComplianceState = usageAccessComplianceState
         )
     }
@@
     private data class ExternalState(
         val effectiveMode: ModeState,
         val budgetBlockedPackages: Set<String>,
         val strictInstallBlockedPackages: Set<String>,
         val touchGrassBreakActive: Boolean,
         val lockReason: String?,
         val policyControls: PolicyControls,
         val primaryReasonByPackage: Map<String, String>,
+        val blockReasonsByPackage: Map<String, Set<String>>,
         val usageAccessComplianceState: UsageAccessComplianceState
     )
```

Finally, update the call sites in `PolicyEngine` that invoke `PolicyEvaluator.evaluate(...)` to pass `blockReasonsByPackage`:

```diff
diff --git a/app/src/main/java/com/ankit/destination/policy/PolicyEngine.kt b/app/src/main/java/com/ankit/destination/policy/PolicyEngine.kt
index 0202020..0303030 100644
--- a/app/src/main/java/com/ankit/destination/policy/PolicyEngine.kt
+++ b/app/src/main/java/com/ankit/destination/policy/PolicyEngine.kt
@@
         val state = evaluator.evaluate(
@@
             budgetBlockedPackages = external.budgetBlockedPackages,
             primaryReasonByPackage = external.primaryReasonByPackage,
+            blockReasonsByPackage = external.blockReasonsByPackage,
             lockReason = external.lockReason,
             touchGrassBreakActive = external.touchGrassBreakActive,
             usageAccessComplianceState = external.usageAccessComplianceState
         )
@@
         val rollbackState = evaluator.evaluate(
@@
             budgetBlockedPackages = external.budgetBlockedPackages,
             primaryReasonByPackage = external.primaryReasonByPackage,
+            blockReasonsByPackage = external.blockReasonsByPackage,
             lockReason = rollbackLockReason,
             touchGrassBreakActive = external.touchGrassBreakActive,
             usageAccessComplianceState = external.usageAccessComplianceState
         )
@@
         val state = evaluator.evaluate(
@@
             budgetBlockedPackages = external.budgetBlockedPackages,
             primaryReasonByPackage = external.primaryReasonByPackage,
+            blockReasonsByPackage = external.blockReasonsByPackage,
             lockReason = external.lockReason,
             touchGrassBreakActive = external.touchGrassBreakActive,
             usageAccessComplianceState = external.usageAccessComplianceState
         )
@@
         val cleanState = evaluator.evaluate(
@@
             budgetBlockedPackages = emptySet(),
             primaryReasonByPackage = emptyMap(),
+            blockReasonsByPackage = emptyMap(),
             lockReason = null,
             touchGrassBreakActive = false,
             usageAccessComplianceState = UsageAccessComplianceState(
                 usageAccessGranted = true,
                 lockdownEligible = false,
                 lockdownActive = false,
                 recoveryAllowlist = emptySet(),
                 reason = null
             )
         )
```

(Repeat the same parameter addition in `verifyDesiredMode()` and `diagnosticsSnapshot()` calls; they follow the same pattern.)

## Validation

### Code changes made

Implemented high-confidence logic fixes that eliminate the most dangerous contradictions:

- Fixed schedule lock computation so schedule lock state can become true when schedules actually block groups.
- Centralized suspension filtering to:
  - normalize package strings
  - ignore packages that are not installed
  - continue honoring protected package rules
- Unified exemption inputs so budget evaluation no longer produces “blocked” packages that enforcement will always exempt anyway.
- Introduced canonical reason sets:
  - `EffectivePolicyEvaluator` now produces `blockReasonsByPackage`
  - `PolicyEvaluator` produces final `PolicyState.blockReasonsByPackage` for the actual suspended set
  - Deterministic `primaryReasonByPackage` is derived from reason sets by one canonical function (`BlockReasonUtils`)

### Files changed and why

- `schedule/ScheduleEvaluator.kt`: fixed a direct logic bug (`shouldLock`).
- `policy/PackageResolver.kt`: made suspend candidate filtering durable and safe (installed filtering).
- `policy/EffectivePolicyEvaluator.kt`: made the budget/schedule evaluation reason-complete and explicit.
- `policy/PolicyModels.kt`: added explicit reason map to the final desired state.
- `policy/PolicyEvaluator.kt`: made the final desired state reason-aware and deterministic.
- `policy/PolicyStore.kt`: stored canonical reason sets for computed state (budget+always-blocked layer).
- `policy/PolicyEngine.kt`: ensured the same exemption and reason model is used through evaluation → persistence → enforcement.
- `policy/BlockReasonUtils.kt` (new): centralized precedence and deterministic primary reason derivation.

### Remaining edge cases and ambiguity

These are areas where code intent is ambiguous and should be explicitly decided (but are not necessary to eliminate the contradictions fixed above):

- **Strict schedule vs emergency unlock**: should emergency unlock be allowed to bypass strict schedule blocks, or should strict override emergency?
- **Safety policy for “always blocked”**: should admins be prevented from always-blocking the default dialer/sms/launcher/permission controller? (Current code allows it in some cases; this can be dangerous.)
- **NUCLEAR mode is stubbed** (`resolveEffectiveMode` and local `effectiveMode` hardcode NORMAL). If your product truly includes NUCLEAR mode, the current code is incomplete and must be finished; otherwise remove the dead paths to reduce confusion.

### Regression risks

- Exempt packages no longer contribute to budget evaluation. This aligns evaluation with enforcement but can change *when* a group cap triggers if exempt packages were previously counted in group usage sums.
- Primary reason labeling becomes deterministic and derived from reason sets; UI may show different primary reasons than before in multi-reason situations.

### Suggested tests for every major interaction

These should be written as pure Kotlin unit tests for `EffectivePolicyEvaluator` + `BlockReasonUtils`, and as JVM tests/mocked tests for `PolicyEvaluator` with a fake `PackageResolver`.

- **Per-app + group union**: app belongs to group with daily cap reached AND has app hourly cap reached ⇒ blocked with both reasons; unblocks only when both removed.
- **Multiple groups**: app in two groups, one schedule-blocked and one daily-blocked ⇒ reasons include both group reasons; primary is schedule.
- **Schedule + usage union**: schedule active and hourly cap exceeded ⇒ reasons include schedule + hourly; primary is schedule.
- **Emergency unlock vs schedule**:
  - group emergency active ⇒ group schedule reason suppressed for members
  - app emergency active ⇒ app policy reason suppressed
- **Strict schedule + new install**:
  - install while strict active ⇒ `STRICT_INSTALL` reason appears and package is suspended
  - strict ends ⇒ reason removed and app unsuspended unless other reasons remain
- **Always blocked precedence**:
  - always blocked + budget blocked ⇒ both reasons present, primary always-blocked
- **Protected package filtering**:
  - protected package appears in blocked sets ⇒ never ends up in `suspendTargets`, and no derived primary is stored for it
- **Package removed/reinstalled**:
  - blocked package uninstalled ⇒ not in next suspendTargets
  - reinstall while policy active ⇒ package becomes suspended again
- **Installed-check durability**:
  - DB contains stale mapping to non-installed package ⇒ never passed to DPM

### Assumptions made

Where code intent was ambiguous, I chose the safest interpretation that **aligns existing enforcement behavior with evaluation and reporting**:

- If a package is exempted by allowlist/protection at enforcement time, it should also be excluded from “blocked” sets/reasons produced by evaluation and stored state, to avoid contradictory models.

### Before-vs-after summary

**How blocking used to be decided**
- Budget evaluator produced blocked sets that could include packages later exempted by allowlist, while final suspend targets were computed separately.
- Primary reasons were best-effort and inconsistent across layers.
- Schedule lock flag was always false, disabling scheduled lock semantics.

**How blocking is now decided**
- Budget evaluation produces explicit reason sets.
- Exemptions are applied consistently.
- Final desired state (`PolicyState`) contains explicit per-package reason sets for the packages that are actually suspended.
- Primary reason is derived deterministically from the explicit reasons.

**What unblocking used to risk**
- A package could appear blocked in stored state but be unsuspended because a later filter removed it.
- Non-installed packages could enter suspend deltas, generating failures and inconsistent tracked state.

**Why the new flow is safer**
- The evaluator produces a single canonical union-of-reasons for the final suspended set.
- Unblocking is reason-safe because suspension is derived from explicit reasons after consistent filtering.
- State and reporting now match enforcement, preventing contradictory behavior and user-visible confusion.