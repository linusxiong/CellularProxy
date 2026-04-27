# Current Work Closeout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Verify the current CellularProxy branch, merge it into `main`, and push `main` to GitHub without leaking secrets or committing temporary brainstorming files.

**Architecture:** This is a repository closeout plan, not the Compose/e2e MVP implementation plan. It preserves the current branch history, verifies the branch before and after merge, and pushes only a clean `main` result.

**Tech Stack:** Git, Gradle wrapper, Kotlin/JVM unit tests, Android debug build when the local SDK/JDK environment permits it.

---

### Task 1: Verify The Current Feature Branch

**Files:**
- Read: `docs/superpowers/specs/2026-04-27-cellularproxy-compose-e2e-mvp-design.md`
- Read: `.gitignore`

- [ ] **Step 1: Confirm the branch and worktree state**

Run:

```bash
git status --short --branch
```

Expected: current branch is `gnhf/brainstorm-skills-sp-cdb453`; there are no unstaged or uncommitted changes except this plan if it has not yet been committed.

- [ ] **Step 2: Scan for placeholders in the new spec and manually verify no secrets are present**

Run:

```bash
rg -n 'TO''DO|TB''D|FIX''ME' docs/superpowers/specs/2026-04-27-cellularproxy-compose-e2e-mvp-design.md
```

Expected: no placeholder matches. Also inspect the spec text before continuing and confirm it describes token handling without including any real token value or real-token fragment.

- [ ] **Step 3: Run whitespace validation**

Run:

```bash
git diff --check
```

Expected: no output and exit code `0`.

- [ ] **Step 4: Run JVM unit tests**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew test --no-daemon
```

Expected: Gradle exits `0`; all unit tests pass.

- [ ] **Step 5: Build the debug APK if the Android SDK is available**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :app:assembleDebug --no-daemon
```

Expected: Gradle exits `0` and writes the debug APK under `app/build/outputs/apk/debug/`.

- [ ] **Step 6: Commit this closeout plan if it is still uncommitted**

Run:

```bash
git add docs/superpowers/plans/2026-04-27-current-work-closeout.md
git commit -m "Add current work closeout plan"
```

Expected: a commit is created with only the closeout plan file.

### Task 2: Merge Into Main

**Files:**
- Git branch history only.

- [ ] **Step 1: Confirm the source branch name**

Run:

```bash
git branch --show-current
```

Expected: `gnhf/brainstorm-skills-sp-cdb453`.

- [ ] **Step 2: Switch to main**

Run:

```bash
git switch main
```

Expected: checkout succeeds.

- [ ] **Step 3: Merge the feature branch without squashing**

Run:

```bash
git merge --no-ff gnhf/brainstorm-skills-sp-cdb453
```

Expected: merge succeeds without conflicts and preserves feature branch commits.

- [ ] **Step 4: Confirm merged main is clean**

Run:

```bash
git status --short --branch
```

Expected: current branch is `main`; worktree is clean.

### Task 3: Verify Merged Main

**Files:**
- Full repository verification.

- [ ] **Step 1: Run JVM unit tests on merged main**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew test --no-daemon
```

Expected: Gradle exits `0`; all unit tests pass.

- [ ] **Step 2: Build the debug APK on merged main if the Android SDK is available**

Run:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :app:assembleDebug --no-daemon
```

Expected: Gradle exits `0` and writes the debug APK under `app/build/outputs/apk/debug/`.

- [ ] **Step 3: Confirm no temporary brainstorming files are tracked**

Run:

```bash
git status --short --ignored
```

Expected: `.superpowers/` may appear as ignored, not tracked. Worktree remains clean.

### Task 4: Push Main To GitHub

**Files:**
- Remote `origin/main`.

- [ ] **Step 1: Fetch remote state**

Run:

```bash
git fetch origin
```

Expected: fetch succeeds.

- [ ] **Step 2: Check local main against origin main**

Run:

```bash
git rev-list --left-right --count main...origin/main
```

Expected: the right-side count is `0`, or remote divergence is inspected and resolved before pushing.

- [ ] **Step 3: Push main**

Run:

```bash
git push origin main
```

Expected: push succeeds without force pushing.

- [ ] **Step 4: Report final state**

Run:

```bash
git status --short --branch
git log --oneline --decorate -n 5
```

Expected: `main` is clean and contains the closeout commits at the top of history.
