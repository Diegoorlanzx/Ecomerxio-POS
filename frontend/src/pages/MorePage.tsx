import { Link } from 'react-router-dom'
import { Chip, Icon, SectionLabel, InfoTip } from '../components/ui'
import { useAuth } from '../lib/auth'
import { useInstall } from '../lib/pwa'
import { APP_NAME } from '../lib/brand'
import { asset } from '../lib/host'

interface Tile {
  label: string
  /** Qué hace el módulo (se ve con la (i) de la tarjeta). */
  help?: string
  text: string
  icon: string
  to?: string
  phase?: number
  /** Solo si la tienda usa este módulo. */
  module?: string
  /** Solo si el asistente con IA está encendido en la plataforma. */
  needsAssistant?: boolean
  show: (can: (p: string) => boolean) => boolean
}

const SECTIONS: { title: string; tiles: Tile[] }[] = [
  {
    title: 'Administración',
    tiles: [
      { label: 'Usuarios', help: "Crea una cuenta para cada empleado: así sabes quién vendió, anuló o ajustó. Desde aquí cambias su rol, restableces su contraseña o lo desactivas cuando se va.", text: 'Empleados y accesos', icon: 'group', to: '/usuarios', show: (c) => c('USUARIOS_GESTIONAR') },
      { label: 'Roles y permisos', help: "Define qué puede hacer cada cargo: vender, anular, ver costos, aprobar compras, ver contabilidad… Hay roles listos y puedes crear los tuyos.", text: 'Qué puede hacer cada cargo', icon: 'admin_panel_settings', to: '/roles', show: (c) => c('ROLES_GESTIONAR') || c('USUARIOS_GESTIONAR') },
      { label: 'Actividad', help: "Registro de inicios de sesión y cambios importantes (precios, anulaciones, ajustes de caja), con quién y cuándo. Sirve para auditar.", text: 'Registro de accesos y cambios', icon: 'history', to: '/actividad', show: (c) => c('AUDITORIA_VER') },
      { label: 'Tipo de negocio', help: "Elige qué tipo de negocio tienes y enciende o apaga módulos (fiado, compras, báscula, seriales, tienda en línea…). Lo apagado desaparece del menú.", text: 'Módulos que usa tu tienda', icon: 'category', to: '/negocio', show: () => true },
      { label: 'Datos de la tienda', help: "Nombre, logo, dirección, teléfono, moneda y zona horaria: lo que sale en el comprobante y en la tienda en línea.", text: 'Logo, contacto, moneda y zona', icon: 'storefront', to: '/configuracion', show: () => true },
      { label: 'Ajustes de caja', help: "Cómo funciona la caja: impuestos, medios de pago, comprobante, reglas (PIN de supervisor, descuentos máximos, cuentas abiertas) y las cajas de la tienda.", text: 'Impuestos, comprobante, pagos y cajas', icon: 'tune', to: '/ajustes-pos', show: () => true },
      { label: 'Copias de seguridad', help: "Copia automática cada noche de toda la información de la tienda. Puedes hacer una manual, restaurar (solo el dueño) o descargar todo en CSV.", text: 'Respaldos, restauración y exportar datos', icon: 'backup', to: '/respaldos', show: (c) => c('CONFIG_EDITAR') },
      { label: 'Integraciones', help: "Conecta correo, WhatsApp y webhooks para recibir avisos, y pasarelas de pago para confirmar pagos digitales solos.", text: 'Correo, webhook, WhatsApp y pagos', icon: 'hub', to: '/notificaciones?tab=config', show: (c) => c('CONFIG_EDITAR') },
      { label: 'Legal y privacidad', help: "Términos y condiciones, licencia de uso, política de servicio, encargo de datos y política de privacidad vigentes, y el registro de quién los aceptó.", text: 'Términos, licencia y tus datos', icon: 'gavel', to: '/legal-y-privacidad', show: () => true },
      { label: 'Mi cuenta', help: "Tu contraseña, tu PIN de autorización, las sesiones abiertas y tus preferencias (como mostrar u ocultar estas ayudas).", text: 'Contraseña y sesiones', icon: 'manage_accounts', to: '/perfil', show: () => true },
    ],
  },
  {
    title: 'Operación',
    tiles: [
      { label: 'Mesas', help: "Plano del restaurante: abre una mesa, toma el pedido con modificadores, manda la comanda a cocina, imprime la precuenta, divide la cuenta y cobra con propina.", text: 'Plano de mesas: libres, ocupadas y por cobrar', icon: 'table_restaurant', to: '/mesas', module: 'RESTAURANTE', show: (c) => c('POS_VENDER') },
      { label: 'Pantalla de cocina', help: "Las comandas llegan solas a la cocina o al bar: se tocan para pasar de pendiente a preparando y a listo. El mesero ve en el plano qué está listo para llevar.", text: 'Comandas de cocina y bar en tiempo real', icon: 'soup_kitchen', to: '/cocina', module: 'RESTAURANTE', show: (c) => c('POS_VENDER') },
      { label: 'Configurar restaurante', help: "Crea salones y mesas, los modificadores de los platos (término, adiciones, sin…), a qué estación va cada categoría y el porcentaje de propina sugerida.", text: 'Mesas, modificadores, cocina y propina', icon: 'settings', to: '/mesas/configurar', module: 'RESTAURANTE', show: (c) => c('CONFIG_EDITAR') || c('PRODUCTOS_EDITAR') },
      { label: 'Agenda', help: "Agenda del día por profesional: agenda citas sin cruces, manda el recordatorio por WhatsApp, recibe citas de la tienda en línea y pasa la cita a la caja para cobrar servicio y productos.", text: 'Citas por profesional y recordatorios', icon: 'calendar_month', to: '/agenda', module: 'SERVICIOS', show: (c) => c('POS_VENDER') },
      { label: 'Órdenes de servicio', help: "Recibe un equipo con su estado y accesorios, diagnostica, manda la cotización por WhatsApp, agrega repuestos del inventario, recibe anticipos y entrégalo cobrando el saldo con garantía.", text: 'Equipos en reparación, repuestos y anticipos', icon: 'build', to: '/ordenes-servicio', module: 'SERVICIOS', show: (c) => c('POS_VENDER') },
      { label: 'Comisiones', help: "Lo que gana cada profesional por sus servicios y productos vendidos en la quincena, con el detalle por venta. Al pagarla queda como gasto de nómina.", text: 'Liquidación quincenal por profesional', icon: 'percent', to: '/comisiones', module: 'SERVICIOS', show: (c) => c('FINANZAS_VER') },
      { label: 'Configurar servicios', help: "Crea a tus profesionales con su horario y comisión, define los servicios con su duración y activa las citas desde la tienda en línea.", text: 'Profesionales, horarios, servicios y citas en línea', icon: 'settings', to: '/servicios/configurar', module: 'SERVICIOS', show: (c) => c('CONFIG_EDITAR') || c('PRODUCTOS_EDITAR') },
      { label: 'Pedidos en línea', help: "Bandeja de los pedidos que llegan de tu tienda en línea: acéptalos, prepáralos, despáchalos y cóbralos en la caja.", text: 'Bandeja de pedidos de tu tienda en línea', icon: 'shopping_bag', to: '/pedidos', module: 'TIENDA_ONLINE', show: (c) => c('POS_VENDER') },
      { label: 'Tienda en línea', help: "Tu vitrina en internet: qué productos se ven, domicilios por zona, horario, formas de pago y el cartel con QR para que te pidan.", text: 'Vitrina, domicilios, horario y cartel QR', icon: 'storefront', to: '/tienda-online', module: 'TIENDA_ONLINE', show: (c) => c('CONFIG_EDITAR') || c('PRODUCTOS_EDITAR') },
      { label: 'Cotizaciones y prefacturas', help: "Cotiza con vigencia y compártela por WhatsApp para que el cliente la acepte con un clic. Pásala a prefactura para revisar los datos (NIT, DV, correo, impuestos, stock) antes de facturar y cobrarla en la caja.", text: 'Cotiza, valida y factura sin errores', icon: 'request_quote', to: '/cotizaciones', show: (c) => c('COTIZACIONES_VER') },
      { label: 'Historial de ventas', help: "Todas las ventas con su comprobante. Busca una, reimprímela, envíala por WhatsApp, anúlala o haz una devolución.", text: 'Comprobantes, anulaciones y devoluciones', icon: 'receipt_long', to: '/ventas', show: (c) => c('POS_VENDER') || c('POS_HISTORIAL') || c('REPORTES_VER') },
      { label: 'Promociones', help: "Crea 3x2, combos y descuentos por producto o categoría, con fechas y días. Se aplican solos en la caja.", text: '3x2, combos y descuentos', icon: 'local_offer', to: '/promociones', show: (c) => c('PRODUCTOS_PRECIOS') },
      { label: 'Movimientos', help: "Cada entrada y salida de inventario (ventas, compras, devoluciones, ajustes, conteos) con quién la hizo y por qué.", text: 'Entradas y salidas de inventario', icon: 'swap_vert', to: '/movimientos', show: (c) => c('INVENTARIO_VER') },
      { label: 'Proveedores', help: "A quién le compras: contactos, productos y precios, tiempos de entrega, cumplimiento y lo que les debes.", text: 'Contactos, precios y entregas', icon: 'local_shipping', to: '/proveedores', module: 'COMPRAS', show: (c) => c('PROVEEDORES_VER') || c('COMPRAS_VER') },
      { label: 'Compras', help: "Pedidos sugeridos según lo que vendes, órdenes de compra y recepción de la mercancía (actualiza stock y costos).", text: 'Pedidos y reposición automática', icon: 'shopping_cart_checkout', to: '/compras', module: 'COMPRAS', show: (c) => c('COMPRAS_VER') || c('COMPRAS_RECIBIR') },
      { label: 'Garantías y seriales', help: "Busca un IMEI o serial vendido: a quién se le vendió, cuándo y si sigue en garantía.", text: 'Buscar un IMEI o serial vendido', icon: 'verified_user', to: '/garantias', module: 'SERIALES', show: (c) => c('POS_VENDER') || c('INVENTARIO_VER') || c('PRODUCTOS_VER') },
      { label: 'Etiquetas de báscula', help: "Imprime etiquetas con código de barras de peso o precio para productos que se venden por kilo.", text: 'Imprimir etiquetas de peso o precio', icon: 'scale', to: '/etiquetas-bascula', module: 'BASCULA', show: (c) => c('PRODUCTOS_VER') || c('INVENTARIO_VER') },
      { label: 'Pagos digitales', help: "Confirma que llegaron los pagos por Nequi, Daviplata, Bre-B y transferencias (también los abonos de fiado), y concílialos con el extracto del banco.", text: 'Verificar y conciliar pagos', icon: 'qr_code_2', to: '/pagos', show: (c) => c('PAGOS_VERIFICAR') || c('PAGOS_CONCILIAR') },
      { label: 'Facturación electrónica', help: "Factura y documento POS electrónico ante la DIAN: datos fiscales, resoluciones de numeración, documentos emitidos, notas crédito y errores.", text: 'Datos fiscales, resoluciones y clientes', icon: 'receipt_long', to: '/facturacion', show: (c) => c('FACTURACION_CONFIGURAR') },
      { label: 'Contabilidad', help: "Contabilidad automática con partida doble: cada venta, anulación y devolución genera su asiento. Libro diario y balance de prueba.", text: 'Asientos automáticos, libro diario y balance', icon: 'menu_book', to: '/contabilidad', show: (c) => c('CONTABILIDAD_VER') },
      { label: 'Gastos y finanzas', help: "Gastos e ingresos, cuentas por pagar a proveedores, flujo de caja y utilidad del mes.", text: 'Flujo de caja, utilidad y por pagar', icon: 'account_balance_wallet', to: '/finanzas', show: (c) => c('FINANZAS_VER') || c('FINANZAS_GASTOS') },
      { label: 'Fiados', help: "Clientes que compran a crédito: cuánto debe cada uno, vencimientos, abonos, recordatorios por WhatsApp y el cuadre de fiados.", text: 'Clientes que pagan después', icon: 'menu_book', to: '/fiados', module: 'FIADO', show: (c) => c('FIADO_GESTIONAR') || c('FIADO_AUTORIZAR') },
    ],
  },
  {
    title: 'Análisis',
    tiles: [
      { label: 'Analítica de ventas', help: "Informes de ventas por día, hora, producto, categoría, empleado y medio de pago, comparados con el período anterior.", text: 'Informes por período', icon: 'monitoring', to: '/analitica', show: (c) => c('REPORTES_VER') },
      { label: 'Rentabilidad', help: "Cuánto te deja de verdad cada producto y categoría, después del costo y los descuentos.", text: 'Cuánto deja cada producto', icon: 'trending_up', to: '/rentabilidad', show: (c) => c('COSTOS_VER') },
      { label: 'Notificaciones', help: "Todos los avisos (stock, compras, pagos, caja, fiados) y la configuración de qué avisos recibir y por dónde.", text: 'Avisos, umbrales y reportes', icon: 'notifications_active', to: '/notificaciones', show: () => true },
      { label: 'Asistente IA', help: "Pregúntale a tus datos en lenguaje normal: \"¿cuánto vendí esta semana?\", \"¿qué le pido a Postobón?\".", text: 'Pregúntale a tus datos', icon: 'auto_awesome', to: '/asistente', show: (c) => c('ASISTENTE_IA'), needsAssistant: true },
      { label: 'Recomendaciones', help: "Sugerencias de precios y promociones según márgenes y rotación. Nada cambia sin tu aprobación.", text: 'Precios y promociones para aprobar', icon: 'sell', to: '/recomendaciones', show: (c) => c('COSTOS_VER') || c('PRODUCTOS_PRECIOS') },
      { label: 'Guía interactiva', help: "Recorridos paso a paso por cada pantalla y el asistente para dejar la tienda lista para vender.", text: 'Tutoriales y asistente de configuración', icon: 'school', to: '/guia', show: () => true },
    ],
  },
]

export default function MorePage() {
  const { me, can, has } = useAuth()
  return (
    <div className="space-y-6">
      <PlanCard />
      <InstallCard />
      {SECTIONS.map((s) => {
        const tiles = s.tiles.filter((t) => t.show(can) && (!t.module || has(t.module)) && (!t.needsAssistant || !!me?.assistantEnabled))
        if (!tiles.length) return null
        return (
          <section key={s.title}>
            <SectionLabel>{s.title}</SectionLabel>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
              {tiles.map((t) => {
                const inner = (
                  <>
                    <div className={`mb-3 flex h-11 w-11 items-center justify-center rounded-xl ${t.to ? 'bg-primary-tint text-primary' : 'bg-line-soft text-muted'}`}>
                      <Icon name={t.icon} className="text-[24px]" />
                    </div>
                    <div className="flex items-start gap-1 text-sm font-semibold">
                      {t.label}
                      {t.help && <InfoTip title={t.label} text={t.help} className="-my-0.5" />}
                    </div>
                    <div className="mt-0.5 text-xs text-muted">{t.text}</div>
                    {t.phase && (
                      <div className="mt-2">
                        <Chip icon="schedule">Fase {t.phase}</Chip>
                      </div>
                    )}
                  </>
                )
                return t.to ? (
                  <Link key={t.label} to={t.to} className="rounded-2xl border border-line bg-card p-4 shadow-tier1 transition hover:border-primary/40 active:scale-[0.98]">
                    {inner}
                  </Link>
                ) : (
                  <div key={t.label} aria-disabled className="rounded-2xl border border-dashed border-line bg-card/60 p-4">
                    {inner}
                  </div>
                )
              })}
            </div>
          </section>
        )
      })}
    </div>
  )
}

/** Invita a instalar la app (Android/PC con un clic; en iPhone explica cómo). */
function InstallCard() {
  const { state, install } = useInstall()
  if (state === 'instalada' || state === 'no') return null
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-blue-200 bg-primary-tint p-4">
      <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary text-white">
        <Icon name="install_mobile" className="text-[24px]" />
      </span>
      <div className="min-w-0 flex-1 text-sm">
        <div className="font-semibold">Instala {APP_NAME} en este equipo</div>
        <div className="text-muted">
          {state === 'ios'
            ? 'En Safari toca Compartir y luego “Agregar a inicio”. Se abre como una app, a pantalla completa y sin internet.'
            : 'Ábrela desde el escritorio o la pantalla de inicio como una app, a pantalla completa y también sin internet.'}
        </div>
      </div>
      {state === 'lista' && (
        <button type="button" onClick={() => install()} className="h-11 rounded-xl bg-primary px-4 text-sm font-semibold text-white">
          Instalar
        </button>
      )}
    </div>
  )
}

/** Plan de Ecomerxio POS de la tienda y, si está en prueba, cuántos días le quedan. */
function PlanCard() {
  const { me } = useAuth()
  const t = me?.tenant
  if (!t?.planName) return null
  const days = t.trialEndsAt ? Math.ceil((new Date(t.trialEndsAt).getTime() - Date.now()) / 86_400_000) : null
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-2xl border border-line bg-card p-4 shadow-tier1">
      <img src={asset('ecomerxio-simbolo.svg')} alt="" className="h-11 w-11 shrink-0" />
      <div className="min-w-0 flex-1 text-sm">
        <div className="font-semibold">
          {APP_NAME} · Plan {t.planName}
        </div>
        <div className="text-muted">
          {days === null
            ? 'Suscripción activa.'
            : days > 0
              ? `Prueba gratis: te quedan ${days} ${days === 1 ? 'día' : 'días'}.`
              : 'Tu prueba gratis terminó.'}
        </div>
      </div>
    </div>
  )
}
