import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Banner, Button, Card, Chip, PasswordField, Toggle, useToast } from '../../components/ui'
import { useGuide } from '../../components/guide'
import { api, ApiError, setToken } from '../../lib/api'
import { useAuth } from '../../lib/auth'

export default function ProfilePage() {
  const { me, refresh } = useAuth()
  const toast = useToast()
  const navigate = useNavigate()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  if (!me) return null

  async function change() {
    setSaving(true)
    setFields({})
    setError('')
    try {
      await api('/auth/change-password', { body: { currentPassword: current, newPassword: next } })
      setCurrent('')
      setNext('')
      toast('Contraseña cambiada')
    } catch (e) {
      if (e instanceof ApiError) {
        setFields(e.fields)
        if (!Object.keys(e.fields).length) setError(e.message)
      }
    } finally {
      setSaving(false)
    }
  }

  async function logoutAll() {
    await api('/auth/logout-all', { method: 'POST' })
    setToken(null)
    await refresh()
    navigate('/login')
  }

  return (
    <div className="mx-auto max-w-2xl space-y-4">
      <Card className="p-5">
        <div className="text-lg font-semibold">{me.fullName}</div>
        <div className="text-sm text-muted">{me.email}</div>
        <div className="mt-3 flex flex-wrap gap-1">
          {me.roles.map((r) => (
            <Chip key={r} tone="primary">
              {r}
            </Chip>
          ))}
        </div>
        <p className="mt-3 text-sm text-muted">
          Tienes {me.permissions.length} permisos en {me.tenant.name}.
        </p>
      </Card>
      <Card className="space-y-4 p-5">
        <h3 className="font-semibold">Cambiar contraseña</h3>
        {error && <Banner>{error}</Banner>}
        <PasswordField label="Contraseña actual" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} error={fields.currentPassword} />
        <PasswordField
          label="Contraseña nueva"
          autoComplete="new-password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
          error={fields.newPassword}
          hint="Mínimo 8 caracteres, con letras y números."
        />
        <Button onClick={change} loading={saving}>
          Guardar contraseña
        </Button>
      </Card>
      <PinCard hasPin={me.hasPin} onSaved={refresh} />
      <Card className="p-5">
        <h3 className="font-semibold">Sesiones</h3>
        <p className="mt-1 text-sm text-muted">Si perdiste un celular o dejaste la sesión abierta en otro equipo, ciérrala en todos lados.</p>
        <Button variant="secondary" icon="devices" className="mt-3" onClick={logoutAll}>
          Cerrar sesión en todos los aparatos
        </Button>
      </Card>
      <PreferencesCard />
    </div>
  )
}

/** Preferencias de este usuario en este equipo: por ahora, mostrar u ocultar las ayudas de la app. */
function PreferencesCard() {
  const guide = useGuide()
  if (!guide) return null
  return (
    <Card className="p-5">
      <h3 className="font-semibold">Preferencias</h3>
      <Toggle
        checked={guide.state.showContextualHelp}
        onChange={(v) => guide.update({ showContextualHelp: v })}
        label="Mostrar ayudas (i) y (?)"
        hint="Explicaciones al pasar el mouse sobre cifras, módulos y campos difíciles. Apágalas cuando ya conozcas la app; puedes volver a encenderlas aquí."
      />
    </Card>
  )
}

function PinCard({ hasPin, onSaved }: { hasPin: boolean; onSaved: () => Promise<void> }) {
  const toast = useToast()
  const [pin, setPin] = useState('')
  const [password, setPassword] = useState('')
  const [fields, setFields] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  async function save(remove = false) {
    setSaving(true)
    setFields({})
    try {
      await api('/auth/pin', { body: { currentPassword: password, pin: remove ? '' : pin } })
      setPin('')
      setPassword('')
      await onSaved()
      toast(remove ? 'PIN eliminado' : 'PIN guardado')
    } catch (e) {
      if (e instanceof ApiError) setFields(Object.keys(e.fields).length ? e.fields : { pin: e.message })
    } finally {
      setSaving(false)
    }
  }
  return (
    <Card className="space-y-4 p-5">
      <div>
        <h3 className="font-semibold">PIN de autorización {hasPin && <span className="ml-1 text-xs font-semibold text-success-ink">· configurado</span>}</h3>
        <p className="mt-1 text-sm text-muted">Con tu PIN autorizas en la caja descuentos, anulaciones, devoluciones y ajustes sin dar tu contraseña.</p>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <PasswordField label="PIN nuevo (4 a 6 números)" inputMode="numeric" value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 6))} error={fields.pin} />
        <PasswordField label="Tu contraseña (para confirmar)" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} error={fields.currentPassword} />
      </div>
      <div className="flex gap-2">
        <Button onClick={() => save()} loading={saving} disabled={pin.length < 4 || !password}>
          Guardar PIN
        </Button>
        {hasPin && (
          <Button variant="ghost" onClick={() => save(true)} disabled={!password}>
            Quitar PIN
          </Button>
        )}
      </div>
    </Card>
  )
}
