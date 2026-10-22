package com.musicvault.app.backup

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable encryption: the password, salt and PBKDF2 parameters work on another device. */
object BackupEncryption {
    private const val ITERATIONS = 210_000
    fun encode(plain: String, password: CharArray?): String {
        if (password == null || password.isEmpty()) return plain
        require(password.size >= 8) { "La contraseña debe tener al menos 8 caracteres." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD("musicvault-encrypted-backup:1".toByteArray())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject().put("format", "musicvault-encrypted-backup").put("version", 1)
            .put("iterations", ITERATIONS).put("salt", b64(salt)).put("iv", b64(iv)).put("data", b64(data)).toString()
    }
    fun decode(text: String, password: CharArray?): String {
        val root = JSONObject(text)
        if (root.optString("format") != "musicvault-encrypted-backup") return text
        require(root.getInt("version") == 1) { "Versión cifrada no compatible." }
        require(password != null && password.isNotEmpty()) { "Este respaldo necesita su contraseña." }
        val rounds = root.getInt("iterations"); require(rounds in 100_000..500_000) { "Parámetros de cifrado inválidos." }
        val salt = Base64.decode(root.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(root.getString("iv"), Base64.NO_WRAP)
        require(salt.size == 16 && iv.size == 12) { "Respaldo cifrado inválido." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, rounds), GCMParameterSpec(128, iv))
        cipher.updateAAD("musicvault-encrypted-backup:1".toByteArray())
        return try { cipher.doFinal(Base64.decode(root.getString("data"), Base64.NO_WRAP)).toString(Charsets.UTF_8) }
        catch (error: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("Contraseña incorrecta o respaldo dañado.", error) }
    }
    private fun key(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
