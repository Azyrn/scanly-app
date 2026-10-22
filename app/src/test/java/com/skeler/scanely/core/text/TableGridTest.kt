package com.skeler.scanely.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The offline engine's tables have to survive an export in the same shape the server's do, so
 * these mirror the Worker's `ocr-format` suite case for case. Every case is stated as a shape:
 * the repair either recognises that shape or leaves the grid exactly as it found it.
 */
class TableGridTest {

    @Test
    fun `folds a heading stranded between two columns of data`() {
        val repaired = TableGrid.repair(
            listOf(
                listOf("CODE", "LIBELLE", "الدين DEBIT", "رؤوس الاموال CAPITAUX", "الاعتمادات CREDIT"),
                listOf("0001CU", "ACHAT CARTE 001", "", "100,00", ""),
                listOf("0001CP", "VIREMENT RECU", "", "", "1,00")
            )
        )

        assertEquals(
            listOf("CODE", "LIBELLE", "الدين DEBIT رؤوس الاموال CAPITAUX", "الاعتمادات CREDIT"),
            repaired[0]
        )
        assertEquals(listOf("0001CU", "ACHAT CARTE 001", "100,00", ""), repaired[1])
        assertEquals(listOf("0001CP", "VIREMENT RECU", "", "1,00"), repaired[2])
    }

    @Test
    fun `folds an invoice heading split over a grouped money band`() {
        val repaired = TableGrid.repair(
            listOf(
                listOf("Description", "Qty", "Unit", "Price", "Amount"),
                listOf("Widget", "2", "", "4.00", "8.00"),
                listOf("Gasket", "1", "", "2.50", "2.50")
            )
        )

        assertEquals(listOf("Description", "Qty", "Unit Price", "Amount"), repaired[0])
        assertEquals(listOf("Widget", "2", "4.00", "8.00"), repaired[1])
    }

    @Test
    fun `keeps a column the document genuinely leaves empty at the edge`() {
        val grid = listOf(
            listOf("Item", "Qty", "Notes"),
            listOf("Widget", "2", ""),
            listOf("Gasket", "1", "")
        )
        assertEquals(grid, TableGrid.repair(grid))
    }

    @Test
    fun `keeps a run of empty columns, such as untouched days on a timesheet`() {
        val grid = listOf(
            listOf("Name", "Mon", "Tue", "Wed", "Thu", "Fri"),
            listOf("Ada", "8", "8", "", "", ""),
            listOf("Grace", "7", "6", "", "", "")
        )
        assertEquals(grid, TableGrid.repair(grid))
    }

    @Test
    fun `keeps an empty column that has no heading of its own`() {
        val grid = listOf(listOf("A", "", "C"), listOf("1", "", "3"))
        assertEquals(grid, TableGrid.repair(grid))
    }

    @Test
    fun `pads ragged rows to one width`() {
        val repaired = TableGrid.repair(
            listOf(listOf("A", "B", "C"), listOf("1"), listOf("1", "2"))
        )
        assertEquals(
            listOf(
                listOf("A", "B", "C"),
                listOf("1", "", ""),
                listOf("1", "2", "")
            ),
            repaired
        )
    }

    @Test
    fun `drops blank rows, which an exporter would read as a table boundary`() {
        val repaired = TableGrid.repair(
            listOf(listOf("A", "B"), listOf("1", "2"), listOf("", ""), listOf("3", "4"))
        )
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2"), listOf("3", "4")), repaired)
    }

    @Test
    fun `trims blank columns off the edges, like a spreadsheet used range`() {
        val repaired = TableGrid.repair(
            listOf(listOf("", "A", "B", ""), listOf("", "1", "2", ""))
        )
        assertEquals(listOf(listOf("A", "B"), listOf("1", "2")), repaired)
    }

    @Test
    fun `leaves a clean table untouched`() {
        val grid = listOf(
            listOf("Element", "Symbol", "Mass"),
            listOf("Hydrogen", "H", "1.008"),
            listOf("Helium", "He", "4.0026")
        )
        assertEquals(grid, TableGrid.repair(grid))
    }

    @Test
    fun `leaves a header-only table alone`() {
        val grid = listOf(listOf("A", "B", "C"))
        assertEquals(grid, TableGrid.repair(grid))
    }

    @Test
    fun `never empties a grid that is blank all the way through`() {
        val grid = listOf(listOf("", ""), listOf("", ""))
        assertEquals(grid, TableGrid.repair(grid))
    }

    @Test
    fun `leaves a single-column table alone`() {
        val grid = listOf(listOf("Heading"), listOf("value"))
        assertEquals(grid, TableGrid.repair(grid))
    }

    @Test
    fun `treats a second heading row as data, so multi-level headings survive`() {
        val grid = listOf(
            listOf("", "Revenue", "Revenue", "Cost"),
            listOf("Region", "2025", "2026", "2026"),
            listOf("EMEA", "10", "12", "5")
        )
        assertEquals(grid, TableGrid.repair(grid))
    }

    @Test
    fun `renders a repaired grid as a pipe table the CSV export can read`() {
        val markdown = Markdown.table(
            TableGrid.repair(
                listOf(
                    listOf("CODE", "DEBIT", "CAPITAUX", "CREDIT"),
                    listOf("0001CU", "", "100,00", ""),
                    listOf("0001CP", "", "", "1,00")
                )
            )
        )

        val rows = TextTables.rows(markdown, asMarkdown = true)
        assertEquals(listOf("CODE", "DEBIT CAPITAUX", "CREDIT"), rows[0])
        assertEquals(listOf("0001CU", "100,00", ""), rows[1])
        assertEquals(listOf("0001CP", "", "1,00"), rows[2])
    }
}
