"""
Crea (o completa) un restaurante de prueba para mostrar la Fase 10: mesas, modificadores, comandas a cocina/bar,
recetas con merma y propina.

Uso:
    python -I scripts/demo_restaurante.py [URL_API] [CORREO] [CONTRASEÑA]
Si la tienda no existe la registra con esos datos (por defecto los del restaurante de prueba de docs/DEV.md).
Se puede correr varias veces: lo que ya existe no se repite.
"""
import json
import sys
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8090/api"
EMAIL = sys.argv[2] if len(sys.argv) > 2 else "restaurante@demo.tiendaposs.test"
PASSWORD = sys.argv[3] if len(sys.argv) > 3 else "Fonda2026pos!"
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
    st, r = call("POST", "/auth/login", {"email": EMAIL, "password": PASSWORD})
    if st != 200:
        st, r = call("POST", "/auth/register", {"storeName": "La Fonda (restaurante de prueba)", "ownerName": "Dueño Fonda",
                                                "email": EMAIL, "password": PASSWORD, "businessType": "RESTAURANTE", "plan": "PRO",
                                                "acceptTerms": True, "acceptPrivacy": True})
        if st not in (200, 201):
            sys.exit(f"No se pudo registrar: {st} {r}")
        print("• Restaurante registrado")
    TOKEN = r["token"]
    call("PUT", "/settings/pos/modules", {"businessType": "RESTAURANTE", "modules": ["RESTAURANTE", "CUENTAS_ABIERTAS", "COMBOS", "COMPRAS"]})

    cats = {c["name"]: c["id"] for c in call("GET", "/categories")[1]}
    for name in ["Platos fuertes", "Entradas", "Bebidas", "Ingredientes"]:
        if name not in cats:
            cats[name] = call("POST", "/categories", {"name": name})[1]["id"]
    call("PUT", "/restaurant/stations", {"categories": {str(cats["Platos fuertes"]): "COCINA", str(cats["Entradas"]): "COCINA",
                                                         str(cats["Bebidas"]): "BAR"}})

    existing = {p["name"]: p for p in call("GET", "/products?size=200")[1]["items"]}

    def product(name, cat, price, cost=0, stock=0, unit="UNIDAD"):
        if name in existing:
            return existing[name]
        st, p = call("POST", "/products", {"name": name, "unit": unit, "categoryId": cats[cat], "cost": cost, "initialStock": stock,
                                           "taxRate": 0, "presentations": [{"name": "Kilo" if unit == "KILOGRAMO" else "Unidad", "factor": 1,
                                                                            "price": price, "isBase": True}]})
        existing[name] = p
        return p

    carne = product("Carne de res", "Ingredientes", 32000, 22000, 20, "KILOGRAMO")
    papa = product("Papa", "Ingredientes", 3000, 1800, 30, "KILOGRAMO")
    pan = product("Pan de hamburguesa", "Ingredientes", 1000, 600, 100)
    hamb = product("Hamburguesa de la casa", "Platos fuertes", 24000)
    bandeja = product("Bandeja paisa", "Platos fuertes", 32000)
    product("Empanadas (3)", "Entradas", 9000, 3500, 50)
    product("Limonada de coco", "Bebidas", 9000, 2500, 80)
    product("Cerveza nacional", "Bebidas", 6000, 3000, 120)
    product("Gaseosa 400 ml", "Bebidas", 4000, 2000, 60)

    if hamb.get("productType") != "COMBO":
        call("PUT", f"/products/{hamb['id']}/components", {"components": [
            {"productId": carne["id"], "quantity": 0.15, "wastePct": 10},
            {"productId": pan["id"], "quantity": 1},
            {"productId": papa["id"], "quantity": 0.2, "wastePct": 15}]})
    if bandeja.get("productType") != "COMBO":
        call("PUT", f"/products/{bandeja['id']}/components", {"components": [
            {"productId": carne["id"], "quantity": 0.2, "wastePct": 10}]})

    groups = {g["name"] for g in call("GET", "/restaurant/modifiers")[1]}
    if "Término de la carne" not in groups:
        call("POST", "/restaurant/modifiers", {"name": "Término de la carne", "minSelect": 1, "maxSelect": 1,
                                               "productIds": [hamb["id"], bandeja["id"]],
                                               "options": [{"name": "Término medio"}, {"name": "Tres cuartos"}, {"name": "Bien asada"}]})
    if "Adiciones" not in groups:
        call("POST", "/restaurant/modifiers", {"name": "Adiciones", "minSelect": 0, "maxSelect": 3, "productIds": [hamb["id"]],
                                               "options": [{"name": "Extra queso", "price": 3000}, {"name": "Tocineta", "price": 4000},
                                                           {"name": "Sin cebolla"}]})

    floor = call("GET", "/restaurant/floor")[1]
    if not floor["areas"]:
        salon = call("POST", "/restaurant/areas", {"name": "Salón"})[1]["id"]
        terraza = call("POST", "/restaurant/areas", {"name": "Terraza"})[1]["id"]
        for i in range(1, 9):
            call("POST", "/restaurant/tables", {"name": f"Mesa {i}", "seats": 4 if i < 7 else 6,
                                                "areaId": salon if i <= 5 else terraza, "sortOrder": i})
        print("• 8 mesas en Salón y Terraza")

    if not call("GET", "/shifts/current")[1]:
        reg = call("GET", "/registers")[1][0]["id"]
        call("POST", "/shifts/open", {"registerId": reg, "openingCash": 100000})
        print("• Turno abierto con $100.000")
    print("Listo: entra con", EMAIL)


if __name__ == "__main__":
    main()
