import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import ProductPicker from "../../components/ProductPicker";
import {
  Banner,
  Button,
  Card,
  Field,
  Icon,
  InfoTip,
  Modal,
  SelectField,
  Spinner,
  TextArea,
  useToast,
} from "../../components/ui";
import { api, ApiError } from "../../lib/api";
import { dateTime, money, qty, shortDate } from "../../lib/format";
import {
  METHOD_LABELS,
  type ProductDto,
  type SaleDto,
  type StoreSettings,
} from "../../lib/posTypes";
import {
  ORDER_STATUS,
  type OrderDto,
  type OrderStatus,
  type StaffDto,
} from "../../lib/servicesTypes";

const STEPS: OrderStatus[] = [
  "RECIBIDO",
  "DIAGNOSTICO",
  "ESPERANDO_APROBACION",
  "EN_REPARACION",
  "LISTO",
  "ENTREGADO",
];

/** Una orden de servicio: diagnóstico y cotización, repuestos, anticipos, avisos por WhatsApp y entrega con cobro. */
export default function ServiceOrderDetailPage() {
  const { id } = useParams();
  const toast = useToast();
  const [o, setO] = useState<OrderDto | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  const [staff, setStaff] = useState<StaffDto[]>([]);
  const [methods, setMethods] = useState<string[]>(["EFECTIVO"]);
  const [diag, setDiag] = useState("");
  const [labor, setLabor] = useState("");
  const [tech, setTech] = useState("");
  const [deposit, setDeposit] = useState(false);
  const [deliver, setDeliver] = useState(false);

  const apply = (x: OrderDto) => {
    setO(x);
    setDiag(x.diagnosis ?? "");
    setLabor(x.laborPrice ? String(x.laborPrice) : "");
    setTech(x.technicianId ? String(x.technicianId) : "");
  };
  const load = useCallback(
    () =>
      void api<OrderDto>(`/service-orders/${id}`)
        .then(apply)
        .catch((e) => setError(e.message)),
    [id],
  );
  useEffect(() => {
    load();
    api<StaffDto[]>("/services/staff")
      .then(setStaff)
      .catch(() => {});
    api<StoreSettings>("/settings/pos")
      .then((s) =>
        setMethods(
          s.paymentMethods.split(",").filter((m) => m && m !== "FIADO"),
        ),
      )
      .catch(() => {});
  }, [load]);

  async function run(
    key: string,
    fn: () => Promise<OrderDto | void>,
    msg?: string,
  ) {
    setBusy(key);
    setError("");
    try {
      const r = await fn();
      if (r) apply(r);
      if (msg) toast(msg);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "No se pudo");
    } finally {
      setBusy("");
    }
  }

  if (!o) return error ? <Banner>{error}</Banner> : <Spinner />;
  const open = o.status !== "ENTREGADO" && o.status !== "CANCELADO";
  const st = ORDER_STATUS[o.status];
  const dirty =
    diag !== (o.diagnosis ?? "") ||
    Number(labor || 0) !== Number(o.laborPrice) ||
    tech !== (o.technicianId ? String(o.technicianId) : "");
  const status = (s: OrderStatus, msg: string) =>
    run(
      s,
      () =>
        api<OrderDto>(`/service-orders/${o.id}/status`, {
          body: { status: s },
        }),
      msg,
    );
  const saveDiag = () =>
    run(
      "save",
      () =>
        api<OrderDto>(`/service-orders/${o.id}`, {
          method: "PUT",
          body: {
            customerId: o.customerId,
            customerName: o.customerName,
            customerPhone: o.customerPhone,
            device: o.device,
            brand: o.brand,
            model: o.model,
            serial: o.serial,
            problem: o.problem,
            accessories: o.accessories,
            conditionNotes: o.conditionNotes,
            diagnosis: diag,
            laborPrice: Number(labor || 0),
            technicianId: tech ? Number(tech) : null,
            warrantyDays: o.warrantyDays,
            promisedAt: o.promisedAt,
          },
        }),
      "Guardado",
    );

  return (
    <div className="space-y-4">
      <Link
        to="/ordenes-servicio"
        className="inline-flex items-center gap-1 text-sm font-semibold text-primary"
      >
        <Icon name="arrow_back" className="text-[18px]" /> Órdenes de servicio
      </Link>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">
            #{o.number} · {o.device} {o.brand ?? ""} {o.model ?? ""}
          </h1>
          <p className="text-sm text-muted">
            {o.customerName}
            {o.customerPhone && ` · ${o.customerPhone}`} · recibido{" "}
            {dateTime(o.createdAt)}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <span
            className={`rounded-lg px-2.5 py-1 text-sm font-semibold ${st.tone}`}
          >
            {st.label}
          </span>
          {o.whatsappUrl && (
            <a
              href={o.whatsappUrl}
              target="_blank"
              rel="noreferrer"
              className="flex h-9 items-center gap-1.5 rounded-lg bg-success px-3 text-sm font-semibold text-white"
            >
              <Icon name="chat" className="text-[18px]" /> Avisar por WhatsApp
            </a>
          )}
        </div>
      </div>

      {o.status !== "CANCELADO" && (
        <ol className="flex gap-1 overflow-x-auto" data-guide="orden-pasos">
          {STEPS.map((s, i) => {
            const done = STEPS.indexOf(o.status) >= i;
            return (
              <li
                key={s}
                className={`flex-1 rounded-lg px-2 py-1.5 text-center text-[11px] font-semibold whitespace-nowrap ${done ? "bg-primary text-white" : "bg-line-soft text-muted"}`}
              >
                {ORDER_STATUS[s].label}
              </li>
            );
          })}
        </ol>
      )}

      {error && <Banner>{error}</Banner>}

      {open && (
        <div className="flex flex-wrap gap-2" data-guide="orden-acciones">
          {o.status === "RECIBIDO" && (
            <Button
              icon="troubleshoot"
              loading={busy === "DIAGNOSTICO"}
              onClick={() => status("DIAGNOSTICO", "En diagnóstico")}
            >
              Empezar diagnóstico
            </Button>
          )}
          {(o.status === "RECIBIDO" || o.status === "DIAGNOSTICO") && (
            <Button
              variant={o.status === "DIAGNOSTICO" ? "primary" : "secondary"}
              icon="request_quote"
              loading={busy === "ESPERANDO_APROBACION"}
              onClick={() =>
                status(
                  "ESPERANDO_APROBACION",
                  "Cotización lista: avísale al cliente por WhatsApp",
                )
              }
            >
              Pedir aprobación ({money(o.total)})
            </Button>
          )}
          {o.status === "ESPERANDO_APROBACION" && (
            <>
              <Button
                icon="thumb_up"
                loading={busy === "yes"}
                onClick={() =>
                  run(
                    "yes",
                    () =>
                      api<OrderDto>(`/service-orders/${o.id}/quote`, {
                        body: { approved: true },
                      }),
                    "Aprobada: a reparar",
                  )
                }
              >
                El cliente aprobó
              </Button>
              <Button
                variant="secondary"
                icon="thumb_down"
                loading={busy === "no"}
                onClick={() =>
                  run(
                    "no",
                    () =>
                      api<OrderDto>(`/service-orders/${o.id}/quote`, {
                        body: { approved: false },
                      }),
                    "Se devuelve sin reparar",
                  )
                }
              >
                No aprobó
              </Button>
            </>
          )}
          {(o.status === "EN_REPARACION" || o.status === "DIAGNOSTICO") && (
            <Button
              variant={o.status === "EN_REPARACION" ? "primary" : "secondary"}
              icon="task_alt"
              loading={busy === "LISTO"}
              onClick={() => status("LISTO", "Listo: avísale al cliente")}
            >
              Marcar listo
            </Button>
          )}
          {o.status === "LISTO" && (
            <Button icon="point_of_sale" onClick={() => setDeliver(true)}>
              Entregar y cobrar {money(o.balance)}
            </Button>
          )}
          <Button
            variant="secondary"
            icon="savings"
            onClick={() => setDeposit(true)}
          >
            Recibir anticipo
          </Button>
          <Button
            variant="ghost"
            icon="block"
            loading={busy === "CANCELADO"}
            onClick={() => status("CANCELADO", "Orden cancelada")}
          >
            Cancelar orden
          </Button>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-[1fr_340px]">
        <div className="space-y-4">
          <Card className="space-y-2 p-4 text-sm">
            <div>
              <span className="font-semibold">Falla reportada:</span>{" "}
              {o.problem}
            </div>
            {o.accessories && (
              <div>
                <span className="font-semibold">Accesorios:</span>{" "}
                {o.accessories}
              </div>
            )}
            {o.conditionNotes && (
              <div>
                <span className="font-semibold">Estado físico:</span>{" "}
                {o.conditionNotes}
              </div>
            )}
            {o.serial && (
              <div>
                <span className="font-semibold">Serial / IMEI:</span> {o.serial}
              </div>
            )}
            {o.promisedAt && (
              <div>
                <span className="font-semibold">Prometido para:</span>{" "}
                {shortDate(o.promisedAt)}
              </div>
            )}
            {o.warrantyUntil && (
              <div className="font-semibold text-success-ink">
                Garantía hasta el {shortDate(o.warrantyUntil)} ({o.warrantyDays}{" "}
                días)
              </div>
            )}
          </Card>

          <Card className="space-y-3 p-4" data-guide="orden-diagnostico">
            <h2 className="flex items-center gap-1 font-semibold">
              Diagnóstico y cotización
              <InfoTip text="Escribe qué tiene el equipo y cuánto cobras de mano de obra. Los repuestos se suman abajo. Con 'Pedir aprobación' el botón de WhatsApp le manda al cliente el diagnóstico y el valor." />
            </h2>
            <TextArea
              label="Diagnóstico"
              value={diag}
              onChange={(e) => setDiag(e.target.value)}
              disabled={!open}
            />
            <div className="grid gap-3 sm:grid-cols-2">
              <Field
                label="Mano de obra"
                inputMode="numeric"
                value={labor}
                onChange={(e) => setLabor(e.target.value.replace(/\D/g, ""))}
                disabled={!open}
              />
              <SelectField
                label="Técnico"
                value={tech}
                onChange={(e) => setTech(e.target.value)}
                disabled={!open}
              >
                <option value="">Sin asignar</option>
                {staff.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </SelectField>
            </div>
            {open && dirty && (
              <Button icon="save" loading={busy === "save"} onClick={saveDiag}>
                Guardar diagnóstico
              </Button>
            )}
          </Card>

          <Card className="space-y-3 p-4" data-guide="orden-repuestos">
            <h2 className="font-semibold">Repuestos</h2>
            {open && (
              <ProductPicker
                placeholder="Agregar repuesto del inventario…"
                onPick={(p: ProductDto) => {
                  const pr =
                    p.presentations.find((x) => x.isBase) ?? p.presentations[0];
                  if (pr)
                    run(
                      "part",
                      () =>
                        api<OrderDto>(`/service-orders/${o.id}/parts`, {
                          body: { presentationId: pr.id, quantity: 1 },
                        }),
                      `${p.name} agregado`,
                    );
                }}
              />
            )}
            {o.parts.length === 0 ? (
              <p className="text-sm text-muted">
                Sin repuestos. Salen del inventario cuando se entrega el equipo.
              </p>
            ) : (
              <ul className="divide-y divide-line-soft text-sm">
                {o.parts.map((p) => (
                  <li key={p.id} className="flex items-center gap-2 py-2">
                    <span className="flex-1">
                      {qty(p.quantity)} × {p.name}
                    </span>
                    <span className="num font-semibold">{money(p.total)}</span>
                    {open && (
                      <button
                        type="button"
                        aria-label="Quitar"
                        onClick={() =>
                          run("rm" + p.id, () =>
                            api<OrderDto>(
                              `/service-orders/${o.id}/parts/${p.id}`,
                              { method: "DELETE" },
                            ),
                          )
                        }
                      >
                        <Icon
                          name="delete"
                          className="text-[18px] text-muted"
                        />
                      </button>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </Card>
        </div>

        <div className="space-y-4">
          <Card className="space-y-1.5 p-4 text-sm">
            <Row label="Repuestos" value={money(o.partsTotal)} />
            <Row label="Mano de obra" value={money(o.laborPrice)} />
            <Row label="Total" value={money(o.total)} bold />
            <Row label="Anticipos" value={`− ${money(o.depositsTotal)}`} />
            <Row
              label={o.status === "ENTREGADO" ? "Cobrado al entregar" : "Saldo"}
              value={money(o.balance)}
              bold
            />
            {o.deposits.length > 0 && (
              <ul className="mt-2 space-y-1 border-t border-line-soft pt-2 text-xs text-muted">
                {o.deposits.map((d) => (
                  <li key={d.id}>
                    {shortDate(d.createdAt)} · {money(d.amount)} en{" "}
                    {(METHOD_LABELS[d.method] ?? d.method).toLowerCase()} ·{" "}
                    {d.userName}
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card className="p-4">
            <h2 className="mb-2 text-sm font-semibold">Historial</h2>
            <ol className="space-y-2 text-xs">
              {o.events
                .slice()
                .reverse()
                .map((e, i) => (
                  <li key={i}>
                    <span className="font-semibold">
                      {ORDER_STATUS[e.status as OrderStatus]?.label ?? e.status}
                    </span>
                    {e.note && ` · ${e.note}`}
                    <div className="text-muted">
                      {dateTime(e.createdAt)} · {e.userName}
                    </div>
                  </li>
                ))}
            </ol>
          </Card>
        </div>
      </div>

      {deposit && (
        <DepositModal
          order={o}
          methods={methods}
          onClose={() => setDeposit(false)}
          onDone={(x) => {
            setDeposit(false);
            apply(x);
            toast("Anticipo recibido");
          }}
        />
      )}
      {deliver && (
        <DeliverModal
          order={o}
          methods={methods}
          onClose={() => setDeliver(false)}
          onDone={(x, sale) => {
            setDeliver(false);
            apply(x);
            toast(sale ? `Entregado: venta #${sale.number}` : "Entregado");
          }}
        />
      )}
    </div>
  );
}

function Row({
  label,
  value,
  bold,
}: {
  label: string;
  value: string;
  bold?: boolean;
}) {
  return (
    <div
      className={`flex justify-between ${bold ? "text-base font-bold" : ""}`}
    >
      <span>{label}</span>
      <span className="num">{value}</span>
    </div>
  );
}

function DepositModal({
  order,
  methods,
  onClose,
  onDone,
}: {
  order: OrderDto;
  methods: string[];
  onClose: () => void;
  onDone: (o: OrderDto) => void;
}) {
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState(methods[0] ?? "EFECTIVO");
  const [reference, setReference] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function save() {
    setBusy(true);
    setError("");
    try {
      onDone(
        await api<OrderDto>(`/service-orders/${order.id}/deposits`, {
          body: { amount: Number(amount), method, reference },
        }),
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "No se pudo registrar");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      open
      title={`Anticipo · orden #${order.number}`}
      onClose={onClose}
      footer={
        <Button
          className="w-full"
          icon="savings"
          loading={busy}
          onClick={save}
          disabled={!Number(amount)}
        >
          Recibir {Number(amount) ? money(Number(amount)) : ""}
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <Field
          label="Valor"
          inputMode="numeric"
          value={amount}
          onChange={(e) => setAmount(e.target.value.replace(/\D/g, ""))}
          autoFocus
        />
        <SelectField
          label="Medio"
          value={method}
          onChange={(e) => setMethod(e.target.value)}
        >
          {methods.map((m) => (
            <option key={m} value={m}>
              {METHOD_LABELS[m] ?? m}
            </option>
          ))}
        </SelectField>
        {method !== "EFECTIVO" && (
          <Field
            label="Referencia (opcional)"
            value={reference}
            onChange={(e) => setReference(e.target.value)}
          />
        )}
        <p className="text-xs text-muted">
          El anticipo cuenta como dinero recibido hoy (en efectivo entra a tu
          caja). Al entregar se descuenta del cobro.
        </p>
      </div>
    </Modal>
  );
}

function DeliverModal({
  order,
  methods,
  onClose,
  onDone,
}: {
  order: OrderDto;
  methods: string[];
  onClose: () => void;
  onDone: (o: OrderDto, s: SaleDto | null) => void;
}) {
  const [method, setMethod] = useState(methods[0] ?? "EFECTIVO");
  const [received, setReceived] = useState(
    order.balance ? String(order.balance) : "",
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const change =
    method === "EFECTIVO"
      ? Math.max(0, Number(received || 0) - order.balance)
      : 0;
  async function go() {
    setBusy(true);
    setError("");
    try {
      const payments =
        order.balance > 0
          ? [
              {
                method,
                amount:
                  method === "EFECTIVO" ? Number(received || 0) : order.balance,
              },
            ]
          : [];
      const r = await api<{ order: OrderDto; sale: SaleDto | null }>(
        `/service-orders/${order.id}/deliver`,
        { body: { payments } },
      );
      onDone(r.order, r.sale);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "No se pudo entregar");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      open
      title={`Entregar orden #${order.number}`}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="done_all" loading={busy} onClick={go}>
          {order.balance > 0
            ? `Cobrar ${money(order.balance)} y entregar`
            : "Entregar"}
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <div className="rounded-xl bg-canvas p-3 text-sm">
          <Row label="Total" value={money(order.total)} />
          <Row label="Anticipos" value={`− ${money(order.depositsTotal)}`} />
          <Row label="Saldo" value={money(order.balance)} bold />
        </div>
        {order.balance > 0 && (
          <>
            <SelectField
              label="Medio de pago"
              value={method}
              onChange={(e) => setMethod(e.target.value)}
            >
              {methods.map((m) => (
                <option key={m} value={m}>
                  {METHOD_LABELS[m] ?? m}
                </option>
              ))}
            </SelectField>
            {method === "EFECTIVO" && (
              <Field
                label="Recibido"
                inputMode="numeric"
                value={received}
                onChange={(e) => setReceived(e.target.value.replace(/\D/g, ""))}
                hint={change > 0 ? `Cambio: ${money(change)}` : undefined}
              />
            )}
          </>
        )}
        <p className="text-xs text-muted">
          Los repuestos salen del inventario, la venta queda en el turno y la
          garantía de {order.warrantyDays} días empieza hoy.
        </p>
      </div>
    </Modal>
  );
}
