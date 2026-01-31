# Q25 Trackpad Customizer

Android app (Accessibility Service) for Zinwa Q25.

## Features
- Per-app trackpad mode: Mouse / Keyboard / Scroll Wheel Mode (True scrolling mode)
- Global default mode + “Apply to all”
- Scroll wheel customization (sensitivity, invert, horizontal)
- Auto switch to Keyboard when focusing text inputs (optional, with per-app override and global settings)
- Hold-key temporary switching - Enable an alternate mode while a key is held (optional, with per-app override and global settings)
- Quick Toggle Activity - Includes an App Shortcut which can be called upon by KeyMapper app to cycle through available modes
  - Select which modes Quick Toggle cycles through (Global + Per-App)
  - Single-Mode Fallback (when only 1 mode is selected)
- Backup & restore settings
- Initial setup helpers (Settings page)
  - Test / Grant Root Access (`su -c id`) to trigger Magisk/KernelSU prompts and also confirm root works
  - Guided Accessibility setup (Restricted Settings flow)

## Download
- You can download the latest version [here](https://github.com/shroyertech/Q25TrackpadCustomizer/releases/latest)

## Setup / Usage

**Prerequisite: 🚨This app requires root!**🚨 
The app also requires enabling its Accessibility Service.

### 1) Install
Install the APK from the Releases page.

### 2) Enable the Accessibility Service (Restricted Settings flow)
Android will require allowing “restricted settings” before the Accessibility toggle can be enabled.

1. Go to **Settings → Accessibility**
2. Tap **Q25 Trackpad Customizer** (it may be grayed out)
   - This will show a **"Restricted settings"** warning
3. Go to **Settings → Apps → Q25 Trackpad Customizer**
4. Tap the **⋮ (3 dots)** menu (top-right) → **Allow restricted settings**
5. Enter your PIN/passcode
6. Go back to **Settings → Accessibility → Q25 Trackpad Customizer → Enable**

> Tip: The app includes a step-by-step setup wizard and a button in Settings (**Initial setup > Accessibility service setup**) that guides you through the above steps.

### 3) Grant Root Access
Grant root access in your root manager (KernelSU, Magisk, etc).

- The app requests root using: `su -c id`
- This serves as both a "check" for root access, and also would prompt apps like Magisk to allow root access if not already granted.
- If you don’t see a prompt or you previously denied it, you can re-check anytime:
  - **Settings → Initial setup → Test / Grant Root Access**

## Quick Toggle

Quick Toggle is an Activity + App Shortcut intended for use with apps like KeyMapper.

### Mode selection (Global + Per-App)
Choose what Quick Toggle cycles through:
- Mouse
- Keyboard
- Scroll Wheel

You can set this globally, and override it per-app.

- If you pick multiple modes, Quick Toggle cycles through only those modes.
- If only one mode is selected, Quick Toggle cycles between your chosen mode and the current per-app mode. 
- If the current per-app mode matches your 1 chosen Quick Toggle, it instead uses your chosen Single-Mode Fallback so it doesn’t “do nothing.”

### Single-Mode Fallback (Global Setting; only applies when only 1 mode is selected)
Example: Quick Toggle is set to **Keyboard only**
  - In Mouse/Scroll Wheel mode > Quick Toggle switches to Keyboard
  - Already in Keyboard mode > Quick Toggle switches to your selected Fallback mode

### Default mode is always part of the cycle
The app’s default/base mode (or system default) is always reachable.
- Example: Default is Keyboard, you select **only** Mouse for Quick Toggle
  - Quick Toggle effectively toggles between **Keyboard > Mouse**

## Notes
- This app uses an AccessibilityService to monitor the foreground app and focused views.
- Scroll wheel mode maps DPAD keys (keyboard mode) to scroll gestures.

## FAQ

### Why did scroll wheel mode / per app settings stop working after updating?
You need to re-enable the Accessibility Service after updating:
- **Settings > Accessibility > Q25 Trackpad Customizer > Enable**

### How can I disable the pop ups when it switches modes?
You can disable toasts individually on the Settings page, near the top. Uncheck any toast categories you don’t want.

### Why doesn’t Magisk prompt me to allow root?
Magisk typically prompts only when the app actually runs `su`. Use:
- **Settings > Initial setup > Test / Grant Root Access**

If you previously denied root and Magisk remembered it, re-enable it in:
- **Magisk > Superuser > Q25 Trackpad Customizer > Allow**

### I don’t see the “⋮ / Allow restricted settings” option in App Info
You likely missed the step where you tap the app entry in **Accessibility** first (that’s what triggers the restricted warning).  
Go back to:
- **Settings > Accessibility > Q25 Trackpad Customizer**  
Then return to App Info and check again.

## License
See [LICENSE](LICENSE).

## Donations
- If you feel this app has improved your experience with your Q25, consider donating to the WVU Cancer Institute
- You can make a donation [here](https://give.wvu.edu/give/430764/#!/donation/checkout)
- You can read more about the WVU Cancer Institute [here](https://give.wvu.edu/campaign/wvu-cancer-institute/c430764)
- Thank you <3

## Attributions
- App icon created by WhoCon from [FlatIcon](https://www.flaticon.com/free-icons/toggle)
