import { lazy, Suspense, type ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import AppShell, { MAIN_NAV } from './components/AppShell'
import { GuideProvider } from './components/guide'
import { Spinner } from './components/ui'
import { useAuth } from './lib/auth'
import MorePage from './pages/MorePage'
import CashPage from './pages/cash/CashPage'
import PosPage from './pages/pos/PosPage'
import LoginPage from './pages/auth/LoginPage'

// Pantallas que se cargan cuando se abren (el service worker igual las guarda todas para usar sin internet).
const SuppliersPage = lazy(() => import('./pages/purchasing/SuppliersPage'))
const SupplierDetailPage = lazy(() => import('./pages/purchasing/SupplierDetailPage'))
const PurchasesPage = lazy(() => import('./pages/purchasing/PurchasesPage'))
const OrderDetailPage = lazy(() => import('./pages/purchasing/OrderDetailPage'))
const SmartInventoryPage = lazy(() => import('./pages/inventory/SmartInventoryPage'))
const CountDetailPage = lazy(() => import('./pages/inventory/CountsPages').then((m) => ({ default: m.CountDetailPage })))
const CountsPage = lazy(() => import('./pages/inventory/CountsPages').then((m) => ({ default: m.CountsPage })))
const ExpiringPage = lazy(() => import('./pages/inventory/CountsPages').then((m) => ({ default: m.ExpiringPage })))
const AssistantPage = lazy(() => import('./pages/assistant/AssistantPage'))
const RecommendationsPage = lazy(() => import('./pages/assistant/RecommendationsPage'))
const NotificationsPage = lazy(() => import('./pages/notifications/NotificationsPage'))
const BackupsPage = lazy(() => import('./pages/admin/BackupsPage'))
const DashboardPage = lazy(() => import('./pages/dashboard/DashboardPage'))
const AnalyticsPage = lazy(() => import('./pages/analytics/AnalyticsPage'))
const ProfitabilityPage = lazy(() => import('./pages/analytics/ProfitabilityPage'))
const PaymentsPage = lazy(() => import('./pages/payments/PaymentsPage'))
const FinancePage = lazy(() => import('./pages/finance/FinancePage'))
const CreditDetailPage = lazy(() => import('./pages/credit/CreditPages').then((m) => ({ default: m.CreditDetailPage })))
const CreditListPage = lazy(() => import('./pages/credit/CreditPages').then((m) => ({ default: m.CreditListPage })))
const SalesPage = lazy(() => import('./pages/sales/SalesPage'))
const PosSettingsPage = lazy(() => import('./pages/admin/PosSettingsPage'))
const InventoryPage = lazy(() => import('./pages/inventory/InventoryPage'))
const MovementsPage = lazy(() => import('./pages/inventory/MovementsPage'))
const PromotionsPage = lazy(() => import('./pages/inventory/PromotionsPage'))
const ActivityPage = lazy(() => import('./pages/admin/ActivityPage'))
const ProfilePage = lazy(() => import('./pages/admin/ProfilePage'))
const RolesPage = lazy(() => import('./pages/admin/RolesPage'))
const StoreSettingsPage = lazy(() => import('./pages/admin/StoreSettingsPage'))
const UsersPage = lazy(() => import('./pages/admin/UsersPage'))
const ForgotPasswordPage = lazy(() => import('./pages/auth/RecoverPages').then((m) => ({ default: m.ForgotPasswordPage })))
const ResetPasswordPage = lazy(() => import('./pages/auth/RecoverPages').then((m) => ({ default: m.ResetPasswordPage })))
const RegisterPage = lazy(() => import('./pages/auth/RegisterPage'))
const SsoPage = lazy(() => import('./pages/auth/SsoPage'))
const FiscalPage = lazy(() => import('./pages/fiscal/FiscalPage'))
const AccountingPage = lazy(() => import('./pages/accounting/AccountingPage'))
const BusinessPage = lazy(() => import('./pages/admin/BusinessPage'))
const StorefrontPage = lazy(() => import('./pages/public/StorefrontPage'))
const OrderTrackPage = lazy(() => import('./pages/public/OrderTrackPage'))
const OnlineStorePage = lazy(() => import('./pages/online/OnlineStorePage'))
const OrdersPage = lazy(() => import('./pages/online/OrdersPage'))
const WarrantyPage = lazy(() => import('./pages/inventory/WarrantyPage'))
const ScaleLabelsPage = lazy(() => import('./pages/inventory/ScaleLabelsPage'))
const GuidePage = lazy(() => import('./pages/guide/GuidePage'))
const OutboxPage = lazy(() => import('./pages/offline/OutboxPage'))

function RequireAuth({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  if (loading) return <Spinner />
  if (!me) return <Navigate to="/login" replace />
  return <>{children}</>
}

function PublicOnly({ children }: { children: ReactNode }) {
  const { me, loading } = useAuth()
  if (loading) return <Spinner />
  if (me) return <Navigate to="/" replace />
  return <>{children}</>
}

function Guard({ when, children }: { when: (can: (p: string) => boolean) => boolean; children: ReactNode }) {
  const { can } = useAuth()
  return when(can) ? <>{children}</> : <Navigate to="/" replace />
}

/** El asistente con IA solo existe si está encendido en la plataforma y el usuario tiene el permiso. */
function AssistantRoute() {
  const { me, can } = useAuth()
  return me?.assistantEnabled && can('ASISTENTE_IA') ? <AssistantPage /> : <Navigate to="/" replace />
}

/** Primera pestaña que el usuario puede ver según sus permisos. */
function Home() {
  const { can } = useAuth()
  const first = MAIN_NAV.find((n) => n.show(can))
  return <Navigate to={first?.to ?? '/mas'} replace />
}

export default function App() {
  return (
    <Suspense fallback={<Spinner />}>
    <Routes>
      {/* Vitrina pública de cada tienda: abre con o sin sesión. */}
      <Route path="/t/:slug" element={<StorefrontPage />} />
      <Route path="/t/:slug/pedido/:token" element={<OrderTrackPage />} />
      {/* Llegada desde el login de ecomerxio.com (código de un solo uso). */}
      <Route path="/sso" element={<SsoPage />} />
      <Route path="/login" element={<PublicOnly><LoginPage /></PublicOnly>} />
      <Route path="/registro" element={<PublicOnly><RegisterPage /></PublicOnly>} />
      <Route path="/recuperar" element={<PublicOnly><ForgotPasswordPage /></PublicOnly>} />
      <Route path="/restablecer" element={<ResetPasswordPage />} />
      <Route element={<RequireAuth><GuideProvider><AppShell /></GuideProvider></RequireAuth>}>
        <Route index element={<Home />} />
        <Route path="/pos" element={<Guard when={(c) => c('POS_VENDER')}><PosPage /></Guard>} />
        <Route path="/dashboard" element={<Guard when={(c) => c('DASHBOARD_VER')}><DashboardPage /></Guard>} />
        <Route path="/analitica" element={<Guard when={(c) => c('REPORTES_VER')}><AnalyticsPage /></Guard>} />
        <Route path="/rentabilidad" element={<Guard when={(c) => c('COSTOS_VER')}><ProfitabilityPage /></Guard>} />
        <Route path="/caja" element={<Guard when={(c) => c('CAJA_OPERAR') || c('CAJA_SUPERVISAR')}><CashPage /></Guard>} />
        <Route path="/ventas" element={<SalesPage />} />
        <Route path="/ajustes-pos" element={<PosSettingsPage />} />
        <Route path="/inventario" element={<Guard when={(c) => c('INVENTARIO_VER') || c('PRODUCTOS_VER')}><InventoryPage /></Guard>} />
        <Route path="/inventario/inteligente" element={<Guard when={(c) => c('INVENTARIO_VER')}><SmartInventoryPage /></Guard>} />
        <Route path="/inventario/conteos" element={<Guard when={(c) => c('INVENTARIO_CONTEO') || c('INVENTARIO_VER')}><CountsPage /></Guard>} />
        <Route path="/inventario/conteos/:id" element={<Guard when={(c) => c('INVENTARIO_CONTEO') || c('INVENTARIO_VER')}><CountDetailPage /></Guard>} />
        <Route path="/inventario/vencimientos" element={<Guard when={(c) => c('INVENTARIO_VER')}><ExpiringPage /></Guard>} />
        <Route path="/proveedores" element={<Guard when={(c) => c('PROVEEDORES_VER') || c('COMPRAS_VER')}><SuppliersPage /></Guard>} />
        <Route path="/proveedores/:id" element={<Guard when={(c) => c('PROVEEDORES_VER') || c('COMPRAS_VER')}><SupplierDetailPage /></Guard>} />
        <Route path="/compras" element={<Guard when={(c) => c('COMPRAS_VER') || c('COMPRAS_RECIBIR')}><PurchasesPage /></Guard>} />
        <Route path="/compras/:id" element={<Guard when={(c) => c('COMPRAS_VER') || c('COMPRAS_RECIBIR')}><OrderDetailPage /></Guard>} />
        <Route path="/promociones" element={<Guard when={(c) => c('PRODUCTOS_PRECIOS')}><PromotionsPage /></Guard>} />
        <Route path="/movimientos" element={<Guard when={(c) => c('INVENTARIO_VER')}><MovementsPage /></Guard>} />
        <Route path="/pagos" element={<Guard when={(c) => c('PAGOS_VERIFICAR') || c('PAGOS_CONCILIAR') || c('REPORTES_VER')}><PaymentsPage /></Guard>} />
        <Route path="/fiados" element={<Guard when={(c) => c('FIADO_GESTIONAR') || c('FIADO_AUTORIZAR') || c('FINANZAS_VER')}><CreditListPage /></Guard>} />
        <Route path="/fiados/:id" element={<Guard when={(c) => c('FIADO_GESTIONAR') || c('FIADO_AUTORIZAR') || c('FINANZAS_VER')}><CreditDetailPage /></Guard>} />
        <Route path="/finanzas" element={<Guard when={(c) => c('FINANZAS_VER') || c('FINANZAS_GASTOS')}><FinancePage /></Guard>} />
        <Route path="/finanzas/por-pagar" element={<Guard when={(c) => c('FINANZAS_VER') || c('FINANZAS_GASTOS') || c('COMPRAS_VER')}><FinancePage /></Guard>} />
        <Route path="/notificaciones" element={<NotificationsPage />} />
        <Route path="/respaldos" element={<Guard when={(c) => c('CONFIG_EDITAR')}><BackupsPage /></Guard>} />
        <Route path="/asistente" element={<AssistantRoute />} />
        <Route path="/recomendaciones" element={<Guard when={(c) => c('COSTOS_VER') || c('PRODUCTOS_PRECIOS')}><RecommendationsPage /></Guard>} />
        <Route path="/facturacion" element={<Guard when={(c) => c('FACTURACION_CONFIGURAR')}><FiscalPage /></Guard>} />
        <Route path="/contabilidad" element={<Guard when={(c) => c('CONTABILIDAD_VER')}><AccountingPage /></Guard>} />
        <Route path="/mas" element={<MorePage />} />
        <Route path="/negocio" element={<BusinessPage />} />
        <Route path="/tienda-online" element={<OnlineStorePage />} />
        <Route path="/pedidos" element={<Guard when={(c) => c('POS_VENDER')}><OrdersPage /></Guard>} />
        <Route path="/garantias" element={<Guard when={(c) => c('POS_VENDER') || c('POS_DEVOLUCION') || c('INVENTARIO_VER') || c('PRODUCTOS_VER')}><WarrantyPage /></Guard>} />
        <Route path="/etiquetas-bascula" element={<Guard when={(c) => c('PRODUCTOS_VER') || c('INVENTARIO_VER')}><ScaleLabelsPage /></Guard>} />
        <Route path="/guia" element={<GuidePage />} />
        <Route path="/pendientes" element={<OutboxPage />} />
        <Route path="/usuarios" element={<Guard when={(c) => c('USUARIOS_GESTIONAR')}><UsersPage /></Guard>} />
        <Route path="/roles" element={<Guard when={(c) => c('ROLES_GESTIONAR') || c('USUARIOS_GESTIONAR')}><RolesPage /></Guard>} />
        <Route path="/actividad" element={<Guard when={(c) => c('AUDITORIA_VER')}><ActivityPage /></Guard>} />
        <Route path="/configuracion" element={<StoreSettingsPage />} />
        <Route path="/perfil" element={<ProfilePage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
    </Suspense>
  )
}
