import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { api, ApiError } from '../lib/api'
import { Banner, Button, Icon, Modal } from './ui'

const PERMISSION_TEXT: Record<string, string> = {
  POS_DESCUENTO: 'aplicar un descuento',
  POS_DESCUENTO_ESPECIAL: 'un descuento mayor al permitido',
  POS_PRECIO_ESPECIAL: 'vender por debajo del precio mínimo',
  POS_ANULAR: 'anular',
  POS_DEVOLUCION: 'registrar una devolución',
  CAJA_AJUSTAR: 'un ajuste de caja',
  FIADO_AUTORIZAR: 'fiar por encima del cupo o a un cliente en mora',
}

interface Pending {
  permission: string
  message: string
  resolve: (token: string | null) => void
}

/**
 * Autorización de supervisor en el mostrador. `run(fn)` ejecuta la acción; si el servidor responde
 * que necesita un permiso, pide el PIN de alguien que lo tenga y reintenta con el pase.
 */
export function useApproval() {
  const [pending, setPending] = useState<Pending | null>(null)

  const ask = useCallback(
    (permission: string, message: string) => new Promise<string | null>((resolve) => setPending({ permission, message, resolve })),
    [],
  )

  const run = useCallback(
    async <T,>(fn: (approvals: string[]) => Promise<T>): Promise<T | null> => {
      const tokens: string[] = []
      for (let i = 0; i < 4; i++) {
        try {
          return await fn(tokens)
        } catch (e) {
          if (e instanceof ApiError && e.status === 403 && e.approval) {
            const token = await ask(e.approval, e.message)
            if (!token) return null
            tokens.push(token)
            continue
          }
          throw e
        }
      }
      return null
    },
    [ask],
  )

  const modal: ReactNode = pending ? (
    <ApprovalModal
      permission={pending.permission}
      message={pending.message}
      onClose={(token) => {
        pending.resolve(token)
        setPending(null)
      }}
    />
  ) : null

  return { run, ask, modal }
}

function ApprovalModal({ permission, message, onClose }: { permission: string; message: string; onClose: (token: string | null) => void }) {
  const [approvers, setApprovers] = useState<{ id: number; fullName: string }[] | null>(null)
  const [approverId, setApproverId] = useState<number | null>(null)
  const [pin, setPin] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const pinRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    api<{ id: number; fullName: string }[]>(`/pos/approvers?permission=${permission}`)
      .then((list) => {
        setApprovers(list)
        if (list.length === 1) setApproverId(list[0]!.id)
      })
      .catch((e) => setError(e.message))
  }, [permission])

  useEffect(() => {
    if (approverId) pinRef.current?.focus()
  }, [approverId])

  async function submit() {
    if (!approverId) return
    setLoading(true)
    setError('')
    try {
      const r = await api<{ token: string }>('/pos/approvals', { body: { approverId, secret: pin, permission } })
      onClose(r.token)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'No se pudo autorizar')
      setPin('')
    } finally {
      setLoading(false)
    }
  }

  return (
    <Modal
      open
      title="Autorización de supervisor"
      onClose={() => onClose(null)}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={() => onClose(null)}>
            Cancelar
          </Button>
          <Button className="flex-1" icon="verified_user" onClick={submit} loading={loading} disabled={!approverId || !pin}>
            Autorizar
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="flex items-start gap-3 rounded-xl bg-warning-soft p-3 text-sm text-warning-ink">
          <Icon name="shield_person" className="text-[22px]" />
          <div>
            <div className="font-semibold">{message}</div>
            <div className="text-xs">Un supervisor con permiso para {PERMISSION_TEXT[permission] ?? 'esta acción'} debe poner su PIN o contraseña.</div>
          </div>
        </div>
        {error && <Banner>{error}</Banner>}
        {approvers && approvers.length === 0 && <Banner tone="warning">Nadie en la tienda tiene este permiso. Pídele al dueño que lo asigne.</Banner>}
        <div className="grid grid-cols-2 gap-2">
          {approvers?.map((a) => (
            <button
              key={a.id}
              type="button"
              onClick={() => setApproverId(a.id)}
              className={`rounded-xl border p-3 text-left text-sm font-semibold ${approverId === a.id ? 'border-primary bg-primary-tint text-primary' : 'border-line'}`}
            >
              {a.fullName}
            </button>
          ))}
        </div>
        <input
          ref={pinRef}
          type="password"
          inputMode="numeric"
          autoComplete="off"
          placeholder="PIN o contraseña"
          value={pin}
          onChange={(e) => setPin(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          className="num h-14 w-full rounded-xl border border-line bg-card px-4 text-center text-2xl tracking-[0.4em] outline-none placeholder:text-base placeholder:tracking-normal focus:border-primary focus:ring-4 focus:ring-primary/15"
        />
      </div>
    </Modal>
  )
}
