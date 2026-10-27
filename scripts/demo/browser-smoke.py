#!/usr/bin/env python3
"""Headless browser smoke for the Slice 1 demo using Chromium CDP.

Requires a running Vite UI at BROWSER_URL, local security/synquest, and the
python websocket-client package. This is a smoke test, not a Playwright suite.
"""
from __future__ import annotations
import base64, hashlib, hmac, json, os, subprocess, sys, time, urllib.parse, urllib.request
from pathlib import Path

import websocket

UI = os.environ.get("BROWSER_URL", "http://127.0.0.1:5173")
SECURITY = os.environ.get("SYNANTON_SECURITY_URL", "http://localhost:18081")
TENANT = os.environ.get("DEMO_TENANT", "demo-v2")
SECRET = os.environ.get("SYNANTON_JWT_SECRET", "local-real-demo-secret-at-least-32-bytes")
DEBUG = int(os.environ.get("BROWSER_DEBUG_PORT", "9223"))
CHROME = os.environ.get("CHROME", "/usr/bin/chromium-browser")


def post(url: str, body: dict, token: str | None = None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, data=json.dumps(body).encode(), headers=headers, method="POST")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def demo_token() -> str:
    return post(SECURITY + "/v1/auth/tenant",
                {"principal_id": "aminin", "tenant_id": TENANT, "capabilities": []})["token"]


def expired_token() -> str:
    def b64(value: bytes) -> str:
        return base64.urlsafe_b64encode(value).rstrip(b"=").decode()
    now = int(time.time())
    header = b64(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
    claims = b64(json.dumps({"sub": "aminin", "tenant_id": TENANT,
                             "token_type": "tenant_access", "iat": now - 120,
                             "exp": now - 60}).encode())
    signature = b64(hmac.new(SECRET.encode(), f"{header}.{claims}".encode(), hashlib.sha256).digest())
    return f"{header}.{claims}.{signature}"


class Cdp:
    def __init__(self, ws):
        self.ws = ws
        self.next_id = 0

    def call(self, method: str, params: dict | None = None) -> dict:
        self.next_id += 1
        ident = self.next_id
        self.ws.send(json.dumps({"id": ident, "method": method, "params": params or {}}))
        while True:
            message = json.loads(self.ws.recv())
            if message.get("id") == ident:
                if "error" in message:
                    raise RuntimeError(f"{method}: {message['error']}")
                return message.get("result", {})

    def eval(self, expression: str, await_promise: bool = False):
        return self.call("Runtime.evaluate", {
            "expression": expression, "returnByValue": True, "awaitPromise": await_promise})["result"].get("value")

    def wait(self, expression: str, timeout: float = 20.0):
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                if self.eval(expression):
                    return
            except Exception:
                pass
            time.sleep(0.25)
        raise TimeoutError(expression)


def wait_http(url: str, timeout: float = 20.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2):
                return
        except Exception:
            time.sleep(0.25)
    raise TimeoutError(url)


def main() -> None:
    chrome = subprocess.Popen([
        CHROME, "--headless=new", "--no-sandbox", "--disable-gpu",
        f"--remote-debugging-port={DEBUG}", "--user-data-dir=/tmp/opencode/chrome-smoke",
        "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        wait_http(f"http://127.0.0.1:{DEBUG}/json/version")
        target = json.loads(urllib.request.urlopen(urllib.request.Request(
            f"http://127.0.0.1:{DEBUG}/json/new?{urllib.parse.quote(UI + '/search', safe='')}",
            method="PUT")).read())
        ws = websocket.create_connection(target["webSocketDebuggerUrl"], timeout=30)
        cdp = Cdp(ws)
        cdp.call("Page.enable")
        cdp.call("Runtime.enable")
        cdp.wait("document.readyState === 'complete'")
        cdp.eval(f"sessionStorage.setItem('synanton.accessToken', {json.dumps(demo_token())}); location.reload(); true")
        cdp.wait("!!document.querySelector('#query')")

        def query(text: str, expected: str, query_type: str | None = None) -> None:
            cdp.eval("""
                (() => {
                  const input = document.querySelector('#query');
                  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
                  setter.call(input, %s);
                  input.dispatchEvent(new Event('input', {bubbles: true}));
                  document.querySelector('button[type=submit]').click();
                  return true;
                })()
            """ % json.dumps(text))
            try:
                cdp.wait(f"document.body.innerText.includes({json.dumps(expected)})", timeout=25)
            except Exception:
                print("BODY:", cdp.eval("document.body.innerText")[:2000])
                raise
            if query_type:
                cdp.wait(f"document.body.innerText.includes({json.dumps(query_type)})", timeout=25)
            print(f"PASS query={text[:45]!r} expected={expected!r}")

        query("Which medications are listed for substance use disorders?", "BM25 #7 · Dense #2 · RRF #2")
        query("IATF 16949", "BM25 #1 · Dense — · RRF #1", "exact")
        query("How does the report's glossary define a major depressive episode?", "BM25 #1 · Dense #4 · RRF #1", "conceptual")
        query("In what year was Globex Manufacturing founded and where is it headquartered?", "BM25 #3 · Dense #1 · RRF #2")

        # Force an expired token, reload, then search: App should re-auth once and retry.
        cdp.eval(f"sessionStorage.setItem('synanton.accessToken', {json.dumps(expired_token())}); location.reload(); true")
        cdp.wait("!!document.querySelector('#query')")
        query("IATF 16949", "BM25 #1 · Dense — · RRF #1")

        # Browser refresh should keep the fresh token and still search.
        cdp.call("Page.reload")
        cdp.wait("document.readyState === 'complete'")
        time.sleep(1.0)
        cdp.wait("!!document.querySelector('#query')")
        query("IATF 16949", "BM25 #1 · Dense — · RRF #1")
        print("BROWSER SMOKE PASS")
    finally:
        chrome.terminate()
        try:
            chrome.wait(timeout=5)
        except subprocess.TimeoutExpired:
            chrome.kill()


if __name__ == "__main__":
    main()
