namespace BlueVoice.Windows;

/// <summary>
/// The receiver already supports 10ms mono PCM16 packets at 16/48kHz.
/// 44.1kHz must retain 20ms frames: 10ms is 441 samples and the
/// current two-samples-per-byte IMA ADPCM decoder would add padding.
/// </summary>
internal static class WindowsLatencyProfile
{
    public static int PacketMilliseconds(int sampleRate, bool lowLatency) =>
        lowLatency && sampleRate != 44100 ? 10 : 20;

    // NAudio 2.x defaults to 100ms WASAPI capture; shorter capture buffers
    // are requested only in low-latency mode, without exclusive audio focus.
    public static int WasapiBufferMilliseconds(bool lowLatency) => lowLatency ? 25 : 100;

    // Bounded queue drops stale audio rather than allowing delay to grow
    // indefinitely under Bluetooth RFCOMM congestion.
    public static int QueueFrames(bool lowLatency) => lowLatency ? 6 : 20;

    public static int PacketBytes(int sampleRate, bool lowLatency) =>
        checked(sampleRate * 2 * PacketMilliseconds(sampleRate, lowLatency) / 1000);
}
