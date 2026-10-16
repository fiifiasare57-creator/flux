"""Layout check only: renders the real components in headless Chromium with a MOCKED Tauri IPC and SAMPLE data
(the sample devices exist only in this harness; the shipped app never contains them)."""
import sys, json, subprocess, time, os
from playwright.sync_api import sync_playwright

def mock(info):
    return """(() => {
  const info = %s; const cbs = {}; let n = 1;
  window.__TAURI_INTERNALS__ = { metadata: { currentWindow: { label: 'main' }, currentWebview: { windowLabel: 'main', label: 'main' } },
    transformCallback: (cb) => { const id = n++; cbs[id] = cb; return id; }, unregisterCallback: () => {}, convertFileSrc: (p) => p,
    invoke: async (cmd, args) => {
      if (cmd === 'app_info') return info; if (cmd === 'history') return [];
      if (cmd.startsWith('plugin:event|listen')) { window.__l = window.__l || {}; window.__l[args.event] = args.handler; return n++; }
      if (cmd.startsWith('plugin:event|')) return null;
      if (cmd === 'begin_pairing') return { uri: 'flux://pair?v=1&a=192.168.1.20%%3A47800&fp=' + 'a'.repeat(64) + '&t=' + 'f'.repeat(64), expires_in_secs: 120 };
      return null; } };
  window.__emit = (e, p) => { const h = (window.__l||{})[e]; if (h) cbs[h]({ event: e, id: 0, payload: p }); };
})();""" % json.dumps(info)

NOW = int(time.time())
BASE = dict(fingerprint='a'*64, port=47800, version='0.1.0', discovery_active=True, lan_addrs=['192.168.1.20:47800'],
            settings=dict(device_name='Fiifi-Laptop', receive_dir='C:\\\\Users\\\\Fiifi\\\\Downloads\\\\FLUX', require_approval_for_trusted=False, discoverable=True),
            trusted=[], nearby=[], queued=[])
PHONE = lambda online: dict(fingerprint='b'*64, name='Galaxy S24', device_type='phone', paired_at=NOW-3600, last_addr=None, online=online, last_seen=NOW-(2 if online else 900))
SCEN = {
  "A_no_phone": dict(BASE),
  "B_phone_online": dict(BASE, trusted=[PHONE(True)]),
  "C_offline_queued": dict(BASE, trusted=[PHONE(False)], queued=[dict(transfer_id='q1', peer_fingerprint='b'*64, peer_name='Galaxy S24', file_count=3, total_bytes=12400000, names=['Biology notes.pdf'], created=NOW, state='queued')]),
  "D_transferring": dict(BASE, trusted=[PHONE(True)]),
}
def main():
    out = "/tmp/dshots"; os.makedirs(out, exist_ok=True)
    srv = subprocess.Popen(["python3", "-m", "http.server", "4190", "-d", "dist"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); time.sleep(1)
    try:
        with sync_playwright() as p:
            b = p.chromium.launch(); errs = []
            for name, info in SCEN.items():
                for (w, h) in [(1280, 720), (1920, 1080)] if name == "B_phone_online" else [(1366, 768)]:
                    pg = b.new_page(viewport={"width": w, "height": h}); pg.on("pageerror", lambda e: errs.append(str(e)))
                    pg.on("console", lambda m: errs.append(m.text) if m.type == "error" else None)
                    pg.add_init_script(mock(info)); pg.goto("http://localhost:4190/"); pg.wait_for_timeout(700)
                    pg.locator(".nav-item", has_text="Devices").click(); pg.wait_for_timeout(500)
                    if name == "D_transferring":
                        pg.evaluate("p => window.__emit('transfer-progress', p)", dict(transfer_id='t', direction='sent', peer_name='Galaxy S24', state='active', file_name='Match.mp4', files_done=0, files_total=1, bytes_done=420, bytes_total=1000, error=None)); pg.wait_for_timeout(400)
                    overflow = pg.evaluate("document.documentElement.scrollWidth > innerWidth")
                    imgs_ok = pg.evaluate("[...document.images].every(i => i.complete && i.naturalWidth > 0)")
                    pg.screenshot(path=f"{out}/{name}_{w}.png")
                    print(f"{name} {w}x{h}: horizontal overflow={overflow} images loaded={imgs_ok}")
                    if name == "A_no_phone" and w == 1366:
                        pg.get_by_role("button", name="Pair a device").first.click(); pg.wait_for_timeout(700); pg.screenshot(path=f"{out}/A_pair_dialog.png")
                    pg.close()
            print("console/page errors:", [e for e in errs if 'ERR_NAME_NOT_RESOLVED' not in e] or "none"); b.close()
    finally: srv.terminate()
main()
