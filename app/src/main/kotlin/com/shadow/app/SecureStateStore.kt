package com.shadow.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.shadow.bot.OfflineCache
import com.shadow.bot.OfflineCacheCodec
import com.shadow.bot.SavedState
import com.shadow.bot.StateCodec
import com.shadow.bot.StateStore
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted, atomic file; no plaintext cookies, message cache or backup copy. */
class SecureStateStore(context: Context) : StateStore {
    private val root = context.noBackupFilesDir
    private val file = AtomicFile(File(root, "bot-session.enc"))
    private val draftsDirectory = File(context.noBackupFilesDir, "bot-drafts")
    private val alias = "shadow-bot-session-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): SavedState = synchronized(sessionLock) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return SavedState()
        val bytes = file.readFully()
        require(bytes.size > 28) { "本地会话文件损坏，请保留原文件后恢复。" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return StateCodec.decode(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    override fun write(state: SavedState) = synchronized(sessionLock) {
        writeEncrypted(file, StateCodec.encode(state).toByteArray(Charsets.UTF_8))
    }
    override fun update(change: (SavedState) -> SavedState): SavedState = synchronized(sessionLock) { val current = read(); change(current).also { if (it != current) write(it) } }
    companion object { private val sessionLock = Any() }
    private fun writeEncrypted(target: AtomicFile, plaintext: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(plaintext)
        val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) } catch (error: Exception) { target.failWrite(stream); throw error }
    }
    @Synchronized fun readPrivate(name: String): String? {
        require(name in setOf("notifications", "chat-cache", "read-marks", "oidc-login"))
        val target = AtomicFile(File(root, "$name.enc"))
        if (!target.baseFile.exists()) return null
        val bytes = target.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    @Synchronized fun writePrivate(name: String, text: String) {
        require(name in setOf("notifications", "chat-cache", "read-marks", "oidc-login"))
        writeEncrypted(AtomicFile(File(root, "$name.enc")), text.toByteArray(Charsets.UTF_8))
    }
    @Synchronized override fun markRead(entryId: String, messageId: String, sequence: Long) {
        val server = read().session?.baseUrl ?: return
        val previous = readPrivate("read-marks")?.let { org.json.JSONObject(it) }
        val state = previous?.takeIf { it.optString("server") == server } ?: org.json.JSONObject().put("server", server).put("entries", org.json.JSONObject())
        val entries = state.getJSONObject("entries")
        val mark = org.json.JSONObject().put("message", messageId).put("sequence", sequence)
        if (entries.optJSONObject(entryId)?.toString() != mark.toString()) {
            entries.put(entryId, mark); writePrivate("read-marks", state.toString())
        }
    }
    @Synchronized override fun readCache(): OfflineCache? = runCatching { readPrivate("chat-cache")?.let(OfflineCacheCodec::decode) }.getOrNull()
    @Synchronized override fun writeCache(cache: OfflineCache) { writePrivate("chat-cache", OfflineCacheCodec.encode(cache)) }
    @Synchronized override fun clearCache() { AtomicFile(File(root, "chat-cache.enc")).delete() }
    private fun draftFile(id: String): AtomicFile {
        require(Regex("[a-f0-9-]{36}").matches(id))
        return AtomicFile(File(draftsDirectory, id))
    }
    @Synchronized fun saveDraftFile(id: String, bytes: ByteArray) {
        require(bytes.size in 1..20 * 1024 * 1024)
        draftsDirectory.mkdirs()
        writeEncrypted(draftFile(id), bytes)
    }
    @Synchronized fun readDraftFile(id: String): ByteArray {
        val bytes = draftFile(id).readFully()
        require(bytes.size in 29..20 * 1024 * 1024 + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    @Synchronized fun removeDraftFile(id: String) { draftFile(id).delete() }
    @Synchronized fun pruneDraftFiles() {
        val retained = read().attachmentDrafts.values.flatten().map { it.id }.toSet()
        draftsDirectory.listFiles()?.filter { it.name.removeSuffix(".bak").removeSuffix(".new") !in retained }?.forEach { it.delete() }
    }
}
