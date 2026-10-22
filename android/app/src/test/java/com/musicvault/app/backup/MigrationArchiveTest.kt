package com.musicvault.app.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

class MigrationArchiveTest {
    @Test fun transfersAudioAndTrackIdentity() {
        val output = ByteArrayOutputStream()
        MigrationArchive.write("{\"format\":\"test\"}", listOf(
            MigrationArchive.Source("track:1", "m4a") { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        ), output)
        val directory = createTempDirectory("musicvault-migration-").toFile()
        try {
            val result = MigrationArchive.read(ByteArrayInputStream(output.toByteArray()), directory)
            assertEquals("{\"format\":\"test\"}", result.libraryJson)
            assertArrayEquals(byteArrayOf(1, 2, 3), result.audioFiles.getValue("track:1").readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsIncompleteOrUnsafeArchive() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("../escape.m4a"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        val directory = createTempDirectory("musicvault-migration-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                MigrationArchive.read(ByteArrayInputStream(output.toByteArray()), directory)
            }
            assertFalse(File(directory.parentFile, "escape.m4a").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsAudioWhoseChecksumDoesNotMatchManifest() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("library.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("audio/0.m4a"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
            val row = JSONObject().put("trackId", "track:1").put("file", "audio/0.m4a")
                .put("size", 3).put("sha256", "0".repeat(64))
            val manifest = JSONObject().put("format", "musicvault-migration").put("version", 1)
                .put("audio", JSONArray().put(row))
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
        }
        val directory = createTempDirectory("musicvault-migration-").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                MigrationArchive.read(ByteArrayInputStream(output.toByteArray()), directory)
            }
        } finally { directory.deleteRecursively() }
    }
}
