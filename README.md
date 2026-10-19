# NextCal

One calendar app for DAVx5 (CalDAV), Google and Outlook/Exchange calendars, starting on Android with a Linux companion planned.

## How it works

```
core/        Kotlin (multiplatform, JVM target): models, CalendarSource interface,
             CalendarRepository (merges sources), month/agenda logic. Unit tested.
androidApp/  Jetpack Compose UI + ProviderCalendarSource (Android Calendar Provider).
```

`CalendarSource` is the seam. The first implementation reads and writes the **Android Calendar
Provider**, which DAVx5, the Google account and Exchange/Outlook accounts already sync into, so one
source covers all three. Local edits are marked dirty and uploaded by each account's own sync
adapter.

Later sources (same interface) for platforms without a provider, i.e. the Linux app:
CalDAV (RFC 4791), Google Calendar API, Microsoft Graph.

### Limits of the provider approach
- Outlook.com / Microsoft 365 calendars appear only if the Outlook app or an Exchange account is
  set up on the device (DAVx5 can't sync them).
- Android only. The Linux app needs the direct sync sources above.

## Building

```
./gradlew :core:jvmTest              # shared logic, no Android SDK needed
./gradlew :androidApp:assembleDebug  # needs an Android SDK (ANDROID_HOME or local.properties)
```

The `:androidApp` module is only included when an Android SDK is detected (see `settings.gradle.kts`).

## Status / known gaps (v0.1)
- Month view + day agenda, per-calendar visibility, create/edit/delete events.
- Editing a recurring event changes only title/location/description; deleting one deletes the series.
- Date/time entry is text-based (YYYY-MM-DD, HH:mm); pickers, week/day views, reminders, attendees
  and delete confirmation are next.
