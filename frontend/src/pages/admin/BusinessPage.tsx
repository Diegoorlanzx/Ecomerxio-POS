import { useEffect, useState } from 'react'
import { Banner, Button, Card, Chip, Field, Icon, SectionLabel, SelectField, Spinner, Toggle, useToast } from '../../components/ui'
import { api, ApiError } from '../../lib/api'
import { useAuth } from '../../lib/auth'
import type { ModulesDto } from '../../lib/posTypes'

/** Tipo de negocio y módulos: la app muestra solo lo que la tienda usa. */
export default function BusinessPage() {
  const { can, refresh } = useAuth()
  const toast = useToast()
  const canEdit = can('CONFIG_EDITAR')
  const [data, setData] = useState<ModulesDto | null>(null)
  const [type, setType] = useState('')
  const [enabled, setEnabled] = useState<Set<string>>(new Set())
  const [scale, setScale] = useState({ prefixes: '', mode: 'PESO' as 'PESO' | 'PRECIO', plu: 5 })
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    api<ModulesDto>('/settings/pos/modules')
      .then((d) => {
        setData(d)
        setType(d.businessType)
        setEnabled(new Set(d.enabled))
        setScale({ prefixes: d.scalePrefixes, mode: d.scaleMode, plu: d.scalePluDigits })
      })
      .catch((e) => setError(e.message))
  }, [])

  if (error && !data) return <Banner>{error}</Banner>
  if (!data) return <Spinner />

  const profile = data.profiles.find((p) => p.code === type)
  const toggle = (code: string, on: boolean) =>
    setEnabled((s) => {
      const n = new Set(s)
      if (on) n.add(code)
      else n.delete(code)
      return n
    })

  async function save() {
    setSaving(true)
    setError('')
    try {
      const d = await api<ModulesDto>('/settings/pos/modules', {
        method: 'PUT',
        body: { businessType: type, modules: [...enabled], scalePrefixes: scale.prefixes, scaleMode: scale.mode, scalePluDigits: scale.plu },
      })
      setData(d)
      setEnabled(new Set(d.enabled))
      await refresh()
      toast('Módulos guardados')
    } catch (e) {
      if (e instanceof ApiError) setError(Object.values(e.fields)[0] ?? e.message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="mx-auto max-w-4xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Tipo de negocio y módulos</h1>
        <p className="text-sm text-muted">Elige qué vendes y TiendaPOSS muestra solo lo que necesitas. Puedes cambiarlo cuando quieras.</p>
      </div>
      {error && <Banner>{error}</Banner>}

      <section>
        <SectionLabel>¿Qué tipo de negocio tienes?</SectionLabel>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {data.profiles.map((p) => (
            <button
              key={p.code}
              type="button"
              disabled={!canEdit}
              onClick={() => setType(p.code)}
              aria-pressed={type === p.code}
              className={`rounded-2xl border bg-card p-4 text-left shadow-tier1 transition ${type === p.code ? 'border-primary ring-2 ring-primary/30' : 'border-line hover:border-primary/40'}`}
            >
              <div className={`mb-2 flex h-10 w-10 items-center justify-center rounded-xl ${type === p.code ? 'bg-primary text-white' : 'bg-primary-tint text-primary'}`}>
                <Icon name={p.icon} />
              </div>
              <div className="text-sm font-semibold">{p.name}</div>
              <div className="mt-0.5 text-xs text-muted">{p.description}</div>
            </button>
          ))}
        </div>
        {profile && canEdit && (
          <div className="mt-3 flex flex-wrap items-center gap-2 text-sm">
            <span className="text-muted">Módulos sugeridos para este negocio:</span>
            {profile.modules.map((m) => (
              <Chip key={m} tone={enabled.has(m) ? 'success' : 'neutral'}>
                {data.modules.find((x) => x.code === m)?.name ?? m}
              </Chip>
            ))}
            <Button size="sm" variant="soft" icon="auto_fix_high" onClick={() => setEnabled(new Set(profile.modules))}>
              Usar los sugeridos
            </Button>
          </div>
        )}
      </section>

      <section>
        <SectionLabel>Módulos</SectionLabel>
        <Card className="divide-y divide-line-soft px-4">
          {data.modules
            .filter((m) => m.available)
            .map((m) => (
              <div key={m.code} className={canEdit ? '' : 'pointer-events-none opacity-70'}>
                <Toggle checked={enabled.has(m.code)} onChange={(v) => toggle(m.code, v)} label={m.name} hint={m.description} />
              </div>
            ))}
        </Card>
        <div className="mt-3 grid gap-2 sm:grid-cols-2">
          {data.modules
            .filter((m) => !m.available)
            .map((m) => (
              <div key={m.code} className="flex items-center gap-2 rounded-xl border border-dashed border-line px-3 py-2.5 text-sm text-muted">
                <Icon name="schedule" className="text-[18px]" /> {m.name} <span className="ml-auto text-xs">{m.description}</span>
              </div>
            ))}
        </div>
      </section>

      {enabled.has('BASCULA') && (
        <section>
          <SectionLabel>Etiquetas de balanza</SectionLabel>
          <Card className="space-y-3 p-4">
            <p className="text-sm text-muted">
              La balanza imprime un código de 13 números que empieza por 2: el código del producto (PLU) y el peso o el precio. Configúralo igual que tu
              balanza.
            </p>
            <div className="grid gap-3 sm:grid-cols-3">
              <SelectField label="La etiqueta trae" value={scale.mode} onChange={(e) => setScale({ ...scale, mode: e.target.value as 'PESO' | 'PRECIO' })}>
                <option value="PESO">El peso (gramos)</option>
                <option value="PRECIO">El precio a cobrar</option>
              </SelectField>
              <SelectField label="Dígitos del PLU" value={String(scale.plu)} onChange={(e) => setScale({ ...scale, plu: Number(e.target.value) })}>
                {[4, 5, 6].map((n) => (
                  <option key={n} value={n}>
                    {n} dígitos (valor de {10 - n})
                  </option>
                ))}
              </SelectField>
              <Field label="Prefijos aceptados" value={scale.prefixes} onChange={(e) => setScale({ ...scale, prefixes: e.target.value })} hint="Del 20 al 29, separados por coma" />
            </div>
            <p className="num rounded-lg bg-canvas px-3 py-2 font-mono text-xs">
              Ejemplo: 20 · {'0'.repeat(scale.plu - 3)}123 · {scale.mode === 'PESO' ? '01250'.padStart(10 - scale.plu, '0').slice(-(10 - scale.plu)) : '22500'.padStart(10 - scale.plu, '0').slice(-(10 - scale.plu))} · dígito de
              control = producto 123, {scale.mode === 'PESO' ? '1,250 kg' : '$22.500'}
            </p>
          </Card>
        </section>
      )}

      {canEdit && (
        <div className="sticky bottom-20 flex justify-end lg:bottom-4">
          <Button size="lg" icon="save" onClick={save} loading={saving}>
            Guardar cambios
          </Button>
        </div>
      )}
    </div>
  )
}
