"""Layout check only: renders the built UI in headless Chromium with a MOCKED Tauri IPC and SAMPLE data.
The sample devices/transfers below exist only in this harness; the shipped app never contains fake data."""
import sys, json, subprocess, time, os
from playwright.sync_api import sync_playwright

MOCK = """
(() => {
  const now = Math.floor(Date.now()/1000);
  const info = { fingerprint: 'a'.repeat(64), port: 47800, version: '0.1.0', discovery_active: true, lan_addrs: ['192.168.1.20:47800'],
    settings: { device_name: 'Fiifi-Laptop', receive_dir: 'C:\\\\Users\\\\Fiifi\\\\Downloads\\\\FLUX', require_approval_for_trusted: false, discoverable: true },
    trusted: [ { fingerprint: 'b'.repeat(64), name: 'Galaxy S24 (sample)', device_type: 'phone', paired_at: now-86400, last_addr: '192.168.1.24:47800' },
               { fingerprint: 'c'.repeat(64), name: 'Study Laptop (sample)', device_type: 'laptop', paired_at: now-86400*3, last_addr: '192.168.1.32:47800' } ],
    nearby: [ { fingerprint: 'b'.repeat(64), name: 'Galaxy S24 (sample)', device_type: 'phone', addrs: ['192.168.1.24:47800'], paired: true },
              { fingerprint: 'd'.repeat(64), name: 'Family PC (sample)', device_type: 'desktop', addrs: ['192.168.1.45:47800'], paired: false } ] };
  const history = [ { id:'1', timestamp: now-120, direction:'received', peer_name:'Galaxy S24 (sample)', peer_fingerprint:'b'.repeat(64), files:['IMG_7421.jpg'], file_count:1, total_bytes:2516582, outcome:'completed', error:null },
                    { id:'2', timestamp: now-720, direction:'sent', peer_name:'Study Laptop (sample)', peer_fingerprint:'c'.repeat(64), files:['Project_Video.mp4'], file_count:1, total_bytes:1288490188, outcome:'completed', error:null },
                    { id:'3', timestamp: now-3600, direction:'received', peer_name:'Family PC (sample)', peer_fingerprint:'d'.repeat(64), files:['Notes.pdf'], file_count:1, total_bytes:12900000, outcome:'failed', error:'timed out: waiting for peer' } ];
  const cbs = {}; let n = 1;
  window.__TAURI_INTERNALS__ = {
    metadata: { currentWindow: { label: 'main' }, currentWebview: { windowLabel: 'main', label: 'main' } },
    transformCallback: (cb) => { const id = n++; cbs[id] = cb; return id; },
    unregisterCallback: () => {},
    convertFileSrc: (p) => p,
    invoke: async (cmd, args) => {
      if (cmd === 'app_info') return info;
      if (cmd === 'history') return history;
      if (cmd.startsWith('plugin:event|listen')) { window.__listeners = window.__listeners || {}; window.__listeners[args.event] = args.handler; return n++; }
      if (cmd.startsWith('plugin:event|')) return null;
      if (cmd === 'begin_pairing') return { uri: 'flux://pair?v=1&a=192.168.1.20%3A47800&fp=' + 'a'.repeat(64) + '&t=' + 'f'.repeat(64) + '&n=Fiifi-Laptop', expires_in_secs: 120 };
      return null;
    },
  };
  window.__emit = (event, payload) => { const h = (window.__listeners||{})[event]; if (h) cbs[h]({ event, id: 0, payload }); };
})();
"""

def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/shots"
    os.makedirs(out, exist_ok=True)
    srv = subprocess.Popen(["python3", "-m", "http.server", "4173", "-d", "dist"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)
    try:
        with sync_playwright() as p:
            b = p.chromium.launch()
            pg = b.new_page(viewport={"width": 1536, "height": 1024})
            errors = []
            pg.on("pageerror", lambda e: errors.append(str(e)))
            pg.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
            pg.add_init_script(MOCK)
            pg.goto("http://localhost:4173/"); pg.wait_for_timeout(800)
            # live transfers (the only way progress appears in the real app is via engine events)
            ev = lambda **k: pg.evaluate("p => window.__emit('transfer-progress', p)", dict(files_done=0, files_total=1, error=None, **k))
            ev(transfer_id='t1', direction='sent', peer_name='Galaxy S24 (sample)', state='active', file_name='IMG_7421.jpg', bytes_done=12_300_000, bytes_total=18_100_000)
            ev(transfer_id='t2', direction='received', peer_name='Study Laptop (sample)', state='active', file_name='Project_Video.mp4', bytes_done=542_000_000, bytes_total=1_288_490_188)
            ev(transfer_id='t3', direction='sent', peer_name='Family PC (sample)', state='awaiting_approval', file_name='Documents.zip', bytes_done=0, bytes_total=50_000_000)
            pg.wait_for_timeout(1500)
            ev(transfer_id='t1', direction='sent', peer_name='Galaxy S24 (sample)', state='active', file_name='IMG_7421.jpg', bytes_done=13_300_000, bytes_total=18_100_000)
            pg.wait_for_timeout(400)
            pg.screenshot(path=f"{out}/share.png")
            for name in ["Devices", "Activity", "Settings"]:
                pg.locator(".nav-item", has_text=name).click(); pg.wait_for_timeout(400)
                pg.screenshot(path=f"{out}/{name.lower()}.png")
            pg.locator(".nav-item", has_text="Share").click()
            pg.evaluate("p => window.__emit('incoming-request', p)", dict(request_id='r1', peer_name='Family PC', peer_fingerprint='d'*64, trusted=False, file_count=3,
                files=[dict(name='Biology notes.pdf', size=2_400_000), dict(name='Cell structure.jpg', size=3_100_000), dict(name='Practical.mp4', size=88_000_000)], total_bytes=93_500_000, destination='C:\\Users\\Fiifi\\Downloads\\FLUX'))
            pg.wait_for_timeout(400); pg.screenshot(path=f"{out}/incoming.png")
            pg.keyboard.press("Tab")  # dialog should keep focus inside
            focused_inside = pg.evaluate("!!document.activeElement.closest('[role=dialog]')")
            print("focus stays inside dialog:", focused_inside)
            print("console/page errors:", errors or "none")
            b.close()
    finally:
        srv.terminate()
main()
