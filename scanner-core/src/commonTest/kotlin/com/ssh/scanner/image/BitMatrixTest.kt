package com.ssh.scanner.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitMatrixTest {

    @Test
    fun storesAndReadsBitsIndependently() {
        val matrix = BitMatrix(width = 130, height = 3)
        matrix[0, 0] = true
        matrix[63, 0] = true
        matrix[64, 0] = true
        matrix[129, 2] = true

        assertTrue(matrix[0, 0])
        assertTrue(matrix[63, 0], "бит на границе первого 64-битного слова")
        assertTrue(matrix[64, 0], "бит в начале второго слова")
        assertTrue(matrix[129, 2])
        assertFalse(matrix[1, 0])
        assertFalse(matrix[64, 1], "соседняя строка не должна задеваться")
    }

    @Test
    fun clearsBit() {
        val matrix = BitMatrix(width = 8, height = 8)
        matrix[3, 4] = true
        matrix[3, 4] = false
        assertFalse(matrix[3, 4])
    }

    @Test
    fun setRegionFillsRectangle() {
        val matrix = BitMatrix(width = 10, height = 10)
        matrix.setRegion(left = 2, top = 3, regionWidth = 4, regionHeight = 2, value = true)

        assertTrue(matrix[2, 3])
        assertTrue(matrix[5, 4])
        assertFalse(matrix[6, 4], "правее области")
        assertFalse(matrix[2, 5], "ниже области")
    }

    @Test
    fun textFixtureRoundTrips() {
        val text = """
            #.#.
            .##.
            ....
        """.trimIndent()

        val matrix = BitMatrix.parse(text)

        assertEquals(4, matrix.width)
        assertEquals(3, matrix.height)
        assertEquals(text + "\n", matrix.toString())
    }

    @Test
    fun outOfBoundsReadsAsWhite() {
        val matrix = BitMatrix(width = 4, height = 4)
        matrix[0, 0] = true

        assertTrue(matrix.getOrFalse(0, 0))
        assertFalse(matrix.getOrFalse(-1, 0))
        assertFalse(matrix.getOrFalse(4, 4))
        assertFalse(matrix.contains(4, 0))
    }

    @Test
    fun rejectsFixtureWithRaggedRows() {
        assertFailsWith<IllegalArgumentException> {
            BitMatrix.parse("###\n##")
        }
    }
}
