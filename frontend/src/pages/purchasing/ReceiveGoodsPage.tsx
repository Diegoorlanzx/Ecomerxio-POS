import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Card, Chip, Icon, InfoTip, Spinner, useToast } from '../../components/ui'
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
        <h1 className="flex items-center gap-2 text-2xl font-bold tracking-tight">
          Ingresar mercancía
          <InfoTip
            title="Ingresar mercancía"
            text="Aquí registras lo que te llega de un proveedor. Elige la opción que corresponde: sin pedido, con la factura electrónica (XML) o un pedido que ya habías hecho en Compras."
          />
        </h1>
        <p className="text-sm text-muted">Lo que registres aquí suma al inventario, actualiza el costo y queda como cuenta por pagar al proveedor.</p>
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        <Option
          icon="inventory"
          title="Llegó sin pedido"
          text="El proveedor trajo mercancía y no hiciste orden en el sistema. Escanea o busca cada producto."
          tip="Lo más común en tienda de barrio: llega el distribuidor y tú anotas qué dejó, cuántas unidades y a qué costo. Se suma al inventario y queda la cuenta por pagar."
          onClick={() => setMode('direct')}
        />
        <Option
          icon="upload_file"
          title="Tengo la factura electrónica"
          text="Sube el XML o ZIP que te mandó el proveedor y los productos se llenan solos."
          tip="El archivo que llega al correo con la factura electrónica (XML o ZIP). El sistema lee productos, cantidades y costos; solo revisas que cada uno quede emparejado con tu producto."
          onClick={() => setMode('xml')}
        />
        <Option
          icon="fact_check"
          title="Llegó un pedido que hice"
          text="Elige abajo la orden de compra y anota lo que llegó, lo que faltó o vino dañado."
          tip="Para cuando hiciste el pedido en Compras. Comparas lo pedido con lo que llegó; si faltó algo, el pedido queda parcial y puedes recibir el resto después."
          onClick={() => document.getElementById('pedidos')?.scrollIntoView({ behavior: 'smooth' })}
        />
      </div>

      <section id="pedidos" className="space-y-2">
        <h2 className="flex items-center gap-2 font-semibold">
          <Icon name="local_shipping" className="text-[20px] text-muted" />
          Pedidos por llegar
          <InfoTip text="Pedidos de Compras ya aprobados que todavía no llegan completos. Toca uno para registrar lo que llegó." />
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

function Option({ icon, title, text, tip, onClick }: { icon: string; title: string; text: string; tip: string; onClick: () => void }) {
  return (
    <div className="relative">
      <button type="button" onClick={onClick} className="flex h-full w-full flex-col items-start gap-2 rounded-2xl border border-line bg-card p-4 text-left shadow-tier1 transition hover:border-primary/40 hover:shadow-tier2">
        <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary-tint text-primary">
          <Icon name={icon} className="text-[24px]" />
        </span>
        <span className="pr-6 font-semibold">{title}</span>
        <span className="text-sm text-muted">{text}</span>
      </button>
      {/* Fuera del botón: un botón no puede ir dentro de otro. */}
      <InfoTip title={title} text={tip} className="absolute top-3 right-3" />
    </div>
  )
}
