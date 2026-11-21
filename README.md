# Q25 Trackpad Customizer

Android app (Accessibility Service) for Zinwa Q25.

[![Donate to the WVU Cancer Institute](https://img.shields.io/badge/%E2%9D%A4%20Donate-WVU%20Cancer%20Institute-EAAA00?style=for-the-badge&labelColor=002855)](https://give.wvu.edu/give/430764/#!/donation/checkout)
[![Support Mawmaw's Books](https://img.shields.io/badge/%F0%9F%93%9A%20Support-Mawmaw%27s%20Books-2E7D32?style=for-the-badge)](https://mawmawsbooks.org)

This app is free and always will be. I don't take personal donations. If it's made your Q25 better, please consider supporting one of these instead (more [below](#donations)).

## Features
- Per-app trackpad mode: Mouse / Keyboard / Scroll Wheel Mode
  - Per-app settings can be switched off completely (Settings > General): every app then uses the default mode and global settings
- Global default mode + “Apply to all”
- Scroll wheel mode with two methods: Classic (the original swipes) and Touch (moves a virtual finger, smoother, needs root)
  - Speed, invert and horizontal options, globally or per app
  - Scroll practice page (Settings > Scrolling) to try the settings out on a big board
- Edge auto-scroll (experimental, off by default)
- Auto switch to Keyboard when focusing text inputs (optional, with per-app override and global settings)
- Hold-key temporary switching - Enable an alternate mode while a key is held (optional, with per-app override and global settings)
- Quick Toggle Activity - Includes an App Shortcut which can be called upon by KeyMapper app to cycle through available modes
  - Select which modes Quick Toggle cycles through (Global + Per-App)
  - Single-Mode Fallback (when only 1 mode is selected)
- Optional notification showing the current mode, with buttons to pick Mouse, Keyboard or Scroll
- Cursor speed setting and a Diagnostics page (with copy button for bug reports)
- Backup & restore settings
- Initial setup helpers (Settings page)
  - Test / Grant Root Access (`su -c id`) to trigger Magisk/KernelSU prompts and also confirm root works
  - Guided Accessibility setup (Restricted Settings flow)

## Download
- You can download the latest version [here](https://github.com/shroyertech/Q25TrackpadCustomizer/releases/latest)

## Setup / Usage

🚨 **Prerequisite: This app requires root!** 🚨 
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

## Donations
I've never asked for money for this project and never will. If it has improved your experience with your Q25, please consider one of these instead. Thank you <3

### WVU Cancer Institute
- Donate [here](https://give.wvu.edu/give/430764/#!/donation/checkout)
- Read more about the WVU Cancer Institute [here](https://give.wvu.edu/campaign/wvu-cancer-institute/c430764)
- There's also a donate button in the app's Settings

### Mawmaw's Books
- [mawmawsbooks.org](https://mawmawsbooks.org)
- Mawmaw's Books isn't set up to take cash donations, but mailed donations of **books and computers** are gladly accepted. Send them to one of the store addresses listed on the website, addressed to **ATTN: Mawmaw's Books**.

### A note for forks
Forks are welcome, and some great ideas have come out of them. If you fork this project, I'd ask that you please keep these charity links in place.

## License
Copyright (C) 2026 Shroyer Tech LLC

Licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).

## Attributions
- App icon created by WhoCon from [FlatIcon](https://www.flaticon.com/free-icons/toggle)
