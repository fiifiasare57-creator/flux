# FLUX design reference (from design/ui-reference-share.png and design/logo-vf.png)

Direction: light, white-and-black, calm and premium. (Supersedes the dark-graphite theme in the
first spec; a dark theme can be added later via the same tokens.)

Logo: black "VF" monogram (design/logo-vf.png). Wordmark "FLUX": wide-tracked geometric sans.

Layout (1536x1024 reference): left nav rail (Share / Devices / Activity / Settings; active item =
solid black pill with white text), top status bar ("Connected to local network", green dot),
centre column (greeting, dashed drop zone with black "Select Files" button, Nearby Devices cards,
Recent Transfers), right column (Quick Send, Transfer Queue with pause/cancel, Recent Activity,
FLUX Flow card). Footer card in nav: "FLUX - Fast. Secure. Connected. v1.0.0".

Tokens (approximate, sampled by eye):
  --bg: #ffffff   --bg-subtle: #f7f8fa   --border: #e6e8ec   --text: #0b0f1a   --muted: #6b7280
  --primary: #0b0f1a (buttons, active nav)   --accent: #2f7df6 (progress, links)
  --success: #16a34a   --offline: #9ca3af   --danger: #dc2626
  radius: 12px cards / 10px buttons   shadow: 0 1px 2px rgba(16,24,40,.04), 0 4px 12px rgba(16,24,40,.04)
  font: Inter (system fallback Segoe UI Variable)   sizes: 28 title / 16 section / 13 body / 12 meta

Reference data in the mock (Samsung Galaxy S24, Study Laptop, speeds, percentages) is illustrative
only: the real UI must never ship fake devices or fake progress (see spec section 5).
