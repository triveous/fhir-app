---
title: Installation Guide
sidebar_label: Installation Guide
---

# Installation Guide for Aarogya Aarohan

This guide provides step-by-step instructions for installing and running Aarogya Aarohan, enabling technical reviewers to launch and test the application independently.

## Prerequisites

### System Requirements

**Development Environment:**
- **Operating System**: Windows 10+, macOS 10.15+, or Ubuntu 18.04+
- **Java**: JDK 11 or higher
- **Android Studio**: Version 4.2 or higher
- **RAM**: Minimum 8GB, recommended 16GB
- **Storage**: At least 10GB free space

**Target Device:**
- **Android Version**: Android 8.0 (API level 26) or higher
- **RAM**: Minimum 4GB
- **Storage**: At least 2GB free space
- **Camera**: Required for image capture functionality

### Required Accounts and Services

1. **GitHub Account**: For accessing the source code repository
2. **Google Account**: For Android development tools
3. **Firebase Account** (optional): For crash reporting and analytics

## Step 1: Clone the Repository

### Get the Source Code

```bash
# Clone the repository
git clone https://github.com/artpark/aarogya-aarohan.git

# Navigate to the project directory
cd aarogya-aarohan

# Check out the latest release
git checkout main
```

### Repository Structure

```
aarogya-aarohan/
├── android/                 # Android application code
│   ├── engine/             # Core engine module
│   ├── quest/              # Main application module
│   ├── geowidget/          # Map functionality module
│   └── build.gradle.kts    # Build configuration
├── docs/                   # Documentation
├── README.md               # Project overview
└── LICENSE                 # Apache License 2.0
```

## Step 2: Set Up Development Environment

### Install Java Development Kit (JDK)

**For Windows:**
1. Download OpenJDK 11 from [AdoptOpenJDK](https://adoptopenjdk.net/)
2. Run the installer and follow the setup wizard
3. Add Java to your PATH environment variable

**For macOS:**
```bash
# Using Homebrew
brew install openjdk@11

# Set JAVA_HOME
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
```

**For Ubuntu:**
```bash
sudo apt update
sudo apt install openjdk-11-jdk

# Set JAVA_HOME
export JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
```

### Install Android Studio

1. Download Android Studio from [developer.android.com](https://developer.android.com/studio)
2. Run the installer and follow the setup wizard
3. Install the following components:
   - Android SDK Platform 26 (API level 26)
   - Android SDK Build-Tools
   - Android Emulator
   - Android SDK Platform-Tools

### Configure Android SDK

1. Open Android Studio
2. Go to **File > Settings** (Windows/Linux) or **Android Studio > Preferences** (macOS)
3. Navigate to **Appearance & Behavior > System Settings > Android SDK**
4. Install the following SDK components:
   - Android 8.0 (API level 26)
   - Android 9.0 (API level 28)
   - Android 10.0 (API level 29)
   - Android 11.0 (API level 30)
   - Android 12.0 (API level 31)

## Step 3: Configure the Project

### Set Up Local Properties

Create a `local.properties` file in the `android/` directory:

```properties
# SDK location
sdk.dir=/path/to/your/Android/Sdk

# OAuth configurations
OAUTH_BASE_URL=https://keycloak-stage.smartregister.org/auth/realms/FHIR_Android/
OAUTH_CLIENT_ID=your_client_id
OAUTH_SCOPE=openid

# FHIR store base URL
FHIR_BASE_URL=https://fhir.labs.smartregister.org/fhir/

# Sentry DSN (optional)
SENTRY_DSN=https://your/sentry/project/dsn

# Application ID for specific build variant
OPENSRP_APP_ID=aarogya-aarohan
```

### Configure Keystore (for Release Builds)

Create a `keystore.properties` file in the `android/` directory:

```properties
KEYSTORE_PASSWORD=your_keystore_password
KEYSTORE_ALIAS=your_keystore_alias
KEY_PASSWORD=your_key_password
```

**Note**: For testing purposes, you can use debug builds which don't require a keystore.

## Step 4: Build the Application

### Open Project in Android Studio

1. Launch Android Studio
2. Select **Open an existing Android Studio project**
3. Navigate to the `android/` directory and select it
4. Wait for the project to sync and index

### Build Configuration

1. In Android Studio, go to **Build > Select Build Variant**
2. Choose the appropriate variant:
   - **Debug**: For testing and development
   - **Release**: For production deployment

### Build the APK

**Using Android Studio:**
1. Go to **Build > Build Bundle(s) / APK(s) > Build APK(s)**
2. Wait for the build to complete
3. Click **locate** in the notification to find the APK file

**Using Command Line:**
```bash
# Navigate to the android directory
cd android

# Build debug APK
./gradlew assembleDebug

# Build release APK (requires keystore)
./gradlew assembleRelease
```

The APK file will be generated in:
```
android/quest/build/outputs/apk/debug/quest-debug.apk
```

## Step 5: Install and Run the Application

### Install on Physical Device

1. Enable **Developer Options** on your Android device:
   - Go to **Settings > About Phone**
   - Tap **Build Number** 7 times
   - Go back to **Settings > Developer Options**
   - Enable **USB Debugging**

2. Connect your device via USB

3. Install the APK:
   ```bash
   adb install android/quest/build/outputs/apk/debug/quest-debug.apk
   ```

### Install on Emulator

1. Create an Android Virtual Device (AVD):
   - Go to **Tools > AVD Manager**
   - Click **Create Virtual Device**
   - Select a device definition (e.g., Pixel 4)
   - Select a system image (API level 26 or higher)
   - Complete the AVD creation

2. Start the emulator and install the APK

### Launch the Application

1. Open the **Aarogya Aarohan** app on your device
2. The app will guide you through the initial setup process
3. Configure the connection to your FHIR server
4. Set up user authentication

## Step 6: Configure FHIR Server

### Set Up HAPI FHIR Server

1. **Option 1: Use Existing Server**
   - Configure the app to connect to an existing FHIR server
   - Update the `FHIR_BASE_URL` in `local.properties`

2. **Option 2: Set Up Local Server**
   ```bash
   # Clone HAPI FHIR server
   git clone https://github.com/hapifhir/hapi-fhir-jpaserver-starter.git
   
   # Configure the server
   cd hapi-fhir-jpaserver-starter
   # Follow HAPI FHIR server setup instructions
   ```

### Configure Keycloak Authentication

1. Set up a Keycloak server or use an existing one
2. Create a realm for the application
3. Configure OAuth client settings
4. Update the OAuth configuration in `local.properties`

## Step 7: Test the Application

### Basic Functionality Test

1. **Login**: Test user authentication
2. **Questionnaire**: Create and fill out a questionnaire
3. **Image Capture**: Test camera functionality for lesion images
4. **Data Sync**: Test synchronization with the FHIR server
5. **Offline Mode**: Test functionality without internet connection

### Advanced Testing

1. **Performance**: Test with large datasets
2. **Memory Usage**: Monitor memory consumption during image operations
3. **Network**: Test with poor network conditions
4. **Security**: Verify data encryption and privacy measures

## Troubleshooting

### Common Issues

**Build Errors:**
- Ensure JDK 11 is installed and JAVA_HOME is set correctly
- Update Android Studio and SDK tools
- Clean and rebuild the project

**Runtime Errors:**
- Check network connectivity
- Verify FHIR server configuration
- Ensure proper permissions are granted

**Performance Issues:**
- Monitor device memory usage
- Check image file sizes
- Verify sync configuration

### Getting Help

- **Documentation**: Check the project documentation
- **Issues**: Report issues on the GitHub repository
- **Community**: Contact ARTPARK at connect@artpark.in

## Verification Checklist

- [ ] Repository cloned successfully
- [ ] Development environment configured
- [ ] Project builds without errors
- [ ] APK installed on device/emulator
- [ ] Application launches successfully
- [ ] Basic functionality works
- [ ] FHIR server connection established
- [ ] Data synchronization working
- [ ] Offline functionality tested

## Conclusion

This installation guide provides all the necessary steps to set up and run Aarogya Aarohan independently. The application is designed to be easily deployable and testable, supporting the goal of being a digital public good that can be freely used and evaluated by technical reviewers.

For additional support or questions, please refer to the project documentation or contact the development team. 