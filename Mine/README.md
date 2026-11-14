# Mine

Tasks with alarms, habits, money across accounts and cards, and carpool dues. Dark theme, works offline, all data stays on your phone.

## Get the APK (no Android Studio needed)

1. Create a new **private** repository on GitHub, for example `mine-app`.
2. Push this folder to it:
   ```
   cd Mine
   git init
   git add .
   git commit -m "Mine app"
   git branch -M main
   git remote add origin https://github.com/<you>/mine-app.git
   git push -u origin main
   ```
3. Open the repo → **Actions** tab. The "Build Mine APK" run takes about 4–6 minutes.
4. When it turns green, open the repo's **Releases** → **Mine – latest build** → download `Mine.apk` on your phone.
   (It is also under the run's **Artifacts** as a zip.)
5. Open the APK and allow "Install unknown apps" for your browser or Files app when Android asks.

Every push to `main` builds a fresh APK, so updating the app is just: change files, commit, push, reinstall.

## Or build with Android Studio

Open the `Mine` folder in Android Studio (Hedgehog or newer), let it sync, then **Build → Build App Bundle(s) / APK(s) → Build APK(s)**. If it asks about a Gradle wrapper, choose to create one.

## First launch on the phone

- Allow notifications.
- Today screen shows a green banner if "Alarms & reminders" or full-screen alarms need permission. Tap **Allow**.
- Settings → **Test alarm in 10 seconds**, then lock the phone to check it rings.
- On Xiaomi, Oppo, Vivo, Realme, OnePlus and Samsung phones also set Mine's battery usage to **Unrestricted / No restrictions** (Settings → Apps → Mine → Battery), otherwise the phone may block alarms.

## How it works

- `app/src/main/assets/index.html` is the whole app UI.
- Native Android code (`app/src/main/java/com/mine/app`) schedules exact alarms with `AlarmManager.setAlarmClock`, rings with your phone's alarm tone, shows a full-screen alarm on the lock screen, and supports Done / Snooze / Dismiss from the notification. Alarms are rescheduled after a reboot.
- Backups, CSV and calendar files are saved to `Downloads/Mine`.

## Credits

Exercise pictures in `app/src/main/assets/ex/` are from [Everkinetic](https://github.com/everkinetic/data) by Greg Priday, licensed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) (copy in `assets/ex/LICENSE-CC-BY-SA-4.0.md`). They are used unchanged.
