"""Extracts the root certificates old Android versions lack from Mozilla's CA bundle (curl.se/ca/cacert.pem)
into app/src/main/assets/certs/roots.pem. Android 4.x-6 trust stores predate these roots (Let's Encrypt,
Google Trust Services, newer DigiCert/Sectigo/Amazon roots), so HTTPS to GitHub, Open VSX or AI APIs fails.

Usage: python tools/make_roots.py path/to/cacert.pem
"""
import os
import re
import sys

WANTED = [
    "ISRG Root X1", "ISRG Root X2",
    "GTS Root R1", "GTS Root R3", "GTS Root R4",
    "GlobalSign Root CA - R3", "GlobalSign Root CA - R6", "GlobalSign Root R46", "GlobalSign Root E46",
    "DigiCert Global Root G2", "DigiCert Global Root G3",
    "USERTrust RSA Certification Authority", "USERTrust ECC Certification Authority", "COMODO RSA Certification Authority",
    "Amazon Root CA 1", "Amazon Root CA 2", "Amazon Root CA 3", "Amazon Root CA 4",
    "Microsoft RSA Root Certificate Authority 2017",
    "Starfield Root Certificate Authority - G2", "Go Daddy Root Certificate Authority - G2",
    "SSL.com Root Certification Authority RSA", "SSL.com Root Certification Authority ECC", "Certum Trusted Network CA",
    "Certainly Root R1", "Certainly Root E1",
    "Sectigo Public Server Authentication Root E46", "Sectigo Public Server Authentication Root R46",
    "Starfield Services Root Certificate Authority - G2", "GlobalSign Root CA", "DigiCert Global Root CA",
]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "certs", "roots.pem")

text = open(sys.argv[1], encoding="utf-8").read()
blocks = re.findall(r"\n([^\n]+)\n=+\n(-----BEGIN CERTIFICATE-----\n.*?-----END CERTIFICATE-----)", text, re.S)
found = {}
for name, pem in blocks:
    if name.strip() in WANTED:
        found[name.strip()] = pem
missing = [w for w in WANTED if w not in found]
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="ascii", newline="\n") as f:
    f.write("# Root certificates from Mozilla's CA bundle (MPL 2.0), via https://curl.se/ca/cacert.pem\n")
    for w in WANTED:
        if w in found:
            f.write("\n# " + w + "\n" + found[w] + "\n")
print("roots:", len(found), "missing:", missing, "bytes:", os.path.getsize(OUT))
