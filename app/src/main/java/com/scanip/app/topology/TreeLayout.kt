package com.scanip.app.topology

import kotlin.math.ceil
import kotlin.math.min

/**
 * Tidy top-down tree layout. Children that have their own children are laid out side by side;
 * leaf children are packed into a grid under their parent so wide flat networks stay compact.
 * All units are abstract (the UI treats them as dp).
 */
class TreeLayout(
    private val children: Map<String, List<String>>,
    val nodeWidth: Float = 150f,
    val nodeHeight: Float = 58f,
    private val gapX: Float = 24f,
    private val rowGap: Float = 18f,
    private val levelGap: Float = 56f,
) {
    data class Box(val x: Float, val y: Float) // top-left corner

    data class Result(val boxes: Map<String, Box>, val width: Float, val height: Float, val leafGrids: Map<String, LeafGrid>)

    /** Geometry of a parent's leaf grid, used to draw the connecting bus lines. */
    data class LeafGrid(val left: Float, val top: Float, val columns: Int, val rows: Int)

    private val widths = HashMap<String, Float>()
    private val boxes = HashMap<String, Box>()
    private val grids = HashMap<String, LeafGrid>()
    private var maxY = 0f

    fun layout(root: String): Result {
        measure(root)
        place(root, 0f, 0f)
        return Result(boxes, widths.getValue(root), maxY, grids)
    }

    private fun kids(n: String) = children[n].orEmpty()
    private fun isLeaf(n: String) = kids(n).isEmpty()

    private fun leafColumns(n: String): Int {
        val leaves = kids(n).count(::isLeaf)
        if (leaves == 0) return 0
        val branches = kids(n).count { !isLeaf(it) }
        return min(leaves, if (branches > 0) 3 else 5)
    }

    private fun measure(n: String): Float {
        val ks = kids(n)
        val branchW = ks.filterNot(::isLeaf).map(::measure).let { w -> if (w.isEmpty()) 0f else w.sum() + gapX * (w.size - 1) }
        val cols = leafColumns(n)
        val leafW = if (cols == 0) 0f else cols * nodeWidth + (cols - 1) * gapX
        val content = branchW + leafW + if (branchW > 0 && leafW > 0) gapX else 0f
        return maxOf(nodeWidth, content).also { widths[n] = it }
    }

    private fun place(n: String, left: Float, top: Float) {
        val width = widths.getValue(n)
        boxes[n] = Box(left + (width - nodeWidth) / 2, top)
        maxY = maxOf(maxY, top + nodeHeight)
        val ks = kids(n)
        if (ks.isEmpty()) return
        val childTop = top + nodeHeight + levelGap
        val branches = ks.filterNot(::isLeaf)
        val leaves = ks.filter(::isLeaf)
        val cols = leafColumns(n)
        val branchW = if (branches.isEmpty()) 0f else branches.sumOf { widths.getValue(it).toDouble() }.toFloat() + gapX * (branches.size - 1)
        val leafW = if (cols == 0) 0f else cols * nodeWidth + (cols - 1) * gapX
        val content = branchW + leafW + if (branchW > 0 && leafW > 0) gapX else 0f
        var x = left + (width - content) / 2
        for (b in branches) {
            place(b, x, childTop)
            x += widths.getValue(b) + gapX
        }
        if (leaves.isNotEmpty()) {
            val rows = ceil(leaves.size / cols.toFloat()).toInt()
            grids[n] = LeafGrid(x, childTop, cols, rows)
            leaves.forEachIndexed { i, leaf ->
                val bx = x + (i % cols) * (nodeWidth + gapX)
                val by = childTop + (i / cols) * (nodeHeight + rowGap)
                boxes[leaf] = Box(bx, by)
                widths[leaf] = nodeWidth
                maxY = maxOf(maxY, by + nodeHeight)
            }
        }
    }
}
