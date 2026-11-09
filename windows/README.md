# BlueVoice: Windows 11 + two Bluetooth audio inputs (experimental)

## Intended wiring

- Android receiver (music box controller): enable **Receive phone + Windows audio (2 sources)** in BlueVoice. If your speaker has an AUX input, connect this Android device to the speaker using a wired headphone output or USB-C audio adapter.
- Phone (source 1): install BlueVoice, connect to the Android receiver through BlueVoice, and choose **App Audio Relay**. Grant Android's screen/audio capture permission. **Start transmitting** to send permitted app playback.
- Windows 11 PC (source 2): pair the PC with the Android receiver, identify the **outgoing Bluetooth Serial (SPP) COM port**, launch BlueVoice.Windows, choose that COM port and click **Connect + stream**.

The Android receiver accepts two simultaneous **RFCOMM/SPP** application streams and plays them through independent AudioTrack outputs. Android's system audio mixer combines the output. The receiver shows the two connected device names separately.

**Important:** This does *not* make an ordinary Bluetooth A2DP speaker accept and mix two audio streams directly. For standard Bluetooth speakers, use the Android receiver as an audio bridge, ideally with the speaker connected by AUX/USB audio. Multiple Bluetooth endpoints sharing a radio can reduce throughput and degrade quality.

## Windows installation

1. Open the GitHub Actions workflow **Build Windows 11 BlueVoice**.
2. Download the `BlueVoice-Windows11-x64` artifact from a successful build.
3. Unzip it and run `BlueVoice.Windows.exe` (Windows 11 x64). The published build is self-contained.
4. Pair your Windows PC and Android receiver via Windows **Settings > Bluetooth & devices**.
5. In the legacy Bluetooth settings, check **COM Ports** for an outgoing serial port associated with the receiver's RFCOMM service. Use that port in the app. If no outgoing SPP port is available, the current Windows transport cannot connect; some Windows Bluetooth adapters/drivers do not expose it.

The app captures your current default Windows output audio via WASAPI loopback (other apps remain audible on the PC). It converts this to 16 kHz mono by default, or 44.1 kHz mono for higher fidelity. It sends 20-ms PCM packets using BlueVoice's protocol. If streaming is choppy, select 16 kHz.

## Limitations

- Requires two Android-capable endpoints (phone and receiver) plus Windows 11. Receiver audio transport is Bluetooth Classic SPP, not Bluetooth A2DP or LE Audio broadcast.
- The existing app's foreground service helps preserve processing priority, but on-device testing is still needed and it does not yet persist the receiver across OS process death.
- Windows playback capture needs a running WASAPI render session and an audio format supported by the capture pipeline (float32 or signed PCM16).
- Audio-mixing timing is managed by independent bounded playback tracks; there is no clock synchronization, echo cancellation or automatic volume ducking.
- Some Android applications prevent playback capture for DRM/policy reasons; no workaround is implemented.
- Both desktop and Android builds must pass CI before being considered installable, and two physical Bluetooth links must be tested.

## Protocol

Each RFCOMM frame starts with `0x5A`, then one type byte, a **big-endian 16-bit payload length**, and the payload. Control packets: type 2 = PTT start, type 3 = PTT end, type 7 = 4-byte big-endian sample rate. Type 1 carries signed 16-bit little-endian mono PCM; Android clients may instead send type 6 IMA ADPCM. Each connection's decoder, sample rate, queue and playback track are independent.
