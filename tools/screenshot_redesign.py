"""Layout check only: renders the REAL redesigned UI in headless Chromium with a MOCKED Tauri IPC and SAMPLE data.
Sample devices/files/transfers exist only in this harness; the shipped app never contains them."""
import json, subprocess, time, os, sys
from playwright.sync_api import sync_playwright

NOW = int(time.time())
def mock(info, history, received):
    return """(() => {
  const info = %s, history = %s, received = %s; const cbs = {}; let n = 1; window.__calls = [];
  window.__TAURI_INTERNALS__ = { metadata: { currentWindow: { label: 'main' }, currentWebview: { windowLabel: 'main', label: 'main' } },
    transformCallback: (cb) => { const id = n++; cbs[id] = cb; return id; }, unregisterCallback: () => {}, convertFileSrc: (p) => p,
    invoke: async (cmd, args) => {
      window.__calls.push(cmd);
      if (cmd === 'app_info') return info; if (cmd === 'history') return history; if (cmd === 'list_received') return received;
      if (cmd.startsWith('plugin:event|listen')) { window.__l = window.__l || {}; window.__l[args.event] = args.handler; return n++; }
      if (cmd.startsWith('plugin:event|')) return null;
      if (cmd === 'plugin:dialog|open') return ['C:\\\\Users\\\\Fiifi\\\\Videos\\\\Match_highlights.mp4', 'C:\\\\Users\\\\Fiifi\\\\Pictures\\\\IMG_4821.jpg'];
      if (cmd === 'begin_pairing') return { uri: 'flux://pair?v=1&a=192.168.1.20%%3A47800&fp=' + 'a'.repeat(64) + '&t=' + 'f'.repeat(64) + '&n=Fiifi-Laptop', code: 'K7M-2QX-9R4', expires_in_secs: 120 };
      if (cmd === 'create_share') return { token: 'Xk3mP9qZa', url: 'http://192.168.1.20:47801/Xk3mP9qZa', expires_at: Math.floor(Date.now()/1000) + 3600, file_count: 2, total_bytes: 12400000, names: ['Match_highlights.mp4','IMG_4821.jpg'], max_downloads: null, downloads: 0 };
      return null; } };
  window.__emit = (e, p) => { const h = (window.__l||{})[e]; if (h) cbs[h]({ event: e, id: 0, payload: p }); };
})();""" % (json.dumps(info), json.dumps(history), json.dumps(received))

BASE = dict(fingerprint='a'*64, port=47800, version='0.1.0', discovery_active=True, lan_addrs=['192.168.1.20:47800'],
    settings=dict(device_name='Fiifi-Laptop', receive_dir='C:\\Users\\Fiifi\\Downloads\\FLUX', require_approval_for_trusted=False, discoverable=True),
    trusted=[], nearby=[], queued=[], shares=[])
PHONE = dict(fingerprint='b'*64, name='Galaxy S24', device_type='phone', paired_at=NOW-7200, last_addr=None, online=True, last_seen=NOW-2)
H = lambda i, d, name, n, size, o='completed', ago=300: dict(id=i, timestamp=NOW-ago, direction=d, peer_name='Galaxy S24', peer_fingerprint='b'*64, files=[name], file_count=n, total_bytes=size, outcome=o, error=None if o=='completed' else 'timed out')
HIST = [H('1','received','IMG_4821.jpg',1,2600000,ago=120), H('2','sent','VID_1234.mp4',1,8100000,ago=300), H('3','received','Document.pdf',1,1200000,ago=3600), H('4','sent','Notes.zip',3,5400000,'failed',7200)]
FILES = [dict(rel='IMG_4821.jpg', name='IMG_4821.jpg', size=2600000, modified=NOW-120), dict(rel='Biology/Document.pdf', name='Document.pdf', size=1200000, modified=NOW-3600), dict(rel='Match_highlights.mp4', name='Match_highlights.mp4', size=88000000, modified=NOW-9000)]
FULL = dict(BASE, trusted=[PHONE], queued=[dict(transfer_id='q1', peer_fingerprint='b'*64, peer_name='Galaxy S24', file_count=3, total_bytes=12400000, names=['Biology notes.pdf'], created=NOW, state='queued')],
            shares=[dict(token='Xk3mP9qZa', url='http://192.168.1.20:47801/Xk3mP9qZa', expires_at=NOW+2700, file_count=2, total_bytes=12400000, names=['a','b'], max_downloads=5, downloads=1)])

def main():
    out = "/tmp/rshots"; os.makedirs(out, exist_ok=True)
    srv = subprocess.Popen(["python3", "-m", "http.server", "4195", "-d", "dist"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); time.sleep(1)
    errs = []
    try:
        with sync_playwright() as p:
            b = p.chromium.launch()
            def page(w, h, info, hist, files):
                pg = b.new_page(viewport={"width": w, "height": h}); pg.on("pageerror", lambda e: errs.append(str(e)))
                pg.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
                pg.add_init_script(mock(info, hist, files)); pg.goto("http://localhost:4195/"); pg.wait_for_timeout(900); return pg
            def check(pg, label):
                ov = pg.evaluate("document.documentElement.scrollWidth > innerWidth || document.querySelector('.content').scrollWidth > document.querySelector('.content').clientWidth + 1")
                ok = pg.evaluate("[...document.images].every(i => i.complete && i.naturalWidth > 0)")
                print(f"{label}: horizontal overflow={ov} images ok={ok}")
            for (w, h) in [(1280, 720), (1366, 768), (1440, 900), (1920, 1080)]:
                pg = page(w, h, FULL, HIST, FILES); check(pg, f"Home {w}x{h}"); pg.screenshot(path=f"{out}/home_{w}.png"); pg.close()
            pg = page(1440, 900, BASE, [], []); check(pg, "Home empty 1440"); pg.screenshot(path=f"{out}/home_empty.png"); pg.close()
            pg = page(1440, 900, FULL, HIST, FILES)
            for nav, name in [("Files", "files"), ("Transfers", "transfers"), ("Devices", "devices"), ("Settings", "settings")]:
                pg.locator(".sb-item", has_text=nav).click(); pg.wait_for_timeout(500); check(pg, nav); pg.screenshot(path=f"{out}/{name}.png")
            pg.locator(".sb-item", has_text="Home").click(); pg.wait_for_timeout(300)
            pg.get_by_role("button", name="Send Files").first.click(); pg.wait_for_timeout(700); pg.screenshot(path=f"{out}/dlg_send.png")
            pg.keyboard.press("Escape"); pg.wait_for_timeout(200)
            pg.get_by_role("button", name="Share Link").first.click(); pg.wait_for_timeout(700); pg.screenshot(path=f"{out}/dlg_share_options.png")
            pg.get_by_role("button", name="Create link").click(); pg.wait_for_timeout(900); pg.screenshot(path=f"{out}/dlg_share_made.png"); pg.keyboard.press("Escape"); pg.wait_for_timeout(200)
            pg.get_by_role("button", name="Receive Files").first.click(); pg.wait_for_timeout(500); pg.screenshot(path=f"{out}/dlg_receive.png"); pg.keyboard.press("Escape"); pg.wait_for_timeout(200)
            pg.get_by_role("button", name="Add Device").click(); pg.wait_for_timeout(600); pg.get_by_role("tab", name="Enter a code").click(); pg.wait_for_timeout(300); pg.screenshot(path=f"{out}/dlg_pair_code.png")
            focus_inside = pg.evaluate("!!document.activeElement.closest('[role=dialog]')"); print("dialog focus inside:", focus_inside)
            pg.close()
            print("console/page errors:", [e for e in errs if 'ERR_NAME_NOT_RESOLVED' not in e] or "none"); b.close()
    finally: srv.terminate()
main()
