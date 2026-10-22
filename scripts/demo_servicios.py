"""
Crea (o completa) una barbería de prueba para mostrar la Fase 11: profesionales con horario y comisión, servicios con
duración, agenda de hoy y mañana, citas desde la tienda en línea y órdenes de servicio técnico.

Uso:
    python -I scripts/demo_servicios.py [URL_API] [CORREO] [CONTRASEÑA]
Si la tienda no existe la registra con esos datos (la cuenta de prueba está en docs/DEV.md).
Se puede correr varias veces: lo que ya existe no se repite.
"""
import datetime
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/api"
EMAIL = sys.argv[2] if len(sys.argv) > 2 else "servicios@demo.tiendaposs.test"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else None
TOKEN = None


def call(method, path, body=None):
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
        return e.code, e.read().decode(errors="replace")[:300]


def main():
    global TOKEN
    if not PASSWORD:
        sys.exit("Falta la contraseña (está en docs/DEV.md)")
    st, r = call("POST", "/auth/login", {"email": EMAIL, "password": PASSWORD})
    if st != 200:
        st, r = call("POST", "/auth/register", {"storeName": "Barbería El Parcero (servicios de prueba)", "ownerName": "Dueño Parcero",
                                                "email": EMAIL, "password": PASSWORD, "businessType": "SERVICIOS", "plan": "PRO",
                                                "acceptTerms": True, "acceptPrivacy": True})
        if st not in (200, 201):
            sys.exit(f"No se pudo registrar: {st} {r}")
        print("• Barbería registrada")
    TOKEN = r["token"]
    call("PUT", "/settings/pos/modules", {"businessType": "SERVICIOS",
                                          "modules": ["SERVICIOS", "CUENTAS_ABIERTAS", "TIENDA_ONLINE", "FIADO", "COMBOS"]})

    cats = {c["name"]: c["id"] for c in call("GET", "/categories")[1]}
    for name in ["Servicios", "Productos", "Repuestos"]:
        if name not in cats:
            cats[name] = call("POST", "/categories", {"name": name})[1]["id"]
    existing = {p["name"]: p for p in call("GET", "/products?size=200")[1]["items"]}

    def product(name, cat, price, cost=0, stock=0, service=False):
        if name in existing:
            return existing[name]
        st, p = call("POST", "/products", {"name": name, "unit": "UNIDAD", "categoryId": cats[cat], "cost": cost, "initialStock": stock,
                                           "trackStock": not service, "taxRate": 0,
                                           "presentations": [{"name": "Servicio" if service else "Unidad", "factor": 1, "price": price, "isBase": True}]})
        existing[name] = p
        return p

    services = {}
    for name, price, minutes in [("Corte de cabello", 25000, 30), ("Arreglo de barba", 15000, 20), ("Corte + barba", 35000, 45),
                                 ("Tinte", 60000, 90), ("Cejas", 8000, 15)]:
        p = product(name, "Servicios", price, service=True)
        call("PUT", f"/services/catalog/{p['id']}", {"minutes": minutes})
        services[name] = p["id"]
    product("Cera para cabello", "Productos", 22000, 11000, 15)
    product("Shampoo anticaspa", "Productos", 20000, 9000, 10)
    pantalla = product("Pantalla celular genérica", "Repuestos", 90000, 55000, 4)

    staff = {s["name"]: s["id"] for s in call("GET", "/services/staff")[1]}

    def hours(days, start, end, lunch=None):
        out = []
        for d in days:
            if lunch:
                out += [{"weekday": d, "start": start, "end": lunch[0]}, {"weekday": d, "start": lunch[1], "end": end}]
            else:
                out.append({"weekday": d, "start": start, "end": end})
        return out

    people = [("Ana Gómez", "#e64553", 40, 10, hours(range(1, 7), "09:00", "19:00", ("13:00", "14:00"))),
              ("Carlos Ríos", "#1e66f5", 50, 10, hours(range(2, 8), "10:00", "20:00")),
              ("Julián Técnico", "#40a02b", 30, 0, hours(range(1, 6), "08:00", "17:00"))]
    for name, color, sp, pp, hs in people:
        if name not in staff:
            body = {"name": name, "color": color, "commissionServicePct": sp, "commissionProductPct": pp, "hours": hs,
                    "onlineBooking": name != "Julián Técnico"}
            if name == "Julián Técnico":
                body["serviceIds"] = [services["Cejas"]]
            staff[name] = call("POST", "/services/staff", body)[1]["id"]
    print("• Profesionales: " + ", ".join(staff))

    store = call("GET", "/online-store")[1]
    if not store.get("enabled"):
        st, r = call("PUT", "/online-store", {"enabled": True, "slug": "barberia-el-parcero", "mode": "PEDIDOS", "welcome": "Pide tu cita en línea",
                                             "showStock": False, "deliveryEnabled": False, "pickupEnabled": True, "minOrder": 0,
                                             "payMethods": ["CONTRA_ENTREGA"], "paused": False, "prepMinutes": 15, "showBadge": True, "zones": []})
        print("• Tienda en línea:", st)
    call("PUT", "/services/booking", {"bookingEnabled": True, "slotMinutes": 30})

    today = datetime.date.today()
    agenda = call("GET", f"/services/agenda?date={today}&days=2")[1]
    if not agenda["appointments"]:
        tomorrow = today + datetime.timedelta(days=1)
        for day, hh, who, svc, client, phone in [
            (today, "10:00", "Ana Gómez", "Corte de cabello", "María Pérez", "3001234567"),
            (today, "11:00", "Carlos Ríos", "Corte + barba", "Andrés López", "3109876543"),
            (today, "15:30", "Ana Gómez", "Tinte", "Laura Restrepo", "3205551234"),
            (tomorrow, "10:30", "Carlos Ríos", "Arreglo de barba", "Felipe Mora", "3014442211"),
            (tomorrow, "16:00", "Ana Gómez", "Corte de cabello", "Sofía Díaz", "3123334455"),
        ]:
            st, r = call("POST", "/services/appointments", {"staffId": staff[who], "serviceId": services[svc], "customerName": client,
                                                            "customerPhone": phone, "start": f"{day}T{hh}:00"})
            if st != 200:
                print("  cita:", st, r)
        print("• Citas de hoy y mañana")

    if not call("GET", "/shifts/current")[1]:
        reg = call("GET", "/registers")[1][0]["id"]
        call("POST", "/shifts/open", {"registerId": reg, "openingCash": 100000})
        print("• Turno abierto con $100.000")

    if not call("GET", "/service-orders")[1]:
        o = call("POST", "/service-orders", {"customerName": "Pedro Gaviria", "customerPhone": "3157778899", "device": "Celular",
                                             "brand": "Samsung", "model": "A12", "problem": "Pantalla rota, toca bien pero no da imagen",
                                             "accessories": "Con forro, sin cargador", "conditionNotes": "Rayón en la tapa",
                                             "technicianId": staff["Julián Técnico"], "warrantyDays": 60,
                                             "promisedAt": str(today + datetime.timedelta(days=2))})[1]
        call("PUT", f"/service-orders/{o['id']}", {"customerName": "Pedro Gaviria", "customerPhone": "3157778899", "device": "Celular",
                                                   "brand": "Samsung", "model": "A12", "problem": "Pantalla rota, toca bien pero no da imagen",
                                                   "accessories": "Con forro, sin cargador", "conditionNotes": "Rayón en la tapa",
                                                   "diagnosis": "Cambio de pantalla completa", "laborPrice": 30000,
                                                   "technicianId": staff["Julián Técnico"], "warrantyDays": 60,
                                                   "promisedAt": str(today + datetime.timedelta(days=2))})
        pres = call("GET", f"/products/{pantalla['id']}")[1]["presentations"][0]["id"]
        call("POST", f"/service-orders/{o['id']}/parts", {"presentationId": pres, "quantity": 1})
        call("POST", f"/service-orders/{o['id']}/status", {"status": "ESPERANDO_APROBACION"})
        call("POST", f"/service-orders/{o['id']}/quote", {"approved": True})
        call("POST", f"/service-orders/{o['id']}/deposits", {"amount": 50000, "method": "EFECTIVO"})
        call("POST", "/service-orders", {"customerName": "Gloria Henao", "customerPhone": "3176665544", "device": "Secador de pelo",
                                         "brand": "Remington", "problem": "No calienta", "technicianId": staff["Julián Técnico"]})
        print("• Órdenes de servicio: celular en reparación con anticipo y un secador recibido")
    print("Listo: entra con", EMAIL)


if __name__ == "__main__":
    main()
