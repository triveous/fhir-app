# Developer setup guide: clone, run and build Aarogya Aarohan

This guide takes you from a computer with nothing installed to:

1. a **debug app** running on your phone or emulator, and
2. a **signed release APK** you can share and install.

You do not need prior Android experience. Follow the steps in order, and don't skip any. Each step ends with a check. If the check fails, fix it before moving on; [Troubleshooting](#troubleshooting) lists the common errors.

> **Time needed:** about 1–2 hours the first time. Most of that is downloads: Android Studio, the Android SDK, and about 3–5 GB of build libraries on the first build.

---

## Contents

- [What you need](#what-you-need)
- [Step 1: Install Git](#step-1-install-git)
- [Step 2: Install Java 17](#step-2-install-java-17)
- [Step 3: Install Android Studio and the Android SDK](#step-3-install-android-studio-and-the-android-sdk)
- [Step 4: Create a GitHub access token](#step-4-create-a-github-access-token)
- [Step 5: Download the code](#step-5-download-the-code)
- [Step 6: Add the OpenCV library](#step-6-add-the-opencv-library)
- [Step 7: Create `local.properties`](#step-7-create-localproperties)
- [Step 8: Create `keystore.properties`](#step-8-create-keystoreproperties)
- [Step 9: Build the debug APK](#step-9-build-the-debug-apk)
- [Step 10: Run the app on a phone or emulator](#step-10-run-the-app-on-a-phone-or-emulator)
- [Step 11: Build a signed release APK](#step-11-build-a-signed-release-apk)
- [Optional: Work in Android Studio](#optional-work-in-android-studio)
- [Troubleshooting](#troubleshooting)
- [Command cheat sheet](#command-cheat-sheet)
- [Files you must never commit](#files-you-must-never-commit)

---

## What you need

| Item | Details |
|---|---|
| Computer | macOS, Windows 10/11 or Linux, 64-bit |
| Memory (RAM) | **16 GB recommended.** The build reserves 8 GB for Gradle. 8 GB machines may fail with "out of memory". |
| Free disk space | At least **20 GB** (Android Studio, SDK, emulator image, build caches) |
| Internet | Needed for the first build. Later builds work mostly from cache. |
| GitHub account | Free. You need it for one library download ([Step 4](#step-4-create-a-github-access-token)). |
| Android phone (optional) | Android **10 or newer**, with a camera. An emulator works for most screens, but the screening camera works best on a real phone. |

Tools you will install:

| Tool | Version | Why |
|---|---|---|
| Git | any recent | Downloads the code |
| Java (JDK) | **17** | Runs the build. **Do not use Java 22 or newer**: this project's Gradle 8.5 cannot run on them. |
| Android Studio | any recent | Provides the Android SDK, emulator and an editor |
| Android SDK Platform | **API 35** | The app compiles against Android 15 (API 35) |
| Android SDK Build-Tools & Platform-Tools | latest | Packaging, signing, and `adb` for installing on a phone |

You do **not** need to install Gradle. The project includes a "Gradle wrapper" (`gradlew`) that downloads the right version (8.5) automatically.

> **About commands in this guide.** Lines in grey boxes are typed into a **terminal**:
> - macOS: open **Terminal** (Spotlight → "Terminal").
> - Windows: open **PowerShell** (Start menu → "PowerShell").
> - Linux: open your terminal app.
>
> When a step differs per system, it shows separate macOS/Linux and Windows versions. On Windows, type `.\gradlew.bat` wherever this guide says `./gradlew`.

---

## Step 1: Install Git

- **macOS:** run `git --version` in Terminal. If Git is missing, macOS offers to install the "Command Line Developer Tools". Click **Install**.
- **Windows:** download and run the installer from <https://git-scm.com/download/win>. The default options are fine.
- **Linux (Ubuntu/Debian):** `sudo apt install git`

**Check:**

```bash
git --version
```

You should see something like `git version 2.4x.x`.

---

## Step 2: Install Java 17

Install **Eclipse Temurin JDK 17** (free):

1. Go to <https://adoptium.net/temurin/releases/?version=17>.
2. Pick your operating system and architecture:
   - Apple Silicon Mac (M1/M2/M3/M4): **aarch64**
   - Intel Mac and most Windows/Linux PCs: **x64**
3. Download the **JDK** installer (`.pkg` on macOS, `.msi` on Windows) and run it.
   - On Windows, turn on **"Set JAVA_HOME variable"** in the installer.

Then point your terminal at Java 17:

- **macOS:** add this line to the file `~/.zshrc`, then open a **new** Terminal window:

  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 17)
  ```

  One way to add it: `echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 17)' >> ~/.zshrc`

- **Windows:** the installer set `JAVA_HOME` if you ticked the option. Open a **new** PowerShell window.
- **Linux:** `sudo apt install openjdk-17-jdk`, then add `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` to `~/.bashrc`.

**Check:**

```bash
java -version
```

The first line must contain **`17`**, for example `openjdk version "17.0.x"`. If it shows another number, see [Wrong Java version](#wrong-java-version).

> Java 21 also works with this project. Java 22 and newer **do not**. Android Studio's bundled Java is often newer than 21, which is why this guide installs 17 separately.

---

## Step 3: Install Android Studio and the Android SDK

1. Download Android Studio from <https://developer.android.com/studio> and install it.
2. Open it and follow the **Setup Wizard**. Choose **Standard** setup. It downloads the Android SDK, which takes a while.
3. When it finishes, open the SDK Manager:
   - On the Welcome screen: **More Actions → SDK Manager**
   - Or inside a project: **Tools → SDK Manager**
4. In the **SDK Platforms** tab, tick **Android 15.0 ("VanillaIceCream") — API Level 35**.
5. In the **SDK Tools** tab, make sure these are ticked:
   - **Android SDK Build-Tools**
   - **Android SDK Platform-Tools**
   - **Android Emulator** (only if you will use an emulator)
6. Click **Apply** and wait for the downloads.
7. Copy the **Android SDK Location** shown at the top of the SDK Manager window. You need it in [Step 7](#step-7-create-localproperties). It is usually:

| System | Typical SDK location |
|---|---|
| macOS | `/Users/<your-name>/Library/Android/sdk` |
| Windows | `C:\Users\<your-name>\AppData\Local\Android\Sdk` |
| Linux | `/home/<your-name>/Android/Sdk` |

**Check:** the folder exists and contains `platforms/android-35`.

- macOS/Linux: `ls ~/Library/Android/sdk/platforms` (on Linux, use `ls ~/Android/Sdk/platforms`)
- Windows: `dir $env:LOCALAPPDATA\Android\Sdk\platforms`

You should see `android-35` in the list.

---

## Step 4: Create a GitHub access token

The app uses customised versions of two Google Android FHIR SDK libraries, `data-capture` (forms) and `workflow`. They are published on **GitHub Packages**, and GitHub requires a login to download from it, **even for public packages**. Without a token, the build fails with `401 Unauthorized`.

Create a token that can only *read* packages:

1. Sign in to <https://github.com>.
2. Open <https://github.com/settings/tokens> (**Settings → Developer settings → Personal access tokens → Tokens (classic)**).
3. Click **Generate new token → Generate new token (classic)**.
4. **Note:** `fhircore build`. **Expiration:** your choice (for example 90 days).
5. Tick **only** `read:packages`. Leave everything else unticked.
6. Click **Generate token** and **copy it now**. It starts with `ghp_`, and GitHub shows it only once.

Keep the token private, like a password. You will put it in [Step 7](#step-7-create-localproperties).

**Check (optional):** replace `YOUR_USERNAME` and `YOUR_TOKEN` and run:

```bash
curl -s -o /dev/null -w "%{http_code}\n" -u YOUR_USERNAME:YOUR_TOKEN https://maven.pkg.github.com/midas-in/android-fhir/org/smartregister/data-capture/1.1.0-preview12-build8-SNAPSHOT/maven-metadata.xml
```

`200` means the token works. `401` means the username or token is wrong, or the token lacks `read:packages`. (On Windows PowerShell, type `curl.exe` instead of `curl`.)

---

## Step 5: Download the code

Choose a folder for your projects, then clone:

```bash
cd ~/Documents
git clone https://github.com/triveous/fhir-app.git
cd fhir-app/android
```

On Windows, use `cd $HOME\Documents` for the first line.

> **Important:** the Android project is the **`android`** folder inside the repository, not the repository root. Every command from now on runs from inside `fhir-app/android`, and that is the folder you open in Android Studio.

**Check:**

```bash
ls
```

You should see `gradlew`, `settings.gradle.kts`, `quest`, `engine` and `geowidget` (on Windows, `dir`).

---

## Step 6: Add the OpenCV library

The app uses **OpenCV 4.11.0** for image processing. The build reads it from a file, `quest/libs/opencv-411-android.aar`, and that file is not stored in git, so download it once from Maven Central.

**macOS / Linux:**

```bash
mkdir -p quest/libs
curl -fL -o quest/libs/opencv-411-android.aar https://repo1.maven.org/maven2/org/opencv/opencv/4.11.0/opencv-4.11.0.aar
```

**Windows (PowerShell):**

```powershell
New-Item -ItemType Directory -Force quest\libs | Out-Null
Invoke-WebRequest -Uri https://repo1.maven.org/maven2/org/opencv/opencv/4.11.0/opencv-4.11.0.aar -OutFile quest\libs\opencv-411-android.aar
```

**Check:** the file exists and is about **110 MB**.

- macOS/Linux: `ls -lh quest/libs`
- Windows: `dir quest\libs`

If you want to confirm the exact file, its SHA-256 is `c21ae9c7bb88795e7abefac74b83c458022298248b4ee72fbf62df7f085dbda7` (`shasum -a 256 quest/libs/opencv-411-android.aar` on macOS, `Get-FileHash quest\libs\opencv-411-android.aar` on Windows).

---

## Step 7: Create `local.properties`

`local.properties` holds settings for **your machine only**: where your Android SDK is, which server the app talks to, and your GitHub token. The build stops if this file is missing.

Create or replace `android/local.properties` with the content below. If the file already exists, **replace everything in it**. Use any plain-text editor (TextEdit in plain-text mode, Notepad, or VS Code).

```properties
# --- Your machine ---
# The Android SDK location from Step 3.
sdk.dir=/Users/your-name/Library/Android/sdk

# --- GitHub Packages login (Step 4) ---
gpr.user=your-github-username
gpr.key=ghp_your_token_here

# --- Server the app connects to ---
# Placeholders are fine for building. Ask your server administrator for real values.
URL=https://fhir.example.org/fhir/
FHIR_BASE_URL=https://fhir.example.org/fhir/
OAUTH_BASE_URL=https://auth.example.org/realms/example/
OAUTH_CLIENT_ID=fhircore-app
OAUTH_SCOPE=openid

# Keep the double quotes on this one line exactly as shown.
OPENSRP_APP_ID="app"

# --- Optional services. Leave empty to turn them off. ---
MAPBOX_SDK_TOKEN=
POSTHOG_API_KEY=
POSTHOG_HOST=
```

Rules that trip people up:

1. **`sdk.dir` on Windows** needs doubled backslashes, and the colon escaped:
   `sdk.dir=C\:\\Users\\your-name\\AppData\\Local\\Android\\Sdk`
2. **No quotes** around URLs, client IDs, scopes or keys. **The one exception** is `OPENSRP_APP_ID="app"`, which **must** keep its quotes.
3. **Keep `POSTHOG_API_KEY=` and `POSTHOG_HOST=` present, even when empty.** If you delete these lines, the build fills in fake `sample_...` values, and the app sends analytics to a key that doesn't exist.
4. Prefer not to store the token in a file? Delete the two `gpr.` lines and set environment variables `GITHUB_USER` and `GITHUB_TOKEN` in your terminal instead.

> **Which server?** On first launch, the app shows a **site list** downloaded from the internet. Choosing a site sets the server for that session, so the server values above only matter as build-time defaults. To actually **log in**, you need a user account on a FHIR server that hosts this app's configuration. See [After installing: first launch](#after-installing-first-launch).

---

## Step 8: Create `keystore.properties`

The build also reads `android/keystore.properties`, even for debug builds. For now, create or replace it with placeholder values:

```properties
KEYSTORE_ALIAS=placeholder
KEY_PASSWORD=placeholder
KEYSTORE_PASSWORD=placeholder
```

Debug builds do not use these values. You will fill in real ones in [Step 11](#step-11-build-a-signed-release-apk), when you make a release build.

---

## Step 9: Build the debug APK

A **debug APK** is for testing on your own devices. It is signed with an automatic development key, so you need no keystore.

From the `android` folder:

**macOS / Linux:**

```bash
./gradlew :quest:assembleOpensrpDebug
```

**Windows:**

```powershell
.\gradlew.bat :quest:assembleOpensrpDebug
```

On macOS/Linux, if you see `permission denied: ./gradlew`, run `chmod +x gradlew` once and try again.

What to expect:

- The **first** run downloads Gradle 8.5 and about **4 GB** of libraries. That takes **15–40 minutes**, depending on your internet speed and computer. Later builds take a few minutes.
- You will see many `warning:` lines. They are normal.
- It ends with **`BUILD SUCCESSFUL`**.

**Check:** the APK exists at:

```
quest/build/outputs/apk/opensrp/debug/quest-opensrp-debug.apk
```

The file is large, about **550 MB**. That is expected: it bundles image-processing and AI libraries for four phone CPU types.

> The project has one "flavor", called **`opensrp`**. That is why every task name contains `Opensrp` (`assembleOpensrpDebug`, `assembleOpensrpRelease`). The installed app is called **Aarogya Aarohan**, with package id `in.ac.iisc.arogyam`.

---

## Step 10: Run the app on a phone or emulator

### Option A: A real Android phone (recommended)

1. On the phone, open **Settings → About phone** and tap **Build number** seven times. This turns on **Developer options**.
2. Open **Settings → System → Developer options** and turn on **USB debugging**.
3. Connect the phone with a USB cable. On the phone, tap **Allow** on the "Allow USB debugging?" prompt.
4. Check that your computer sees the phone:

   ```bash
   adb devices
   ```

   You should see one line ending in `device`. If `adb` is "not found", see [`adb: command not found`](#adb-command-not-found).

5. Build and install in one command:

   ```bash
   ./gradlew :quest:installOpensrpDebug
   ```

   Or install the APK you already built:

   ```bash
   adb install -r quest/build/outputs/apk/opensrp/debug/quest-opensrp-debug.apk
   ```

6. Open **Aarogya Aarohan** from the phone's app drawer.

### Option B: An emulator

1. In Android Studio, open **Tools → Device Manager → Create Virtual Device** (the **+** button).
2. Pick a phone (for example **Pixel 7**), then pick a system image with **API 34 or 35**. Click **Download** next to it if needed, then **Finish**.
3. Start the emulator with the ▶ button.
4. Once it has booted, run `./gradlew :quest:installOpensrpDebug` from the terminal.

The emulator's camera is simulated, so photo capture and AI screening work best on a real phone.

### After installing: first launch

1. The app shows **"Select your site"**. This list is downloaded from the internet, so the phone needs a connection.
2. Choose a site. The app then shows the **login** screen for that site's server.
3. Log in with an account on that server. **You need credentials from the server's administrator.** This guide cannot provide them.

If you are setting up **your own** server, you need a HAPI FHIR server, a Keycloak login server and this app's configuration resources on it. That is beyond this guide. Start with the [OpenSRP FHIR Core documentation](https://docs.smartregister.org/).

> Debug builds log network traffic and store the local database **unencrypted**. Never use a debug build with real patient data.

---

## Step 11: Build a signed release APK

A **release APK** is the version you give to users. It must be signed with **your own private key** (a "keystore"). Android only installs an update when it is signed with the same key as the installed app, so **if you lose the keystore or its password, you can never update that app again.** Back it up somewhere safe, outside the project folder.

### 11.1 Create your keystore (once)

From the `android` folder, run the line for your system. `keytool` comes with Java 17.

macOS / Linux:

```bash
keytool -genkeypair -v -keystore quest/opensrp.jks -alias release -keyalg RSA -keysize 2048 -validity 10000
```

Windows:

```powershell
keytool -genkeypair -v -keystore quest\opensrp.jks -alias release -keyalg RSA -keysize 2048 -validity 10000
```

It asks for:

- a **keystore password**: choose a strong one and write it down;
- your name, organisation and country (any reasonable values);
- a **key password**: you can press Enter to reuse the keystore password.

The build always looks for the keystore at **`quest/opensrp.jks`**, so keep that exact name and location. Git is already set to ignore `*.jks` files.

### 11.2 Fill in `keystore.properties`

Replace the placeholders from Step 8 with your real values:

```properties
KEYSTORE_ALIAS=release
KEY_PASSWORD=the-key-password-you-chose
KEYSTORE_PASSWORD=the-keystore-password-you-chose
```

If you pressed Enter for the key password in 11.1, `KEY_PASSWORD` is the same as `KEYSTORE_PASSWORD`.

On build servers, you can instead set the environment variables `KEYSTORE_ALIAS`, `KEY_PASSWORD` and `KEYSTORE_PASSWORD`. They override the file, but the file must still exist (the placeholder version is enough).

### 11.3 AI screening (optional)

The app is built **without** an AI model by default, and the release build does not need one. To include an AI screening model, follow the [AI model integration guide](ai-model-integration-guide.md).

### 11.4 Build

macOS / Linux:

```bash
./gradlew :quest:assembleOpensrpRelease
```

Windows:

```powershell
.\gradlew.bat :quest:assembleOpensrpRelease
```

**Check:** the signed APK is at:

```
quest/build/outputs/apk/opensrp/release/quest-opensrp-release.apk
```

### 11.5 Verify the signature (recommended)

`apksigner` is in your SDK's `build-tools` folder. Replace `35.0.0` with whichever version you have; `ls ~/Library/Android/sdk/build-tools` lists them.

macOS:

```bash
~/Library/Android/sdk/build-tools/35.0.0/apksigner verify --print-certs quest/build/outputs/apk/opensrp/release/quest-opensrp-release.apk
```

Windows:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs quest\build\outputs\apk\opensrp\release\quest-opensrp-release.apk
```

You should see `Signer #1 certificate DN: CN=...`, showing the details you entered in 11.1.

### 11.6 Install the release APK

```bash
adb install -r quest/build/outputs/apk/opensrp/release/quest-opensrp-release.apk
```

If the phone already has a **debug** build, the signatures differ and the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the old one first with `adb uninstall in.ac.iisc.arogyam`. **This deletes the app's data on that phone, including cases that have not been synced.**

### Optional: Build an App Bundle for Google Play

The Play Store needs an `.aab` instead of an `.apk`:

```bash
./gradlew :quest:bundleOpensrpRelease
```

The output is `quest/build/outputs/bundle/opensrpRelease/quest-opensrp-release.aab`.

### Before you ship a release

- **Version:** each new release needs a higher `versionCode`. Both `versionCode` and `versionName` are in `buildSrc/src/main/kotlin/BuildConfigs.kt`.
- **Server values:** a release build uses the values in `local.properties`. Make sure they point to the right production server, not the `example.org` placeholders.
- **Test** the release APK on a real phone: site list, login, sync, a full screening with photos, submit, and sync again.

---

## Optional: Work in Android Studio

Everything above works from the terminal. To browse and edit the code in Android Studio:

1. **File → Open** and select the **`android`** folder. Do not select the repository root.
2. **Set the Gradle JDK to 17.** This step matters, because Android Studio's built-in Java is too new for this project:
   - macOS: **Android Studio → Settings → Build, Execution, Deployment → Build Tools → Gradle**
   - Windows/Linux: **File → Settings → Build, Execution, Deployment → Build Tools → Gradle**
   - Set **Gradle JDK** to the Temurin 17 you installed. If it's not listed, choose **Add JDK…** and select its folder.
3. Click **Sync Project with Gradle Files** (the elephant icon in the toolbar). The first sync is slow.
4. In **Build → Select Build Variant**, set `quest` to **`opensrpDebug`**.
5. Choose your phone or emulator in the device dropdown, then press the green ▶ **Run** button.

To build a release APK from the menu instead, use **Build → Generate Signed App Bundle / APK…**, choose **APK**, point it at `quest/opensrp.jks`, and pick the **opensrpRelease** variant. The Gradle command in Step 11 does the same thing with less clicking.

---

## Troubleshooting

Find your error message below. If it isn't here, scroll up in the build output to the **first** line containing `What went wrong:`. That line usually names the real problem.

### `File .../local.properties not found` or `File .../keystore.properties not found`

The file is missing or in the wrong folder. Both files belong directly inside `android/`, next to `gradlew`, not inside `quest/`. See [Step 7](#step-7-create-localproperties) and [Step 8](#step-8-create-keystoreproperties).

### `SDK location not found` / `The SDK directory '...' does not exist`

`sdk.dir` in `local.properties` is wrong. Copy the exact path from **SDK Manager → Android SDK Location**. On Windows, remember the `C\:\\...` escaping.

### `Received status code 401 from server: Unauthorized` (URL contains `maven.pkg.github.com`)

Your GitHub token is missing, wrong or expired, or it lacks the `read:packages` permission. Check `gpr.user` and `gpr.key` in `local.properties`, or make a new token ([Step 4](#step-4-create-a-github-access-token)). `gpr.user` must be your GitHub **username**, not your email.

### `Could not find org.smartregister:data-capture:...` or `...workflow:...`

This is usually the same token problem as above. If the token check in Step 4 returns `200`, run the build again with `--refresh-dependencies`.

### Errors mentioning `org.opencv`, `Unresolved reference: opencv` or `opencv-411-android.aar`

The OpenCV file is missing or misnamed. It must be exactly `quest/libs/opencv-411-android.aar`. Redo [Step 6](#step-6-add-the-opencv-library).

### Wrong Java version

Typical messages:

- The build fails within seconds, and `What went wrong:` is followed by **nothing but a version number**, such as `25.0.3`. That is Gradle 8.5's (unhelpful) way of saying "this Java is too new". It is what you get with Android Studio's bundled Java.
- `Unsupported class file major version 66` (or any number above 65)
- `Gradle requires JVM 17 or later`

To fix it:

1. Run `java -version` in the same terminal. It must say 17 (21 also works).
2. On macOS, check that `echo $JAVA_HOME` prints a path containing `17`. If not, redo the `~/.zshrc` line in [Step 2](#step-2-install-java-17) and open a **new** Terminal window.
3. In Android Studio, set the **Gradle JDK** to 17 (see [Optional: Work in Android Studio](#optional-work-in-android-studio)).
4. Then run `./gradlew --stop` and build again.

### `OutOfMemoryError: Java heap space` or `Gradle build daemon disappeared unexpectedly`

The build needs about 8 GB of free memory. Close other apps (browsers, emulators) and run `./gradlew --stop`, then build again. On a machine with 8 GB of RAM, lower `org.gradle.jvmargs=-Xmx8192m` to `-Xmx4096m` in `android/gradle.properties`. **Don't commit that change.**

### `error: cannot find symbol` pointing at `OPENSRP_APP_ID` in `BuildConfig.java`

The quotes are missing. The line must be exactly `OPENSRP_APP_ID="app"`.

### `Keystore file '.../quest/opensrp.jks' not found for signing config 'release'`

The keystore must be at `android/quest/opensrp.jks`. See [11.1](#111-create-your-keystore-once).

### `Keystore was tampered with, or password was incorrect` / `Cannot recover key`

A password in `keystore.properties` doesn't match the keystore, or `KEYSTORE_ALIAS` is wrong. To list the aliases inside your keystore, run `keytool -list -keystore quest/opensrp.jks`.

### `INSTALL_FAILED_UPDATE_INCOMPATIBLE`

A copy of the app signed with a different key is already installed. See [11.6](#116-install-the-release-apk).

### `adb: command not found`

`adb` is in the SDK's `platform-tools` folder. Either run it with its full path (`~/Library/Android/sdk/platform-tools/adb devices` on macOS, `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices` on Windows), or add that folder to your `PATH`.

### `adb devices` shows `unauthorized` or nothing at all

Unplug the cable and plug it back in, then accept the "Allow USB debugging?" prompt on the phone. Try a different cable: some cables only charge. On Windows, you may need your phone maker's USB driver.

### The app opens, but the site list is empty or shows an error

The site list is downloaded from the internet. Check the phone's connection, and that it can open web pages.

### Anything else: a clean retry

```bash
./gradlew --stop
./gradlew clean
./gradlew :quest:assembleOpensrpDebug --stacktrace
```

`--stacktrace` prints more detail. Include that output if you ask the maintainers for help, but **remove your token and passwords from anything you paste**.

---

## Command cheat sheet

Run these from the `android` folder. On Windows, use `.\gradlew.bat` instead of `./gradlew`.

| Goal | Command |
|---|---|
| Build debug APK | `./gradlew :quest:assembleOpensrpDebug` |
| Build and install debug on a connected phone | `./gradlew :quest:installOpensrpDebug` |
| Build signed release APK | `./gradlew :quest:assembleOpensrpRelease` |
| Build Play Store bundle | `./gradlew :quest:bundleOpensrpRelease` |
| Run app unit tests | `./gradlew :quest:testOpensrpDebugUnitTest` |
| Run engine unit tests | `./gradlew :engine:testDebugUnitTest` |
| Check code formatting | `./gradlew spotlessCheck` |
| Stop background Gradle processes | `./gradlew --stop` |
| Delete build outputs | `./gradlew clean` |
| List connected devices | `adb devices` |
| Uninstall the app (deletes its data) | `adb uninstall in.ac.iisc.arogyam` |

Output locations:

| Build | File |
|---|---|
| Debug APK | `quest/build/outputs/apk/opensrp/debug/quest-opensrp-debug.apk` |
| Release APK | `quest/build/outputs/apk/opensrp/release/quest-opensrp-release.apk` |
| Release bundle | `quest/build/outputs/bundle/opensrpRelease/quest-opensrp-release.aab` |

---

## Files you must never commit

These files contain secrets, or content we are not allowed to publish. Always check `git status` before committing.

If `git status` lists `local.properties` or `keystore.properties` as **modified**, older copies of them are tracked in the repository. Don't stage them. Tell git to stop showing your local edits:

```bash
git update-index --skip-worktree local.properties keystore.properties
```

| File | Why |
|---|---|
| `android/local.properties` | Your GitHub token, server addresses and machine paths |
| `android/keystore.properties` | Your keystore passwords |
| `android/quest/opensrp.jks` (or any `*.jks`) | Your release signing key. Anyone who has it can publish fake updates of your app. |
| AI model weights (`*.pt`, `*.onnx`, …) | Not licensed for publication. See the [AI model integration guide](ai-model-integration-guide.md#where-the-model-file-goes). |
| `quest/libs/opencv-411-android.aar` | Large binary; everyone downloads it themselves (Step 6) |
| APK / AAB files | Build outputs; share them through your release channel, not git |

---

*Last verified 2026-10-22 on macOS (Apple Silicon) with Temurin JDK 17, using a fresh clone and empty build caches: the debug build, the release build, and the `apksigner` check all passed. The Windows and Linux commands follow standard tooling, but have not been run end to end.*
