package com.musicvault.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.musicvault.app.providers.NewPipeStreamResolver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class ReleaseDownloadSmokeTest {
    @Test fun newPipeExtractorInitializesInInstalledApk() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            NewPipeStreamResolver.init()
        }
        assertTrue(true)
    }

    @Test fun downloadAndReadAudioWhenUrlProvided() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("musicvault.smokeUrl")
        org.junit.Assume.assumeTrue("Pass a permitted test URL to exercise a live download", !url.isNullOrBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        com.musicvault.app.providers.Providers.init(context)
        val key = "smoke-${java.util.UUID.randomUUID()}"
        val result = com.musicvault.app.providers.Providers.download(key, "MusicVault smoke", requestId = key,
            sourceUrl = url, quality = DownloadQuality.BEST)
        assertTrue(result.toString(), result is com.musicvault.app.providers.ProviderResult.Success)
        result as com.musicvault.app.providers.ProviderResult.Success
        try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, android.net.Uri.parse(result.audioUri))
                assertTrue((retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0)
            } finally { retriever.release() }
        } finally {
            val path = result.localPath
            if (path != null && !path.startsWith("content:")) java.io.File(path).delete()
            else android.provider.DocumentsContract.deleteDocument(context.contentResolver, android.net.Uri.parse(result.audioUri))
        }
    }
}
