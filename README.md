# Random Photo Wallpaper

A minimal Android launcher app. Tap its icon to choose a random image from the selected folders and set a randomly positioned crop as both the Home and Lock Screen wallpaper.

## Behavior
- First launch opens Android's system folder picker. Select one folder containing photos.
- A notification appears after each successful wallpaper change with two actions:
  - **Delete & change next**: deletes the current source photo from the selected folder, then sets another wallpaper.
  - **Add more folder**: opens the system folder picker and adds another folder to the scan list.
- Multiple selected folders are supported. Only images directly inside each selected folder are scanned; subfolders are not scanned.
- Hidden files (names beginning with a dot) are skipped.
- Photos are shuffled without replacement across all selected folders until the current photo set has been exhausted, then the cycle restarts.
- Newly added images are eligible immediately; deleted images are pruned from the effective history.
- Original photos are not modified except when the user explicitly taps **Delete & change next**.
- Crop position is randomized, the display aspect ratio is preserved, and a 20% black overlay is applied.
- No internet permission, ads, analytics, or network code.

## Permissions and notes
- Android notification permission is requested so the two notification actions can be shown.
- The selected folders need to grant write access for **Delete & change next** to delete the original photo. If a document provider does not allow deletion, the app reports that and still attempts to set the next wallpaper.
- Android's system folder picker is used to grant persistent access.
- Setting Home and Lock Screen wallpaper is handled through Android's `WallpaperManager`; manufacturer behavior may vary.
- Requires Android 9 (API 28) or newer.

## Build
The project includes a GitHub Actions workflow at `.github/workflows/android-apk.yml`. Push the project to the `main` branch or run the workflow manually from the Actions tab to build the debug APK.

## Automatic wallpaper schedule

The app registers a unique WorkManager periodic task with a 4-hour interval. Android may defer the exact execution time to optimize battery life. Each run skips wallpaper changes from 9:00 PM through 8:59 AM (device local time), while the device is in landscape orientation, while Android Battery Saver is active, and while Do Not Disturb is active when Notification Policy Access has been granted. WorkManager also requires the battery-not-low condition. The schedule is persisted by WorkManager across app restarts and device reboots. The first registration happens when the app is opened; force-stopping the app prevents background work until it is opened again.

Android does not allow apps to grant themselves Notification Policy Access. If that access is not enabled in Android settings, the app cannot reliably read DND state and therefore cannot guarantee DND-based skipping without the user enabling that system-level access.
