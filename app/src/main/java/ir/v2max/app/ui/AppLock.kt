package ir.v2max.app.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * قفل برنامه با اثر انگشت / چهره / رمز دستگاه.
 * اگر دستگاه هیچ روش احراز هویتی نداشته باشد، قفل غیرفعال می‌شود (بدون قفل‌شدن کاربر در برنامه).
 */
object AppLock {

    const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun isAvailable(context: Context): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** درخواست باز کردن قفل؛ نتیجه در callback برگردانده می‌شود */
    fun prompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!isAvailable(activity)) {
            // روش احراز هویت تنظیم نشده — قفل دور زده میشود تا کاربر قفل نشود
            onSuccess()
            return
        }
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
        runCatching { prompt.authenticate(info) }
            .onFailure { onFailure(it.message ?: "احراز هویت ممکن نشد") }
    }
}
