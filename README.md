# EiN for Android

The [EiN](https://einhq.eu) app for Android phones: EiN's Web UI in an app of its own, paired with
your EiN by a QR code or by signing in once with your password, which the phone does not keep. How to pair a phone and what the
app does is in EiN's guide, [The EiN app](https://einhq.eu/docs/guide/phone.html#the-ein-app).

It is one activity holding a WebView (`app/src/main/kotlin/eu/einhq/app/`):

- **Pairing.** EiN shows a QR code on a phone's **Info** tab,
  `ein://pair?server=<origin>&device=<phone>&code=<code>`. The app scans it with Google's code
  scanner, or the phone's camera opens the app with it, and trades the one-time code for a token at
  `POST /__app/pair`. Or the app asks for EiN's address (`https://` unless typed otherwise), a
  username and a password, and sends them once to `POST /__app/sign-in` with what it says of the
  phone (`ThisPhone.kt`): EiN pairs it as the phone marked with its installation, or answers which
  phones the account may pair and whether it may add one, and the app asks which. Either way it
  names its installation, Android's `ANDROID_ID` for the app, which EiN keeps on the phone it pairs
  as. The token is kept under a key of the phone's keystore and is never backed up.
- **Signing in.** The app trades the token for a Web UI session at `GET /__app/session`, and does
  so again whenever the Web UI goes to its sign-in page. EiN refusing the token, once the phone is
  unpaired, asks for a new pairing. Unpaired or refused, the app deletes all the WebView kept: its
  cookies, its cache and the sites' storage, the Web UI's service worker and the pages it kept for
  use without a connection among them.
- **Only https.** A release reaches EiN over https alone: its network security config allows no
  plain http, and an address or a QR code naming `http://` is refused with what to do. The phone's
  user-added certificate authorities are trusted, for a household's own. A debug build also reaches
  `http://`, for an emulator or an EiN on the computer it is built on (`src/debug`).
- **The page.** Pages of the paired EiN load in the WebView, any other link in the app that opens
  it, and Android's Back goes through the Web UI's history. A file input offers the phone's files
  and its camera, a download goes to Downloads, and what the page shows full screen is.
- **The bridge.** The paired EiN's pages, and no others, get `window.EinApp` (a web message
  listener). A call is the JSON `{ "id", "method", "params" }`, answered `{ "id", "result" }` or
  `{ "id", "error" }`: `info` answers `{ version, server, device, user }`, `unpair` has the
  app take its pairing away at `POST /__app/unpair` with its own token, then forget it, and
  `backup`, `backupSet`, `backupAllow` and `backupNow` read and change the photo backup, and
  `tracking`, `trackingSet`, `trackingAllow` and `trackingSend` the position reports, and
  `shared`, `sharedRead` and `sharedDone` hand on the shared files. The Web UI's App page (`/app`)
  is built on it. A geolocation request from the paired EiN's pages is answered once the app may
  know the phone's position.
- **Sharing to EiN** (`SharedFiles.kt`). EiN is in the phone's Share menu for pictures, videos and
  recordings. The app keeps what is shared to it and opens the Web UI's `/share` page, which lists
  the files (`shared`), reads each in 3 MB pieces in base64 (`sharedRead`), has the app forget them
  (`sharedDone`) and opens its Import media dialog with them, as for what Chrome shares to the web
  app. Shared before the app is paired, they wait until it is.
- **The photo backup** (`backup/`). WorkManager runs it when MediaStore's photos or videos change,
  every hour, and when asked, on Wi-Fi only or while charging only if so set. It reads the chosen
  folders (`DCIM/Camera/` at first), leaves out what its record (a SQLite table, by MediaStore id
  and version) says was sent, reads the rest through for their sha256, asks EiN which it has
  (`POST /checksums`), and sends the others in 8 MB pieces with tus at `/upload/`, into the folder
  the App page chose, under their own names and times. All of it is signed in with the token. With
  `ACCESS_MEDIA_LOCATION` it reads each photo as written (`MediaStore.setRequireOriginal`), where it
  was taken included. While it sends, it runs in a foreground service with a notification.
- **The position reports** (`track/`). While they are on, a foreground service of the `location`
  type takes the phone's position from Google's fused provider as it moves (balanced, or precise
  when so set) and pauses while activity recognition says it lies still. Positions less sure than
  100 m are left out; the rest wait in a SQLite queue and go to the phone's track,
  `POST /file/dev/<phone>/position`, as JSON lists of EiN's own fixes with the battery and what the
  phone is doing, sent by a WorkManager job once there is a connection. The position all the time
  is asked for on its own, after a disclosure of the app's; with it the reports go on after the
  phone restarts.

## Building

It needs a JDK, 17 or newer, and the Android SDK with its licences accepted (`ANDROID_HOME`); the
build fetches the platform and build tools it names.

```bash
./gradlew lint testDebugUnitTest assembleDebug   # what the checks run, with bundleRelease
./gradlew installDebug                          # onto a phone or emulator adb reaches
```

A debug build lets Chrome's `chrome://inspect` look into the WebView.

## Releasing

`version.json` holds the version; the app's `versionName` is it and its `versionCode`
`major × 1 000 000 + minor × 1 000 + patch`, which only rises, as Google Play wants. A release is a
`v<version>` tag. On GitHub the tag builds the App Bundle for Google Play and an APK, signed with
the upload key from the repository's secrets, and attaches them to the tag's release
(`.github/workflows/release.yml`). Google Play signs what it hands out with a key of its own.

Locally, a release build is signed when `EIN_UPLOAD_KEYSTORE` names the keystore and
`EIN_UPLOAD_KEYSTORE_PASSWORD` is its password, and is left unsigned otherwise.

## The store listing

What Google Play shows of the app is in `fastlane/metadata/android/en-GB/`, in the layout fastlane's
`supply` reads: the title, the short and the full description (`title.txt`,
`short_description.txt`, `full_description.txt`, plain text with `•` for bullets), and under
`images/` the 512 × 512 icon, the 1024 × 500 feature graphic and the phone screenshots
(`phoneScreenshots/1.png` on, 1080 × 1920, in the order Google Play shows them). The Play Console's
store listing is filled from it, so a change to the listing is made here first. The screenshots show
EiN's demo archive, the invented Wrenfield family's, as the screenshots on einhq.eu do, and never a
real household's. Each shows these parts of it:

1. The start page's news: the albums "Christmas in Wellington" and the family's weddings.
2. A photo of Ruby Wrenfield's from "Christmas in Wellington", with its people and place.
3. The map of "Erik's archipelago" and "Sailing in the archipelago", in the Stockholm archipelago:
   Erik's photos there are photos from Wikimedia Commons under CC0, and the tracks between them are
   generated.
4. Helen Okafor's page, her albums on a timeline.
