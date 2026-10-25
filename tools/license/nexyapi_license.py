#!/usr/bin/env python3
"""
NexYapı lisans aracı.

Kurulum:   pip install cryptography
Kullanım:
  1) Anahtar çifti üret (BİR KEZ):
       python3 nexyapi_license.py keygen
     -> private_key.pem oluşur (GİZLİ: depoya koyma, yedeğini al).
     -> Ekrana basılan açık anahtarı app/src/main/res/values/license_key.xml içine yapıştır.

  2) Lisans üret:
       python3 nexyapi_license.py issue --id L-0001 --holder "Örnek Mühendislik Ltd." --expires 2027-10-31
       (--expires verilmezse süresiz lisans)

  3) Lisans doğrula (kontrol için):
       python3 nexyapi_license.py verify NXY1-....

Biçim uygulamadaki LicenseVerifier ile birebir aynıdır (bkz. core/.../license/License.kt).
"""
import argparse
import base64
import datetime as dt
import os
import sys

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_KEY = os.path.join(HERE, "private_key.pem")
PREFIX = "NXY1-"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_decode(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def load_private(path: str):
    with open(path, "rb") as f:
        return serialization.load_pem_private_key(f.read(), password=None)


def public_b64(private_key) -> str:
    der = private_key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return base64.b64encode(der).decode("ascii")


def cmd_keygen(args):
    if os.path.exists(args.key):
        sys.exit(f"HATA: {args.key} zaten var. Üzerine yazılırsa eski lisansların hepsi geçersiz olur.")
    key = ec.generate_private_key(ec.SECP256R1())
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    fd = os.open(args.key, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(pem)
    print(f"Özel anahtar yazıldı: {args.key}  (GİZLİ tut, yedeğini al)")
    print("Uygulamaya konacak açık anahtar:")
    print(public_b64(key))


def check_value(name: str, value: str):
    if "\n" in value or "\r" in value:
        sys.exit(f"HATA: {name} satır sonu içeremez.")


def cmd_issue(args):
    key = load_private(args.key)
    issued = args.issued or dt.date.today().isoformat()
    for d in (issued, args.expires):
        if d:
            dt.date.fromisoformat(d)  # biçim kontrolü
    for n, v in (("id", args.id), ("holder", args.holder)):
        check_value(n, v)
        if not v.strip():
            sys.exit(f"HATA: {n} boş olamaz.")
    payload = (
        "v=1\n"
        f"id={args.id}\n"
        f"holder={args.holder}\n"
        f"plan={args.plan}\n"
        f"issued={issued}\n"
        f"expires={args.expires or ''}\n"
    ).encode("utf-8")
    sig = key.sign(payload, ec.ECDSA(hashes.SHA256()))
    print(PREFIX + b64url(payload) + "." + b64url(sig))


def cmd_verify(args):
    key = load_private(args.key).public_key()
    token = "".join(args.token.split())
    if not token.startswith(PREFIX):
        sys.exit("Geçersiz: önek yok")
    p, s = token[len(PREFIX):].split(".")
    payload = b64url_decode(p)
    try:
        key.verify(b64url_decode(s), payload, ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        sys.exit("Geçersiz imza")
    print("İmza geçerli:\n" + payload.decode("utf-8"))


def main():
    ap = argparse.ArgumentParser(description="NexYapı lisans aracı")
    ap.add_argument("--key", default=DEFAULT_KEY, help="özel anahtar dosyası")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("keygen").set_defaults(func=cmd_keygen)

    i = sub.add_parser("issue")
    i.add_argument("--id", required=True)
    i.add_argument("--holder", required=True)
    i.add_argument("--plan", default="pro", choices=["pro"])
    i.add_argument("--issued", help="YYYY-MM-DD (varsayılan: bugün)")
    i.add_argument("--expires", help="YYYY-MM-DD (boş: süresiz)")
    i.set_defaults(func=cmd_issue)

    v = sub.add_parser("verify")
    v.add_argument("token")
    v.set_defaults(func=cmd_verify)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
