package com.yuanzhe.kotomo.engine

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the app's side of the engine ↔ app contract (doc/en-us/engine-contract.md, version 3):
 * the snapshot decoder accepts exactly what the engine's encoder writes and drops anything else
 * whole, and a stale update never replaces a newer one.
 *
 * Notes: the bytes are written here field by field as the contract's table lists them, not by the
 * native encoder, so a change on one side only fails this test; the corpus run crosses the real
 * boundary.
 */
class SnapshotTest {
    /** Writes the contract's little-endian integers and length-prefixed strings. */
    private class Bytes {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v) }
        fun u16(v: Int) = apply { u8(v and 0xff); u8(v shr 8 and 0xff) }
        fun i32(v: Int) = apply { u16(v and 0xffff); u16(v ushr 16) }
        fun u64(v: Long) = apply { i32(v.toInt()); i32((v ushr 32).toInt()) }
        fun str(s: String) = apply { val b = s.toByteArray(); u16(b.size); out.write(b) }
        fun raw(s: String) = apply { out.write(s.toByteArray()) }
    }

    /** A snapshot of `hon wo` with a two-piece Japanese candidate and a Chinese one. */
    private fun sample(version: Int = Snapshot.VERSION, target: Int = 1, pieceEnd: Int = 6, cursor: Int = 6, pinned: Int = 1) =
        Bytes().raw("KS").u16(version).u64(7).u8(1).str("hon wo").u16(cursor).str("本").u16(2)
            .str("本を").u8(target).i32(4200).u8(8).u16(2)
            .u16(0).u16(3).str("本").str("ほん").u8(1).u8(1).u8(0)
            .u16(4).u16(pieceEnd).str("を").str("を").u8(1).u8(5).u8(pinned)
            .str("红").u8(0).i32(-5).u8(1).u16(1)
            .u16(0).u16(3).str("红").str("hon").u8(0).u8(0).u8(0)
            .out.toByteArray()

    @Test
    fun decodesEveryField() {
        val s = Snapshot.decode(sample())!!
        assertEquals(7L, s.generation)
        assertEquals(1, s.stage)
        assertEquals("hon wo", s.input)
        assertEquals(6, s.cursor)
        assertEquals("本", s.commit)
        assertEquals(listOf("本を", "红"), s.candidates.map { it.text })
        val ja = s.candidates[0]
        assertEquals(Lang.JAPANESE, ja.target)
        assertEquals(4200, ja.cost)
        assertFalse(ja.crossLanguage)
        assertEquals(Piece(4, 6, "を", "を", Lang.JAPANESE, Origin.RULE, pinned = true), ja.pieces[1])
        assertEquals(Piece(0, 3, "本", "ほん", Lang.JAPANESE, Origin.MOZC, pinned = false), ja.pieces[0])
        assertEquals(-5, s.candidates[1].cost)
        assertTrue(s.candidates[1].crossLanguage)
    }

    @Test
    fun decodesAnEmptySnapshot() {
        val s = Snapshot.decode(Bytes().raw("KS").u16(Snapshot.VERSION).u64(0).u8(0).str("").u16(0).str("").u16(0).out.toByteArray())
        assertNotNull(s)
        assertTrue(s!!.candidates.isEmpty())
    }

    @Test
    fun dropsDamagedBuffersWhole() { // negative: nothing is half-read
        val bytes = sample()
        for (n in bytes.indices) assertNull("truncated to $n", Snapshot.decode(bytes.copyOf(n)))
        assertNull("trailing byte", Snapshot.decode(bytes + 0))
        assertNull("another version", Snapshot.decode(sample(version = Snapshot.VERSION + 1)))
        assertNull("the previous version", Snapshot.decode(sample(version = 1)))
        assertNull("unknown language", Snapshot.decode(sample(target = 3)))
        assertNull("piece outside the composition", Snapshot.decode(sample(pieceEnd = 7)))
        assertNull("cursor outside the composition", Snapshot.decode(sample(cursor = 7)))
        assertNull("pinned is 0 or 1", Snapshot.decode(sample(pinned = 2)))
        assertNull("magic", Snapshot.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }))
        // A string that is not UTF-8 is refused rather than shown with replacement characters.
        val text = bytes.copyOf()
        val at = bytes.indexOfFirst { it == 0xe6.toByte() } // the first byte of 本
        text[at] = 0xff.toByte()
        assertNull("malformed UTF-8", Snapshot.decode(text))
    }

    @Test
    fun aStaleUpdateNeverReplacesANewerOne() {
        assertTrue(Snapshot.replaces(8, 0, shownGeneration = 7, shownStage = 3))
        assertTrue(Snapshot.replaces(7, 2, shownGeneration = 7, shownStage = 1))
        assertFalse(Snapshot.replaces(7, 1, shownGeneration = 7, shownStage = 1))
        assertFalse(Snapshot.replaces(6, 5, shownGeneration = 7, shownStage = 0))
    }
}
