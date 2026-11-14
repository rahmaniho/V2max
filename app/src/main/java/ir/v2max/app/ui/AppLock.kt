package ir.v2max.app.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * قفل برنامه با اثر انگشت / چهره / رمز دستگاه.
 * نبودن روش احراز هویت، مجوز ورود محسوب نمی‌شود؛ کاربر می‌تواند قفل دستگاه را تنظیم کند.
 */
object AppLock {

    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(context: Context): Boolean {
        return runCatching {
            BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
    }

    /** درخواست باز کردن قفل؛ نتیجه در callback برگردانده می‌شود */
    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!isAvailable(activity)) {
            onFailure("روش احراز هویت در دسترس نیست. تنظیمات قفل دستگاه را در اندروید بررسی کنید و دوباره تلاش کنید.")
            return
        }
        runCatching {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        onFailure(errString.toString())
                    }
                }
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle("باز کردن V2max")
                .setSubtitle("برای دسترسی به برنامه احراز هویت کنید")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info)
        }.onFailure { onFailure("نمایش احراز هویت ممکن نشد؛ دوباره تلاش کنید.") }
    }
}
