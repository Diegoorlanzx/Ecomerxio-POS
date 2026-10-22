import { useCallback, useEffect, useState } from "react";
import {
  Banner,
  Button,
  Card,
  EmptyState,
  Icon,
  InfoTip,
  Modal,
  SelectField,
  Spinner,
  StatCard,
  useToast,
} from "../../components/ui";
import { api, ApiError } from "../../lib/api";
import { useAuth } from "../../lib/auth";
import { money, shortDate } from "../../lib/format";
import {
  addDays,
  todayIso,
  type CommissionReport,
  type StaffCommission,
} from "../../lib/servicesTypes";

/** Quincena colombiana que contiene la fecha: del 1 al 15 o del 16 a fin de mes. */
function quincena(date: string): [string, string] {
  const [y, m, d] = date.split("-").map(Number) as [number, number, number];
  const last = new Date(y, m, 0).getDate();
  const mm = String(m).padStart(2, "0");
  return d <= 15
    ? [`${y}-${mm}-01`, `${y}-${mm}-15`]
    : [`${y}-${mm}-16`, `${y}-${mm}-${last}`];
}

/**
 * Comisiones por profesional en la quincena: lo que generó en servicios y en productos, el detalle venta por venta y
 * el botón para pagarlas (queda como gasto de nómina en Finanzas).
 */
export default function CommissionsPage() {
  const { can } = useAuth();
  const [[from, to], setRange] = useState<[string, string]>(() =>
    quincena(todayIso()),
  );
  const [rep, setRep] = useState<CommissionReport | null>(null);
  const [error, setError] = useState("");
  const [detail, setDetail] = useState<StaffCommission | null>(null);
  const [paying, setPaying] = useState<StaffCommission | null>(null);

  const load = useCallback(() => {
    api<CommissionReport>(`/services/commissions?from=${from}&to=${to}`)
      .then((r) => (setRep(r), setError("")))
      .catch((e) => setError(e.message));
  }, [from, to]);
  useEffect(load, [load]);

  if (error) return <Banner>{error}</Banner>;
  if (!rep) return <Spinner />;
  const pending = rep.staff
    .filter((s) => s.total > 0 && !s.payoutId)
    .reduce((a, s) => a + s.total, 0);

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="flex items-center gap-1.5 text-2xl font-bold tracking-tight">
            Comisiones
            <InfoTip text="Cada venta cobrada a nombre de un profesional (una cita, una orden de servicio o una cuenta suya) le genera comisión: el % de servicios sobre los servicios y el % de productos sobre lo demás, sin IVA y sin lo devuelto. Al pagarla queda como gasto de nómina." />
          </h1>
          <p className="text-sm text-muted">
            Del {shortDate(rep.from)} al {shortDate(rep.to)}
          </p>
        </div>
        <div
          className="flex items-center gap-2"
          data-guide="comisiones-periodo"
        >
          <Button
            variant="secondary"
            size="sm"
            icon="chevron_left"
            onClick={() => setRange(quincena(addDays(from, -1)))}
          >
            <span className="sr-only">Quincena anterior</span>
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => setRange(quincena(todayIso()))}
          >
            Esta quincena
          </Button>
          <Button
            variant="secondary"
            size="sm"
            icon="chevron_right"
            onClick={() => setRange(quincena(addDays(to, 1)))}
          >
            <span className="sr-only">Quincena siguiente</span>
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <StatCard
          label="Comisiones de la quincena"
          value={money(rep.total)}
          icon="percent"
          help="Lo que generaron todos los profesionales en el período."
        />
        <StatCard
          label="Por pagar"
          value={money(pending)}
          icon="pending_actions"
          tone="wallet"
          help="Comisiones del período que todavía no se han pagado."
        />
      </div>

      {rep.staff.length === 0 ? (
        <EmptyState
          icon="groups"
          title="Sin profesionales"
          text="Crea tus profesionales en Agenda → Configurar."
        />
      ) : (
        <div
          className="grid gap-3 md:grid-cols-2"
          data-guide="comisiones-lista"
        >
          {rep.staff.map((s) => (
            <Card key={s.staffId} className="p-4">
              <div className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span
                    className="h-3.5 w-3.5 rounded-full"
                    style={{ background: s.color }}
                  />
                  <span className="font-bold">{s.name}</span>
                </div>
                <span className="num text-xl font-bold">{money(s.total)}</span>
              </div>
              <div className="mt-2 grid grid-cols-2 gap-2 text-sm">
                <div className="rounded-xl bg-canvas p-2">
                  <div className="text-xs text-muted">
                    Servicios ({s.services})
                  </div>
                  <div className="num font-semibold">
                    {money(s.serviceCommission)}
                  </div>
                  <div className="num text-xs text-muted">
                    de {money(s.serviceBase)}
                  </div>
                </div>
                <div className="rounded-xl bg-canvas p-2">
                  <div className="text-xs text-muted">Productos</div>
                  <div className="num font-semibold">
                    {money(s.productCommission)}
                  </div>
                  <div className="num text-xs text-muted">
                    de {money(s.productBase)}
                  </div>
                </div>
              </div>
              <div className="mt-3 flex gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  icon="list"
                  onClick={() => setDetail(s)}
                  disabled={!s.lines.length}
                >
                  Detalle
                </Button>
                {s.payoutId ? (
                  <span className="flex items-center gap-1 text-sm font-semibold text-success-ink">
                    <Icon name="check_circle" className="text-[18px]" /> Pagada
                  </span>
                ) : (
                  can("FINANZAS_GASTOS") &&
                  s.total > 0 && (
                    <Button
                      size="sm"
                      icon="payments"
                      onClick={() => setPaying(s)}
                    >
                      Pagar {money(s.total)}
                    </Button>
                  )
                )}
              </div>
            </Card>
          ))}
        </div>
      )}

      {rep.payouts.length > 0 && (
        <div>
          <h2 className="mb-2 text-sm font-semibold text-muted uppercase">
            Pagos hechos
          </h2>
          <ul className="divide-y divide-line-soft rounded-2xl border border-line bg-card text-sm">
            {rep.payouts.map((p) => (
              <li
                key={p.id}
                className="flex items-center justify-between gap-2 px-4 py-2.5"
              >
                <span>
                  <b>{p.staffName}</b> · del {shortDate(p.from)} al{" "}
                  {shortDate(p.to)}
                </span>
                <span className="num font-semibold">{money(p.amount)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {detail && (
        <Modal
          open
          wide
          title={`Comisiones de ${detail.name}`}
          onClose={() => setDetail(null)}
        >
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted uppercase">
              <tr>
                <th className="py-1">Fecha</th>
                <th>Venta</th>
                <th>Concepto</th>
                <th className="text-right">Base</th>
                <th className="text-right">%</th>
                <th className="text-right">Comisión</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-soft">
              {detail.lines.map((l, i) => (
                <tr key={i}>
                  <td className="py-1.5">{shortDate(l.date)}</td>
                  <td>#{l.saleNumber}</td>
                  <td>
                    {l.item}{" "}
                    {l.service && (
                      <span className="text-xs text-primary">servicio</span>
                    )}
                  </td>
                  <td className="num text-right">{money(l.base)}</td>
                  <td className="num text-right">{l.pct}</td>
                  <td className="num text-right font-semibold">
                    {money(l.commission)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Modal>
      )}
      {paying && (
        <PayModal
          s={paying}
          from={rep.from}
          to={rep.to}
          onClose={() => setPaying(null)}
          onDone={(r) => (setPaying(null), setRep(r))}
        />
      )}
    </div>
  );
}

function PayModal({
  s,
  from,
  to,
  onClose,
  onDone,
}: {
  s: StaffCommission;
  from: string;
  to: string;
  onClose: () => void;
  onDone: (r: CommissionReport) => void;
}) {
  const toast = useToast();
  const [paidFrom, setPaidFrom] = useState("BANCO");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function pay() {
    setBusy(true);
    setError("");
    try {
      onDone(
        await api<CommissionReport>("/services/commissions/pay", {
          body: { staffId: s.staffId, from, to, paidFrom },
        }),
      );
      toast(`Comisiones de ${s.name} pagadas`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "No se pudo pagar");
    } finally {
      setBusy(false);
    }
  }
  return (
    <Modal
      open
      title={`Pagar a ${s.name}`}
      onClose={onClose}
      footer={
        <Button className="w-full" icon="payments" loading={busy} onClick={pay}>
          Pagar {money(s.total)}
        </Button>
      }
    >
      <div className="space-y-3">
        {error && <Banner>{error}</Banner>}
        <p className="text-sm">
          Queda un gasto de <b>Nómina</b> por {money(s.total)} en Finanzas:
          "Comisiones de {s.name} del {from} al {to}".
        </p>
        <SelectField
          label="¿De dónde sale el dinero?"
          value={paidFrom}
          onChange={(e) => setPaidFrom(e.target.value)}
        >
          <option value="BANCO">Banco / transferencia</option>
          <option value="CAJA">
            Efectivo de la caja (necesita el turno abierto)
          </option>
          <option value="OTRO">Otro</option>
        </SelectField>
      </div>
    </Modal>
  );
}
