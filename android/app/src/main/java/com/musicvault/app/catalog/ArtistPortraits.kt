package com.musicvault.app.catalog

import android.content.Context
import com.musicvault.app.providers.RelatedArtists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Photos are optional: onboarding remains usable offline and during provider failures. */
object ArtistPortraits {
    private val permits = Semaphore(3)
    suspend fun details(context: Context, name: String): com.musicvault.app.providers.RelatedArtists.ArtistDetails? = permits.withPermit {
        val prefs = context.getSharedPreferences("artist-portraits-v2", Context.MODE_PRIVATE)
        val photo = prefs.getString(name, null)
        if (photo != null && prefs.contains("fans:$name")) return@withPermit com.musicvault.app.providers.RelatedArtists.ArtistDetails(name, photo, prefs.getLong("fans:$name", 0))
        try {
            RelatedArtists.artistDetails(name)?.also {
                prefs.edit().putString(name, it.photo).putLong("fans:$name", it.fans).apply()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun load(context: Context, name: String): String? {
        val prefs = context.getSharedPreferences("artist-portraits-v2", Context.MODE_PRIVATE)
        prefs.getString(name, null)?.let { return it }
        if (com.musicvault.app.providers.Providers.isOffline()) return null
        return permits.withPermit {
            prefs.getString(name, null) ?: try {
                RelatedArtists.portrait(name)?.also { prefs.edit().putString(name, it).apply() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
}
