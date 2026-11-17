# Build the APK using only your Android phone

This project includes a GitHub Actions workflow that builds the APK in GitHub's cloud. You do not need a computer or Android Studio.

## Phone-only steps

1. In your phone browser, open https://github.com and sign in or create a free account.
2. Create a **new public repository** named `RandomPhotoWallpaper`.
3. Open the repository's **Add file → Upload files** page.
4. Extract this ZIP on your phone using your Files app / ZIP extractor.
5. Upload the project files and folders to the repository's `main` branch. Make sure the `.github/workflows/android-apk.yml` file is included. If your file picker hides dot-folders, create `.github/workflows/android-apk.yml` in GitHub using **Add file → Create new file**, and paste the workflow content from the same path in this ZIP.
6. Commit the upload. GitHub Actions should start automatically.
7. In the repository, open **Actions → Build Android APK → latest run**.
8. When it finishes successfully, scroll to **Artifacts** and download `RandomPhotoWallpaper-debug-apk`.
9. Open the downloaded artifact ZIP on your phone; inside is `app-debug.apk`. Tap it to install. Android may ask you to allow installs from your browser or Files app.

If the build fails, open the failed run and share the error text so it can be fixed.

## Important
- A GitHub account is required for this cloud-build route.
- The APK produced is a debug APK, suitable for installing on your own phone.
- On first launch, choose the Pictures folder in Android's folder picker. Later taps on the app icon choose a random photo, apply a random crop, and set Home + Lock Screen wallpaper.
