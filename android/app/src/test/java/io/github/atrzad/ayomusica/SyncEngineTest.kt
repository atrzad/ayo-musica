package io.github.atrzad.ayomusica

import io.github.atrzad.ayomusica.data.Song
import io.github.atrzad.ayomusica.sync.Matcher
import io.github.atrzad.ayomusica.sync.SongKeys
import io.github.atrzad.ayomusica.sync.SyncEngine
import io.github.atrzad.ayomusica.sync.SyncHooks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The server's rule, in memory: newest "at" wins, changes numbered by rev. */
private class FakeServer {
    data class Row(val value: JsonElement, val deleted: Boolean, val at: Long, val device: String, val rev: Long)
    val rows = LinkedHashMap<String, Row>()
    var rev = 0L

    fun handle(device: String, since: Long, changes: List<JsonObject>): JsonObject {
        for (change in changes) {
            val id = "${change.text("kind")}\u0000${change.text("key")}"
            val at = change["at"]!!.jsonPrimitive.content.toLong()
            val old = rows[id]
            if (old != null && (old.at > at || (old.at == at && old.device >= device))) continue
            rows[id] = Row(change["value"]!!, change["deleted"]?.jsonPrimitive?.content == "true", at, device, ++rev)
        }
        val out = rows.filter { it.value.rev > since }.entries.sortedBy { it.value.rev }.map { (id, row) ->
            val (kind, key) = id.split('\u0000')
            buildJsonObject {
                put("kind", kind); put("key", key); put("value", row.value); put("deleted", row.deleted)
                put("at", row.at); put("device", row.device)
            }
        }
        return buildJsonObject { put("rev", rev); put("more", false); put("changes", JsonArray(out)) }
    }

    private fun JsonObject.text(key: String) = (this[key] as JsonPrimitive).content
}

/** A device with favorites and play counts in memory. */
private class Device(val name: String, val server: FakeServer, val songs: List<Song>, var clock: Long = 1000) {
    val favorites = mutableSetOf<Long>()
    val plays = mutableMapOf<Long, Int>()
    val playlists = mutableMapOf<String, List<String>>()  // uuid → song keys
    private val file = Files.createTempFile("sync", ".json").toFile().apply { delete() }
    val engine = SyncEngine(file, SyncHooks(
        songs = { songs.map { it to SongKeys.of(it) } },
        export = { keys ->
            buildMap {
                favorites.forEach { id -> keys.name(id)?.let { put(SyncEngine.id("flags", it), buildJsonObject { put("favorite", true) }) } }
                plays.forEach { (id, n) -> keys.name(id)?.let { put(SyncEngine.id("plays", "$it@$name"), buildJsonObject { put("plays", n) }) } }
                playlists.forEach { (uuid, keys) -> put(SyncEngine.id("playlist", uuid), JsonArray(keys.map(::JsonPrimitive))) }
            }
        },
        apply = { kind, key, value, matcher ->
            when (kind) {
                "flags" -> matcher.find(key)?.let { song ->
                    if (value?.jsonObject?.get("favorite")?.jsonPrimitive?.content == "true") favorites += song.id else favorites -= song.id
                    true
                } ?: false
                "playlist" -> { if (value == null) playlists.remove(key) else playlists[key] = (value as JsonArray).map { it.jsonPrimitive.content }; true }
                else -> true
            }
        },
    ), device = { name }, transport = { since, changes -> server.handle(name, since, changes) }, now = { clock++ })
}

class SyncEngineTest {
    private fun song(id: Long, title: String, artist: String, seconds: Int) = Song(id, title, artist, "", 1, durationMs = seconds * 1000L)

    @Test
    fun keysAndMatchingAcrossDevices() {
        assertEquals("seu pereira e coletivo 401|obsoleto|223", SongKeys.of("Seu  Pereira e Coletivo 401", "Obsoleto", 223_800))
        val phone = song(5, "Construção", "Chico Buarque", 384)
        val cloud = Song(-9, "Construção", "Chico Buarque", "", 1, durationMs = 383_000, cloudId = 9)
        val matcher = Matcher(listOf(cloud to SongKeys.of(cloud), phone to SongKeys.of(phone)))
        assertEquals(5L, matcher.find("chico buarque|construcao|386")?.id)  // the phone's copy wins over the cloud's
        assertNull(matcher.find("chico buarque|construcao|392"))           // 8 s apart: another recording
        assertEquals(5L, matcher.find("chico buarque|construcao")?.id)      // likes and lyrics: by name, the phone's first
        assertEquals("chico buarque|construcao", SongKeys.name("chico buarque|construcao|384"))
    }

    @Test
    fun twoDevicesConverge() {
        val server = FakeServer()
        val phone = Device("celular", server, listOf(song(1, "Oceano", "Djavan", 224), song(2, "Lucro", "BaianaSystem", 200)))
        val pc = Device("pc", server, listOf(song(70, "Oceano", "Djavan", 225), song(71, "Prantos", "Sant", 180)))
        phone.favorites += 1
        phone.plays[1] = 3
        phone.favorites += 2               // the PC does not have this song
        phone.playlists["p1"] = listOf("djavan|oceano|224")
        phone.engine.sync()
        pc.plays[70] = 2
        pc.engine.sync()
        assertEquals(setOf(70L), pc.favorites)                     // same song, other file, 1 s apart
        assertEquals(listOf("djavan|oceano|224"), pc.playlists["p1"])
        assertEquals(Triple(3, 0, 0L), pc.engine.remotePlays()["djavan|oceano"])  // the phone's plays, kept apart

        // The PC unlikes it; the phone gets that and keeps the like of the song the PC lacks.
        pc.favorites -= 70
        pc.engine.sync()
        phone.engine.sync()
        assertEquals(setOf(2L), phone.favorites)
        assertTrue(server.rows.keys.any { it.startsWith("flags\u0000baianasystem|lucro") })  // not deleted by the PC
        assertEquals(2, phone.engine.remotePlays()["djavan|oceano"]?.first)

        // Deleting a playlist on one side deletes it on the other.
        phone.playlists.remove("p1")
        phone.engine.sync()
        pc.engine.sync()
        assertNull(pc.playlists["p1"])
        // Nothing left to send once both agree.
        assertEquals(0, pc.engine.sync().sent)
        assertEquals(0, phone.engine.sync().sent)
    }

    @Test
    fun heldRecordsApplyWhenTheSongShowsUp() {
        val server = FakeServer()
        val phone = Device("celular", server, listOf(song(2, "Lucro", "BaianaSystem", 200)))
        phone.favorites += 2
        phone.engine.sync()
        val songs = mutableListOf(song(80, "Outra", "Banda", 100))
        val pc = Device("pc", server, songs)
        assertEquals(1, pc.engine.sync().held)
        assertTrue(pc.favorites.isEmpty())
        // The PC gets the song later (or it shows up in the cloud): the like applies on the next sync.
        val later = Device("pc", server, songs + song(81, "Lucro", "BaianaSystem", 201))
        later.engine.sync()
        assertEquals(setOf(81L), later.favorites)
    }
}
