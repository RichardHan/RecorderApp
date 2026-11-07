package com.eva.cloudtranscribe.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * API key encrypted with a non-exportable AES-GCM key in AndroidKeyStore.
 * Ciphertext lives in `cloud_ai_secure.xml`, which is excluded from backup/device transfer.
 * The plaintext is never logged or written anywhere else.
 */
@Singleton
class ApiKeyStore @Inject constructor(@ApplicationContext context: Context) :
	EncryptedKeyStore(context, PREFS_ASR, ALIAS_ASR) {
	private companion object {
		// unchanged from earlier versions so existing saved keys keep working
		const val PREFS_ASR = "cloud_ai_secure"
		const val ALIAS_ASR = "recorderapp_cloud_ai_api_key"
	}
}

/** Separate key for the independent live key-point summary endpoint (own prefs file + own Keystore alias). */
@Singleton
class SummaryApiKeyStore @Inject constructor(@ApplicationContext context: Context) :
	EncryptedKeyStore(context, "cloud_ai_summary_secure", "recorderapp_summary_api_key")

open class EncryptedKeyStore(context: Context, prefsName: String, private val ALIAS: String) {
	private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
	private val _hasKey = MutableStateFlow(prefs.contains(KEY_CIPHER))
	val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

	private fun secretKey(): SecretKey {
		val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		(ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
		generator.init(
			KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build(),
		)
		return generator.generateKey()
	}

	fun save(apiKey: String) {
		val trimmed = apiKey.trim()
		require(trimmed.isNotEmpty())
		val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
		val enc = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
		prefs.edit()
			.putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
			.putString(KEY_CIPHER, Base64.encodeToString(enc, Base64.NO_WRAP))
			.apply()
		_hasKey.value = true
	}

	fun read(): String? {
		val iv = prefs.getString(KEY_IV, null) ?: return null
		val data = prefs.getString(KEY_CIPHER, null) ?: return null
		return runCatching {
			val cipher = Cipher.getInstance(TRANSFORM)
			cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
			String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
		}.getOrNull()
	}

	fun clear() {
		prefs.edit().clear().apply()
		runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
		_hasKey.value = false
	}

	private companion object {
		const val KEY_IV = "iv"
		const val KEY_CIPHER = "cipher"
		const val TRANSFORM = "AES/GCM/NoPadding"
	}
}
