import { createContext, useCallback, useContext, useEffect, useId, useRef, useState, type ButtonHTMLAttributes, type HTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react'
import { createPortal } from 'react-dom'
import { QUEUED_MESSAGE } from '../lib/http'
import { portalRoot } from '../lib/host'

export function Icon({ name, className = '', fill = false }: { name: string; className?: string; fill?: boolean }) {
  return (
    <span aria-hidden className={`icon ${fill ? 'fill' : ''} ${className}`}>
      {name}
    </span>
  )
}

type Variant = 'primary' | 'navy' | 'secondary' | 'ghost' | 'danger' | 'soft'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-primary text-white hover:bg-primary-active shadow-tier1',
  navy: 'bg-navy text-white hover:bg-navy-2 shadow-tier1',
  secondary: 'bg-card text-ink border border-line hover:bg-canvas shadow-tier1',
  ghost: 'text-muted hover:text-ink hover:bg-line-soft',
  danger: 'bg-danger-ink text-white hover:bg-red-800 shadow-tier1',
  soft: 'bg-primary-tint text-primary hover:bg-primary-soft',
}

export function Button({
  variant = 'primary',
  size = 'md',
  icon,
  loading = false,
  className = '',
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant
  size?: 'sm' | 'md' | 'lg'
  icon?: string
  loading?: boolean
}) {
  const sizes = { sm: 'h-9 px-3 text-sm rounded-lg', md: 'h-11 px-4 text-sm rounded-xl', lg: 'h-14 px-5 text-base rounded-xl' }
  return (
    <button
      {...rest}
      disabled={rest.disabled || loading}
      className={`inline-flex items-center justify-center gap-2 font-semibold transition active:scale-[0.98] disabled:opacity-50 ${sizes[size]} ${VARIANTS[variant]} ${className}`}
    >
      {loading ? <Icon name="progress_activity" className="animate-spin text-[20px]" /> : icon && <Icon name={icon} className="text-[20px]" />}
      {children}
    </button>
  )
}

export function Field({
  label,
  error,
  hint,
  helpTip,
  className = '',
  trailing,
  ...rest
}: InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string; helpTip?: ReactNode; trailing?: ReactNode }) {
  const id = useId()
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 flex items-center gap-1 text-xs font-semibold tracking-wide text-muted uppercase">
        <span>{label}</span>{helpTip}
      </label>
      <div className="relative">
        <input
          id={id}
          {...rest}
          aria-invalid={!!error}
          className={`h-12 w-full rounded-xl border bg-card px-3.5 text-base text-ink outline-none transition placeholder:text-slate-400 focus:border-primary focus:ring-4 focus:ring-primary/15 ${
            error ? 'border-danger' : 'border-line'
          } ${trailing ? 'pr-12' : ''}`}
        />
        {trailing && <div className="absolute inset-y-0 right-1 flex items-center">{trailing}</div>}
      </div>
      {error ? (
        <p className="mt-1 text-xs font-medium text-danger-ink">{error}</p>
      ) : (
        hint && <p className="mt-1 text-xs text-muted">{hint}</p>
      )}
    </div>
  )
}

export function PasswordField(props: InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string }) {
  const [show, setShow] = useState(false)
  return (
    <Field
      {...props}
      type={show ? 'text' : 'password'}
      trailing={
        <button
          type="button"
          onClick={() => setShow((s) => !s)}
          aria-label={show ? 'Ocultar contraseña' : 'Mostrar contraseña'}
          className="flex h-10 w-10 items-center justify-center rounded-lg text-muted hover:bg-line-soft hover:text-ink"
        >
          <Icon name={show ? 'visibility_off' : 'visibility'} className="text-[20px]" />
        </button>
      }
    />
  )
}

export function Card({ children, className = '', ...rest }: HTMLAttributes<HTMLDivElement> & { children: ReactNode; className?: string }) {
  return (
    <div {...rest} className={`rounded-2xl border border-line bg-card shadow-tier1 ${className}`}>
      {children}
    </div>
  )
}

type Tone = 'success' | 'warning' | 'danger' | 'wallet' | 'primary' | 'neutral'
const TONES: Record<Tone, string> = {
  success: 'bg-success-soft text-success-ink',
  warning: 'bg-warning-soft text-warning-ink',
  danger: 'bg-danger-soft text-danger-ink',
  wallet: 'bg-wallet-soft text-wallet-ink',
  primary: 'bg-primary-soft text-primary-active',
  neutral: 'bg-line-soft text-muted',
}

export function Chip({ tone = 'neutral', icon, children }: { tone?: Tone; icon?: string; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-bold tracking-wide ${TONES[tone]}`}>
      {icon && <Icon name={icon} className="text-[13px]" />}
      {children}
    </span>
  )
}

export function SectionLabel({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="mb-2 flex items-center justify-between">
      <h2 className="text-xs font-semibold tracking-[0.06em] text-muted uppercase">{children}</h2>
      {action}
    </div>
  )
}

export function Modal({
  open,
  title,
  onClose,
  children,
  footer,
  wide = false,
}: {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  footer?: ReactNode
  wide?: boolean
}) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
  if (!open) return null
  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-navy/60 sm:items-center sm:p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal
        aria-label={title}
        onClick={(e) => e.stopPropagation()}
        className={`flex max-h-[92vh] w-full flex-col rounded-t-3xl border border-slate-300 bg-card shadow-tier3 sm:rounded-2xl ${wide ? 'sm:max-w-3xl' : 'sm:max-w-lg'}`}
      >
        <div className="flex items-center justify-between border-b border-line-soft px-5 py-4">
          <h2 className="text-lg font-semibold tracking-tight">{title}</h2>
          <button onClick={onClose} aria-label="Cerrar" className="flex h-10 w-10 items-center justify-center rounded-xl text-muted hover:bg-line-soft">
            <Icon name="close" />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-5 py-4">{children}</div>
        {footer && <div className="flex gap-2 border-t border-line-soft px-5 py-4 pb-safe">{footer}</div>}
      </div>
    </div>
  )
}

export function EmptyState({ icon, title, text, action }: { icon: string; title: string; text?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center px-6 py-14 text-center">
      <div className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-primary-tint text-primary">
        <Icon name={icon} className="text-[32px]" />
      </div>
      <h3 className="text-lg font-semibold">{title}</h3>
      {text && <p className="mt-1 max-w-sm text-sm text-muted">{text}</p>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  )
}

export function Spinner({ label = 'Cargando…' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-16 text-muted">
      <Icon name="progress_activity" className="animate-spin" />
      <span className="text-sm">{label}</span>
    </div>
  )
}

export function Banner({ tone: asked = 'danger', children }: { tone?: 'danger' | 'success' | 'warning' | 'primary'; children: ReactNode }) {
  // Algo guardado sin internet no es un error: se muestra en verde aunque la pantalla lo trate como error.
  const tone = children === QUEUED_MESSAGE ? 'success' : asked
  const map = {
    danger: 'bg-danger-soft text-danger-ink border-red-200',
    success: 'bg-success-soft text-success-ink border-emerald-200',
    warning: 'bg-warning-soft text-warning-ink border-amber-200',
    primary: 'bg-primary-tint text-primary-active border-blue-200',
  }
  const icon = { danger: 'error', success: 'check_circle', warning: 'warning', primary: 'info' }[tone]
  return (
    <div className={`flex items-start gap-2 rounded-xl border px-3.5 py-3 text-sm font-medium ${map[tone]}`}>
      <Icon name={icon} className="mt-px text-[18px]" />
      <div>{children}</div>
    </div>
  )
}

// ---------- Avisos flotantes ----------
interface Toast {
  id: number
  text: string
  tone: 'success' | 'danger'
}
const ToastContext = createContext<(text: string, tone?: 'success' | 'danger') => void>(() => {})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const push = useCallback((text: string, asked: 'success' | 'danger' = 'success') => {
    const id = Date.now() + Math.random()
    const tone = text === QUEUED_MESSAGE ? 'success' : asked
    // El mismo aviso dos veces seguidas (la pantalla y el aviso general) se muestra una sola vez.
    setToasts((t) => (t.some((x) => x.text === text) ? t : [...t, { id, text, tone }]))
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3500)
  }, [])
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed inset-x-0 bottom-24 z-[70] flex flex-col items-center gap-2 px-4 lg:bottom-6">
        {toasts.map((t) => (
          <div
            key={t.id}
            role="status"
            className={`animate-toast flex items-center gap-2 rounded-xl px-4 py-3 text-sm font-semibold text-white shadow-tier3 ${t.tone === 'success' ? 'bg-navy' : 'bg-danger-ink'}`}
          >
            <Icon name={t.tone === 'success' ? 'check_circle' : 'error'} className="text-[18px]" />
            {t.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  return useContext(ToastContext)
}

const fieldClass =
  'w-full rounded-xl border bg-card px-3.5 text-base text-ink outline-none transition placeholder:text-slate-400 focus:border-primary focus:ring-4 focus:ring-primary/15 disabled:bg-canvas'

export function SelectField({
  label,
  error,
  hint,
  className = '',
  children,
  ...rest
}: SelectHTMLAttributes<HTMLSelectElement> & { label: string; error?: string; hint?: string }) {
  const id = useId()
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-xs font-semibold tracking-wide text-muted uppercase">
        {label}
      </label>
      <select id={id} {...rest} className={`h-12 ${fieldClass} ${error ? 'border-danger' : 'border-line'}`}>
        {children}
      </select>
      {error ? <p className="mt-1 text-xs font-medium text-danger-ink">{error}</p> : hint && <p className="mt-1 text-xs text-muted">{hint}</p>}
    </div>
  )
}

export function TextArea({
  label,
  error,
  className = '',
  ...rest
}: TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; error?: string }) {
  const id = useId()
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-xs font-semibold tracking-wide text-muted uppercase">
        {label}
      </label>
      <textarea id={id} rows={3} {...rest} className={`py-3 ${fieldClass} ${error ? 'border-danger' : 'border-line'}`} />
      {error && <p className="mt-1 text-xs font-medium text-danger-ink">{error}</p>}
    </div>
  )
}

export function Toggle({ checked, onChange, label, hint }: { checked: boolean; onChange: (v: boolean) => void; label: string; hint?: string }) {
  return (
    <label className="flex cursor-pointer items-start justify-between gap-4 py-2">
      <span>
        <span className="block text-sm font-semibold">{label}</span>
        {hint && <span className="block text-xs text-muted">{hint}</span>}
      </span>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={`relative mt-0.5 h-7 w-12 shrink-0 rounded-full transition ${checked ? 'bg-primary' : 'bg-slate-300'}`}
      >
        <span className={`absolute top-0.5 h-6 w-6 rounded-full bg-white shadow transition ${checked ? 'left-[22px]' : 'left-0.5'}`} />
      </button>
    </label>
  )
}

export function Tabs<T extends string>({ value, onChange, items }: { value: T; onChange: (v: T) => void; items: { value: T; label: string; count?: number }[] }) {
  return (
    <div data-guide="tabs" className="flex gap-1 rounded-xl bg-primary-tint p-1">
      {items.map((it) => (
        <button
          key={it.value}
          type="button"
          onClick={() => onChange(it.value)}
          className={`flex h-10 flex-1 items-center justify-center gap-1.5 rounded-lg px-2 text-sm font-semibold transition ${
            value === it.value ? 'bg-card text-ink shadow-tier1' : 'text-muted hover:text-ink'
          }`}
        >
          {it.label}
          {it.count !== undefined && <span className="num rounded-full bg-primary-soft px-1.5 text-[11px] text-primary-active">{it.count}</span>}
        </button>
      ))}
    </div>
  )
}

export function StatCard({ label, value, icon, tone = 'primary', sub, help }: { label: string; value: ReactNode; icon: string; tone?: 'primary' | 'danger' | 'success' | 'wallet' | 'navy'; sub?: ReactNode; help?: ReactNode }) {
  const tones = {
    primary: 'bg-primary-tint text-primary',
    danger: 'bg-danger-soft text-danger-ink',
    success: 'bg-success-soft text-success-ink',
    wallet: 'bg-wallet-soft text-wallet-ink',
    navy: 'bg-line-soft text-navy',
  }
  return (
    <Card className="p-4">
      <div className="flex items-start justify-between gap-2">
        <span className="flex items-center gap-1 text-[11px] font-bold tracking-[0.06em] text-muted uppercase">
          {label}
          {help && <InfoTip title={label} text={help} />}
        </span>
        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-xl ${tones[tone]}`}>
          <Icon name={icon} className="text-[20px]" />
        </span>
      </div>
      <div className="num mt-1 text-2xl font-bold tracking-tight">{value}</div>
      {sub && <div className="mt-1 text-xs text-muted">{sub}</div>}
    </Card>
  )
}

export function Confirm({ open, title, text, confirmLabel = 'Confirmar', danger = false, onConfirm, onClose, loading }: {
  open: boolean
  title: string
  text: ReactNode
  confirmLabel?: string
  danger?: boolean
  onConfirm: () => void
  onClose: () => void
  loading?: boolean
}) {
  return (
    <Modal
      open={open}
      title={title}
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" className="flex-1" onClick={onClose}>
            Cancelar
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} className="flex-1" onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="text-sm text-ink/80">{text}</div>
    </Modal>
  )
}

// ---------- Ayuda (i) ----------
const TIP_W = 256

/**
 * Ícono (i) o (?) que explica algo: se abre al pasar el mouse y, en pantallas táctiles, al tocarlo.
 */
export function InfoTip({ text, title, className = '', icon = 'info' }: { text: ReactNode; title?: string; className?: string; icon?: 'help' | 'info' }) {
  const id = useId()
  const btnRef = useRef<HTMLButtonElement>(null)
  const [pos, setPos] = useState<{ top?: number; bottom?: number; left: number } | null>(null)

  useEffect(() => {
    if (!pos) return
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent && e.key !== 'Escape') return
      if (e instanceof MouseEvent && btnRef.current?.contains(e.target as Node)) return
      setPos(null)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    window.addEventListener('scroll', close, true)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [pos])

  function open() {
    const r = btnRef.current!.getBoundingClientRect()
    const left = Math.min(Math.max(r.left + r.width / 2 - TIP_W / 2, 12), window.innerWidth - TIP_W - 12)
    setPos(r.top > window.innerHeight * 0.6 ? { bottom: window.innerHeight - r.top + 8, left } : { top: r.bottom + 8, left })
  }

  function toggle(e: React.MouseEvent) {
    e.preventDefault()
    e.stopPropagation()
    if (pos) return setPos(null)
    open()
  }

  // Con mouse, se abre al pasar por encima; en pantallas táctiles, al tocar.
  const hover = typeof window !== 'undefined' && window.matchMedia?.('(hover: hover)').matches

  return (
    <>
      <button
        ref={btnRef}
        type="button"
        onClick={toggle}
        onMouseEnter={hover ? open : undefined}
        onMouseLeave={hover ? () => setPos(null) : undefined}
        aria-label={title ? `Ayuda: ${title}` : 'Ayuda'}
        aria-expanded={!!pos}
        aria-describedby={pos ? id : undefined}
        className={`inline-flex h-5 w-5 items-center justify-center rounded-full text-muted transition-colors hover:text-primary focus-visible:ring-2 focus-visible:ring-primary ${className}`}
      >
        <Icon name={icon} className="text-[16px]" />
      </button>
      {pos &&
        createPortal(
          <div
            id={id}
            role="tooltip"
            style={{ position: 'fixed', width: TIP_W, zIndex: 8000, ...pos }}
            className="rounded-xl border border-line bg-card p-3 text-left text-sm normal-case tracking-normal shadow-tier3"
          >
            {title && <div className="mb-1 font-semibold text-ink">{title}</div>}
            <div className="leading-relaxed text-muted">{text}</div>
          </div>,
          portalRoot(),
        )}
    </>
  )
}
