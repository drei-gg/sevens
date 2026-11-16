package gg.seven.vault

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * iOS `BiometricKey` (a Keychain item bound to `.biometryCurrentSet`) on Android: an AES-256-GCM
 * key in the Android Keystore, in StrongBox when the device has one, usable only after a strong
 * biometric for each use, and invalidated by a new biometric enrollment. It wraps the open vault's
 * age identity (the core's `exportForCustody`). One vault at a time, as on iOS. No device
 * credential fallback: the vault's own secret is the fallback.
 */
object BiometricKey {
    private const val STORE = "AndroidKeyStore"
    private const val ALIAS = "vault.biometric"
    private const val PREFS = "vault-biometric"

    class Unavailable(message: String) : Exception(message)

    /** Why biometric unlock can't be turned on here, or null when it can. */
    fun unavailable(context: Context): String? =
        when (BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set up a fingerprint or face unlock in Android settings first."
            else -> "This device has no strong biometric sensor Vault can use."
        }

    fun storedId(context: Context): UUID? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("id", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    fun remove(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { clear() }
        runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun generate(context: Context): SecretKey {
        fun spec(strongBox: Boolean) =
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .apply {
                    if (Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT < 30) setUserAuthenticationValidityDurationSeconds(-1)
                    if (Build.VERSION.SDK_INT >= 28 && strongBox) setIsStrongBoxBacked(true)
                }
                .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        val strongBox = Build.VERSION.SDK_INT >= 28 && context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return try {
            generator.init(spec(strongBox))
            generator.generateKey()
        } catch (e: Exception) {
            if (!strongBox || (Build.VERSION.SDK_INT >= 28 && e !is StrongBoxUnavailableException && e.cause !is StrongBoxUnavailableException)) throw e
            generator.init(spec(false))
            generator.generateKey()
        }
    }

    private fun key(): SecretKey? = (KeyStore.getInstance(STORE).apply { load(null) }.getKey(ALIAS, null) as? SecretKey)

    private suspend fun authenticate(activity: FragmentActivity, title: String, cipher: Cipher): Cipher =
        suspendCancellableCoroutine { continuation ->
            val prompt =
                BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val authorized = result.cryptoObject?.cipher
                            if (authorized == null) continuation.resumeWithException(Unavailable("Biometric unlock did not complete."))
                            else continuation.resume(authorized)
                        }

                        override fun onAuthenticationError(code: Int, message: CharSequence) {
                            continuation.resumeWithException(Cancelled(message.toString()))
                        }
                    },
                )
            val info =
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle("Open your encrypted vault")
                    .setNegativeButtonText("Use your secret")
                    .setAllowedAuthenticators(BIOMETRIC_STRONG)
                    .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    /** The person dismissed the prompt or chose their secret instead. Not an error to show. */
    class Cancelled(message: String) : Exception(message)

    /** Wrap `secret` (the identity) for vault `id`, after a biometric. Replaces any earlier vault's. */
    suspend fun enable(activity: FragmentActivity, id: UUID, secret: ByteArray) {
        unavailable(activity)?.let { throw Unavailable(it) }
        remove(activity)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, generate(activity)) }
        try {
            val authorized = authenticate(activity, "Turn on biometric unlock", cipher)
            val sealed = authorized.doFinal(secret)
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                putString("id", id.toString())
                putString("iv", Base64.encodeToString(authorized.iv, Base64.NO_WRAP))
                putString("sealed", Base64.encodeToString(sealed, Base64.NO_WRAP))
            }
        } catch (e: Exception) {
            remove(activity)
            throw e
        } finally {
            secret.fill(0)
        }
    }

    /**
     * The wrapped identity, after a biometric. A new enrollment has invalidated the key: it is
     * removed and the person is told to use their secret and turn it on again.
     */
    suspend fun read(activity: FragmentActivity): Pair<UUID, ByteArray> {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = storedId(activity) ?: throw Unavailable("Biometric unlock is off.")
        val iv = Base64.decode(prefs.getString("iv", null) ?: throw Unavailable("Biometric unlock is off."), Base64.NO_WRAP)
        val sealed = Base64.decode(prefs.getString("sealed", null) ?: throw Unavailable("Biometric unlock is off."), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        try {
            cipher.init(Cipher.DECRYPT_MODE, key() ?: throw KeyPermanentlyInvalidatedException(), GCMParameterSpec(128, iv))
        } catch (_: KeyPermanentlyInvalidatedException) {
            remove(activity)
            throw Unavailable("Biometric unlock was turned off because this device's fingerprints or faces changed. Unlock with your secret, then turn it on again in Settings.")
        }
        val authorized = authenticate(activity, "Unlock Vault", cipher)
        return id to authorized.doFinal(sealed)
    }
}
