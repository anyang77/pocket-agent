package dev.pocketagent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("pocket_agent_secrets", Context.MODE_PRIVATE)

    fun putApiKey(value: String) {
        putApiKey(KEY_API, value)
    }

    fun getApiKey(): String = getApiKey(KEY_API)

    fun putApiKey(profileId: String, value: String) {
        if (value.isBlank()) {
            preferences.edit().remove(secretKey(profileId)).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        preferences.edit().putString(secretKey(profileId), payload).apply()
    }

    fun getApiKey(profileId: String): String {
        val payload = preferences.getString(secretKey(profileId), null) ?: return ""
        return runCatching {
            val (ivText, cipherText) = payload.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun removeApiKey(profileId: String) {
        preferences.edit().remove(secretKey(profileId)).apply()
    }

    private fun secretKey(profileId: String): String =
        if (profileId == KEY_API) KEY_API else "${KEY_API}_$profileId"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pocket_agent_api_key"
        private const val KEY_API = "api_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
