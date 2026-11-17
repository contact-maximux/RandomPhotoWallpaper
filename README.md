# Random Photo Wallpaper

A minimal Android launcher app. Tap its icon to choose a random image from a folder and set a randomly positioned crop as both the Home and Lock Screen wallpaper.

## Behavior
- No in-app screens or controls.
- First launch opens Android's system folder picker once. Choose the Pictures folder (or another folder containing photos).
- Later launches directly choose and apply a photo.
- Crop position is randomized while preserving the phone display aspect ratio.
- Photos are shuffled without replacement until the folder's available photo set has been exhausted, then the cycle restarts.
- Uses the device's actual display pixel dimensions at runtime.
- No internet permission, ads, analytics, or network code.

## Build
1. Open this folder in Android Studio (recent version).
2. Allow Gradle sync to finish
3. Install the generated APK.

## Notes
- Android's system folder picker is required for the one-time folder permission.
- Setting the lock-screen wallpaper separately is supported by Android's `WallpaperManager`; some manufacturer software may apply device-specific behavior.
- This project targets Android 15 (API 35) and requires Android 9 (API 28) or newer.
