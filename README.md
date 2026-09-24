# OLK for Android

The native Android client for [Open Library Kashmir](https://www.openlibrarykashmir.com) —
a privacy-first, community-run platform for donating and lending used books across
Kashmir. No shipping, no fees: you find a book near you and meet the person who has it.

This repository is the phone app. The web app, the database schema and every
migration live in the main [`olk`](https://github.com/Owais-Saleem-Lone/olk)
repository, which stays the single source of truth for both.

> **Not affiliated with the Internet Archive** or its Open Library service. OLK is an
> independent community project.

---

## Why this is a front end, not a second backend

Every authorisation rule in OLK lives in Postgres — RLS policies, `SECURITY DEFINER`
helpers like `is_club_member()`, rate-limit triggers, and the membership-approval
`WITH CHECK` constraints. The web app is overwhelmingly client-side Supabase calls
against those rules; only two files use Next.js server actions, and both are admin
tooling.

That has a direct consequence for this app: **it talks to the same Supabase project
directly and inherits every rule unchanged.** There is no API layer to duplicate and
no business logic to port. If a rule holds on the website, it holds here, because
both are asking the same database and the database is the thing enforcing it.

The anon key shipped in the APK carries no privilege of its own — it is the same key
published in the web app's JavaScript bundle.

## Architecture

```
:app                    Compose UI, navigation, ViewModels, DI wiring
:core:data              Supabase client, DTOs, repositories  ← platform-agnostic
:core:designsystem      Material 3 theme carrying the web brand
```

`:core:data` may not import `android.*` or `androidx.compose.*`. That is not a style
preference — it is enforced by a Gradle task:

```bash
./gradlew :core:data:checkNoAndroidImports
```

The reason is iOS. `supabase-kt` and Compose Multiplatform are both KMP-native, so a
future iOS app is a matter of moving this module to `commonMain` rather than
rewriting the app. That option stays open only while the boundary holds, and
boundaries that are not enforced do not hold.

**Stack:** Kotlin 2.4.20 · Compose (BOM 2026.09.00) · Material 3 · Navigation Compose
(type-safe routes) · Koin · supabase-kt 3.8.0 · Ktor · Coil 3 · CameraX + ZXing
(barcode scanning, deliberately not ML Kit — no Play services) · AGP 9.4 / Gradle 9.7.1.

Koin rather than Hilt: no annotation processing, so builds stay fast, and it is
KMP-native like everything else in `:core:data`.

## Getting started

**Requirements:** JDK 21 (AGP does not support 25 yet) and Android SDK platform
37.2 with build-tools 37.0.0.

```bash
git clone git@github.com:Owais-Saleem-Lone/olk-android.git
cd olk-android

cp secrets.defaults.properties secrets.properties
# Fill in SUPABASE_URL and SUPABASE_ANON_KEY from your Supabase project settings
# (Project Settings → API). These are the same two values the web app uses as
# NEXT_PUBLIC_SUPABASE_URL and NEXT_PUBLIC_SUPABASE_ANON_KEY.

./gradlew :app:assembleDebug
```

If Gradle cannot find your SDK, point `local.properties` at it:

```properties
sdk.dir=/path/to/Android/Sdk
```

### Against a local Supabase stack

Environment variables override `secrets.properties`, so a debug build can point
at the web repo's local Docker stack without touching your real values. From the
emulator, the host machine is `10.0.2.2`; debug builds (only) allow plain HTTP
to it, see `app/src/debug/res/xml/network_security_config.xml`.

```bash
SUPABASE_URL=http://10.0.2.2:54321 \
SUPABASE_ANON_KEY=<anon key from `npx supabase status` in the web repo> \
./gradlew :app:installDebug
```

The next build without those variables points back at your usual project.

### Useful commands

| Command | What it does |
|---|---|
| `./gradlew :app:assembleDebug` | Debug APK |
| `./gradlew :app:assembleRelease` | Minified release APK (~2.4MB) |
| `./gradlew test` | Unit tests |
| `./gradlew check` | Tests plus the `:core:data` boundary guard |
| `./gradlew installDebug` | Install on a connected device |

## SDK levels

| | | Why |
|---|---|---|
| `minSdk` | 26 | Notification channels (required for FCM), adaptive icons, `java.time`. Covers ~98% of active devices. |
| `targetSdk` | 36 | Controls runtime behaviour opt-in. No reason to adopt API 37's behaviour changes before testing them. |
| `compileSdk` | 37.2 | Not optional — navigation-compose 2.10.1 and Coil 3.6.2 publish AAR metadata demanding 37. API 37 ships as minor platforms, hence `compileSdkMinor`. |

## Roadmap

**v1 — the core loop**

- [x] Project foundation, build, CI
- [x] Email/password auth with session persistence
- [x] Home (the website homepage: announcements, stats, Book of the Month, recently added, live activity) as the first tab; Browse opens from its search
- [x] Browse with debounced search and pagination
- [x] Browse filters: genre, giving away / lending, condition, area, distance (measured in the database from your own saved location; the app never sends coordinates)
- [x] Book detail and the request flow
- [x] My books — add (ISBN scan or manual entry, cover from the gallery or
      camera), list, edit, change cover, mark given, delete
- [x] Requests — accept, decline, handover, return, finish a donated book
      (reading progress and ratings still on the website)
- [x] Messages — inbox and live chat over Supabase Realtime
- [x] Notifications — list and unread badge, live over Supabase Realtime; admin
      feature switches (e.g. messaging off) respected
- [x] Profile — name, area, bio, approximate location for "books near me", weekly
      digest, suspension notice, sign out
- [x] Saved books and wishlist (tabs in My Books; wishlist follows the `feature_wishlists` flag)
- [x] Public profiles, ratings, reading progress and "Books you're reading" in My Books
- [x] Community notes on the book page — members who have owned or borrowed a book add,
      edit and delete theirs (up to 500 words); moderators can remove any note
- [x] Clubs — browse, request to join, the owner's approvals, members-only chat, ratings
- [x] Events — browse, RSVP, the joining link for those going, who's going, add to
      calendar, the organiser's cancel; a club's upcoming events on its page
- [x] Contact Admin — the private thread with the admin team, live, with replies
      announced in the app and by email
- [x] Join the OLK Team — the volunteer / internship application with a CV, sent to the
      website's endpoint (which stores the CV privately and emails the team)
- [x] Suspensions — a notice on every screen while one lasts, and the real reason when
      the database refuses something (it enforces suspensions itself)
- [ ] Starting a club and scheduling an event — still on the website
- [ ] Push notifications (FCM) — parked; email and in-app notifications cover it for now

**Known gaps to close before v1 ships**

These are places where the web app does something the phone currently cannot:

1. **Push has no backend.** Needs a `device_tokens` table plus something that calls
   FCM when a notification row is inserted.

**Later**

- Offline cache (Room) — matters more here than in most apps, given network
  conditions in the region
- F-Droid release alongside Play

## Contributing

See [CONTRIBUTING.md](https://github.com/Owais-Saleem-Lone/olk/blob/main/CONTRIBUTING.md)
in the main repository. Schema changes belong there, not here.

## License

[GNU Affero General Public License v3.0](LICENSE), matching the web app.

Note for the future: AGPL/GPL-family licenses conflict with the Apple App Store's
terms, so an iOS release would require relicensing that codebase.
