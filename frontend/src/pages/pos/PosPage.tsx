import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useApproval } from '../../components/Approval'
import { Banner, Button, Confirm, Field, Icon, Modal, Spinner, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { calculate, lineFrom, scaleFor, type CartLine } from '../../lib/cart'
import { money } from '../../lib/format'
import { usePosData } from '../../lib/offline/posData'
import { kvGet, kvSet } from '../../lib/offline/idb'
import { confirmSent, discardSale, enqueueSale, newClientRef, SALES_SYNCED_EVENT, syncPending, updateSale, useOnline, usePendingSales, type PendingSale } from '../../lib/offline/queue'
import type { AccountSummary, PosProduct, Receipt, SaleDto } from '../../lib/posTypes'
import CameraScanner from './CameraScanner'
import CheckoutModal, { type CheckoutPayload } from './CheckoutModal'
import { AccountsBoard, CartList, HoldsModal, LineEditor, NewAccountModal, PresentationPicker } from './PosParts'
import { SerialPrompt, VariantPicker } from './PosCatalogParts'
import { parseScaleCode, readScaleWeight, scaleSupported } from '../../lib/scale'
import { PrintArea, ReceiptView } from './Receipt'
import { ProductCard } from './ProductGrid'

type Mode = { kind: 'sale' } | { kind: 'account'; sale: SaleDto }

/** Carrito en curso por tienda y usuario: sobrevive a recargar, cerrar la pestaña o un apagón. */
const cartKey = (tenantId?: number, userId?: number) => `tp_cart_${tenantId ?? 0}_${userId ?? 0}`

/** Minúsculas y sin tildes: "aguila" encuentra "Águila". */
const fold = (s: string) => s.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '')

export default function PosPage() {
  const { me, has } = useAuth()
  const toast = useToast()
  const online = useOnline()
  const approval = useApproval()
  const { data, fromCache, error, reload, consumeLocal } = usePosData(me?.tenant.id)
  const pending = usePendingSales(me?.tenant.id)
  const currency = me?.tenant.currency ?? 'COP'
  const scale = scaleFor(currency)

  const [view, setView] = useState<'sell' | 'accounts'>('sell')
  const [mode, setMode] = useState<Mode>({ kind: 'sale' })
  const [cart, setCart] = useState<CartLine[]>(() => {
    try {
      return JSON.parse(localStorage.getItem(cartKey(me?.tenant.id, me?.id)) ?? '[]')
    } catch {
      return []
    }
  })
  const [query, setQuery] = useState('')
  const [categoryId, setCategoryId] = useState<number | null>(null)
  const [favorites, setFavorites] = useState(false)
  const [picker, setPicker] = useState<PosProduct | null>(null)
  const [variantPicker, setVariantPicker] = useState<PosProduct | null>(null)
  /** Pedido de seriales: la presentación, cuántos y (si es una línea existente) cuál. */
  const [serialAsk, setSerialAsk] = useState<{ presentationId: number; count: number } | null>(null)
  const [editing, setEditing] = useState<string | null>(null)
  const [checkout, setCheckout] = useState(false)
  const [receipt, setReceipt] = useState<{ r: Receipt; provisional: boolean } | null>(null)
  const [cartOpen, setCartOpen] = useState(false)
  const [camera, setCamera] = useState(false)
  const [accounts, setAccounts] = useState<AccountSummary[]>([])
  const [newAccount, setNewAccount] = useState(false)
  const [holds, setHolds] = useState<SaleDto[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [cancelAccount, setCancelAccount] = useState(false)
  const [cancelReason, setCancelReason] = useState('')
  const searchRef = useRef<HTMLInputElement>(null)

  // Cada cambio del carrito se guarda dos veces: localStorage (inmediato) e IndexedDB (confirmado en disco).
  const restoredCart = useRef(false)
  useEffect(() => {
    const key = cartKey(me?.tenant.id, me?.id)
    if (!restoredCart.current) {
      restoredCart.current = true
      // Si localStorage se perdió en el apagón, se recupera la copia de IndexedDB.
      if (!cart.length) {
        void kvGet<CartLine[]>(key).then((saved) => {
          if (saved?.length) setCart((c) => (c.length ? c : saved))
        })
        return
      }
    }
    try {
      localStorage.setItem(key, JSON.stringify(cart))
    } catch {
      /* sin almacenamiento */
    }
    void kvSet(key, cart)
  }, [cart, me?.tenant.id, me?.id])

  const catalog = data?.catalog ?? []
  const byPresentation = useMemo(() => {
    const m = new Map<number, { p: PosProduct; pr: PosProduct['presentations'][number] }>()
    catalog.forEach((p) => p.presentations.forEach((pr) => m.set(pr.id, { p, pr })))
    return m
  }, [catalog])
  const byBarcode = useMemo(() => {
    const m = new Map<string, number>()
    catalog.forEach((p) => p.presentations.forEach((pr) => pr.barcode && m.set(pr.barcode, pr.id)))
    return m
  }, [catalog])
  const variantsOf = useMemo(() => {
    const m = new Map<number, PosProduct[]>()
    catalog.forEach((p) => p.parentId && m.set(p.parentId, [...(m.get(p.parentId) ?? []), p]))
    return m
  }, [catalog])
  /** Tarjeta del padre: precio "desde" y stock sumado de sus variantes. */
  const cards = useMemo(
    () =>
      catalog
        .filter((p) => p.productType !== 'VARIANTE')
        .map((p) => {
          if (p.productType !== 'PADRE') return p
          const vs = variantsOf.get(p.id) ?? []
          const bases = vs.map((v) => v.presentations.find((x) => x.isBase) ?? v.presentations[0]).filter(Boolean)
          const cheapest = bases.sort((a, b) => a!.price - b!.price)[0]
          const stock = vs.reduce((t, v) => t + (v.trackStock ? v.stock : 0), 0)
          return {
            ...p,
            trackStock: vs.some((v) => v.trackStock),
            stock,
            stockStatus: stock <= 0 ? 'AGOTADO' : vs.some((v) => v.stockStatus === 'BAJO' || v.stockStatus === 'AGOTADO') ? 'BAJO' : 'NORMAL',
            presentations: cheapest ? [cheapest] : [],
          } as PosProduct
        }),
    [catalog, variantsOf],
  )
  const categoryParents = useMemo(() => new Map((data?.categories ?? []).map((c) => [c.id, c.parentId])), [data?.categories])
  const categoryNames = useMemo(() => new Map((data?.categories ?? []).map((c) => [c.id, c.name])), [data?.categories])
  const topCategories = useMemo(() => (data?.categories ?? []).filter((c) => !c.parentId && c.active), [data?.categories])

  const loadAccounts = useCallback(() => {
    if (!navigator.onLine) return
    api<AccountSummary[]>('/pos/accounts').then(setAccounts).catch(() => {})
  }, [])
  useEffect(() => {
    loadAccounts()
    const t = setInterval(loadAccounts, 8000)
    return () => clearInterval(t)
  }, [loadAccounts])

  // Al volver la red: enviar ventas pendientes y refrescar catálogo y cuentas.
  useEffect(() => {
    if (!online || !me) return
    syncPending(me.tenant.id).then((n) => {
      if (n > 0) {
        toast(`${n} ${n === 1 ? 'venta enviada' : 'ventas enviadas'} al servidor`)
        reload()
      }
    })
    loadAccounts()
  }, [online, me, toast, reload, loadAccounts])
  // Reintento periódico: el equipo puede tener red y el servidor estar caído o reiniciándose.
  useEffect(() => {
    if (!me || pending.pending === 0) return
    const t = setInterval(() => {
      syncPending(me.tenant.id).then((n) => n > 0 && toast(`${n} ${n === 1 ? 'venta enviada' : 'ventas enviadas'} al servidor`))
    }, 20000)
    return () => clearInterval(t)
  }, [me, pending.pending, toast])
  useEffect(() => {
    const onSynced = () => reload()
    window.addEventListener(SALES_SYNCED_EVENT, onSynced)
    return () => window.removeEventListener(SALES_SYNCED_EVENT, onSynced)
  }, [reload])

  // Líneas visibles: el carrito local o las líneas de la cuenta abierta.
  const lines: CartLine[] = useMemo(() => {
    if (mode.kind === 'sale') return cart
    return mode.sale.lines.map((l) => {
      const hit = byPresentation.get(l.presentationId)
      return {
        key: String(l.id),
        productId: l.productId,
        presentationId: l.presentationId,
        name: l.productName,
        presentationName: l.presentationName,
        factor: Number(l.factor),
        quantity: Number(l.quantity),
        listPrice: Number(l.listPrice),
        unitPrice: Number(l.unitPrice),
        minPrice: Number(hit?.pr.minPrice ?? 0),
        discountPct: Number(l.discountPct),
        taxRate: Number(l.taxRate),
        fixedTax: Number(hit?.p.fixedTax ?? 0),
        categoryId: hit?.p.categoryId,
        unit: hit?.p.unit ?? 'UNIDAD',
        trackSerials: hit?.p.trackSerials,
        serials: l.serials,
      }
    })
  }, [mode, cart, byPresentation])

  const totals = useMemo(
    () => (data ? calculate(lines, data.settings, data.promotions, categoryParents, 0, 0, scale) : null),
    [lines, data, categoryParents, scale],
  )
  const itemCount = lines.reduce((s, l) => s + l.quantity, 0)

  const visible = useMemo(() => {
    const q = fold(query.trim())
    let list = cards
    if (favorites) list = list.filter((p) => p.favorite)
    if (categoryId) list = list.filter((p) => p.categoryId === categoryId || (p.categoryId && categoryParents.get(p.categoryId) === categoryId))
    if (q) {
      list = list.filter(
        (p) =>
          fold(p.name).includes(q) ||
          p.sku.toLowerCase().includes(q) ||
          fold(p.brand ?? '').includes(q) ||
          p.presentations.some((pr) => pr.barcode?.includes(q)) ||
          (variantsOf.get(p.id) ?? []).some((v) => v.sku.toLowerCase().includes(q) || fold(v.variantLabel ?? '').includes(q) || v.presentations.some((pr) => pr.barcode?.includes(q))),
      )
    }
    return list.slice(0, 120)
  }, [cards, query, categoryId, favorites, categoryParents, variantsOf])

  // ---------------- agregar productos ----------------

  const addPresentation = useCallback(
    async (presentationId: number, quantity = 1, serials?: string[]) => {
      const hit = byPresentation.get(presentationId)
      if (!hit) return
      if (hit.p.trackSerials && !serials) {
        // Cada unidad lleva su serial: se piden antes de agregar.
        setSerialAsk({ presentationId, count: Math.max(1, Math.round(quantity * Number(hit.pr.factor))) })
        return
      }
      if (hit.p.trackStock && hit.p.stock <= 0 && data && !data.settings.allowNegativeStock) {
        toast(`${hit.p.name} está agotado`, 'danger')
        return
      }
      navigator.vibrate?.(20)
      if (mode.kind === 'account') {
        setBusy(true)
        try {
          const sale = await approval.run((approvals) =>
            api<SaleDto>(`/pos/accounts/${mode.sale.id}/lines`, { body: { line: { presentationId, quantity, serials }, approvals } }),
          )
          if (sale) setMode({ kind: 'account', sale })
        } catch (e) {
          toast(e instanceof ApiError ? e.message : 'No se pudo agregar', 'danger')
        } finally {
          setBusy(false)
        }
        return
      }
      setCart((c) => {
        const i = c.findIndex((l) => l.presentationId === presentationId && l.unitPrice === l.listPrice && !l.discountPct)
        if (i >= 0) {
          const next = [...c]
          next[i] = { ...next[i]!, quantity: next[i]!.quantity + quantity, serials: serials ? [...(next[i]!.serials ?? []), ...serials] : next[i]!.serials }
          return next
        }
        return [...c, { ...lineFrom(hit.p, hit.pr, quantity), serials }]
      })
    },
    [byPresentation, mode, approval, toast, data],
  )

  const addProduct = useCallback(
    (p: PosProduct) => {
      if (p.productType === 'PADRE') {
        setVariantPicker(p)
        return
      }
      const base = p.presentations.find((x) => x.isBase) ?? p.presentations[0]
      if (base) addPresentation(base.id)
    },
    [addPresentation],
  )

  /** Lector de código de barras (teclado) o Enter en la búsqueda. */
  const onScan = useCallback(
    (code: string) => {
      const c = code.trim()
      if (!c) return
      const pid = byBarcode.get(c)
      if (pid) {
        addPresentation(pid)
        setQuery('')
        return true
      }
      if (data && has('BASCULA')) {
        const hit = parseScaleCode(c, data.settings, catalog)
        if (hit && 'error' in hit) {
          toast(hit.error, 'danger')
          setQuery('')
          return true
        }
        if (hit) {
          addPresentation(hit.presentationId, hit.quantity)
          setQuery('')
          return true
        }
      }
      return false
    },
    [byBarcode, addPresentation, data, has, catalog, toast],
  )

  // ---------------- atajos de teclado ----------------
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (checkout || receipt || editing || picker) return
      if (e.key === 'F2' || (e.key === '/' && document.activeElement?.tagName !== 'INPUT')) {
        e.preventDefault()
        searchRef.current?.focus()
      } else if (e.key === 'F4' || e.key === 'F9') {
        e.preventDefault()
        if (lines.length) setCheckout(true)
      } else if (e.key === 'Escape' && document.activeElement === searchRef.current) {
        setQuery('')
      } else if ((e.key === '+' || e.key === '-') && document.activeElement?.tagName !== 'INPUT' && lines.length) {
        const last = lines[lines.length - 1]!
        changeQty(last.key, last.quantity + (e.key === '+' ? 1 : -1))
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  // ---------------- editar carrito ----------------

  async function changeQty(key: string, q: number) {
    const current = lines.find((l) => l.key === key)
    if (current?.trackSerials && q > current.quantity) {
      setSerialAsk({ presentationId: current.presentationId, count: Math.max(1, Math.round((q - current.quantity) * current.factor)) })
      return
    }
    if (mode.kind === 'account') {
      setBusy(true)
      try {
        const sale =
          q <= 0
            ? await api<SaleDto>(`/pos/accounts/${mode.sale.id}/lines/${key}`, { method: 'DELETE' })
            : await api<SaleDto>(`/pos/accounts/${mode.sale.id}/lines/${key}`, { method: 'PATCH', body: { quantity: q } })
        setMode({ kind: 'account', sale })
      } catch (e) {
        toast(e instanceof ApiError ? e.message : 'No se pudo cambiar', 'danger')
      } finally {
        setBusy(false)
      }
      return
    }
    setCart((c) =>
      q <= 0
        ? c.filter((l) => l.key !== key)
        : c.map((l) =>
            l.key === key
              ? { ...l, quantity: Math.round(q * 1000) / 1000, serials: l.serials ? l.serials.slice(0, Math.round(q * l.factor)) : l.serials }
              : l,
          ),
    )
  }

  async function patchLine(key: string, patch: Partial<CartLine>) {
    if (mode.kind === 'account') {
      // En la cuenta: precio/descuento nuevos = quitar y volver a agregar con esos valores.
      const l = lines.find((x) => x.key === key)!
      setBusy(true)
      try {
        const changedPrice = patch.unitPrice !== l.unitPrice || patch.discountPct !== l.discountPct
        if (!changedPrice) {
          await changeQty(key, patch.quantity ?? l.quantity)
        } else {
          const sale = await approval.run((approvals) =>
            api<SaleDto>(`/pos/accounts/${mode.sale.id}/lines`, {
              body: {
                line: { presentationId: l.presentationId, quantity: patch.quantity ?? l.quantity, unitPrice: patch.unitPrice, discountPct: patch.discountPct },
                approvals,
              },
            }),
          )
          if (sale) {
            const after = await api<SaleDto>(`/pos/accounts/${mode.sale.id}/lines/${key}`, { method: 'DELETE' })
            setMode({ kind: 'account', sale: after })
          }
        }
      } catch (e) {
        toast(e instanceof ApiError ? e.message : 'No se pudo cambiar', 'danger')
      } finally {
        setBusy(false)
      }
      return
    }
    setCart((c) => c.map((l) => (l.key === key ? { ...l, ...patch } : l)))
  }

  // ---------------- cobro ----------------

  async function showReceipt(sale: SaleDto, provisional: boolean) {
    let r: Receipt | null = null
    if (!provisional) {
      try {
        r = await api<Receipt>(`/pos/sales/${sale.id}/receipt`)
      } catch {
        r = null
      }
    }
    if (!r && data && me) {
      r = {
        sale,
        storeName: me.tenant.name,
        logo: me.tenant.logo,
        header: data.settings.receiptHeader,
        footer: data.settings.receiptFooter,
        widthMm: data.settings.receiptWidthMm,
        showTax: data.settings.receiptShowTax,
        currency,
      }
    }
    if (r) setReceipt({ r, provisional })
  }

  async function submitCheckout(p: CheckoutPayload): Promise<string | null> {
    if (!data || !me) return 'La caja no está lista'
    try {
      if (mode.kind === 'account') {
        const sale = await approval.run((approvals) => api<SaleDto>(`/pos/accounts/${mode.sale.id}/checkout`, { body: { ...p, approvals } }))
        if (!sale) return null
        setCheckout(false)
        setMode({ kind: 'sale' })
        setView('accounts')
        loadAccounts()
        reload()
        await showReceipt(sale, false)
        return null
      }
      const clientRef = newClientRef()
      const payload = {
        clientRef,
        lines: cart.map((l) => ({
          presentationId: l.presentationId,
          quantity: l.quantity,
          unitPrice: l.unitPrice !== l.listPrice ? l.unitPrice : undefined,
          discountPct: l.discountPct || undefined,
          serials: l.serials?.length ? l.serials : undefined,
        })),
        ...p,
      }
      // Tiquete provisional y datos para reenviar: se arman ANTES de enviar, porque la venta se guarda primero en
      // el buzón del equipo (confirmado en disco). Si se va la luz o el internet a mitad del envío, no se pierde.
      const buildOffline = () => {
        const t = calculate(cart, data.settings, data.promotions, categoryParents, p.saleDiscountPct ?? 0, p.saleDiscountAmount ?? 0, scale)
        let remaining = t.total
        let change = 0
        const pays = p.payments.map((x, i) => {
          const applied = Math.min(x.amount, remaining)
          if (x.method === 'EFECTIVO' && x.amount > remaining) change += x.amount - remaining
          remaining -= applied
          return { id: -i - 1, method: x.method, amount: applied, received: x.method === 'EFECTIVO' ? x.amount : undefined, changeGiven: 0, reference: x.reference, status: 'PENDIENTE', createdAt: new Date().toISOString() }
        })
        const now = new Date().toISOString()
        const provisional: SaleDto = {
          id: 0,
          status: 'PAGADA',
          userId: me.id,
          userName: me.fullName,
          customerName: p.customer?.name,
          customerDoc: p.customer?.docNumber,
          subtotal: t.subtotal,
          discountTotal: t.discountTotal,
          taxTotal: t.taxTotal,
          total: t.total,
          paidTotal: t.total,
          changeAmount: change,
          refundedTotal: 0,
          offline: true,
          reviewFlags: [],
          createdAt: now,
          completedAt: now,
          clientRef,
          lines: cart.map((l, i) => ({
            id: -i - 1,
            productId: l.productId,
            presentationId: l.presentationId,
            productName: l.name,
            presentationName: l.presentationName,
            factor: l.factor,
            quantity: l.quantity,
            listPrice: l.listPrice,
            unitPrice: l.unitPrice,
            discountPct: l.discountPct,
            promoName: t.lines[i]!.promoName,
            gross: t.lines[i]!.gross,
            discount: t.lines[i]!.discount,
            taxRate: l.taxRate,
            tax: t.lines[i]!.tax,
            lineTotal: t.lines[i]!.lineTotal,
            returnedQty: 0,
            serials: l.serials,
          })),
          payments: pays,
          returns: [],
        }
        const item: PendingSale = {
          clientRef,
          tenantId: me.tenant.id,
          createdAt: now,
          payload: {
            ...payload,
            offline: true,
            offlineCreatedAt: now,
            offlineShiftId: data.shift?.id,
            lines: cart.map((l) => ({
              presentationId: l.presentationId,
              quantity: l.quantity,
              unitPrice: l.unitPrice,
              discountPct: l.discountPct || undefined,
              serials: l.serials?.length ? l.serials : undefined,
            })),
          },
          receipt: provisional,
          status: 'SENDING',
          error: null,
          attempts: 0,
        }
        return item
      }
      const staged = buildOffline()
      const saved = await enqueueSale(staged)
      const offlineSale = async (): Promise<string | null> => {
        // Sin internet: queda en el buzón con lo que se le cobró al cliente y se envía sola al reconectar.
        if (!saved) return 'Este equipo no permite guardar ventas sin internet (navegador en modo privado).'
        await updateSale(clientRef, { status: 'PENDING' })
        consumeLocal(cart.map((l) => ({ productId: l.productId, baseQty: l.quantity * l.factor })))
        setCart([])
        setCheckout(false)
        setCartOpen(false)
        await showReceipt(staged.receipt, true)
        return null
      }
      if (!navigator.onLine) return await offlineSale()
      try {
        const sale = await approval.run((approvals) => api<SaleDto>('/pos/sales', { body: { ...payload, approvals } }))
        if (!sale) {
          // Se canceló la autorización: la venta no se hizo.
          if (saved) await discardSale(clientRef)
          return null
        }
        if (saved) await confirmSent(staged, sale)
        setCart([])
        setCheckout(false)
        setCartOpen(false)
        reload()
        await showReceipt(sale, false)
        return null
      } catch (e) {
        if (e instanceof ApiError && e.status === 0) return await offlineSale()
        // El servidor la rechazó (stock, precio, datos): no se hizo, sale del buzón.
        if (saved) await discardSale(clientRef)
        throw e
      }
    } catch (e) {
      return e instanceof ApiError ? e.message : 'No se pudo cobrar'
    }
  }

  // ---------------- cuentas y ventas en espera ----------------

  async function openAccount(id: number) {
    try {
      const sale = await api<SaleDto>(`/pos/accounts/${id}`)
      setMode({ kind: 'account', sale })
      setView('sell')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo abrir', 'danger')
      loadAccounts()
    }
  }

  async function createAccount(label: string): Promise<string | null> {
    try {
      const sale = await api<SaleDto>('/pos/accounts', { body: { label } })
      setNewAccount(false)
      setMode({ kind: 'account', sale })
      setView('sell')
      loadAccounts()
      // Si había productos en el carrito, se pasan a la cuenta nueva.
      if (cart.length) {
        let last = sale
        for (const l of cart) {
          last = await api<SaleDto>(`/pos/accounts/${sale.id}/lines`, {
            body: { line: { presentationId: l.presentationId, quantity: l.quantity, serials: l.serials } },
          })
        }
        setCart([])
        setMode({ kind: 'account', sale: last })
      }
      return null
    } catch (e) {
      if (e instanceof ApiError) return e.fields.label ?? e.message
      return 'No se pudo abrir la cuenta'
    }
  }

  async function doCancelAccount() {
    if (mode.kind !== 'account') return
    try {
      const done = await approval.run(async (approvals) => {
        await api(`/pos/accounts/${mode.sale.id}/cancel`, { body: { reason: cancelReason, approvals } })
        return true
      })
      if (!done) return
      setCancelAccount(false)
      setCancelReason('')
      setMode({ kind: 'sale' })
      setView('accounts')
      loadAccounts()
      toast('Cuenta cerrada sin cobrar')
    } catch (e) {
      toast(e instanceof ApiError ? e.fields.reason ?? e.message : 'No se pudo cerrar', 'danger')
    }
  }

  async function holdSale() {
    if (!cart.length) return
    try {
      await api('/pos/holds', {
        body: {
          label: `Venta de ${new Date().toLocaleTimeString('es-CO', { hour: 'numeric', minute: '2-digit' })}`,
          lines: cart.map((l) => ({
            presentationId: l.presentationId,
            quantity: l.quantity,
            unitPrice: l.unitPrice,
            discountPct: l.discountPct || undefined,
            serials: l.serials,
          })),
        },
      })
      setCart([])
      setCartOpen(false)
      toast('Venta guardada en espera')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo guardar', 'danger')
    }
  }

  async function recoverHold(id: number) {
    try {
      const s = await api<SaleDto>(`/pos/holds/${id}/recover`, { method: 'POST' })
      const recovered: CartLine[] = s.lines.flatMap((l) => {
        const hit = byPresentation.get(l.presentationId)
        if (!hit) return []
        return [{ ...lineFrom(hit.p, hit.pr, Number(l.quantity)), unitPrice: Number(l.unitPrice), discountPct: Number(l.discountPct), serials: l.serials }]
      })
      setCart((c) => [...c, ...recovered])
      setHolds(null)
      setMode({ kind: 'sale' })
      setView('sell')
      toast('Venta recuperada')
    } catch (e) {
      toast(e instanceof ApiError ? e.message : 'No se pudo recuperar', 'danger')
    }
  }

  // ---------------- render ----------------

  if (error && !data) return <Banner>{error}</Banner>
  if (!data || !totals) return <Spinner label="Cargando la caja…" />

  const needsShift = data.settings.requireOpenShift && !data.shift
  const editingLine = editing ? lines.find((l) => l.key === editing) : null

  const cartPanel = (
    <div className="flex h-full flex-col" data-guide="pos-carrito">
      <div className="flex items-center justify-between border-b border-line-soft px-4 py-3">
        <div className="min-w-0">
          {mode.kind === 'account' ? (
            <>
              <div className="text-[11px] font-bold tracking-wide text-primary uppercase">Cuenta abierta</div>
              <div className="truncate font-semibold">{mode.sale.accountLabel}</div>
            </>
          ) : (
            <>
              <div className="text-[11px] font-bold tracking-wide text-muted uppercase">Venta en curso</div>
              <div className="num font-semibold">{itemCount ? `${itemCount} productos` : 'Sin productos'}</div>
            </>
          )}
        </div>
        <div className="flex gap-1">
          {mode.kind === 'sale' && cart.length > 0 && (
            <>
              <button type="button" onClick={holdSale} aria-label="Guardar en espera" title="Guardar en espera" className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary-tint text-primary">
                <Icon name="pause" />
              </button>
              <button type="button" onClick={() => setCart([])} aria-label="Vaciar carrito" title="Vaciar" className="flex h-10 w-10 items-center justify-center rounded-xl bg-danger-soft text-danger-ink">
                <Icon name="delete_sweep" />
              </button>
            </>
          )}
          {mode.kind === 'account' && (
            <>
              <button type="button" onClick={() => setCancelAccount(true)} aria-label="Cerrar sin cobrar" title="Cerrar sin cobrar" className="flex h-10 w-10 items-center justify-center rounded-xl bg-danger-soft text-danger-ink">
                <Icon name="cancel" />
              </button>
              <button
                type="button"
                onClick={() => (setMode({ kind: 'sale' }), setView('accounts'), loadAccounts())}
                aria-label="Volver a las cuentas"
                className="flex h-10 items-center gap-1 rounded-xl bg-primary-tint px-3 text-xs font-bold text-primary"
              >
                <Icon name="arrow_back" className="text-[18px]" /> Cuentas
              </button>
            </>
          )}
        </div>
      </div>
      <div className="flex-1 overflow-y-auto px-4">
        <CartList lines={lines} totals={totals} currency={currency} onQty={changeQty} onEdit={setEditing} busy={busy} />
      </div>
      <div className="border-t border-line-soft px-4 pt-3 pb-4">
        {totals.discountTotal > 0 && (
          <div className="num mb-1 flex justify-between text-sm text-success-ink">
            <span>Ahorro</span>
            <span>-{money(totals.discountTotal, currency)}</span>
          </div>
        )}
        <div className="mb-3 flex items-end justify-between">
          <span className="text-[11px] font-bold tracking-wide text-muted uppercase">Total a pagar</span>
          <span className="num text-[32px] leading-none font-extrabold tracking-tight">{money(totals.total, currency)}</span>
        </div>
        <Button size="lg" className="w-full" icon="point_of_sale" disabled={!lines.length || needsShift || busy} onClick={() => setCheckout(true)} data-guide="pos-cobrar">
          {mode.kind === 'account' ? 'Cobrar cuenta' : 'Cobrar'} <span className="ml-1 hidden text-xs opacity-70 lg:inline">(F4)</span>
        </Button>
      </div>
    </div>
  )

  return (
    <div className="lg:-mx-6 lg:-mt-3 lg:grid lg:h-[calc(100dvh-4rem)] lg:grid-cols-[1fr_340px] xl:grid-cols-[1fr_390px]">
      <section className="lg:overflow-y-auto lg:px-6 lg:pt-3 lg:pb-6">
        {/* Avisos de estado */}
        <div className="space-y-2">
          {(!online || fromCache) && (
            <Banner tone="warning">
              Sin internet: sigues vendiendo con el catálogo guardado
              {pending.pending > 0 ? ` · ${pending.pending} ${pending.pending === 1 ? 'venta' : 'ventas'} por enviar` : ''}.
            </Banner>
          )}
          {online && !fromCache && pending.pending > 0 && (
            <Banner tone="primary">
              {pending.pending === 1 ? '1 venta hecha sin internet está' : `${pending.pending} ventas hechas sin internet están`} por enviarse. Se envían solas al volver la conexión.
            </Banner>
          )}
          {pending.errors > 0 && (
            <Banner tone="danger">
              {pending.errors} {pending.errors === 1 ? 'venta sin internet no se pudo enviar' : 'ventas sin internet no se pudieron enviar'}. <Link to="/ventas" className="underline">Revisar</Link>
            </Banner>
          )}
          {needsShift && (
            <div className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-amber-200 bg-warning-soft px-4 py-3 text-sm text-warning-ink">
              <span className="flex items-center gap-2 font-semibold">
                <Icon name="lock" /> Abre tu turno de caja para empezar a vender.
              </span>
              <Link to="/caja">
                <Button size="sm">Abrir turno</Button>
              </Link>
            </div>
          )}
        </div>

        {/* Venta / Cuentas */}
        <div className="mt-2 mb-3 flex gap-1 rounded-xl bg-primary-tint p-1">
          <button
            type="button"
            onClick={() => setView('sell')}
            className={`flex h-10 flex-1 items-center justify-center gap-1.5 rounded-lg text-sm font-semibold ${view === 'sell' ? 'bg-card shadow-tier1' : 'text-muted'}`}
          >
            <Icon name="shopping_cart" className="text-[18px]" /> {mode.kind === 'account' ? mode.sale.accountLabel : 'Venta'}
          </button>
          {has('CUENTAS_ABIERTAS') && (
          <button
            type="button"
            onClick={() => (setView('accounts'), loadAccounts())}
            className={`flex h-10 flex-1 items-center justify-center gap-1.5 rounded-lg text-sm font-semibold ${view === 'accounts' ? 'bg-card shadow-tier1' : 'text-muted'}`}
          >
            <Icon name="receipt_long" className="text-[18px]" /> Cuentas
            <span className="num rounded-full bg-primary-soft px-1.5 text-[11px] text-primary-active">
              {accounts.length}/{data.settings.openAccountsLimit}
            </span>
          </button>
          )}
          <button
            type="button"
            onClick={() => api<SaleDto[]>('/pos/holds').then(setHolds).catch(() => toast('Necesitas internet para ver las ventas en espera', 'danger'))}
            className="flex h-10 items-center justify-center gap-1 rounded-lg px-3 text-sm font-semibold text-muted"
            aria-label="Ventas en espera"
          >
            <Icon name="pause_circle" className="text-[18px]" /> <span className="hidden sm:inline">En espera</span>
          </button>
        </div>

        {view === 'accounts' ? (
          <AccountsBoard accounts={accounts} limit={data.settings.openAccountsLimit} currency={currency} offline={!online} onOpen={openAccount} onNew={() => setNewAccount(true)} />
        ) : (
          <>
            <div className="mb-2 flex items-center gap-1.5" data-guide="pos-buscar">
              <div className="relative flex-1">
                <Icon name="search" className="pointer-events-none absolute top-1/2 left-3 -translate-y-1/2 text-[22px] text-muted" />
                <input
                  ref={searchRef}
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault()
                      if (onScan(query)) return
                      if (visible.length === 1) {
                        addProduct(visible[0]!)
                        setQuery('')
                      } else if (/^\d{6,}$/.test(query.trim())) {
                        toast(`No hay ningún producto con el código ${query.trim()}`, 'danger')
                      }
                    }
                  }}
                  placeholder="Buscar producto, marca o código… (F2)"
                  className="h-12 w-full rounded-xl bg-card pr-3 pl-10 text-base shadow-tier1 ring-1 ring-line outline-none focus:ring-2 focus:ring-primary"
                  aria-label="Buscar producto o escanear código"
                  autoComplete="off"
                />
              </div>
              <button type="button" onClick={() => setCamera(true)} aria-label="Escanear con la cámara" className="flex h-12 w-12 items-center justify-center rounded-xl bg-primary text-white shadow-tier1 active:scale-95">
                <Icon name="barcode_scanner" className="text-[24px]" />
              </button>
              <button
                type="button"
                onClick={() => setFavorites((f) => !f)}
                aria-label="Solo favoritos"
                aria-pressed={favorites}
                className={`flex h-12 w-11 items-center justify-center rounded-xl shadow-tier1 ring-1 ring-line ${favorites ? 'bg-warning-soft text-warning' : 'bg-card text-muted'}`}
              >
                <Icon name="star" fill={favorites} className="text-[22px]" />
              </button>
            </div>
            <div className="no-scrollbar -mx-4 mb-3 flex gap-2 overflow-x-auto px-4 lg:mx-0 lg:px-0" data-guide="pos-categorias">
              <button
                type="button"
                onClick={() => setCategoryId(null)}
                className={`flex h-9 shrink-0 items-center gap-1.5 rounded-full px-3.5 text-xs font-semibold shadow-tier1 ${!categoryId ? 'bg-primary text-white' : 'bg-card text-muted'}`}
              >
                <Icon name="grid_view" className="text-[16px]" /> Todos
                <span className="num rounded-full bg-white/20 px-1.5 text-[10px]">{cards.length}</span>
              </button>
              {topCategories.map((c) => (
                <button
                  key={c.id}
                  type="button"
                  onClick={() => setCategoryId(c.id === categoryId ? null : c.id)}
                  className={`h-9 shrink-0 rounded-full px-3.5 text-xs font-semibold shadow-tier1 ${categoryId === c.id ? 'bg-primary text-white' : 'bg-card text-muted'}`}
                >
                  {c.name}
                </button>
              ))}
            </div>
            {catalog.length === 0 ? (
              <div className="rounded-2xl bg-card p-8 text-center shadow-tier1">
                <Icon name="inventory_2" className="text-[40px] text-primary" />
                <p className="mt-2 font-semibold">Aún no hay productos para vender</p>
                <Link to="/inventario" className="mt-3 inline-block">
                  <Button>Crear productos</Button>
                </Link>
              </div>
            ) : (
              <div className="grid grid-cols-2 gap-2.5 pb-32 sm:grid-cols-[repeat(auto-fill,minmax(165px,1fr))] lg:pb-0" data-guide="pos-catalogo">
                {visible.map((p) => (
                  <ProductCard
                    key={p.id}
                    p={p}
                    currency={currency}
                    categoryName={p.categoryId ? categoryNames.get(p.categoryId) : undefined}
                    topSeller={p.favorite}
                    onAdd={addProduct}
                    onPick={setPicker}
                  />
                ))}
              </div>
            )}
          </>
        )}
      </section>

      {/* Carrito: panel fijo en PC */}
      <aside className="hidden border-l border-line bg-card lg:block">{cartPanel}</aside>

      {/* Carrito: barra inferior + hoja en celular */}
      {view === 'sell' && (
        <div className="fixed inset-x-0 bottom-16 z-30 px-3 pb-2 lg:hidden">
          <button
            type="button"
            onClick={() => setCartOpen(true)}
            data-guide="pos-carrito"
            className="flex w-full items-center gap-3 rounded-2xl bg-card/95 px-4 py-3 shadow-tier3 ring-1 ring-line backdrop-blur-xl"
          >
            <span className="num flex h-8 w-8 items-center justify-center rounded-full bg-primary text-sm font-bold text-white">{Math.round(itemCount)}</span>
            <span className="flex-1 text-left">
              <span className="block text-sm font-semibold">{mode.kind === 'account' ? mode.sale.accountLabel : 'Venta en curso'}</span>
              <span className="text-xs text-primary">Ver detalle</span>
            </span>
            <span className="text-right">
              <span className="block text-[10px] font-bold tracking-wide text-muted uppercase">Total a pagar</span>
              <span className="num text-[22px] leading-none font-extrabold">{money(totals.total, currency)}</span>
            </span>
          </button>
        </div>
      )}
      {cartOpen && (
        <div className="fixed inset-0 z-50 bg-navy/60 lg:hidden" onClick={() => setCartOpen(false)}>
          <div className="pb-safe absolute inset-x-0 bottom-0 h-[85dvh] rounded-t-3xl bg-card shadow-tier3" onClick={(e) => e.stopPropagation()}>
            <div className="mx-auto mt-2 h-1.5 w-12 rounded-full bg-line" />
            {cartPanel}
          </div>
        </div>
      )}

      {picker && (
        <PresentationPicker
          p={picker}
          currency={currency}
          onClose={() => setPicker(null)}
          onPick={(id) => {
            addPresentation(id)
            setPicker(null)
          }}
        />
      )}
      {variantPicker && (
        <VariantPicker
          parent={variantPicker}
          variants={variantsOf.get(variantPicker.id) ?? []}
          currency={currency}
          allowNegative={data.settings.allowNegativeStock}
          onClose={() => setVariantPicker(null)}
          onPick={(v) => {
            setVariantPicker(null)
            addProduct(v)
          }}
        />
      )}
      {serialAsk && byPresentation.get(serialAsk.presentationId) && (
        <SerialPrompt
          product={byPresentation.get(serialAsk.presentationId)!.p}
          count={serialAsk.count}
          taken={lines.flatMap((l) => l.serials ?? [])}
          online={online}
          onClose={() => setSerialAsk(null)}
          onDone={(serials) => {
            const f = Number(byPresentation.get(serialAsk.presentationId)!.pr.factor) || 1
            setSerialAsk(null)
            addPresentation(serialAsk.presentationId, serials.length / f, serials)
          }}
        />
      )}
      {editingLine && (
        <LineEditor
          line={editingLine}
          weigh={
            has('BASCULA') && scaleSupported() && editingLine.unit !== 'UNIDAD'
              ? async () => {
                  const kg = await readScaleWeight()
                  return editingLine.unit === 'GRAMO' ? kg * 1000 : editingLine.unit === 'LIBRA' ? kg / 0.453592 : kg
                }
              : undefined
          }
          currency={currency}
          maxDiscount={data.settings.maxDiscountPct}
          onClose={() => setEditing(null)}
          onRemove={() => {
            changeQty(editingLine.key, 0)
            setEditing(null)
          }}
          onSave={(patch) => {
            patchLine(editingLine.key, patch)
            setEditing(null)
          }}
        />
      )}
      {checkout && (
        <CheckoutModal
          lines={lines}
          settings={data.settings}
          promotions={data.promotions}
          categoryParents={categoryParents}
          currency={currency}
          scale={scale}
          title={mode.kind === 'account' ? `Cobrar cuenta · ${mode.sale.accountLabel}` : 'Cobrar venta'}
          initialCustomer={mode.kind === 'account' ? { name: mode.sale.customerName, docNumber: mode.sale.customerDoc, customerId: mode.sale.customerId } : undefined}
          offline={!online && mode.kind === 'sale'}
          onClose={() => setCheckout(false)}
          onSubmit={submitCheckout}
        />
      )}
      {receipt && (
        <Modal
          open
          title={receipt.provisional ? 'Venta guardada sin internet' : `Venta #${receipt.r.sale.number} cobrada`}
          onClose={() => setReceipt(null)}
          footer={
            <>
              <Button variant="secondary" icon="print" className="flex-1" onClick={() => window.print()}>
                Imprimir
              </Button>
              <Button className="flex-1" icon="add_shopping_cart" onClick={() => (setReceipt(null), searchRef.current?.focus())} autoFocus>
                Nueva venta
              </Button>
            </>
          }
        >
          <div className="space-y-3">
            {receipt.r.sale.changeAmount > 0 && (
              <div className="flex items-center justify-between rounded-2xl bg-success-soft px-4 py-3">
                <span className="font-semibold text-success-ink">Entregar cambio</span>
                <span className="num text-[28px] font-extrabold text-success-ink">{money(receipt.r.sale.changeAmount, currency)}</span>
              </div>
            )}
            <div className="rounded-xl bg-line-soft p-3">
              <ReceiptView r={receipt.r} provisional={receipt.provisional} />
            </div>
          </div>
        </Modal>
      )}
      <PrintArea r={receipt?.r ?? null} provisional={receipt?.provisional} />
      {camera && (
        <CameraScanner
          onClose={() => setCamera(false)}
          onCode={(code) => {
            if (!onScan(code)) toast(`No hay ningún producto con el código ${code}`, 'danger')
          }}
        />
      )}
      {newAccount && <NewAccountModal onClose={() => setNewAccount(false)} onCreate={createAccount} />}
      {holds && <HoldsModal holds={holds} currency={currency} onClose={() => setHolds(null)} onRecover={recoverHold} />}
      <Confirm
        open={cancelAccount}
        danger
        title="Cerrar la cuenta sin cobrar"
        confirmLabel="Cerrar cuenta"
        onClose={() => setCancelAccount(false)}
        onConfirm={doCancelAccount}
        text={
          <div className="space-y-3">
            <p>Los productos no se descuentan del inventario. Si la cuenta tiene productos, queda registrada como anulación.</p>
            {lines.length > 0 && <Field label="Motivo" value={cancelReason} onChange={(e) => setCancelReason(e.target.value)} />}
          </div>
        }
      />
      {approval.modal}
    </div>
  )
}
