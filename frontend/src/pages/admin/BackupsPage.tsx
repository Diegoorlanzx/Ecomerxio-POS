import { useCallback, useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, EmptyState, Field, Icon, Modal, PasswordField, Spinner, Toggle, useToast } from '../../components/ui'
import { api, apiBlob, apiUpload, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import { dateTime, download } from '../../lib/format'
import type { BackupItem, Branch } from '../../lib/notificationTypes'

const KIND: Record<BackupItem['kind'], { label: string; tone: 'primary' | 'neutral' | 'warning' }> = {
  MANUAL: { label: 'Manual', tone: 'primary' },
  AUTOMATICA: { label: 'Automática', tone: 'neutral' },
  ANTES_RESTAURAR: { label: 'Antes de restaurar', tone: 'warning' },
}

const size = (b: number) => (b < 1024 * 1024 ? `${Math.max(1, Math.round(b / 1024))} KB` : `${(b / 1048576).toFixed(1)} MB`)

/** Copias de seguridad, restauración, exportación de datos y sucursales. */
export default function BackupsPage() {
  const { me, signOut } = useAuth()
  const toast = useToast()
  const [data, setData] = useState<{ autoBackup: boolean; backups: BackupItem[]; schemaVersion: string } | null>(null)
  const [creating, setCreating] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [restore, setRestore] = useState<{ backup?: BackupItem; file?: File } | null>(null)
  const [error, setError] = useState('')

  const load = useCallback(() => {
    api<typeof data>('/backups')
      .then(setData)
      .catch((e) => setError(e.message))
  }, [])
  useEffect(load, [load])

  async function create() {
    setCreating(true)
    try {
      await api('/backups', { method: 'POST' })
      toast('Copia de seguridad creada')
      load()
    } catch (e) {
      toast(e instanceof Error ? e.message : 'No se pudo crear la copia', 'danger')
    } finally {
      setCreating(false)
    }
  }

  async function get(b: BackupItem) {
    download(`tiendaposs_copia_${b.id}_${b.createdAt.slice(0, 10)}.tpbak`, await apiBlob(`/backups/${b.id}/download`))
  }

  async function exportAll() {
    setExporting(true)
    try {
      download(`tiendaposs_datos_${new Date().toLocaleDateString('en-CA')}.zip`, await apiBlob('/data-export'))
    } finally {
      setExporting(false)
    }
  }

  async function setAuto(v: boolean) {
    await api('/backups/settings', { method: 'PUT', body: { autoBackup: v } })
    load()
  }

  if (error) return <Banner>{error}</Banner>
  if (!data) return <Spinner />
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Copias de seguridad y datos</h1>
        <p className="text-sm text-muted">Guarda una copia de toda la información de la tienda, recupérala si algo sale mal y descarga tus datos cuando quieras.</p>
      </div>

      <Card className="p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="max-w-xl">
            <Toggle checked={data.autoBackup} onChange={setAuto} label="Copia automática cada noche" hint="A las 3:30 a. m. se guarda una copia completa. Se conservan las últimas 7." />
          </div>
          <Button icon="backup" onClick={create} loading={creating}>
            Crear copia ahora
          </Button>
        </div>
      </Card>

      <Card>
        <div className="flex items-center justify-between border-b border-line-soft px-4 py-3">
          <span className="text-sm font-semibold">Copias guardadas</span>
          <label className="inline-flex cursor-pointer items-center gap-1.5 text-sm font-semibold text-primary">
            <Icon name="upload_file" className="text-[18px]" /> Restaurar desde archivo
            <input type="file" accept=".tpbak" className="hidden" onChange={(e) => e.target.files?.[0] && setRestore({ file: e.target.files[0] })} />
          </label>
        </div>
        {data.backups.length === 0 ? (
          <EmptyState icon="cloud_off" title="Aún no hay copias" text="Crea la primera o espera la copia automática de esta noche." />
        ) : (
          <div className="divide-y divide-line-soft">
            {data.backups.map((b) => (
              <div key={b.id} className="flex flex-wrap items-center gap-3 px-4 py-3">
                <Icon name="inventory" className="text-[22px] text-primary" />
                <div className="min-w-0 flex-1 text-sm">
                  <div className="flex items-center gap-2 font-semibold">
                    Copia #{b.id} <Chip tone={KIND[b.kind].tone}>{KIND[b.kind].label}</Chip>
                  </div>
                  <div className="text-xs text-muted">
                    {dateTime(b.createdAt)} · {size(b.sizeBytes)} · {b.rowCounts.products ?? 0} productos · {b.rowCounts.sales ?? 0} ventas · {b.createdBy}
                  </div>
                </div>
                <div className="flex gap-2">
                  <Button size="sm" variant="secondary" icon="download" onClick={() => get(b)}>
                    Descargar
                  </Button>
                  {me?.owner && (
                    <Button size="sm" variant="ghost" icon="settings_backup_restore" onClick={() => setRestore({ backup: b })}>
                      Restaurar
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card className="p-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="max-w-xl">
            <h3 className="font-semibold">Exportar todos los datos</h3>
            <p className="text-sm text-muted">Un archivo ZIP con productos, clientes, proveedores, ventas, pagos, inventario, compras, gastos, fiado, turnos y usuarios en CSV (se abren en Excel).</p>
          </div>
          <Button variant="secondary" icon="folder_zip" onClick={exportAll} loading={exporting}>
            Descargar ZIP
          </Button>
        </div>
      </Card>

      <BranchesCard />

      {restore && (
        <RestoreModal
          target={restore}
          onClose={() => setRestore(null)}
          onDone={(relogin) => {
            setRestore(null)
            toast('Tienda restaurada')
            if (relogin) signOut()
            else window.location.reload()
          }}
        />
      )}
    </div>
  )
}

function RestoreModal({ target, onClose, onDone }: { target: { backup?: BackupItem; file?: File }; onClose: () => void; onDone: (relogin: boolean) => void }) {
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  async function submit() {
    setLoading(true)
    setError('')
    try {
      const r = target.backup
        ? await api<{ reloginRequired: boolean }>(`/backups/${target.backup.id}/restore`, { method: 'POST', body: { password } })
        : await apiUpload<{ reloginRequired: boolean }>('/backups/restore-file', target.file!, { 'X-Password': password })
      onDone(r.reloginRequired)
    } catch (e) {
      setError(e instanceof ApiError ? Object.values(e.fields)[0] ?? e.message : 'No se pudo restaurar')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      open
      title="Restaurar copia de seguridad"
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant="danger" className="flex-1" onClick={submit} loading={loading} disabled={!password || confirm.trim().toUpperCase() !== 'RESTAURAR'}>
            Restaurar
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Banner tone="warning">
          Todos los datos de la tienda volverán a como estaban en {target.backup ? `la copia #${target.backup.id} (${dateTime(target.backup.createdAt)})` : `el archivo ${target.file?.name}`}. Lo que se
          hizo después se perderá. Antes de restaurar se guarda automáticamente una copia del estado actual para poder deshacerlo.
        </Banner>
        <PasswordField label="Tu contraseña" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        <Field label='Escribe "RESTAURAR" para confirmar' value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        {error && <Banner>{error}</Banner>}
      </div>
    </Modal>
  )
}

function BranchesCard() {
  const { can } = useAuth()
  const toast = useToast()
  const [branches, setBranches] = useState<Branch[] | null>(null)
  const [edit, setEdit] = useState<Branch | null>(null)
  const [form, setForm] = useState({ name: '', address: '', phone: '' })
  useEffect(() => {
    api<Branch[]>('/branches').then(setBranches).catch(() => setBranches([]))
  }, [])
  if (!branches) return null

  async function save() {
    if (!edit) return
    try {
      setBranches(await api<Branch[]>(`/branches/${edit.id}`, { method: 'PUT', body: form }))
      setEdit(null)
      toast('Sucursal actualizada')
    } catch (e) {
      toast(e instanceof Error ? e.message : 'No se pudo guardar', 'danger')
    }
  }

  return (
    <Card className="p-4">
      <h3 className="font-semibold">Sucursales</h3>
      <p className="mb-3 text-sm text-muted">Hoy la tienda opera con una sede. El sistema ya guarda a qué sucursal pertenece cada caja, para abrir más sedes en el futuro.</p>
      <div className="divide-y divide-line-soft rounded-xl border border-line">
        {branches.map((b) => (
          <div key={b.id} className="flex items-center gap-3 px-3 py-2.5 text-sm">
            <Icon name="store" className="text-[20px] text-primary" />
            <div className="min-w-0 flex-1">
              <div className="font-semibold">
                {b.name} {b.main && <Chip tone="primary">Principal</Chip>}
              </div>
              <div className="text-xs text-muted">
                {[b.address, b.phone].filter(Boolean).join(' · ') || 'Sin dirección'} · {b.registers} caja{b.registers === 1 ? '' : 's'}
              </div>
            </div>
            {can('CONFIG_EDITAR') && (
              <Button size="sm" variant="ghost" icon="edit" onClick={() => (setEdit(b), setForm({ name: b.name, address: b.address ?? '', phone: b.phone ?? '' }))}>
                Editar
              </Button>
            )}
          </div>
        ))}
      </div>
      {edit && (
        <Modal
          open
          title="Editar sucursal"
          onClose={() => setEdit(null)}
          footer={
            <Button className="flex-1" icon="save" onClick={save}>
              Guardar
            </Button>
          }
        >
          <div className="space-y-3">
            <Field label="Nombre" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <Field label="Dirección" value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
            <Field label="Teléfono" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
          </div>
        </Modal>
      )}
    </Card>
  )
}
