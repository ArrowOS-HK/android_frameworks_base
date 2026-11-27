/*
 * Copyright (C) 2026 ArrowOS-T
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.inv

/**
 * 2D grid occupancy packer shared by Controls and QS Tiles grids.
 * Places heterogeneous spans (1x1, 2x1, 2x2, etc.) from top-left to bottom-right
 * without overlaps, automatically growing rows as needed.
 */
object InvGridPacker {

    data class PlacedItem<T>(
        val data: T,
        val listIndex: Int,
        val row: Int,
        val col: Int,
        val rowSpan: Int,
        val colSpan: Int
    )

    fun <T> pack(
        items: List<T>,
        cols: Int,
        maxRows: Int = Int.MAX_VALUE,
        shrinkHeightToFit: Boolean = false,
        spanOf: (T) -> Pair<Int, Int>
    ): List<PlacedItem<T>> {
        val safeCols = cols.coerceAtLeast(1)
        val occupancy = mutableListOf<BooleanArray>()

        fun ensureRows(needed: Int) {
            while (occupancy.size < needed) {
                occupancy.add(BooleanArray(safeCols))
            }
        }

        val placed = ArrayList<PlacedItem<T>>(items.size)
        items.forEachIndexed { idx, item ->
            val (rawRows, rawCols) = spanOf(item)
            val rSpan = rawRows.coerceIn(1, maxRows.coerceAtLeast(1))
            val cSpan = rawCols.coerceIn(1, safeCols)

            var placedItem: PlacedItem<T>? = null
            var tryRows = rSpan
            while (placedItem == null && tryRows >= 1) {
                var foundRow = -1
                var foundCol = -1
                var r = 0
                while (foundRow == -1 && r + tryRows <= maxRows) {
                    ensureRows(r + tryRows)
                    for (c in 0..(safeCols - cSpan)) {
                        if (canFit(occupancy, r, c, tryRows, cSpan)) {
                            foundRow = r
                            foundCol = c
                            break
                        }
                    }
                    r++
                }

                if (foundRow != -1) {
                    for (rr in foundRow until foundRow + tryRows) {
                        for (cc in foundCol until foundCol + cSpan) {
                            occupancy[rr][cc] = true
                        }
                    }
                    placedItem = PlacedItem(item, idx, foundRow, foundCol, tryRows, cSpan)
                } else if (shrinkHeightToFit && tryRows > 1) {
                    tryRows = 1
                } else {
                    break
                }
            }
            if (placedItem != null) {
                placed.add(placedItem)
            }
        }
        return placed
    }

    private fun canFit(
        occupancy: List<BooleanArray>,
        startRow: Int,
        startCol: Int,
        rowSpan: Int,
        colSpan: Int
    ): Boolean {
        for (rr in startRow until startRow + rowSpan) {
            for (cc in startCol until startCol + colSpan) {
                if (occupancy[rr][cc]) return false
            }
        }
        return true
    }
}
