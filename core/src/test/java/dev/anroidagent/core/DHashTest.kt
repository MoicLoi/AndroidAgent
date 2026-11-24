package dev.anroidagent.core

import dev.anroidagent.core.media.DHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dHash。
 *
 * 这个值会决定"两张照片算不算重复"，所以边界行为必须明确：
 * 不同构图要拉开距离，同一张图的两次导出差 0~3 位。
 */
class DHashTest {

    private fun flat(v: Int) = IntArray(DHash.PIXELS) { v }

    private fun ascending(): IntArray {
        val a = IntArray(DHash.PIXELS)
        for (y in 0 until DHash.HEIGHT) {
            for (x in 0 until DHash.WIDTH) a[y * DHash.WIDTH + x] = x * 20
        }
        return a
    }

    private fun descending(): IntArray {
        val a = IntArray(DHash.PIXELS)
        for (y in 0 until DHash.HEIGHT) {
            for (x in 0 until DHash.WIDTH) a[y * DHash.WIDTH + x] = 255 - x * 20
        }
        return a
    }

    @Test
    fun `flat image hashes to zero`() {
        assertEquals(0L, DHash.hash(flat(128)))
    }

    @Test
    fun `left to right increasing sets no bits`() {
        assertEquals(0L, DHash.hash(ascending()))
    }

    @Test
    fun `left to right decreasing sets all bits`() {
        assertEquals(-1L, DHash.hash(descending()))
    }

    @Test
    fun `uses exactly 64 bits`() {
        val h = DHash.hash(descending())
        assertEquals(64, java.lang.Long.bitCount(h))
    }

    @Test
    fun `identical images have zero distance`() {
        val a = DHash.hash(descending())
        val b = DHash.hash(descending())
        assertEquals(0, DHash.hamming(a, b))
    }

    @Test
    fun `opposite images differ in all bits`() {
        assertEquals(64, DHash.hamming(DHash.hash(ascending()), DHash.hash(descending())))
    }

    @Test
    fun `hamming is symmetric`() {
        val a = 0b1011L
        val b = 0b0110L
        assertEquals(DHash.hamming(a, b), DHash.hamming(b, a))
    }

    @Test
    fun `single pixel change barely moves the hash`() {
        val base = descending()
        val tweaked = base.copyOf()
        tweaked[0] = 254 // 只改左上角

        val d = DHash.hamming(DHash.hash(base), DHash.hash(tweaked))
        assertTrue("局部微调不应大幅改变哈希，实际 $d", d <= 2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects wrong pixel count`() {
        DHash.hash(IntArray(10))
    }

    // ---------------- sampleGray ----------------

    @Test
    fun `sampleGray converts white to 255`() {
        val rgba = IntArray(9 * 8) { 0xFFFFFF }
        val gray = DHash.sampleGray(rgba, 9, 8)
        assertEquals(DHash.PIXELS, gray.size)
        assertTrue(gray.all { it == 255 })
    }

    @Test
    fun `sampleGray converts black to 0`() {
        val rgba = IntArray(9 * 8) { 0x000000 }
        assertTrue(DHash.sampleGray(rgba, 9, 8).all { it == 0 })
    }

    @Test
    fun `sampleGray downscales by nearest neighbour`() {
        // 900x800 的图，左半黑右半白；取样后应当保持左右分布
        val w = 900
        val h = 800
        val rgba = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) 0x000000 else 0xFFFFFF
        }
        val gray = DHash.sampleGray(rgba, w, h)

        assertEquals(DHash.PIXELS, gray.size)
        // 第 0 列全黑，第 8 列全白
        assertTrue(gray[0] == 0)
        assertTrue(gray[8] == 255)
    }

    @Test
    fun `left dark right bright is distinguishable from inverse`() {
        val w = 9
        val h = 8
        val darkLeft = IntArray(w * h) { i -> if (i % w < 4) 0x000000 else 0xFFFFFF }
        val brightLeft = IntArray(w * h) { i -> if (i % w < 4) 0xFFFFFF else 0x000000 }

        val a = DHash.hash(DHash.sampleGray(darkLeft, w, h))
        val b = DHash.hash(DHash.sampleGray(brightLeft, w, h))

        assertTrue("明暗反转必须可区分", DHash.hamming(a, b) > 0)
    }
}
