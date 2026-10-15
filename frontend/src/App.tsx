import type { ReactNode } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import AppShell, { MAIN_NAV } from './components/AppShell'
import { Spinner } from './components/ui'
import { useAuth } from './lib/auth'
import ComingSoon from './pages/ComingSoon'
import MorePage from './pages/MorePage'
import SuppliersPage from './pages/purchasing/SuppliersPage'
import SupplierDetailPage from './pages/purchasing/SupplierDetailPage'
import PurchasesPage from './pages/purchasing/PurchasesPage'
import OrderDetailPage from './pages/purchasing/OrderDetailPage'
import SmartInventoryPage from './pages/inventory/SmartInventoryPage'
import { CountDetailPage, CountsPage, ExpiringPage } from './pages/inventory/CountsPages'
import CashPage from './pages/cash/CashPage'
import PaymentsPage from './pages/payments/PaymentsPage'
import FinancePage from './pages/finance/FinancePage'
import { CreditDetailPage, CreditListPage } from './pages/credit/CreditPages'
import SalesPage from './pages/sales/SalesPage'
import PosSettingsPage from './pages/admin/PosSettingsPage'
import PosPage from './pages/pos/PosPage'
import InventoryPage from './pages/inventory/InventoryPage'
import MovementsPage from './pages/inventory/MovementsPage'
import PromotionsPage from './pages/inventory/PromotionsPage'
import ActivityPage from './pages/admin/ActivityPage'
import ProfilePage from './pages/admin/ProfilePage'
import RolesPage from './pages/admin/RolesPage'
import StoreSettingsPage from './pages/admin/StoreSettingsPage'
import UsersPage from './pages/admin/UsersPage'
import LoginPage from './pages/auth/LoginPage'
import { ForgotPasswordPage, ResetPasswordPage } from './pages/auth/RecoverPages'
import RegisterPage from './pages/auth/RegisterPage'

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

/** Primera pestaña que el usuario puede ver según sus permisos. */
function Home() {
  const { can } = useAuth()
  const first = MAIN_NAV.find((n) => n.show(can))
  return <Navigate to={first?.to ?? '/mas'} replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<PublicOnly><LoginPage /></PublicOnly>} />
      <Route path="/registro" element={<PublicOnly><RegisterPage /></PublicOnly>} />
      <Route path="/recuperar" element={<PublicOnly><ForgotPasswordPage /></PublicOnly>} />
      <Route path="/restablecer" element={<ResetPasswordPage />} />
      <Route element={<RequireAuth><AppShell /></RequireAuth>}>
        <Route index element={<Home />} />
        <Route path="/pos" element={<Guard when={(c) => c('POS_VENDER')}><PosPage /></Guard>} />
        <Route
          path="/dashboard"
          element={
            <ComingSoon
              icon="space_dashboard"
              title="Dashboard principal"
              phase={4}
              items={['Ventas del día, semana, quincena y mes', 'Utilidad bruta y neta estimada', 'Estado de caja y pagos por verificar', 'Más vendidos, baja rotación y agotados', 'Alertas y accesos rápidos']}
            />
          }
        />
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
        <Route path="/mas" element={<MorePage />} />
        <Route path="/usuarios" element={<Guard when={(c) => c('USUARIOS_GESTIONAR')}><UsersPage /></Guard>} />
        <Route path="/roles" element={<Guard when={(c) => c('ROLES_GESTIONAR') || c('USUARIOS_GESTIONAR')}><RolesPage /></Guard>} />
        <Route path="/actividad" element={<Guard when={(c) => c('AUDITORIA_VER')}><ActivityPage /></Guard>} />
        <Route path="/configuracion" element={<StoreSettingsPage />} />
        <Route path="/perfil" element={<ProfilePage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
