package com.zapiack.push.core

import org.json.JSONObject

/**
 * A push template's grid layout, as Zapiack sends it in the `zp_layout` data key: rows and stacks that share their space by
 * weight, cut from a 24-column grid on the server so every platform draws the same thing.
 *
 * An element is named by a short reference: `t` the title, `b` the body, `i` the small picture, `p` the big picture, `l<n>` the
 * n-th live element (see [Live]) and `a<n>` the n-th button.
 */
sealed class LayoutTree {
    /** `row` lays children side by side; otherwise they stack. */
    data class Node(val row: Boolean, val children: List<Child>) : LayoutTree()

    sealed class Child {
        abstract val weight: Int
        data class Sub(override val weight: Int, val node: Node) : Child()
        /** An element, and its height in grid rows. */
        data class Element(override val weight: Int, val ref: String, val rows: Int) : Child()
        /** Empty space. */
        data class Space(override val weight: Int) : Child()
    }

    companion object {
        const val DATA_KEY = "zp_layout"
        /** How tall one grid row is, in dp. */
        const val ROW_DP = 8
        private val REF = Regex("^(t|b|i|p|l\\d{1,2}|a\\d)$")

        /** Reads the tree from a push's data, or null when there is none or it cannot be read. */
        fun fromData(data: Map<String, String?>): Node? = data[DATA_KEY]?.let { raw -> runCatching { node(JSONObject(raw), 0) }.getOrNull() }

        private fun node(o: JSONObject, depth: Int): Node {
            require(depth < 12) { "too deep" }
            val row = o.getString("d") == "r"
            val list = o.getJSONArray("c")
            val children = (0 until list.length()).mapNotNull { i -> child(list.getJSONObject(i), depth) }
            return Node(row, children)
        }

        private fun child(o: JSONObject, depth: Int): Child? {
            val weight = o.optInt("w", 0).coerceIn(0, 80)
            if (weight <= 0) return null
            return when {
                o.has("n") -> Child.Sub(weight, node(o.getJSONObject("n"), depth + 1))
                o.has("e") -> o.getString("e").takeIf { REF.matches(it) }?.let { Child.Element(weight, it, o.optInt("h", 1).coerceIn(1, 80)) }
                else -> Child.Space(weight)
            }
        }

        /** How tall a node draws, in grid rows: a stack adds its parts up, a row is as tall as its tallest part. */
        fun rows(node: Node): Int = if (node.row) node.children.maxOfOrNull { rowsOf(it) } ?: 0 else node.children.sumOf { rowsOf(it) }

        private fun rowsOf(c: Child): Int = when (c) {
            is Child.Sub -> rows(c.node)
            is Child.Element -> c.rows
            is Child.Space -> c.weight
        }

        /**
         * The same tree cut to its first [maxRows] rows, for the collapsed notification: an element that would not fit whole is left
         * out rather than cut in half. Null when nothing fits.
         */
        fun top(node: Node, maxRows: Int): Node? {
            if (node.row) {
                val fits = rows(node) <= maxRows
                return if (fits) node else null
            }
            val out = mutableListOf<Child>()
            var used = 0
            for (c in node.children) {
                val r = rowsOf(c)
                if (used + r <= maxRows) {
                    out += c
                    used += r
                } else {
                    if (c is Child.Sub) top(c.node, maxRows - used)?.let { out += Child.Sub(rows(it), it) }
                    break
                }
            }
            return if (out.any { it !is Child.Space }) Node(false, out) else null
        }

        /** Every element reference in a tree. */
        fun refs(node: Node): List<String> = node.children.flatMap { c ->
            when (c) {
                is Child.Sub -> refs(c.node)
                is Child.Element -> listOf(c.ref)
                is Child.Space -> emptyList()
            }
        }
    }
}
