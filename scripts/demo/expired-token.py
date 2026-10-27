#!/usr/bin/env python3
import base64, hashlib, hmac, json, os, time
tenant = os.environ.get("DEMO_TENANT", "demo-v2")
secret = os.environ.get("SYNANTON_JWT_SECRET", "local-real-demo-secret-at-least-32-bytes")
def b(v): return base64.urlsafe_b64encode(v).rstrip(b"=").decode()
now = int(time.time())
h = b(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
c = b(json.dumps({"sub": "aminin", "tenant_id": tenant, "token_type": "tenant_access",
                  "iat": now - 120, "exp": now - 60}).encode())
s = b(hmac.new(secret.encode(), f"{h}.{c}".encode(), hashlib.sha256).digest())
print(f"{h}.{c}.{s}")
