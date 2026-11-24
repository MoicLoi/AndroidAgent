package dev.anroidagent.app

import dev.anroidagent.app.media.TrashCodec
import dev.anroidagent.core.media.TrashEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂存区索引编解码。
 *
 * 这个索引是还原的唯一依据，所以两条不变量：
 * 1. 路径与文件名必须无损往返
 * 2. 缺路径/缺文件名的脏记录必须被丢弃（半条记录比没有更危险——
 *    会让 UI 显示一个还原不了的文件）
 */
class TrashCodecTest {

    private val sha = "a".repeat(64)

    private fun entry(
        src: String = "/sdcard/DCIM/Camera/IMG_0001.jpg",
        name: String = "0001-IMG_0001.jpg",
        sha256: String? = sha,
    ) = TrashEntry(
        mediaId = "42",
        srcPath = src,
        trashName = name,
        sizeBytes = 1_234_567L,
        movedAtMs = 1_700_000_000_000L,
        sha256 = sha256,
    )

    @Test
    fun `round trip preserves paths and digest`() {
        val list = listOf(entry(), entry("sdcard/DCIM/b.jpg".let { "/$it" }, "0002-b.jpg"))
        val back = TrashCodec.decode(TrashCodec.encode(list))

        assertEquals(2, back.size)
        assertEquals("/sdcard/DCIM/Camera/IMG_0001.jpg", back[0].srcPath)
        assertEquals("0001-IMG_0001.jpg", back[0].trashName)
        assertEquals(sha, back[0].sha256)
        assertEquals(1_234_567L, back[0].sizeBytes)
        assertEquals("42", back[0].mediaId)
    }

    @Test
    fun `null digest survives as null`() {
        val back = TrashCodec.decode(TrashCodec.encode(listOf(entry(sha256 = null))))
        assertNull("没有摘要的记录还原时会被拒，但必须能读出来", back[0].sha256)
    }

    @Test
    fun `drops records without source path`() {
        val json = """[{"srcPath":"","trashName":"x.jpg","sizeBytes":1}]"""
        assertTrue(TrashCodec.decode(json).isEmpty())
    }

    @Test
    fun `drops records without trash name`() {
        val json = """[{"srcPath":"/sdcard/a.jpg","trashName":"","sizeBytes":1}]"""
        assertTrue(TrashCodec.decode(json).isEmpty())
    }

    @Test
    fun `keeps remaining records when one is corrupt`() {
        val json = """[
            {"srcPath":"/sdcard/a.jpg","trashName":"0001-a.jpg"},
            {"srcPath":"","trashName":"bad.jpg"},
            {"srcPath":"/sdcard/b.jpg","trashName":"0002-b.jpg"}
        ]"""
        val back = TrashCodec.decode(json)
        assertEquals(2, back.size)
        assertEquals("0001-a.jpg", back[0].trashName)
    }

    @Test
    fun `short digest is treated as absent`() {
        val json = """[{"srcPath":"/sdcard/a.jpg","trashName":"0001-a.jpg","sha256":"abc"}]"""
        assertNull(TrashCodec.decode(json)[0].sha256)
    }

    @Test
    fun `degrades on corrupt input`() {
        assertTrue(TrashCodec.decode(null).isEmpty())
        assertTrue(TrashCodec.decode("").isEmpty())
        assertTrue(TrashCodec.decode("{broken").isEmpty())
        assertTrue(TrashCodec.decode("[]").isEmpty())
    }

    @Test
    fun `unicode paths survive`() {
        val e = entry(src = "/sdcard/DCIM/相机/照片 1.jpg", name = "0001-照片_1.jpg")
        val back = TrashCodec.decode(TrashCodec.encode(listOf(e)))
        assertEquals("/sdcard/DCIM/相机/照片 1.jpg", back[0].srcPath)
    }
}
