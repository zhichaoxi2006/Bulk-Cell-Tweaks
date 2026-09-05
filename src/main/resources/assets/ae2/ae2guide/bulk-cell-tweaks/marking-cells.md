---
navigation:
  parent: bulk-cell-tweaks/bulk-cell-tweaks-index.md
  title: Marking Cells
---

# Marking Cells

Each bulk cell's filter determines what it stores. The marker lets you edit the filter of every
mounted bulk cell from one screen.

## The 63-Slot Grid

Every slot shows the filter of one cell. A cell with no filter yet shows an empty slot.

* <ItemImage id="minecraft:diamond" /> **Left-click with an item** to set that cell's filter to the item.
* **Left-click with an empty hand** to clear the cell's filter.
* Only one item per slot — the rest of the stack stays on your cursor.
* If another cell already has exactly the same filter, its contents are moved into the new
  cell first and then its filter is cleared — the mark moves to the new cell together with
  the items. Cells that merely cover the item through a compression chain (a different
  variant as their filter) are left alone.
* A cell with no filter but stored contents (a stranded cell) shows its contents in the slot.
  **Left-click it with an empty hand** to drain the contents back into the network (powered,
  like the IO Port). The auto-mark button repairs stranded cells automatically instead.

## Paging

The left toolbar has ◀ and ▶ buttons. When there are more than 63 cells, use them to page
through. Unusable buttons are grayed out; the buttons never move or disappear.

## Auto-Mark Button

The sort-by-amount button in the left toolbar (below ◀ and ▶) marks every empty cell at once,
based on what your network actually holds:

1. Items in the ME network are ordered by stored amount, largest first.
2. Each item is written as the filter of the first empty cell — items already covered by an
   existing filter (or by a compression-card cell covering the same compression chain) are skipped.
3. Marking stops when no empty cells are left.

Before marking, stranded cells (no filter but with contents) are repaired: the stored item is
written back as their filter. Then cells that share exactly the same filter are consolidated:
their contents are merged into one of them (the first that can hold everything) and the other
cells have their filters cleared, freeing them up for new marks.

After marking, the marker transfers the network contents into the matching bulk cells:

* Every filtered cell pulls its item type out of the rest of the network and into itself.
* Only non-bulk storage (regular cells, storage buses, ...) is drawn from — bulk cells
  never pull from each other.
* Transfers consume grid energy exactly like the IO Port; items that can't be powered stay
  where they are.
* A cell with a compression card pulls in the whole compression chain (e.g. iron nuggets,
  ingots and blocks all move into a cell filtered to iron ingots).
* Items already inside their cell stay put; items that don't fit the cell's remaining
  capacity stay in the network.

The button is grayed out when the network has no bulk cells mounted.
