# PatchFox

Making Firefox for Android a little bit better — an Xposed module that recolors
Firefox with your system's Material You (Monet) colors and tightens up the
browser chrome.

Requires **Firefox 158.x** and an Xposed implementation (LSPosed, or a
zygisk-based fork such as Vector) on a rooted device. Android 12+ (dynamic
color, minSdk 31).

## Features

- **Material You recolor** — Firefox's design-system palette is remapped onto
  the system Monet scheme: both light and dark themes, retinted live with your
  wallpaper. Private mode keeps its purple identity, success/warning states
  keep their semantic green/yellow, and accents stay solid and flat — no
  gradients.
- **Compact address bar** — the URL pill shrinks from 48dp to 40dp (tool strip
  56dp) with text size untouched. The kebab menu gets 8dp of end padding so it
  no longer sits flush with the screen edge, and the search-selector card sits
  flush at the start of the pill content with a 6dp gap to the text field.
- **See-through system bars** — status and navigation bars render over web
  content Chrome-style; works with both top and bottom toolbar placement.
  Status icons adapt light/dark by sampling the content behind them, with
  hysteresis to avoid flicker.
- **Bare tab counter** *(off by default)* — the tab count loses its stroked
  box and renders as a plain enlarged numeral.
- **MD3 Expressive module UI** — the settings screen is a native Material 3
  Expressive screen with a per-feature toggle list.

## Install

1. Download `PatchFox.apk` from the [releases] and install it.
2. Enable the module in your Xposed manager with scope **Firefox**
   (Release/Beta/Nightly, Fenix and Fennec F-Droid are in scope too).
3. Force-stop Firefox and launch it again.

Open the module settings via the entry PatchFox adds to Firefox's own Settings
screen, or by long-pressing the Firefox icon in your launcher. Every toggle
change needs a Firefox restart to apply.

| Toggle            | Default | What it does                                              |
| ----------------- | ------- | --------------------------------------------------------- |
| Monet recolor     | ON      | full palette remap onto the system scheme                 |
| Compact toolbar   | ON      | 40dp pill, 56dp strip, menu and selector spacing fixes    |
| See-through bars  | ON      | transparent system bars + adaptive status icons           |
| Bare tab counter  | OFF     | tab count without the box outline                         |

## Build

No Gradle — a single script:

```sh
bash build.sh
```

It expects an SDK checkout in `../sdk` (or pass `SDK=/path`), with
build-tools 35.0.0 and platforms/android-35. `keystore.jks` is generated on
first run; a fresh keystore means a new signature, so reinstall the APK when
switching machines.

## Notes

- Hooks target Firefox 158 internals; a Firefox update can break them until
  adjusted.
- Not affiliated with Mozilla.

[releases]: https://github.com/mihoillo/PatchFox/releases
