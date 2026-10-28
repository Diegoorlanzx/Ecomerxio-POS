"""
Crea (o completa) una droguería de prueba para mostrar el tipo de negocio Droguería (plan de tipos de negocio, fase 2):
medicamentos por tableta, blíster y caja con registro INVIMA y principio activo, lotes con vencimiento, medicamentos
con fórmula y de control especial, ventas con fórmula en el libro y un lote vencido que la caja no deja vender.

Uso:
    python -I scripts/demo_drogueria.py [URL_API] [CORREO] [CONTRASEÑA]
Si la tienda no existe la registra con esos datos (la cuenta de prueba está en docs/DEV.md).
Se puede correr varias veces: lo que ya existe no se repite.
"""
import datetime
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/api"
EMAIL = sys.argv[2] if len(sys.argv) > 2 else "drogueria@demo.tiendaposs.test"
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


def step(msg):
    print("•", msg)


# nombre, categoría, principio activo, INVIMA, fórmula, control, costo por tableta, precio tableta/blíster/caja, unidades por blíster/caja
MEDICINES = [
    ("Acetaminofén 500 mg", "Analgésicos", "Acetaminofén", "INVIMA 2016M-0004532", False, False, 60, (200, 1800, 5000), (10, 30)),
    ("Ibuprofeno 400 mg", "Analgésicos", "Ibuprofeno", "INVIMA 2017M-0011870", False, False, 90, (300, 2700, 7500), (10, 30)),
    ("Loratadina 10 mg", "Antialérgicos", "Loratadina", "INVIMA 2015M-0003311", False, False, 80, (300, 2800, 8000), (10, 30)),
    ("Amoxicilina 500 mg", "Antibióticos", "Amoxicilina", "INVIMA 2019M-0012345", True, False, 220, (600, 5500, 15000), (10, 30)),
    ("Losartán 50 mg", "Cardiovascular", "Losartán potásico", "INVIMA 2018M-0007790", True, False, 150, (400, 3800, 10500), (10, 30)),
    ("Clonazepam 2 mg", "Control especial", "Clonazepam", "INVIMA 2014M-0002210", True, True, 450, (1200, 11000, 32000), (10, 30)),
]
OTHERS = [  # nombre, categoría, costo, precio (por unidad, sin lotes de fórmula)
    ("Suero oral sabor fresa", "Cuidado personal", 2500, 4200),
    ("Alcohol antiséptico 350 ml", "Cuidado personal", 3200, 5500),
    ("Tapabocas x 10", "Cuidado personal", 3000, 6000),
]


def main():
    global TOKEN
    if not PASSWORD:
        sys.exit("Falta la contraseña (está en docs/DEV.md)")
    st, r = call("POST", "/auth/login", {"email": EMAIL, "password": PASSWORD})
    if st != 200:
        st, r = call("POST", "/auth/register", {"storeName": "Droguería La Salud (droguería de prueba)", "ownerName": "Dueña La Salud",
                                                "email": EMAIL, "password": PASSWORD, "businessType": "DROGUERIA", "plan": "PRO",
                                                "acceptTerms": True, "acceptPrivacy": True})
        if st not in (200, 201):
            sys.exit(f"No se pudo registrar: {st} {r}")
        step("Droguería registrada")
    TOKEN = r["token"]

    cats = {c["name"]: c["id"] for c in call("GET", "/categories")[1]}
    for name in ["Analgésicos", "Antialérgicos", "Antibióticos", "Cardiovascular", "Control especial", "Cuidado personal"]:
        if name not in cats:
            cats[name] = call("POST", "/categories", {"name": name})[1]["id"]
    existing = {p["name"]: p for p in call("GET", "/products?size=200")[1]["items"]}

    suppliers = {s["name"]: s["id"] for s in (call("GET", "/suppliers")[1] or [])}
    if "Distribuidora Farmacéutica" not in suppliers:
        suppliers["Distribuidora Farmacéutica"] = call("POST", "/suppliers", {"name": "Distribuidora Farmacéutica", "whatsapp": "3001112233",
                                                                              "creditDays": 30, "leadTimeDays": 2})[1]["id"]
    supplier = suppliers["Distribuidora Farmacéutica"]
    today = datetime.date.today()

    meds = {}
    for name, cat, ingredient, invima, rx, controlled, cost, prices, packs in MEDICINES:
        p = existing.get(name)
        if not p:
            st, p = call("POST", "/products", {
                "name": name, "unit": "UNIDAD", "categoryId": cats[cat], "cost": cost, "trackLots": True, "taxRate": 0, "taxClass": "EXCLUIDO",
                "stockMin": 30, "presentations": [
                    {"name": "Tableta", "factor": 1, "price": prices[0], "isBase": True},
                    {"name": f"Blíster x{packs[0]}", "factor": packs[0], "price": prices[1], "isBase": False},
                    {"name": f"Caja x{packs[1]}", "factor": packs[1], "price": prices[2], "isBase": False}]})
            if st not in (200, 201):
                sys.exit(f"No se pudo crear {name}: {st} {p}")
            call("PUT", f"/products/{p['id']}/pharmacy", {"invimaCode": invima, "activeIngredient": ingredient,
                                                          "requiresPrescription": rx, "controlled": controlled})
            # Dos lotes: uno que vence pronto y otro lejano (la caja vende primero el que vence antes).
            call("POST", "/receipts", {"supplierId": supplier, "lines": [
                {"productId": p["id"], "unitsReceived": 60, "unitCost": cost, "lotCode": f"{ingredient[:3].upper()}-A1",
                 "expiryDate": str(today + datetime.timedelta(days=45))},
                {"productId": p["id"], "unitsReceived": 120, "unitCost": cost, "lotCode": f"{ingredient[:3].upper()}-B7",
                 "expiryDate": str(today + datetime.timedelta(days=540))}]})
            existing[name] = p
        meds[name] = p
    step(f"{len(MEDICINES)} medicamentos con tableta, blíster y caja, INVIMA y lotes")

    for name, cat, cost, price in OTHERS:
        if name not in existing:
            call("POST", "/products", {"name": name, "unit": "UNIDAD", "categoryId": cats[cat], "cost": cost, "initialStock": 40,
                                       "taxRate": 19, "presentations": [{"name": "Unidad", "factor": 1, "price": price, "isBase": True}]})
    step("Productos de cuidado personal")

    regs = call("GET", "/registers")[1]
    shift = call("GET", "/shifts/current")[1]
    if not shift:
        call("POST", "/shifts/open", {"registerId": regs[0]["id"], "openingCash": 100000})

    def pres(p, prefix):
        return next(x["id"] for x in p["presentations"] if x["name"].startswith(prefix))

    def sell(lines, prescription=None, label=""):
        body = {"clientRef": str(uuid.uuid4()), "lines": lines, "payments": [{"method": "EFECTIVO", "amount": 9999999}]}
        if prescription:
            body["prescription"] = prescription
        st, r = call("POST", "/pos/sales", body)
        step(f"Venta {label}: {'ok' if st in (200, 201) else st}")

    book = call("GET", f"/pharmacy/prescriptions?from={today}&to={today}")[1] or []
    if not book:
        sell([{"presentationId": pres(meds["Acetaminofén 500 mg"], "Blíster"), "quantity": 1},
              {"presentationId": pres(meds["Loratadina 10 mg"], "Tableta"), "quantity": 4}], label="de venta libre por blíster y tableta")
        sell([{"presentationId": pres(meds["Amoxicilina 500 mg"], "Caja"), "quantity": 1}],
             {"doctorName": "Dra. Ana María Gómez", "doctorLicense": "RM 11.234", "prescriptionNumber": "F-2201",
              "prescriptionDate": str(today), "patientName": "Luis Fernando Pérez", "patientDoc": "1020304050"}, "con fórmula")
        sell([{"presentationId": pres(meds["Clonazepam 2 mg"], "Blíster"), "quantity": 1}],
             {"doctorName": "Dr. Jorge Restrepo", "doctorLicense": "RM 8.871", "prescriptionNumber": "FC-0091",
              "prescriptionDate": str(today - datetime.timedelta(days=1)), "patientName": "Marta Lucía Ríos", "patientDoc": "43123456"},
             "de control especial")
    print("\nListo. Entra con", EMAIL, "y prueba: buscar 'ibuprofeno' por principio activo, vender un blíster,")
    print("cobrar Amoxicilina (pide la fórmula) y ver Más > Fórmulas despachadas.")


if __name__ == "__main__":
    main()
