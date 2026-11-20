
package notepad.autowall
import android.content.Context
import android.content.res.Configuration
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Calendar
import java.util.concurrent.TimeUnit

class WallpaperChangeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {

        val context = applicationContext

        if (
            isQuietHours() ||
            isLandscape(context) ||
            isPowerSaveMode(context) ||
            isDoNotDisturb(context)
        ) {
            return Result.success()
        }

        return try {
            // Shared wallpaper implementation.
            WallpaperEngine.changeWallpaper(context)

            Result.success()

        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun isQuietHours(): Boolean {
        val hour = Calendar.getInstance()
            .get(Calendar.HOUR_OF_DAY)

        return hour >= 21 || hour < 9
    }

    private fun isLandscape(context: Context): Boolean {
        return context.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
    }

    private fun isPowerSaveMode(context: Context): Boolean {
        val power = context.getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        return power.isPowerSaveMode
    }

    private fun isDoNotDisturb(context: Context): Boolean {
        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            !manager.isNotificationPolicyAccessGranted
        ) {
            return false
        }

        return try {
            manager.currentInterruptionFilter !=
                NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: SecurityException) {
            false
        }
    }
}

object WallpaperScheduler {

    fun schedule(context: Context) {

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request =
            PeriodicWorkRequestBuilder<WallpaperChangeWorker>(
                4,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "random_wallpaper_every_four_hours",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
}
