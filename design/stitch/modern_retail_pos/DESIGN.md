---
name: Modern Retail POS
colors:
  surface: '#f8f9ff'
  surface-dim: '#cbdbf5'
  surface-bright: '#f8f9ff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#eff4ff'
  surface-container: '#e5eeff'
  surface-container-high: '#dce9ff'
  surface-container-highest: '#d3e4fe'
  on-surface: '#0b1c30'
  on-surface-variant: '#434655'
  inverse-surface: '#213145'
  inverse-on-surface: '#eaf1ff'
  outline: '#737686'
  outline-variant: '#c3c6d7'
  surface-tint: '#0053db'
  primary: '#004ac6'
  on-primary: '#ffffff'
  primary-container: '#2563eb'
  on-primary-container: '#eeefff'
  inverse-primary: '#b4c5ff'
  secondary: '#565e74'
  on-secondary: '#ffffff'
  secondary-container: '#dae2fd'
  on-secondary-container: '#5c647a'
  tertiary: '#632ecd'
  on-tertiary: '#ffffff'
  tertiary-container: '#7d4ce7'
  on-tertiary-container: '#f6edff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dbe1ff'
  primary-fixed-dim: '#b4c5ff'
  on-primary-fixed: '#00174b'
  on-primary-fixed-variant: '#003ea8'
  secondary-fixed: '#dae2fd'
  secondary-fixed-dim: '#bec6e0'
  on-secondary-fixed: '#131b2e'
  on-secondary-fixed-variant: '#3f465c'
  tertiary-fixed: '#e9ddff'
  tertiary-fixed-dim: '#d0bcff'
  on-tertiary-fixed: '#23005c'
  on-tertiary-fixed-variant: '#5516be'
  background: '#f8f9ff'
  on-background: '#0b1c30'
  surface-variant: '#d3e4fe'
typography:
  headline-lg:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 34px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Inter
    fontSize: 22px
    fontWeight: '600'
    lineHeight: 28px
    letterSpacing: -0.015em
  headline-sm:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: -0.01em
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '500'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '600'
    lineHeight: 18px
    letterSpacing: 0.01em
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.02em
  label-sm:
    fontFamily: Inter
    fontSize: 10px
    fontWeight: '700'
    lineHeight: 12px
    letterSpacing: 0.04em
  currency-display:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 28px
    letterSpacing: -0.02em
  currency-large:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '800'
    lineHeight: 36px
    letterSpacing: -0.03em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  gutter: 0.75rem
  margin: 1rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 0.75rem
  space-lg: 1rem
  space-xl: 1.5rem
---

## Brand & Style

This design system establishes a high-precision, utilitarian, yet executive interface tailored for high-cadence retail operations in Colombia (cigarrerías, licoreras, minimarkets de conveniencia). The UI balances commercial agility with enterprise rigor: lightning-fast visual scanning during high-volume counter sales, combined with authoritative data clarity for inventory, cash reconciliations, and bookkeeping.

The visual style is **Corporate Modern with Tactile Refinements**:
- Dense, structured surface geometry optimized for compact 390px viewport limits.
- High-contrast, clean visual planes eliminating visual noise under fluctuating store lighting.
- Tactile, oversized touch anchors calibrated for rapid single-thumb POS interactions without mistaps.
- Trustworthy financial grounding using structured deep navy framing combined with energetic electric cobalt feedback.

## Colors

The palette leverages a structured corporate light foundation punctuated by high-contrast operational status cues:

- **Primary (`#2563EB` / Active: `#1D4ED8`):** Electric blue dedicated to high-intent primary interactions, terminal action confirmation, dynamic badges, and focused interactive inputs.
- **Secondary (`#0F172A` / Tonal: `#1E293B`):** Deep marine slate providing structural gravity. Applied across navigation rails, summary card backdrops, key operational metrics, and prominent currency headers.
- **Tertiary (`#8B5CF6` / Accent: `#A855F7`):** Soft digital violet engineered specifically for instant recognition of domestic digital wallet rails (Nequi, Daviplata, Transfiya).
- **Neutral Palette:**
  - Base Canvas: `#F8FAFC`
  - Elevated Container / Cards: `#FFFFFF`
  - Subtle Dividing Borders: `#E2E8F0`
  - Muted Text / Secondary Labels: `#64748B`
  - High-Contrast Body Text: `#0F172A`
- **Semantic Signals:**
  - Success / Transacted: Emerald `#10B981` (Completed sales, positive cash balance).
  - Caution / Low Stock: Amber `#F59E0B` (Stock warning thresholds, pending validations).
  - Critical / Void: Coral Red `#EF4444` (Out-of-stock items, register shortages, voided receipts).

## Typography

The typography uses Inter across all levels to guarantee crisp legibility on compact displays and hand-held barcode/terminal readers.

- **Tabular Figures & Currency:** All numeric financial tallies, cart computations, and stock tallies must enforce tabular figure alignment (`font-feature-settings: 'tnum' on, 'cv05' on`).
- **Currency Convention:** Colombian Peso values must be formatted as `$ XX.XXX` with explicit period grouping. Use `currency-large` for checkout totals and register balancing, ensuring immediate legibility from arm's-length distance.
- **Hierarchy Rules:** Section headers require strong semi-bold or bold treatments (`headline-md`) paired with muted, compact metadata labels (`label-md` or `label-sm`) in uppercase for operational categories (e.g., `STOCK DISPONIBLE`, `NEQUI`).

## Layout & Spacing

The mobile layout is anchored to a standard 390px baseline (portrait counter POS experience) with fluid scaling across responsive containers.

- **Mobile Grid & Margins:** Uses a 4-column fluid layout with an outer canvas boundary `margin` of `1rem` (16px) and an inner element `gutter` of `0.75rem` (12px).
- **Ergonomic Safe Zones:**
  - Bottom Bar Action Area: Dedicated sticky action zone at least 64px tall, sitting flush against the device safe-area inset for thumb access to "Cobrar" and "Agregar".
  - Quick-Select Grids: 2-column card layouts on mobile screen widths; dense item lists use single-row fluid cells with 48px minimum vertical touch heights.
- **Desktop/Tablet Breakpoints (768px and 1024px):** When shifting to POS tablet stands or admin back-office screens, the layout expands to an 8-column or 12-column system, anchoring the live ticket/cart to a persistent 360px right-hand pane while the catalog/admin table fills the left canvas.

## Elevation & Depth

Visual hierarchy uses crisp boundaries over intense shadows, keeping operational views sharp and uncluttered:

- **Tier 0 (Base Canvas):** Background rendered in `#F8FAFC`. Zero elevation.
- **Tier 1 (Surface Cards & Row Panels):** Pure white background (`#FFFFFF`) defined with a crisp 1px stroke of `#E2E8F0`. Layered with a featherweight ambient shadow: `0 1px 3px 0 rgba(15, 23, 42, 0.05)`.
- **Tier 2 (Sticky Headers, Drawers & Cart Trays):** Background `#FFFFFF` with low-contrast baseline border and soft vertical elevation: `0 4px 12px -2px rgba(15, 23, 42, 0.08)`.
- **Tier 3 (Modals, NumPad Overlays, Cash Drawer Confirms):** Centered surfaces framed in 1px `#CBD5E1`, backed by an ambient backdrop scrim (`rgba(15, 23, 42, 0.60)`) and deep dimensional shadow: `0 12px 28px -4px rgba(15, 23, 42, 0.16)`.

## Shapes

The design uses moderate roundedness (`roundedness: 2`) to deliver a modern, approachable feel while preserving efficient screen real estate:

- **Base Components:** Inputs, compact chips, and list selections use `rounded-md` (0.5rem / 8px).
- **Core Cards & Containers:** Product cards, modal shells, and POS summary containers utilize `rounded-xl` (1rem / 16px).
- **Interactive Action Pills:** Badges, numeric quantity counters, and status tags utilize fully rounded geometry (`rounded-full` / 9999px) to distinctly contrast with structural content cards.

## Components

### Buttons & Quick Actions
- **Primary Checkout Button:** Height of 52px to 56px for rapid thumb activation. Background `#2563EB` with bold white typography, 16px font size, and `rounded-xl`. Active state compresses slightly (`scale: 0.98`) to provide physical tactile response.
- **Secondary / Action Controls:** Background `#0F172A` with white typography for secondary administrative executions (e.g., "Arqueo de Caja", "Cierre Diario").
- **Payment Method Buttons:** Dedicated segmented cards for payment methods:
  - Cash / Efectivo: Subtle emerald highlight border `#10B981` on select.
  - Nequi / Daviplata: Subtle purple highlight border `#8B5CF6` with a soft purple tint (`#F3E8FF`) when active.

### Product & Inventory Cards
- **POS Catalog Card:** Compact card layout with `rounded-xl` styling and a 1px border (`#E2E8F0`). Top section contains product title (`body-md`, `#0F172A`) and stock chip. Bottom section features price rendered in `currency-display` alongside a prominent (+) quick-add hit area (minimum 44x44px).
- **Stock Depletion States:** When stock is below the threshold, cards feature an amber badge (`#F59E0B`). When completely depleted (`0`), the card receives a 40% visual opacity with a coral red tag (`#EF4444`) labeled "AGOTADO".

### Input Fields & Keypads
- **Touch-Friendly POS Inputs:** Search inputs and barcode manual entry fields must have a minimum height of 48px with clear interior icons (magnifier, scan barcode) in `#64748B`.
- **Integrated Number Pad (Arqueos / Cobro Manual):** High-touch calculator pad buttons (minimum 54px height) built with `#FFFFFF` tiles, bold labels (`20px`), separated by 8px grid gaps for rapid cash-tender entry.

### Chips & Status Badges
- **Semantic Pills:** Padding `4px 10px`, typography `label-sm`.
  - Transacción Exitosa: Background `#ECFDF5`, Text `#047857`.
  - Stock Bajo: Background `#FEF3C7`, Text `#B45309`.
  - Crítico / Devolución: Background `#FEF2F2`, Text `#B91C1C`.
  - Métodos Digitales (Nequi/Daviplata): Background `#F5F3FF`, Text `#6D28D9`.

### Lists & Cart Summaries
- **Itemized Cart Rows:** Border-bottom 1px `#F1F5F9`. Features item name, single-unit price in `$ COP`, inline quantity stepper `[- 1 +]` with 36px touch targets, and total line price aligned right with bold tabular formatting.