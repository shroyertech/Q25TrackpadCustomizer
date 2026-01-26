# Q25 Trackpad Customizer

Android app (Accessibility Service) for Zinwa Q25

## Features
- Per-app trackpad mode: Mouse / Keyboard / Scroll Wheel Mode (True scrolling mode)
- Global default mode + “Apply to all”
- Scroll wheel customization (sensitivity, invert, horizontal)
- Auto switch to Keyboard when focusing text inputs (optional, with per-app override and global settings)
- Hold-key temporary switching - Enable an alternate mode while a key is held (optional, with per-app override and global settings)
- Quick Toggle Activity - Includes an App Shortcut which can be called upon by KeyMapper app to cycle through available modes
- Backup & restore settings

## Setup / Usage
1. Install the app.
2. Enable the Accessibility Service:
   - Settings → Accessibility → Q25 Trackpad Customizer → Enable
   - This will show "Restricted Settings" error
   - Go to Apps → Q25 Trackpad Customizer → Tap 3 dots in upper right corner → Allow restricted settings
   - Go back to Settings → Accessibility → Q25 Trackpad Customizer → Enable
3. Grant root access in KernelSU, Magisk, etc.

## Notes
- This app uses an AccessibilityService to monitor the foreground app and focused views.
- Scroll wheel mode maps DPAD keys (keyboard mode) to scroll gestures.

## License
See [LICENSE](LICENSE).
