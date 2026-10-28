"""
Crea (o completa) una tienda demo por cada tipo de negocio nuevo (plan de tipos de negocio, fases 3 a 6). Cada una lleva
SOLO los módulos de su tipo: una ferretería no trae mesas, una cafetería no trae fórmulas (regla del usuario).

    python -I scripts/demo_tipos_negocio.py [URL_API] [CONTRASEÑA] [TIPO ...]
    (contraseña de todas en docs/DEV.md; sin TIPO crea todas)

Correos: <tipo>@demo.tiendaposs.test (ferreteria@, cafeteria@, lavanderia@, taller@, panaderia@, veterinaria@, agro@,
optica@, papeleria@, licorera@, gimnasio@, supermercado@). Se puede correr varias veces: lo que existe no se repite.
"""
import datetime
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/api"
PASSWORD = sys.argv[2] if len(sys.argv) > 2 else None
ONLY = [t.upper() for t in sys.argv[3:]]
TOKEN = None
TODAY = datetime.date.today()


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


def step(msg):
    print("  -", msg)


def login_or_register(code, store):
    global TOKEN
    TOKEN = None
    email = f"{code.lower()}@demo.tiendaposs.test"
    st, r = call("POST", "/auth/login", {"email": email, "password": PASSWORD})
    if st != 200:
        for _ in range(4):
            st, r = call("POST", "/auth/register", {"storeName": store, "ownerName": "Dueño demo", "email": email, "password": PASSWORD,
                                                    "businessType": code, "plan": "PRO", "acceptTerms": True, "acceptPrivacy": True})
            if st != 429:
                break
            print("  (límite de registros: espero un minuto)")
            time.sleep(65)
        if st not in (200, 201):
            sys.exit(f"No se pudo registrar {store}: {st} {r}")
    TOKEN = r["token"]
    print(f"• {store} ({email})")
    return {p["name"]: p for p in call("GET", "/products?size=300")[1]["items"]}


def product(existing, name, price, cost=0, stock=0, unit="UNIDAD", track=True, tax=0, extra_pres=()):
    if name in existing:
        return existing[name]
    pres = [{"name": {"UNIDAD": "Unidad", "METRO": "Metro", "KILOGRAMO": "Kilo", "LITRO": "Litro"}.get(unit, "Unidad"),
             "factor": 1, "price": price, "isBase": True}]
    for n, f, p in extra_pres:
        pres.append({"name": n, "factor": f, "price": p, "isBase": False})
    st, p = call("POST", "/products", {"name": name, "unit": unit, "cost": cost, "initialStock": stock if track else None,
                                       "trackStock": track, "taxRate": tax, "presentations": pres})
    if st not in (200, 201):
        print(f"    ! {name}: {st} {p}")
        return None
    existing[name] = p
    return p


def base(p):
    return next(x["id"] for x in p["presentations"] if x["isBase"])


def open_shift():
    if not call("GET", "/shifts/current")[1]:
        regs = call("GET", "/registers")[1]
        call("POST", "/shifts/open", {"registerId": regs[0]["id"], "openingCash": 100000})


def sell(lines, customer=None, label=""):
    body = {"clientRef": str(uuid.uuid4()), "lines": lines, "payments": [{"method": "EFECTIVO", "amount": 99999999}]}
    if customer:
        body["customer"] = {"customerId": customer}
    st, r = call("POST", "/pos/sales", body)
    step(f"venta {label}: {'ok' if st in (200, 201) else st}" + (f" · turno {r.get('counterNumber')}" if st in (200, 201) and isinstance(r, dict) and r.get('counterNumber') else ""))
    return r


def order(customer, phone, device, serial, problem, days=2):
    st, r = call("POST", "/service-orders", {"customerName": customer, "customerPhone": phone, "device": device, "serial": serial,
                                             "problem": problem, "promisedAt": str(TODAY + datetime.timedelta(days=days))})
    step(f"orden {device}: {'ok' if st == 200 else st}")


# ----------------------------------------------------------------------------------------------- tipos

def ferreteria():
    ex = login_or_register("FERRETERIA", "Ferretería El Tornillo (demo)")
    cable = product(ex, "Cable eléctrico #12", 3200, 1900, 300, unit="METRO", extra_pres=[("Rollo x100 m", 100, 280000)])
    puntilla = product(ex, "Puntilla 2 pulgadas", 9000, 5500, 50, unit="KILOGRAMO")
    product(ex, "Pintura blanca", 62000, 41000, 20, extra_pres=[("Cuñete 5 gal", 5, 290000)])
    product(ex, "Cemento gris 50 kg", 32000, 27000, 80)
    open_shift()
    if cable and puntilla:
        sell([{"presentationId": base(cable), "quantity": 7.5}, {"presentationId": base(puntilla), "quantity": 0.75}], label="7,5 m de cable y 0,75 kg de puntilla")


def cafeteria():
    ex = login_or_register("CAFETERIA", "Café La Esquina (demo)")
    tinto = product(ex, "Tinto", 2000, track=False)
    cap = product(ex, "Capuchino", 6500, track=False)
    product(ex, "Pandebono", 2500, 900, 60)
    open_shift()
    for i in range(3):
        sell([{"presentationId": base(tinto), "quantity": 1 + i}, {"presentationId": base(cap), "quantity": 1}], label=f"mostrador {i + 1}")


def lavanderia():
    login_or_register("LAVANDERIA", "Lavandería Burbujas (demo)")
    order("Marcela Ruiz", "3001234567", "Vestido de fiesta", "T-101", "Lavado en seco, mancha de vino")
    order("Jorge Pérez", "3109876543", "3 camisas", "T-102", "Lavar y planchar", 1)


def taller():
    login_or_register("TALLER", "Taller MotoRápido (demo)")
    order("Pedro Gómez", "3125551234", "Moto Yamaha FZ", "ABC12D", "Cambio de aceite y frenos", 1)
    order("Pedro Gómez", "3125551234", "Moto Yamaha FZ", "ABC 12D", "Revisión de cadena", 3)
    order("Luisa Mora", "3015559876", "Moto Honda CB", "XYZ98E", "No enciende")


def panaderia():
    ex = login_or_register("PANADERIA", "Panadería Doña Rosa (demo)")
    pan = product(ex, "Pan francés", 600, 250, 200)
    product(ex, "Torta de chocolate (porción)", 5500, 2200, 30)
    open_shift()
    if pan:
        sell([{"presentationId": base(pan), "quantity": 10}], label="10 panes")
    order("Carolina Díaz", "3007654321", "Torta de chocolate para 20 personas", None, "Para el sábado, dedicatoria 'Feliz cumpleaños Sofi'", 4)


def veterinaria():
    login_or_register("VETERINARIA", "Veterinaria Huellitas (demo)")
    pets = call("GET", "/pets")[1] or []
    if not pets:
        st, r = call("POST", "/pets", {"ownerName": "Laura Castro", "ownerPhone": "3014445566", "name": "Max", "species": "Perro",
                                       "breed": "Labrador", "sex": "Macho", "birthDate": "2021-03-10"})
        if st == 200:
            call("POST", f"/pets/{r['id']}/vaccines", {"vaccine": "Rabia", "appliedOn": str(TODAY - datetime.timedelta(days=360)),
                                                      "nextDue": str(TODAY + datetime.timedelta(days=5))})
        st, r = call("POST", "/pets", {"ownerName": "Andrés Vélez", "ownerPhone": "3157778899", "name": "Luna", "species": "Gato"})
        if st == 200:
            call("POST", f"/pets/{r['id']}/vaccines", {"vaccine": "Triple felina", "appliedOn": str(TODAY - datetime.timedelta(days=20)),
                                                      "nextDue": str(TODAY + datetime.timedelta(days=345))})
    step("mascotas y vacunas")


def agro():
    ex = login_or_register("AGRO", "Agroinsumos El Campo (demo)")
    for name, price, ica in [("Fungicida sistémico 1 L", 48000, "ICA 4532"), ("Abono triple 15 x 50 kg", 165000, "ICA 1290")]:
        p = product(ex, name, price, int(price * 0.7), 30)
        if p:
            call("PUT", f"/products/{p['id']}/extras", {"icaCode": ica, "adultsOnly": False})
    step("productos con registro ICA")


def optica():
    login_or_register("OPTICA", "Óptica Visión Clara (demo)")
    if not call("GET", "/optical-prescriptions")[1]:
        call("POST", "/optical-prescriptions", {"patientName": "Marta Lucía Ríos", "patientDoc": "43123456", "patientPhone": "3002223344",
                                                "examDate": str(TODAY), "optometrist": "Opt. Camilo Torres", "odSphere": -1.25,
                                                "odCylinder": -0.5, "odAxis": 180, "oiSphere": -1.0, "oiCylinder": -0.25, "oiAxis": 170,
                                                "addition": 1.5, "pupillaryDistance": 62, "lensType": "Progresivo"})
    order("Marta Lucía Ríos", "3002223344", "Lentes progresivos con montura", "F-001", "Fórmula del examen de hoy", 5)


def papeleria():
    ex = login_or_register("PAPELERIA", "Papelería El Lápiz (demo)")
    cua = product(ex, "Cuaderno 100 hojas", 4500, 2800, 200)
    lap = product(ex, "Lápiz HB", 800, 350, 500)
    col = product(ex, "Colores x12", 9500, 6000, 80)
    if cua and lap and col and not call("GET", "/school-lists")[1]:
        call("POST", "/school-lists", {"school": "Colegio San José", "grade": "Tercero",
                                       "items": [{"presentationId": base(cua), "quantity": 5}, {"presentationId": base(lap), "quantity": 4},
                                                 {"presentationId": base(col), "quantity": 1}]})
    step("lista de útiles Colegio San José · Tercero")


def licorera():
    ex = login_or_register("LICORERA", "Licorera La 70 (demo)")
    for name, price in [("Aguardiente Antioqueño 750 ml", 52000), ("Ron Medellín 750 ml", 48000), ("Cerveza Club Colombia", 3800)]:
        p = product(ex, name, price, int(price * 0.7), 48)
        if p:
            call("PUT", f"/products/{p['id']}/extras", {"adultsOnly": True})
    product(ex, "Hielo 2 kg", 3500, 1500, 30)
    step("licores marcados solo para mayores de edad")


def gimnasio():
    ex = login_or_register("GIMNASIO", "Gimnasio Fuerza Total (demo)")
    for name, price, days in [("Mensualidad", 80000, 30), ("Trimestre", 210000, 90), ("Día de entrenamiento", 10000, 1)]:
        p = product(ex, name, price, track=False)
        if p:
            call("PUT", f"/products/{p['id']}/membership", {"days": days})
    st, c = call("POST", "/pos/customers", {"name": "Andrés Socio Demo", "docNumber": "1012345678", "phone": "3001112233"})
    open_shift()
    if st == 201:
        sell([{"presentationId": base(ex["Mensualidad"]), "quantity": 1}], customer=c["id"], label="mensualidad a un socio")


def supermercado():
    ex = login_or_register("SUPERMERCADO", "Supermercado El Ahorro (demo)")
    arroz = product(ex, "Arroz Diana 500 g", 2900, 2100, 200)
    product(ex, "Aceite 1 L", 11500, 8900, 60)
    product(ex, "Tomate", 4200, 2500, 40, unit="KILOGRAMO")
    product(ex, "Cerveza Águila lata", 3200, 2300, 120)
    regs = call("GET", "/registers")[1]
    if len(regs) < 2:
        call("POST", "/registers", {"name": "Caja 2"})
    open_shift()
    if arroz:
        sell([{"presentationId": base(arroz), "quantity": 3}], label="3 arroces")


TYPES = {"FERRETERIA": ferreteria, "CAFETERIA": cafeteria, "LAVANDERIA": lavanderia, "TALLER": taller, "PANADERIA": panaderia,
         "VETERINARIA": veterinaria, "AGRO": agro, "OPTICA": optica, "PAPELERIA": papeleria, "LICORERA": licorera,
         "GIMNASIO": gimnasio, "SUPERMERCADO": supermercado}


def main():
    if not PASSWORD:
        sys.exit("Falta la contraseña (está en docs/DEV.md)")
    for code, fn in TYPES.items():
        if not ONLY or code in ONLY:
            fn()
    print("\nListo.")


if __name__ == "__main__":
    main()
