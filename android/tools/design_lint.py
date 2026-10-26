"""Design lint for the FLUX phone app. Enforces the typography/spacing rules mechanically (no device needed).
Run: python tools/design_lint.py     Exit code 1 if any rule fails."""
import re, sys, pathlib
src = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/java/app/flux"
main = (src / "MainActivity.java").read_text(); ui = (src / "Ui.java").read_text()
fails = []
def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if detail and not ok else ""));
    if not ok: fails.append(name)

# 1. one type scale: sizes only come from Ui.Type
sizes = sorted({float(x) for x in re.findall(r"new Type\((\d+(?:\.\d+)?)f?,", ui)})
check(f"type scale has few sizes ({len(sizes)}): {[int(s) for s in sizes]}", len(sizes) <= 9)
check("no text size below 12sp", min(sizes) >= 12)
check("no raw setTextSize in screens (sizes come from Ui.Type)", "setTextSize(" not in main.replace("in.setTextSize(20)", ""), "raw setTextSize found")
check("no pixel text sizes (all sizes are sp)", "COMPLEX_UNIT_PX" not in main + ui and "COMPLEX_UNIT_DIP" not in main + ui)
# 2. spacing scale: dp literals in screens must be on the scale or a named constant
allowed = {0, 1, 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 56, 64, 72, 88, 96, 104, 140, 200}
used = sorted({int(x) for x in re.findall(r"\bdp\((\d+)\)", main)})
bad = [u for u in used if u not in allowed]
check(f"dp literals in MainActivity are on the scale {used}", not bad, str(bad))
check("spacing uses Ui.S* constants (count of Ui.S usages >= 80)", len(re.findall(r"Ui\.S(4|8|12|16|24|32)", main)) >= 80, str(len(re.findall(r"Ui\.S(4|8|12|16|24|32)", main))))
check("no arithmetic on spacing steps (Ui.S4 + 2 style off-scale values)", not re.findall(r"Ui\.S\d+\s*[+/*-]\s*\d", main), str(re.findall(r"Ui\.S\d+\s*[+/*-]\s*\d", main)))
check("no middle-ellipsis on multi-line text", "TruncateAt.MIDDLE" not in main)
# 3. no fixed heights on text/controls (breaks with large system fonts)
fixed = re.findall(r"LayoutParams\(\s*-?\d+\s*,\s*dp\((?!1\)|2\))\d+\)", main)
check("no fixed-height rows/buttons with text (wrap_content + min height only)", not fixed, str(fixed[:3]))
check("buttons use minHeight, not a fixed height", "setMinHeight(dp(c, 52))" in ui and "LayoutParams(-1, dp(c, 5" not in ui)
# 4. every TextView goes through Ui.text (explicit LayoutParams; no left-align-by-default)
check("no bare 'new TextView(' in screens", "new TextView(" not in main)
check("Ui.text sets explicit LayoutParams", "v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2))" in ui)
# 5. touch targets and a11y
check("icon buttons are 48dp touch targets", "dp(c, TOUCH), dp(c, TOUCH)" in ui and "TOUCH = 48" in ui)
check("icon-only buttons have content descriptions", "setContentDescription(description)" in ui)
check("nav labels are single-line and ellipsized", "lb.setSingleLine(true)" in main)
check("tabular figures used for changing numbers", 'setFontFeatureSettings("tnum")' in ui and "Ui.num(" in main)
check("long file names wrap to 2 lines then ellipsize", "Ui.lines(tx(title, Ui.SUBHEAD, Ui.INK), 2)" in main)
check("text colours are AA-contrast tokens (MUTED/GREEN/RED/ACCENT)", all(t in ui for t in ("MUTED = 0xFF5F6775", "GREEN = 0xFF15803D", "RED = 0xFFB42318")))
check("no ALL CAPS label styling (setAllCaps(true)/textAllCaps)", "setAllCaps(true)" not in main + ui and "textAllCaps" not in main + ui)
check("font scale is respected (no fontScale override / no fixed text px)", "fontScale = " not in main and "setFontScale" not in main)
check("status bar/nav bar left to the system (no hard inset hacks)", "getWindowInsets" not in main and "setDecorFitsSystemWindows(false)" not in main)
check("saved PC address list is rebuilt without string replace()", "addrs.replace(addr" not in main and "addrList()" in main)
print("\nRESULT:", "ALL RULES PASS" if not fails else f"{len(fails)} FAILED"); sys.exit(1 if fails else 0)
