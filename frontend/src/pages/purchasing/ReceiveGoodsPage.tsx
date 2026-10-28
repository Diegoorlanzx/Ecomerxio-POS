import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Card, Chip, Icon, Spinner, useToast } from '../../components/ui'
import { api } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { money, shortDate } from '../../lib/format'
import { ORDER_STATUS, type PurchaseOrder, type Supplier } from '../../lib/purchasingTypes'
import { DirectReceiptModal, ReceiveModal } from './OrderDialogs'
import { ImportInvoiceModal } from './SupplierInvoicePages'

/**
 * Un solo lugar para meter mercancía al inventario, se haya pedido o no: llegó un pedido que hice, llegó sin pedido,
 * o tengo la factura electrónica del proveedor. Antes esto estaba repartido dentro de Compras.
 */
export default function ReceiveGoodsPage() {
  const { can } = useAuth()
  const toast = useToast()
  const [suppliers, setSuppliers] = useState<Supplier[]>([])
  const [pending, setPending] = useState<PurchaseOrder[] | null>(null)
  const [mode, setMode] = useState<'direct' | 'xml' | null>(null)
  const [order, setOrder] = useState<PurchaseOrder | null>(null)

  const load = useCallback(() => {
    Promise.all([api<PurchaseOrder[]>('/purchases?status=APROBADA'), api<PurchaseOrder[]>('/purchases?status=PARCIAL')])
      .then(([a, p]) => setPending([...p, ...a]))
      .catch(() => setPending([]))
  }, [])

  useEffect(() => {
    api<Supplier[]>('/suppliers').then(setSuppliers).catch(() => {})
    load()
  }, [load])

  const done = (msg: string) => {
    setMode(null)
    setOrder(null)
    toast(msg)
    load()
  }

  return (
    <div className="mx-auto max-w-4xl space-y-5">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Ingresar mercancía</h1>
        <p className="text-sm text-muted">Lo que registres aquí suma al inventario, actualiza el costo y queda como cuenta por pagar al proveedor.</p>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        <Option
          icon="inventory"
          title="Llegó sin pedido"
          text="El proveedor trajo mercancía y no hiciste orden en el sistema. Escanea o busca cada producto."
          onClick={() => setMode('direct')}
        />
        <Option
          icon="upload_file"
          title="Tengo la factura electrónica"
          text="Sube el XML o ZIP que te mandó el proveedor y los productos se llenan solos."
          onClick={() => setMode('xml')}
        />
        <Option
          icon="fact_check"
          title="Llegó un pedido que hice"
          text="Elige abajo la orden de compra y anota lo que llegó, lo que faltó o vino dañado."
          onClick={() => document.getElementById('pedidos')?.scrollIntoView({ behavior: 'smooth' })}
        />
      </div>

      <section id="pedidos" className="space-y-2">
        <h2 className="flex items-center gap-2 font-semibold">
          <Icon name="local_shipping" className="text-[20px] text-muted" />
          Pedidos por llegar
        </h2>
        {!pending ? (
          <Spinner />
        ) : pending.length === 0 ? (
          <Card className="p-4 text-sm text-muted">
            No tienes pedidos aprobados esperando mercancía.
            {can('COMPRAS_CREAR') && (
              <>
                {' '}
                Si quieres pedirle a un proveedor, hazlo en{' '}
                <Link to="/compras" className="font-semibold text-primary">
                  Compras
                </Link>
                .
              </>
            )}
          </Card>
        ) : (
          <ul className="space-y-2">
            {pending.map((o) => {
              const st = ORDER_STATUS[o.status]
              return (
                <li key={o.id}>
                  <button
                    type="button"
                    onClick={() => setOrder(o)}
                    className="flex w-full items-center gap-3 rounded-2xl border border-line bg-card p-4 text-left shadow-tier1 transition hover:border-primary/40"
                  >
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="num font-bold">Pedido #{o.number}</span>
                        <Chip tone={st.tone}>{st.label}</Chip>
                        {o.late && <Chip tone="danger">Atrasado</Chip>}
                      </div>
                      <div className="truncate font-semibold">{o.supplierName}</div>
                      <div className="text-xs text-muted">
                        {o.lines.length} producto{o.lines.length === 1 ? '' : 's'}
                        {o.expectedDate ? ` · llega ${shortDate(o.expectedDate)}` : ''}
                        {o.estimatedTotal ? ` · ${money(o.estimatedTotal)}` : ''}
                      </div>
                    </div>
                    <span className="flex items-center gap-1 text-sm font-semibold text-primary">
                      Recibir <Icon name="chevron_right" />
                    </span>
                  </button>
                </li>
              )
            })}
          </ul>
        )}
      </section>

      <p className="text-xs text-muted">
        ¿Solo quieres corregir una cantidad que no viene de un proveedor (un sobrante, un regalo)? Usa el ajuste de existencias en{' '}
        <Link to="/inventario" className="font-semibold text-primary">
          Inventario
        </Link>
        .
      </p>

      {mode === 'direct' && <DirectReceiptModal suppliers={suppliers} onClose={() => setMode(null)} onDone={() => done('Mercancía recibida e inventario actualizado')} />}
      {mode === 'xml' && (
        <ImportInvoiceModal suppliers={suppliers} onClose={() => setMode(null)} onDone={(inv) => done(`Factura ${inv.invoiceNumber} recibida: inventario y cuenta por pagar actualizados`)} />
      )}
      {order && (
        <ReceiveModal
          order={order}
          onClose={() => setOrder(null)}
          onDone={(x) => done(x.status === 'RECIBIDA' ? `Pedido #${x.number} recibido completo` : `Pedido #${x.number}: recepción parcial registrada`)}
        />
      )}
    </div>
  )
}

function Option({ icon, title, text, onClick }: { icon: string; title: string; text: string; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="flex flex-col items-start gap-2 rounded-2xl border border-line bg-card p-4 text-left shadow-tier1 transition hover:border-primary/40 hover:shadow-tier2">
      <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary-tint text-primary">
        <Icon name={icon} className="text-[24px]" />
      </span>
      <span className="font-semibold">{title}</span>
      <span className="text-sm text-muted">{text}</span>
    </button>
  )
}
