package ru.fortport.client

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование текста сообщения чата — как encryptMessage() в crypto-utils.js сайта:
 * ключ = SHA-256(chatId), AES-256-GCM, 12-байтный IV, результат = base64(iv + шифртекст).
 * Нужно, чтобы отправить ссылку в чат («Поделиться»), не открывая сам экран чата.
 */
object ChatCrypto {
    fun encrypt(chatId: String, text: String): String {
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(chatId.toByteArray(Charsets.UTF_8)), "AES")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}
