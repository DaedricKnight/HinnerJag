# Hinner jag?

An Android Automotive OS prototype: pick a nearby ferry, see whether you will make
the next departure, and if you won't, the app says so calmly and suggests a coffee
instead of a faster drive. Swedish UI, Gothenburg routes.

Built with the Car App Library (POI category), so it renders through the system
templates host and the same code can target Android Auto.

## What it does

| Screen | Template | Content |
| --- | --- | --- |
| Ferries nearby | `PlaceListMapTemplate` | Three routes with distance, departure, drive time and a verdict; numbered markers on the host map |
| One ferry | `PaneTemplate` | On time / tight / missed, a timeline (arrive, board-by, depart) and two actions |
| Marginaler | `ListTemplate` | Boarding margin per route (check-in 20, queue 5, walk to the pier 10 min), demo switches |

**Kör** hands the port to the car's navigation app (`ACTION_NAVIGATE`) and starts a
trip watcher. It re-checks the drive every 45 s (15 s in demo) and posts a high-importance
notification only when the verdict gets worse: on time → tight → missed.

### The verdict rule

- `slack = (departure − boarding margin) − arrival`, arrival rounded up to the minute.
- `slack ≥ 5` → **Du hinner**; `0…4` → **Ont om tid**; `< 0` → missed.
- There is no "you'll make it if you hurry". Tight always comes with "Kör inte fortare" and
  the next departure. `SafetyCopyTest` sweeps every route, minute and drive time and fails
  if any sentence urges speed.
- A departure you can't make is only mentioned when the next one is 20+ minutes away
  (that's when "Ta en fika" makes sense). For a ferry every 15 minutes the app talks
  about the one you will make.

## Data

- `app/src/main/assets/ferries.json`: **invented** departure times for Göteborg → Frederikshavn,
  Lilla Varholmen → Hönö and Saltholmen → Styrsö. Port coordinates are from OpenStreetMap.
- Drive time comes from the public OSRM demo server: no live traffic, and a fair-use policy
  of about one request a second. A product needs a routing API with traffic.
  If OSRM is unreachable the app falls back to a straight-line estimate and marks it
  "(uppskattad)".

## Demo mode (on by default)

The clock starts at 13:30 and the car stands in Kungälv, which reproduces the mockup's
three cases with real OSRM times: Frederikshavn missed (coffee, ~3 h), Hönö tight
(3 min), Styrsö on time (15 min). Turn it off under **Marginaler** to use the real
clock and the car's location.

To see the in-drive warning: **Marginaler → Simulerad kö under resan → +20 min**, then
open Saltholmen → Styrsö and tap **Kör**. Half a minute later:
"Du hinner inte till 14:30 — Kö på vägen: du är vid Saltholmen ~14:26. Ingen stress –
nästa båt går 15:00."

## Build and run

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.artemkhateev.hinnerjag/androidx.car.app.activity.CarAppActivity
```

Tested on the `Automotive_1408p_landscape_with_Google_Play` AVD (API 35, templates host
and Google Maps preinstalled).

**Heads-up on the emulator.** That image ships with
`settings global heads_up_notifications_enabled = 0`, so warnings land in the notification
centre (bell icon) instead of popping up over navigation. Turning it on should let the
warning pop up (not verified yet):

```bash
adb -s emulator-5554 shell settings put global heads_up_notifications_enabled 1
```

## Layout

```
model/   Ferry.kt (timetable), Verdicts.kt (the rule), Copy.kt (every Swedish sentence)
eta/     OSRM, straight-line fallback, 60 s cache
car/     CarAppService + Session, list, detail and margins screens, UI helpers
trip/    TripWatcher, notifications, foreground service (type location)
```

## Not done

- Real timetables (Västtrafik, Stena Line) and live traffic.
- Android Auto build (needs an `app-projected` module; the screens are shared).
- Map on the detail screen (`MapWithContentTemplate`, car API level 7).
- The coffee search relies on the navigation app understanding `geo:0,0?q=`.
