# FLUX Android: type and spacing system

All styling lives in `Ui.java`. Screens (`MainActivity.java`) may not invent sizes: they pick a **Type**, a **spacing step** and a **component**.
`python tools/design_lint.py` (also run by the Android build workflow) checks these rules mechanically.

## Type scale (sp: scales with the user's system font size)
| Style | Size / weight | Used for |
|---|---|---|
| DISPLAY | 32 / bold | splash wordmark |
| TITLE | 26 / bold | screen titles (Files, History, Settings), result title, onboarding headline |
| HEADLINE | 20 / semibold | state headlines on Home ("Connected", "Ready to connect") |
| SECTION | 18 / semibold | section headings ("Quick actions"), empty-state titles, waiting-files summary |
| SUBHEAD | 16 / semibold | row titles, file names, top-bar titles, summary values |
| BODY | 16 / regular | reading text (onboarding intro, result message, splash tagline) |
| BODY_SM | 14 / regular | supporting text, values, banners |
| CAPTION | 13 / regular | metadata (never smaller than 13) |
| LABEL | 16 / semibold | button labels |
| TAB | 14 / medium | segmented control and tab labels |
| NAV | 12 / medium | bottom navigation labels |
| STAT | 32 / bold, tabular figures | progress percentage |
Numbers that change (percentage, bytes, counts) use tabular figures so digits line up and do not jitter.
Weights use the system font (Roboto) via `Typeface.create(DEFAULT, weight)`; no font dependency.

## Spacing and shape
Steps (dp): 4, 8, 12, 16, 24, 32. Screen gutter 24dp (16dp below 360dp width). Row minimum 64dp. Touch target 48dp. Radii 12 / 16 / 20.
Headings sit 4-8dp above their descriptions (one unit); sections are separated by 24dp; related controls by 12dp.

## Rules that fix the bugs found in the first on-device screenshots
* Text is always created through `Ui.text`, which sets explicit layout params. (Before, headings stretched to full width and hugged the left while their descriptions were centred.)
* Buttons, rows and bars use `wrap_content` + a minimum height, never a fixed height, so larger system fonts grow them instead of clipping.
* The bottom navigation cell is `wrap_content` (min 56dp) with a centred single-line label.
* File names wrap to two lines then ellipsize; metadata is one line; numbers never share a line with an ellipsizing name.
* Status is always words plus an icon ("Completed", "Not completed"), never colour alone. Text colours meet WCAG AA on white.
* Messages are structured as **what happened / what to do / small technical detail** (`Problem`), and transient banners clear themselves after 8 seconds so they cannot contradict the state shown below them.

## Not covered / limitations
The QR scanner screen is the scanner library's own UI; system dialogs (AlertDialog) use Android's styling. Nothing here has been checked on a physical device or with a real large-font setting yet; the lint verifies the rules, not the pixels.
