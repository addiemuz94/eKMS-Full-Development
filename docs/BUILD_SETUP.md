# eKMS Build Setup — Windows / Android Studio

## Required toolchain

| Item | Required version |
|---|---:|
| Gradle JDK | 17 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.11.1 |
| Kotlin | 2.2.20 |
| Android compile SDK | 36 |

This project deliberately uses the Gradle 8.x / Android Gradle Plugin 8.x
baseline. Do not choose Gradle 9.x for this project unless the whole KMP build
is migrated to the newer Android Gradle Plugin model.

## Android Studio configuration

1. Open the `ekms-platform` folder, not an individual module folder.
2. Select **File > Settings > Build, Execution, Deployment > Build Tools > Gradle**.
3. Set **Gradle JDK** to **Embedded JDK 17** (or another installed JDK 17).
4. Set **Gradle distribution** to the installed **Gradle 8.13** location.
5. Click **Apply**, then select **File > Sync Project with Gradle Files**.
6. In the device selector, choose the `terminalApp` configuration and your
   F7G18P terminal or an Android emulator.

## Why this is required

Kotlin was compiling to JVM 21 while Android's Java task compiled to JVM 11.
Gradle correctly rejects a build whose Java and Kotlin bytecode targets differ.
The project build scripts now explicitly make both targets JVM 17.

## If Android SDK 36 is missing

Open **Tools > SDK Manager > SDK Platforms**, install **Android 16.0 / API 36**,
then sync the project again.
