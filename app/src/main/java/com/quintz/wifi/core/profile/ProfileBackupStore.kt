package com.quintz.wifi.core.profile

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated private record. AtomicFile fsyncs before rename; never included in logs/backup. */
class ProfileBackupStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "pending-profile-v1"))
    private val alias = "quintz-profile-backup-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun exists(): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
    @Synchronized fun read(): Bundle? {
        if (!exists()) return null
        val bytes = file.readFully()
        check(bytes.size in 30..1_048_576 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(alias.toByteArray())
        val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return Parcel.obtain().let { parcel ->
            try { parcel.unmarshall(plain, 0, plain.size); parcel.setDataPosition(0); parcel.readBundle(javaClass.classLoader)!!.also { check(parcel.dataAvail() == 0) } }
            finally { parcel.recycle(); plain.fill(0) }
        }
    }
    @Synchronized fun write(record: Bundle) {
        val plain = Parcel.obtain().let { parcel ->
            try { parcel.writeBundle(record); parcel.marshall() } finally { parcel.recycle() }
        }
        check(plain.size <= 1_000_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(alias.toByteArray())
        val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        plain.fill(0)
        val out = file.startWrite()
        try { out.write(encrypted); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        // Decrypt and decode the actual durable file before any profile mutation.
        val restored = read()!!
        for (name in listOf("original", "expected")) {
            val actual = restored.getBundle(name)!!; val expected = record.getBundle(name)!!
            check(actual.getByteArray("payload")!!.contentEquals(expected.getByteArray("payload")!!))
            check(actual.getByteArray("fingerprint")!!.contentEquals(expected.getByteArray("fingerprint")!!))
            check(actual.getString("platform") == expected.getString("platform"))
            check(actual.getInt("fingerprintVersion", 1) == expected.getInt("fingerprintVersion", 1))
        }
        val settings = record.getBundle("expected")?.getBundle("appSettings")
        if (settings != null) {
            val saved = restored.getBundle("expected")!!.getBundle("appSettings")!!
            check(saved.keySet() == settings.keySet() && settings.keySet().all { saved.getString(it) == settings.getString(it) })
        }
    }
    @Synchronized fun clear() { file.delete(); check(!exists()) }
}
