# Bulk Cell Tweaks

Bulk Cell Tweaks is a [NeoForge](https://neoforged.net/) addon for
[Applied Energistics 2](https://www.curseforge.com/minecraft/mc-mods/applied-energistics-2) and
[MEGA Cells](https://www.curseforge.com/minecraft/mc-mods/mega-cells). It adds the **Bulk Cell
Marker**, a ME network block that lets you manage the filters of *every* bulk cell mounted in your
network from a single GUI.

Instead of opening each drive, ME chest, or cell dock one by one, the marker discovers all mounted
bulk cells through the ME network and presents them in one screen.

## Features

- **One screen for all bulk cells** — every mounted bulk cell appears in a 63-slot grid (with
  paging for larger networks), in a fixed, predictable order.
- **Set or clear filters by hand** — left-click a slot with an item to stamp it as that cell's
  filter, or with an empty hand to clear it. If another cell already has the exact same filter,
  its contents are moved over first and its mark moves along with the items.
- **Auto-mark by amount** — one button marks every empty cell based on what your network actually
  holds: items are sorted by stored amount (largest first) and written into empty cells until none
  are left. Stranded cells (contents but no filter) are repaired, and duplicate marks are
  consolidated into a single cell.
- **Automatic transfer** — after marking, each filtered cell pulls its item type from the rest of
  the network into itself. Regular cells and storage buses are drained first; bulk cells never
  pull from each other. Transfers are powered exactly like the IO Port.
- **Compression card management** — right-click a slot while holding a compression card to install
  it into that cell, or right-click with an empty hand to take one out. No need to open the cell
  dock.
- **Stranded-cell repair** — cells that hold items but lost their filter show their contents in the
  grid; one click drains them back into the network.

## Usage

1. Place the **Bulk Cell Marker** on your ME network. It requires one channel and draws 2 ae/t
   while idle.
2. Right-click the block to open its GUI. The marker has no inventory of its own — it works
   directly on the bulk cells mounted in your drives, ME chests, and cell docks.
3. Use the 63-slot grid to set and clear filters, the arrows in the left toolbar to page through
   large networks, and the auto-mark button to mark and fill every empty cell at once.

The mod also ships an in-game guide (via GuideME): open the AE2 guide and look for the
*Bulk Cell Tweaks* entry.

## Requirements

| Dependency | Version |
| ---------- | ------- |
| Minecraft  | 1.21.1 |
| NeoForge   | 21.1+  |
| Applied Energistics 2 | 19.2+ |
| MEGA Cells | 4.11+ |
| GuideME    | 21.1+ |

## License

Bulk Cell Tweaks is licensed under the **GNU Lesser General Public License v3.0 (LGPL-3.0)**.
See [LICENSE](LICENSE) for the full text.
