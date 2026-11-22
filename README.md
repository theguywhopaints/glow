# Glow

Glow is a Fire TV app that syncs a TP-Link Tapo colour bulb to what is on the screen, or to the music that is playing. It runs on the TV stick and talks to the bulb over the home network, so it needs no computer, capture box or cloud service once set up.

Tested on Fire OS 7 (Android 9) with a Tapo L530E bulb.

## Features

- **Screen sync:** follows the dominant colour on screen, ignoring black bars and giving the edges more weight. Scene cuts land at once.
- **Music sync:** changes colour on bass beats.
- **Two styles:** Vivid (strong and quick) and Cinema (soft and slow).
- **Room calibration:** match the bulb to 11 reference colours with the remote.
- **Scenes:** trigger lighting, and optionally open an app, from Siri Shortcuts or anything else that can send an HTTP request.
- **Restore:** puts the bulb back the way it was when syncing stops.

## How it works

1. `GlowService` captures the screen at 64×36, about 9 times a second.
2. `ColorPicker` picks the strongest group of hues instead of averaging, so red and blue don't turn into purple.
3. `Calibration` corrects that colour for the bulb and room.
4. `Tapo` sends the newest colour to the bulb over the local KLAP protocol, up to about 10 times a second.

`RemoteService` serves scenes on port 8765.

## Limitations

- Copy-protected video (Netflix, Prime Video) captures as black, so the light holds a soft warm glow.
- One bulb is supported.
- The bulb must have **Third-Party Compatibility** turned on in the Tapo app.
- The bulb accepts one controller at a time, so other local control tools will interrupt Glow.

## Build

You need JDK 17, the Android SDK (API 34) with `ANDROID_HOME` set, and Gradle 8.9. There is no Gradle wrapper. Release builds are signed with the local Android debug key, for sideloading only.

```sh
cd app
gradle test
gradle assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Set up

Turn on ADB debugging on the Fire TV and connect with `adb connect <tv-ip>`.

Create the login hash on your computer. Your email and password are not sent to the TV.

```sh
python3 -c 'import hashlib,getpass; h=lambda s: hashlib.sha1(s.encode()).digest(); print(hashlib.sha256(h(input("Tapo email: "))+h(getpass.getpass())).hexdigest())'
```

Treat the hash like a password, because it is enough to control the bulb. Then configure the app:

```sh
adb shell pm grant io.github.theguywhopaints.glow android.permission.RECORD_AUDIO
adb shell appops set io.github.theguywhopaints.glow PROJECT_MEDIA allow
adb shell am broadcast -n io.github.theguywhopaints.glow/.SetupReceiver -a io.github.theguywhopaints.glow.SETUP \
  --es ip <bulb-ip> --es auth <login-hash>
```

Open Glow on the TV and choose **Screen sync** or **Music sync**. Turn ADB debugging off when you have finished.

## Scenes (optional)

Generate a key with `openssl rand -hex 24`, then store it on the TV:

```sh
adb shell am broadcast -n io.github.theguywhopaints.glow/.SetupReceiver -a io.github.theguywhopaints.glow.SETUP --es token <key>
```

`movie` (red light), `party` (music sync) and `off` (restore) are built in. To run one:

```sh
curl -H "X-Glow-Token: <key>" http://<tv-ip>:8765/scene/movie
```

To define or replace a scene:

```sh
adb shell am broadcast -n io.github.theguywhopaints.glow/.SetupReceiver -a io.github.theguywhopaints.glow.SETUP \
  --es scene movie --es light color --ei hue 0 --ei sat 100 --ei bri 50 --es app <package>
```

`light` can be `color`, `sync`, `music`, `off` or `none`.

The server uses plain HTTP and only answers private-network addresses. Don't forward its port. The key also works as `?key=<key>`, so don't share those links or QR codes made from them.

## Bulb test scripts

`test_bulb.py` (speed test) and `watch_bulb.py` (prints the bulb's state) need `python-kasa` 0.7.7 and a git-ignored `.tapo` file with the email on line 1 and the password on line 2. Run them with the bulb's address, for example `BULB_IP=<bulb-ip> python test_bulb.py`.
