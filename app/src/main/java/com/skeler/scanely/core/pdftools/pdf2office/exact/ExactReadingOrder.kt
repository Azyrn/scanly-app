package com.skeler.scanely.core.pdftools.pdf2office.exact

internal fun ExactPageLayout.forEachInReadingOrder(onBlock: (Block) -> Unit, onTable: (ExactTable) -> Unit) {
    val before = tables.sortedWith(compareBy({ it.box.top }, { it.box.left })).groupBy { table ->
        blocks.indexOfFirst { followsTable(it, table) }.let { if (it < 0) blocks.size else it }
    }
    blocks.forEachIndexed { index, block ->
        before[index].orEmpty().forEach(onTable)
        onBlock(block)
    }
    before[blocks.size].orEmpty().forEach(onTable)
}

private fun ExactPageLayout.followsTable(block: Block, table: ExactTable): Boolean {
    val top = heightPt - block.lines.first().baselineV - block.pitchPt * ExactLayout.BASELINE_RATIO
    return block.angleDeg == 0 && top >= table.box.bottom && block.lines.any {
        minOf(it.u1, table.box.right) > maxOf(it.u0, table.box.left)
    }
}
