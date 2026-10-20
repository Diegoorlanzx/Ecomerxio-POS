"""
Carga en la tienda de ejemplo (Cigarrería El Paisa) datos para mostrar Facturación electrónica y Contabilidad.

Uso:
    python -I scripts/demo_facturacion_contabilidad.py [URL_API] [CORREO] [CONTRASEÑA]
    (por defecto http://localhost:8090/api y el dueño de prueba de docs/DEV.md)

Qué hace (se puede correr varias veces: lo que ya existe no se repite):
  1. Datos fiscales del negocio y dos resoluciones de numeración (POS electrónico y factura).
  2. Clasifica como "excluido" los productos con IVA 0 % que no tengan clasificación.
  3. Documento de prueba con el proveedor SIMULADO y activa la facturación (sin validez ante la DIAN).
  4. Activa la contabilidad automática desde hoy.
  5. Clientes con datos para factura (persona natural, empresa y dos de muestra para el centro de errores).
  6. Ventas de ejemplo: consumidor final (documento POS), facturas con cliente, pago Nequi, fiado, una
     anulación (nota crédito) y una devolución parcial; una venta a "SIM-RECHAZO" queda rechazada para mostrar
     el centro de errores.
Todo es de demostración: el proveedor es simulado y el PUC es una propuesta para revisar con el contador.
"""
import json
import sys
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/api"
EMAIL = sys.argv[2] if len(sys.argv) > 2 else "dueno@demo.tiendaposs.test"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "Demo2026pos"
TOKEN = None


def call(method, path, body=None, ok=(200, 201, 204)):
    headers = {"Content-Type": "application/json"}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    req = urllib.request.Request(BASE + path, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        if e.code in ok:
            return e.code, None
        return e.code, raw[:300]


def step(msg):
    print("•", msg)


def main():
    global TOKEN
    st, r = call("POST", "/auth/login", {"email": EMAIL, "password": PASSWORD})
    if st != 200:
        sys.exit(f"No se pudo entrar: {st} {r}")
    TOKEN = r["token"]
    today = date.today()

    # 1. Datos fiscales
    nit = "71654321"
    dv = call("GET", f"/fiscal/dv?number={nit}")[1]["dv"]
    st, r = call("PUT", "/fiscal/profile", {
        "personType": "NATURAL", "idType": "NIT", "idNumber": nit, "dv": dv,
        "legalName": "Jorge Iván Restrepo Gómez", "tradeName": "Cigarrería El Paisa",
        "ivaResponsible": True, "responsibilities": ["R-99-PN"], "ciiu": "4711",
        "address": "Carrera 70 # 44-21", "municipalityCode": "05001", "municipalityName": "Medellín",
        "departmentName": "Antioquia", "postalCode": "050031", "invoicingEmail": "facturas@elpaisa.demo",
        "phone": "3001234567", "registeredAsIssuer": True, "issuerRegisteredOn": str(today - timedelta(days=30)),
        "providerName": "Simulador (demostración)", "providerAuthorized": True, "environment": "PRUEBAS"})
    step(f"Datos fiscales: {st}")

    existing = {(x["documentType"], x.get("prefix")) for x in (call("GET", "/fiscal/resolutions")[1] or [])}
    for typ, prefix, num, frm, to in [("POS_ELECTRONICO", "POSE", "18764000001234", 1, 5000),
                                      ("FACTURA", "FEPA", "18764000005678", 1, 2000)]:
        if (typ, prefix) in existing:
            continue
        st, r = call("POST", "/fiscal/resolutions", {
            "documentType": typ, "resolutionNumber": num, "prefix": prefix, "rangeFrom": frm, "rangeTo": to,
            "validFrom": str(today - timedelta(days=30)), "validTo": str(today + timedelta(days=700))})
        step(f"Resolución {typ} {prefix}: {st} {'' if st == 200 else r}")

    # 2. Productos con IVA 0 % sin clasificar
    pending = call("GET", "/fiscal/products?pending=true")[1] or []
    ids = [p.get("productId", p.get("id")) for p in pending]
    if ids:
        st, r = call("POST", "/fiscal/products/classify", {"productIds": ids, "taxClass": "EXCLUIDO"})
        step(f"{len(ids)} productos clasificados como excluidos: {st}")

    # 3. Prueba con el simulador y activar
    summary = call("GET", "/einvoice/summary")[1] or {}
    if not summary.get("enabled"):
        st, r = call("POST", "/einvoice/test")
        step(f"Documento de prueba: {st}")
        st, r = call("POST", "/einvoice/enable", {"enabled": True})
        step(f"Facturación activada: {st} {'' if st == 200 else r}")

    # 4. Contabilidad
    acc = call("GET", "/accounting/status")[1] or {}
    if not acc.get("enabled"):
        st, r = call("POST", "/accounting/enable", {"enabled": True, "startDate": str(today)})
        step(f"Contabilidad activada desde hoy: {st}")

    # 5. Clientes
    def customer(name, body):
        found = [c for c in (call("GET", "/pos/customers?q=" + urllib.request.quote(name))[1] or []) if c["name"] == name]
        if found:
            return found[0]["id"]
        st, r = call("POST", "/pos/customers", {"name": name, **body})
        if st not in (200, 201):
            step(f"Cliente {name}: {st} {r}")
            return None
        return r["id"]

    maria = customer("María Fernanda Gómez", {"docType": "CC", "docNumber": "43876543", "personType": "NATURAL",
                                              "invoiceEmail": "maria.gomez@correo.demo", "phone": "3105551234"})
    la70 = customer("Distribuidora La 70 SAS", {"docType": "NIT", "docNumber": "900456789", "personType": "JURIDICA",
                                                "dv": call("GET", "/fiscal/dv?number=900456789")[1]["dv"],
                                                "invoiceEmail": "compras@la70.demo", "phone": "6044445566"})
    rechazo = customer("Cliente de prueba SIM-RECHAZO", {"docType": "CC", "docNumber": "1000000001", "personType": "NATURAL",
                                                         "invoiceEmail": "rechazo@correo.demo"})
    step("Clientes de ejemplo listos")

    # 6. Turno y ventas
    shift = call("GET", "/shifts/current")[1]
    if not shift:
        reg = call("GET", "/registers")[1][0]["id"]
        call("POST", "/shifts/open", {"registerId": reg, "openingCash": 100000})
        step("Turno abierto con base de $100.000")

    catalog = call("GET", "/pos/catalog")[1]
    by_name = {p["name"]: p for p in catalog}
    # Si ya hay ventas con documento de ejemplo (corrida anterior), solo se completa lo que falte.
    docs = call("GET", "/einvoice/documents")[1] or []
    done = {d.get("customerName") for d in (docs if isinstance(docs, list) else docs.get("items", []))}

    def pres(name):
        p = by_name.get(name) or next((x for x in catalog if x["presentations"]), None)
        return p["presentations"][0]["id"], float(p["presentations"][0]["price"])

    def sell(items, method="EFECTIVO", cust=None, invoice=False, label=""):
        lines = [{"presentationId": pres(n)[0], "quantity": q} for n, q in items]
        total = sum(pres(n)[1] * q for n, q in items)
        body = {"clientRef": str(uuid.uuid4()), "lines": lines, "payments": [{"method": method, "amount": round(total)}],
                "electronicInvoice": invoice}
        if method == "NEQUI":
            body["payments"][0]["reference"] = "M" + uuid.uuid4().hex[:8].upper()
        if cust:
            body["customer"] = {"customerId": cust}
        st, r = call("POST", "/pos/sales", body)
        if st not in (200, 201):
            step(f"Venta {label}: {st} {r}")
            return None
        step(f"Venta #{r['number']} {label}: ${r['total']:,.0f}".replace(",", "."))
        return r

    # Productos con existencias suficientes (los más surtidos primero).
    stocked = sorted((p for p in catalog if p["presentations"] and float(p.get("stock") or 0) >= 8), key=lambda p: -float(p.get("stock") or 0))
    a, b, c = (stocked + catalog)[0]["name"], (stocked + catalog)[1]["name"], (stocked + catalog)[2]["name"]
    first_run = "María Fernanda Gómez" not in done
    if first_run:
        sell([(a, 2), (c, 3)], label="consumidor final (POS electrónico)")
        sell([(b, 2), (a, 1)], method="NEQUI", label="consumidor final por Nequi")
        sell([(c, 2), (b, 1)], cust=maria, invoice=True, label="factura a María Fernanda")
    big = None
    if la70 and "Distribuidora La 70 SAS" not in done:
        big = sell([(a, 6), (c, 4)], cust=la70, invoice=True, label="factura a Distribuidora La 70")
    if maria and first_run:
        sell([(b, 3)], method="FIADO", cust=maria, invoice=True, label="factura fiada a María Fernanda")
    void = sell([(a, 1)], label="que se anulará (nota crédito)") if first_run else None
    if void:
        st, r = call("POST", f"/pos/sales/{void['id']}/void", {"reason": "El cliente se arrepintió (ejemplo)"})
        step(f"Anulación de la venta #{void['number']}: {st}")
    if big:
        line = big["lines"][0]
        st, r = call("POST", f"/pos/sales/{big['id']}/returns", {"reason": "Producto en mal estado (ejemplo)", "refundMethod": "EFECTIVO",
                                                                 "lines": [{"saleLineId": line["id"], "quantity": 2, "restock": False}]})
        step(f"Devolución parcial de la venta #{big['number']}: {st}")
    if rechazo and "Cliente de prueba SIM-RECHAZO" not in done:
        sell([(a, 1)], cust=rechazo, invoice=True, label="factura que el simulador rechaza (centro de errores)")

    call("POST", "/einvoice/process")
    s = call("GET", "/einvoice/summary")[1] or {}
    print("\nFacturación:", json.dumps({k: v for k, v in s.items() if not isinstance(v, (list, dict))}, ensure_ascii=False))
    tb = call("GET", "/accounting/trial-balance")[1] or {}
    print("Contabilidad: balance de prueba", "cuadra" if tb.get("balanced") else "NO cuadra",
          f"(débitos {tb.get('totalDebit')} = créditos {tb.get('totalCredit')})")


if __name__ == "__main__":
    main()
