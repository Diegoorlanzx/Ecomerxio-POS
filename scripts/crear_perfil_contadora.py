"""
Crea (o renueva) un usuario de prueba con el rol "Contador o analista" en una tienda demo, para una contadora aliada que
va a mostrar y vender Ecomerxio POS. Ve dashboard, ventas, costos, finanzas, facturación electrónica, contabilidad,
cotizaciones y nómina; no vende en caja ni cambia usuarios.

Uso:
    python -I scripts/crear_perfil_contadora.py --nombre "Nombre Apellido" --correo correo@ejemplo.com
        [--url https://pos.ecomerxio.com/api] [--dueno dueno@demo.tiendaposs.test] [--clave-dueno ...]
    (por defecto: servidor local y el dueño de Cigarrería El Paisa de docs/DEV.md)

La contraseña se genera al azar y se muestra UNA vez en pantalla: entrégasela a ella por un canal privado y pídele que
la cambie en Más > Mi cuenta. Si el correo ya existe en la tienda, se le asigna el rol y una contraseña nueva.
Para que tenga facturas y asientos que mostrar, corre antes scripts/demo_facturacion_contabilidad.py en la misma tienda.
"""
import argparse
import getpass
import json
import secrets
import string
import sys
import urllib.error
import urllib.request

TOKEN = None
BASE = None


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


def new_password():
    # Cumple la regla de la app (letras y números, 8+); sin caracteres que se confundan al dictarla.
    alphabet = "".join(c for c in string.ascii_letters + string.digits if c not in "0OoIl1")
    while True:
        p = "".join(secrets.choice(alphabet) for _ in range(12))
        if any(c.isdigit() for c in p) and any(c.isalpha() for c in p):
            return p


def main():
    global TOKEN, BASE
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="replace")
    ap = argparse.ArgumentParser(description="Perfil de prueba con rol Contador en una tienda demo")
    ap.add_argument("--nombre", required=True)
    ap.add_argument("--correo", required=True)
    ap.add_argument("--url", default="http://localhost:8090/api")
    ap.add_argument("--dueno", default="dueno@demo.tiendaposs.test")
    ap.add_argument("--clave-dueno", default=None, help="si no se da, se pide (en local: la de docs/DEV.md)")
    a = ap.parse_args()
    BASE = a.url.rstrip("/")
    owner_pass = a.clave_dueno or ("Demo2026pos" if "localhost" in BASE else getpass.getpass(f"Contraseña de {a.dueno}: "))

    st, r = call("POST", "/auth/login", {"email": a.dueno, "password": owner_pass})
    if st != 200:
        sys.exit(f"No se pudo entrar como dueño: {st} {r}")
    TOKEN = r["token"]
    store = r["me"]["tenant"]["name"]

    st, roles = call("GET", "/roles")
    role = next((x for x in roles if x.get("systemCode") == "CONTADOR"), None) if st == 200 else None
    if not role:
        sys.exit(f"La tienda no tiene el rol Contador: {st} {roles}")

    password = new_password()
    st, users = call("GET", "/users")
    existing = next((u for u in users if u["email"].lower() == a.correo.lower()), None) if st == 200 else None
    if existing:
        call("PUT", f"/users/{existing['id']}", {"fullName": a.nombre, "email": a.correo, "roleIds": [role["id"]]})
        call("POST", f"/users/{existing['id']}/activate")
        st, r = call("POST", f"/users/{existing['id']}/password", {"password": password})
        action = "actualizado"
    else:
        st, r = call("POST", "/users", {"fullName": a.nombre, "email": a.correo, "password": password, "roleIds": [role["id"]]})
        action = "creado"
    if st not in (200, 201, 204):
        sys.exit(f"No se pudo guardar el usuario: {st} {r}")

    front = BASE[:-4] if BASE.endswith("/api") else BASE
    print(f"\nUsuario {action} en «{store}» con el rol {role['name']}.")
    print(f"  Entrar en:  {front.replace(':8090', ':5190')}/login")
    print(f"  Correo:     {a.correo}")
    print(f"  Contraseña: {password}")
    print("Entrégasela por un canal privado y pídele que la cambie en Más > Mi cuenta.")


if __name__ == "__main__":
    main()
