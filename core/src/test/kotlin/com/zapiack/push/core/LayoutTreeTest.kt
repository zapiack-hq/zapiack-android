package com.zapiack.push.core

import kotlin.test.*

class LayoutTreeTest {
    // The server's tree for: title over a timer (left) beside a button (right), with a gap above the button.
    private val raw = """{"d":"c","c":[{"w":4,"e":"t","h":4},{"w":8,"n":{"d":"r","c":[{"w":12,"e":"l0","h":8},{"w":12,"n":{"d":"c","c":[{"w":2,"s":1},{"w":6,"e":"a0","h":6}]}}]}}]}"""

    @Test
    fun `reads the tree and its references`() {
        val tree = LayoutTree.fromData(mapOf("zp_layout" to raw))!!
        assertFalse(tree.row)
        assertEquals(listOf("t", "l0", "a0"), LayoutTree.refs(tree))
        assertEquals(12, LayoutTree.rows(tree))
    }

    @Test
    fun `keeps only whole elements when cut for the collapsed view`() {
        val tree = LayoutTree.fromData(mapOf("zp_layout" to raw))!!
        val top = LayoutTree.top(tree, 6)!!
        assertEquals(listOf("t"), LayoutTree.refs(top))
        assertNull(LayoutTree.top(LayoutTree.Node(false, listOf(LayoutTree.Child.Space(3))), 6))
    }

    @Test
    fun `ignores what it cannot read rather than failing`() {
        assertNull(LayoutTree.fromData(mapOf("zp_layout" to "{oops")))
        assertNull(LayoutTree.fromData(emptyMap()))
        val odd = LayoutTree.fromData(mapOf("zp_layout" to """{"d":"c","c":[{"w":2,"e":"<script>","h":2},{"w":2,"e":"b","h":2},{"w":0,"e":"t"}]}"""))!!
        assertEquals(listOf("b"), LayoutTree.refs(odd))
    }
}
