package eu.einhq.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The app's pairing: the EiN it is paired with (an origin), the phone and account it is paired as, and its token. */
data class Pairing(val server: String, val device: String, val user: String, val token: String)

/**
 * The pairing, kept in the app's preferences with its token sealed by a key that never leaves this
 * phone's keystore. A token that no longer opens, its key gone, reads as no pairing.
 */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun load(): Pairing? {
        val server = prefs.getString(SERVER, null) ?: return null
        val device = prefs.getString(DEVICE, null) ?: return null
        val user = prefs.getString(USER, null) ?: return null
        val token = prefs.getString(TOKEN, null)?.let(Sealer::open) ?: return null
        return Pairing(server, device, user, token)
    }

    fun save(pairing: Pairing) = prefs.edit {
        putString(SERVER, pairing.server)
        putString(DEVICE, pairing.device)
        putString(USER, pairing.user)
        putString(TOKEN, Sealer.seal(pairing.token))
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val SERVER = "server"
        const val DEVICE = "device"
        const val USER = "user"
        const val TOKEN = "token"
    }
}

/** AES-GCM under a key of the Android keystore: a sealed text is its IV and ciphertext, in Base64. */
private object Sealer {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "pairing"
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(text: String): String {
        val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = try {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
