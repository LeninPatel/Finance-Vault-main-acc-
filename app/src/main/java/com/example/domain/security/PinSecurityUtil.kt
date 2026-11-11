package com.example.domain.security

import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.ContextCompat
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory

/**
 * Cryptographic PIN hashing (PBKDF2-HMAC-SHA256 with legacy SHA-256 migration support),
 * exponential brute-force lockout throttling, sensitive field masking, Android Keystore AES-GCM
 * field encryption, and hardware Biometric authentication utility.
 *
 * Security Guarantees:
 * - PINs are never stored or logged in plaintext.
 * - Uses PBKDF2-HMAC-SHA256 (120,000 iterations, 256-bit derived key, 128-bit CSPRNG salt).
 * - Uses constant-time byte comparison (`MessageDigest.isEqual`) to prevent timing side-channels.
 */
object PinSecurityUtil {

    const val ALGORITHM_PBKDF2_SHA256 = "PBKDF2WithHmacSHA256"
    const val ALGORITHM_PBKDF2 = ALGORITHM_PBKDF2_SHA256
    const val ALGORITHM_LEGACY_SHA256 = "SHA-256"
    const val DEFAULT_PBKDF2_ITERATIONS = 120_000
    const val DEFAULT_ITERATIONS = DEFAULT_PBKDF2_ITERATIONS
    private const val KEY_LENGTH_BITS = 256
    private const val VERSION_PREFIX = "pbkdf2_sha256"

    private val secureRandom = SecureRandom()

    fun generateSaltHex(byteCount: Int = 16): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Derives a PBKDF2-HMAC-SHA256 PIN hash formatted with self-describing parameters:
     * `pbkdf2_sha256$<iterations>$<saltHex>$<derivedHex>`
     */
    fun hashPinPbkdf2(
        pin: String,
        saltHex: String,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS
    ): String {
        val cleanPin = pin.trim()
        require(cleanPin.isNotEmpty()) { "PIN cannot be blank." }
        val saltBytes = hexToBytes(saltHex).takeIf { it.isNotEmpty() }
            ?: saltHex.toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(
            cleanPin.toCharArray(),
            saltBytes,
            iterations.coerceAtLeast(10_000),
            KEY_LENGTH_BITS
        )
        return try {
            val skf = SecretKeyFactory.getInstance(ALGORITHM_PBKDF2_SHA256)
            val derived = skf.generateSecret(spec).encoded
            val derivedHex = derived.joinToString("") { "%02x".format(it) }
            "$VERSION_PREFIX$$iterations$$saltHex$$derivedHex"
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Legacy SHA-256 hash retained strictly for verifying and migrating existing v1-v6 PIN records.
     */
    fun hashPinLegacySha256(pin: String, saltHex: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = "$saltHex:${pin.trim()}:finance_vault_v1".toByteArray(Charsets.UTF_8)
        val hashed = digest.digest(input)
        return hashed.joinToString("") { "%02x".format(it) }
    }

    /**
     * Retained as primary hashing entry point for callers; now produces PBKDF2-HMAC-SHA256 hashes.
     */
    fun hashPinSha256(pin: String, saltHex: String): String {
        return hashPinPbkdf2(pin = pin, saltHex = saltHex, iterations = DEFAULT_PBKDF2_ITERATIONS)
    }

    /**
     * Verifies an entered PIN against either a modern PBKDF2-HMAC-SHA256 encoded hash
     * or a legacy salted SHA-256 hash using constant-time comparison.
     */
    fun verifyPin(
        enteredPin: String,
        storedHash: String?,
        storedSalt: String?,
        algorithm: String = ALGORITHM_PBKDF2_SHA256,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS
    ): Boolean {
        if (enteredPin.isBlank() || storedHash.isNullOrBlank()) return false
        val trimmedHash = storedHash.trim()

        if (trimmedHash.startsWith("$VERSION_PREFIX$")) {
            val parts = trimmedHash.split("$")
            if (parts.size == 4) {
                val iterCount = parts[1].toIntOrNull() ?: iterations
                val saltFromRecord = parts[2].ifBlank { storedSalt.orEmpty() }
                if (saltFromRecord.isBlank()) return false
                val recomputed = hashPinPbkdf2(enteredPin, saltFromRecord, iterCount)
                return MessageDigest.isEqual(
                    recomputed.toByteArray(Charsets.UTF_8),
                    trimmedHash.toByteArray(Charsets.UTF_8)
                )
            }
        }

        // Fallback verification for legacy SHA-256 PINs so existing users are never locked out
        if (storedSalt.isNullOrBlank()) return false
        val legacyComputed = hashPinLegacySha256(enteredPin, storedSalt)
        return MessageDigest.isEqual(
            legacyComputed.toByteArray(Charsets.UTF_8),
            trimmedHash.toByteArray(Charsets.UTF_8)
        )
    }

    fun verifyPin(
        enteredPin: String,
        storedHashHex: String?,
        storedSaltHex: String?,
        algorithm: String = ALGORITHM_PBKDF2_SHA256,
        iterations: Int = DEFAULT_PBKDF2_ITERATIONS,
        unusedOverloadMarker: Boolean = true
    ): Boolean = verifyPin(enteredPin, storedHashHex, storedSaltHex, algorithm, iterations)

    /**
     * Returns true if the stored hash or algorithm uses the legacy SHA-256 format and should be upgraded to PBKDF2.
     */
    fun needsLegacyMigration(storedHashOrAlgo: String?): Boolean {
        if (storedHashOrAlgo.isNullOrBlank()) return false
        val trimmed = storedHashOrAlgo.trim()
        if (trimmed.equals(ALGORITHM_PBKDF2_SHA256, ignoreCase = true)) return false
        if (trimmed.equals(ALGORITHM_LEGACY_SHA256, ignoreCase = true)) return true
        return !trimmed.startsWith("$VERSION_PREFIX$")
    }

    /**
     * Computes progressive exponential retry delay (in milliseconds) after consecutive failed PIN attempts:
     * - Attempts 1..3: 0 ms (immediate retry)
     * - Attempt 4: 15,000 ms (15s)
     * - Attempt 5: 30,000 ms (30s)
     * - Attempt 6: 60,000 ms (60s)
     * - Attempt 7: 120,000 ms (120s)
     * - Attempt 8+: 300,000 ms (300s / 5m cap)
     */
    fun calculateLockoutDurationMillis(failedAttempts: Int): Long {
        return when {
            failedAttempts <= 3 -> 0L
            failedAttempts == 4 -> 15_000L
            failedAttempts == 5 -> 30_000L
            failedAttempts == 6 -> 60_000L
            failedAttempts == 7 -> 120_000L
            else -> 300_000L
        }
    }

    /**
     * Masks an account, PAN, or card number so only the last 4 characters are visible.
     * Returns "Not provided" if blank.
     */
    fun maskAccountNumber(raw: String, isPrivacyActive: Boolean = false): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Not provided"
        if (isPrivacyActive) return "•••• ••••"
        val digitsOnly = trimmed.filter { it.isLetterOrDigit() }
        if (digitsOnly.length <= 4) return "••••$digitsOnly"
        return "•••• •••• ${digitsOnly.takeLast(4)}"
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.length % 2 != 0) return ByteArray(0)
        return runCatching {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrDefault(ByteArray(0))
    }
}

/**
 * Android Keystore AES-256-GCM field-level encryption utility for sensitive secrets stored at rest
 * (e.g., gift card redemption codes or confidential notes).
 *
 * Clearly documented behavior:
 * - On Android devices with Keystore hardware/software backing, encrypts plaintext with a non-exportable
 *   AES-256-GCM key (`finance_vault_field_key_v1`) and prefixes ciphertext with `enc_gcm_v1:`.
 * - Existing legacy masked/plaintext values without the prefix are returned safely as-is so zero
 *   existing user data is lost.
 */
object VaultKeystoreCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "finance_vault_field_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val CIPHER_PREFIX = "enc_gcm_v1:"

    private fun getOrCreateSecretKey(): SecretKey? {
        return runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existingKey != null) return@runCatching existingKey

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.getOrNull()
    }

    fun encryptSensitiveField(plainText: String): String {
        val trimmed = plainText.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(CIPHER_PREFIX)) return trimmed
        return runCatching {
            val key = getOrCreateSecretKey() ?: return@runCatching "Protected (${trimmed.takeLast(2).padStart(4, '•')})"
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
            "$CIPHER_PREFIX$ivB64:$dataB64"
        }.getOrDefault("Protected (${trimmed.takeLast(2).padStart(4, '•')})")
    }

    fun decryptOrFormatSensitiveField(storedValue: String, isPrivacyActive: Boolean = false): String {
        if (storedValue.isBlank()) return "Not provided"
        if (isPrivacyActive) return "•••• ••••"
        if (!storedValue.startsWith(CIPHER_PREFIX)) return storedValue
        return runCatching {
            val payload = storedValue.removePrefix(CIPHER_PREFIX)
            val parts = payload.split(":")
            if (parts.size != 2) return@runCatching "Protected (Encrypted)"
            val key = getOrCreateSecretKey() ?: return@runCatching "Protected (Encrypted)"
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherBytes = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val decrypted = String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            "Protected (${decrypted.takeLast(2).padStart(4, '•')})"
        }.getOrDefault("Protected (Encrypted)")
    }
}

/**
 * Password-based AES-256-GCM encryption and SHA-256 integrity digest utility for Vault JSON Backups.
 *
 * Uses PBKDF2-HMAC-SHA256 (120,000 iterations, 128-bit random salt) to derive a 256-bit AES key
 * and encrypts backup JSON with 96-bit random IV and 128-bit GCM authentication tag.
 */
object BackupPasswordCipher {
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128
    private val secureRandom = SecureRandom()

    fun sha256Hex(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(content.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun encryptJsonWithPassword(plainJson: String, password: String): org.json.JSONObject {
        val cleanPassword = password.trim()
        require(cleanPassword.isNotEmpty()) { "Backup encryption password cannot be empty." }

        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }

        val keySpec = PBEKeySpec(cleanPassword.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val cipherBytes = try {
            val skf = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val rawKey = skf.generateSecret(keySpec).encoded
            val aesKey = javax.crypto.spec.SecretKeySpec(rawKey, "AES")
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))
        } finally {
            keySpec.clearPassword()
        }

        return org.json.JSONObject().apply {
            put("app", "FinanceVault")
            put("schemaVersion", 2)
            put("encrypted", true)
            put("kdf", KDF_ALGORITHM)
            put("iterations", PBKDF2_ITERATIONS)
            put("cipher", CIPHER_TRANSFORMATION)
            put("saltHex", bytesToHex(salt))
            put("ivHex", bytesToHex(iv))
            put("cipherHex", bytesToHex(cipherBytes))
            put("plainSha256", sha256Hex(plainJson))
        }
    }

    fun decryptJsonWithPassword(envelope: org.json.JSONObject, password: String): String {
        val cleanPassword = password.trim()
        require(cleanPassword.isNotEmpty()) { "Password is required to decrypt this backup file." }

        val iterations = envelope.optInt("iterations", PBKDF2_ITERATIONS).coerceAtLeast(10_000)
        val salt = hexToBytes(envelope.optString("saltHex", ""))
        val iv = hexToBytes(envelope.optString("ivHex", ""))
        val cipherBytes = hexToBytes(envelope.optString("cipherHex", ""))
        require(salt.isNotEmpty() && iv.isNotEmpty() && cipherBytes.isNotEmpty()) {
            "Encrypted backup envelope is missing cryptographic parameters."
        }

        val keySpec = PBEKeySpec(cleanPassword.toCharArray(), salt, iterations, KEY_BITS)
        val decryptedJson = try {
            val skf = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val rawKey = skf.generateSecret(keySpec).encoded
            val aesKey = javax.crypto.spec.SecretKeySpec(rawKey, "AES")
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid backup password or corrupted encrypted backup payload.", e)
        } finally {
            keySpec.clearPassword()
        }

        val expectedSha = envelope.optString("plainSha256", "")
        if (expectedSha.isNotBlank()) {
            val actualSha = sha256Hex(decryptedJson)
            require(
                MessageDigest.isEqual(
                    expectedSha.lowercase().toByteArray(Charsets.UTF_8),
                    actualSha.lowercase().toByteArray(Charsets.UTF_8)
                )
            ) {
                "Backup integrity check failed after decryption: SHA-256 digest mismatch."
            }
        }
        return decryptedJson
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        if (clean.isEmpty() || clean.length % 2 != 0) return ByteArray(0)
        return runCatching {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrDefault(ByteArray(0))
    }
}

enum class BiometricAvailability(
    val canAuthenticate: Boolean,
    val statusMessage: String
) {
    AVAILABLE(true, "Fingerprint / Face Unlock ready on this device"),
    NONE_ENROLLED(false, "Biometric sensor available — enroll Fingerprint or Face Unlock in Android Settings"),
    UNAVAILABLE(false, "Biometric sensor not active on this device/emulator (PIN fallback active)")
}

object BiometricAuthHelper {

    fun checkBiometricAvailability(context: Context): BiometricAvailability {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bm = context.getSystemService(BiometricManager::class.java)
                ?: return BiometricAvailability.UNAVAILABLE
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
            return when (bm.canAuthenticate(authenticators)) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
                else -> BiometricAvailability.UNAVAILABLE
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bm = context.getSystemService(BiometricManager::class.java)
                ?: return BiometricAvailability.UNAVAILABLE
            @Suppress("DEPRECATION")
            return when (bm.canAuthenticate()) {
                BiometricManager.BIOMETRIC_SUCCESS -> BiometricAvailability.AVAILABLE
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricAvailability.NONE_ENROLLED
                else -> BiometricAvailability.UNAVAILABLE
            }
        } else {
            val pm = context.packageManager
            val hasFp = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
            return if (hasFp) BiometricAvailability.AVAILABLE else BiometricAvailability.UNAVAILABLE
        }
    }

    /**
     * Launches the Android system BiometricPrompt for Fingerprint or Face ID authentication,
     * with a negative button allowing immediate fallback to the 4-digit PIN keypad.
     */
    fun promptBiometricUnlock(
        context: Context,
        onSuccess: () -> Unit,
        onUsePinFallback: () -> Unit = {},
        onErrorMessage: (String) -> Unit = {}
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            onErrorMessage("Biometric prompt requires Android 9+. Please enter your PIN.")
            onUsePinFallback()
            return
        }

        val availability = checkBiometricAvailability(context)
        if (!availability.canAuthenticate) {
            onErrorMessage(availability.statusMessage)
            onUsePinFallback()
            return
        }

        runCatching {
            val executor = ContextCompat.getMainExecutor(context)
            val cancellationSignal = CancellationSignal()

            val builder = BiometricPrompt.Builder(context)
                .setTitle("Unlock Finance Vault")
                .setSubtitle("Verify with Fingerprint or Face ID")
                .setDescription("Touch the fingerprint sensor or look at the front camera, or use your Security PIN.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
            }

            builder.setNegativeButton(
                "Use Security PIN",
                executor,
                DialogInterface.OnClickListener { _, _ ->
                    onUsePinFallback()
                }
            )

            val prompt = builder.build()
            prompt.authenticate(
                cancellationSignal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        super.onAuthenticationSucceeded(result)
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                        ) {
                            errString?.toString()?.let { msg ->
                                if (msg.isNotBlank()) onErrorMessage(msg)
                            }
                        }
                        onUsePinFallback()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        onErrorMessage("Biometric not recognized. Try again or enter your PIN.")
                    }
                }
            )
        }.onFailure { e ->
            onErrorMessage("Biometric prompt unavailable (${e.localizedMessage ?: "use PIN"}).")
            onUsePinFallback()
        }
    }
}

