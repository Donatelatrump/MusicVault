package com.musicvault.app.jams

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/** Random per-installation identity: never backed up, derived from hardware or sent to analytics. */
object InstallationIdentity {
    @Synchronized fun get(context: Context): String {
        val file = AtomicFile(File(context.noBackupFilesDir, "installation-uid"))
        val existing = runCatching { file.openRead().bufferedReader().use { it.readText().trim() } }.getOrNull()
        if (existing != null && runCatching { UUID.fromString(existing).toString() == existing }.getOrDefault(false)) return existing
        val uid = UUID.randomUUID().toString()
        val stream = file.startWrite()
        try { stream.write(uid.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        return uid
    }
}
