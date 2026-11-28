package io.github.richardhan.voicenote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ConnectionVault(context: Context, private val alias: String) {
	private val prefs = context.getSharedPreferences("secure_connections", Context.MODE_PRIVATE)
	private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
	private fun encryptionKey(): SecretKey {
		val existing = store().getKey(alias, null) as? SecretKey
		if (existing != null) return existing
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
		generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
			.setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
		return generator.generateKey()
	}
	fun save(data: JSONObject) {
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
		val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
		val encrypted = Base64.encodeToString(cipher.doFinal(data.toString().toByteArray()), Base64.NO_WRAP)
		check(prefs.edit().putString(alias, "$iv.$encrypted").commit())
	}
	fun load(): JSONObject? {
		val encoded = prefs.getString(alias, null) ?: return null
		val key = store().getKey(alias, null) as? SecretKey ?: return null
		val iv = Base64.decode(encoded.substringBefore('.'), Base64.NO_WRAP)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
		return JSONObject(String(cipher.doFinal(Base64.decode(encoded.substringAfter('.'), Base64.NO_WRAP)), Charsets.UTF_8))
	}
	fun remove() {
		store().deleteEntry(alias)
		check(prefs.edit().remove(alias).commit())
	}
}
