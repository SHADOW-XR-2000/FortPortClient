package ru.fortport.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Аккаунты, с которых пользователь входил в приложении. Пароль шифруется ключом Android Keystore
 * (AES-GCM, ключ не покидает устройство), в SharedPreferences лежит только шифртекст.
 */
class SavedAccounts(context: Context) {

    class Account(
        val userId: String,
        val username: String,
        val avatar: String,
        val identifier: String,
        val password: String
    )

    private val prefs = context.applicationContext.getSharedPreferences("saved_accounts", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "fortport_saved_accounts"
        private const val PREF_LIST = "list"
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = c.iv
        val enc = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP)
    }

    private fun decrypt(packed: String): String? = try {
        val parts = packed.split(":")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val enc = Base64.decode(parts[1], Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        String(c.doFinal(enc), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun readArray(): JSONArray = try {
        JSONArray(prefs.getString(PREF_LIST, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    /** Последний вошедший — первым. Записи, пароль которых не удалось расшифровать, пропускаются. */
    fun list(): List<Account> {
        val arr = readArray()
        val out = ArrayList<Account>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pw = decrypt(o.optString("pw", "")) ?: continue
            out.add(
                Account(
                    o.optString("id", ""), o.optString("name", ""), o.optString("avatar", ""),
                    o.optString("ident", ""), pw
                )
            )
        }
        return out
    }

    fun save(userId: String, username: String, avatar: String, identifier: String, password: String) {
        try {
            val enc = encrypt(password)
            val arr = readArray()
            val res = JSONArray()
            val o = JSONObject()
            o.put("id", userId)
            o.put("name", username)
            o.put("avatar", avatar)
            o.put("ident", identifier)
            o.put("pw", enc)
            res.put(o)
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (e.optString("id", "") != userId) res.put(e)
            }
            prefs.edit().putString(PREF_LIST, res.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.w("SavedAccounts", "Не удалось сохранить аккаунт", e)
        }
    }

    fun remove(userId: String) {
        val arr = readArray()
        val res = JSONArray()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("id", "") != userId) res.put(e)
        }
        prefs.edit().putString(PREF_LIST, res.toString()).apply()
    }
}
