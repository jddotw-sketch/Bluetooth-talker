using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace BlueVoice.Windows;

/// <summary>
/// NAudio 2.x WasapiLoopbackCapture uses a fixed 100ms buffer.
/// The supported WasapiCapture overload exposes a shorter polling buffer
/// while retaining the same shared-mode system-audio loopback behavior.
/// </summary>
internal sealed class ConfigurableLoopbackCapture : WasapiCapture
{
    public ConfigurableLoopbackCapture(bool lowLatency)
        : base(
            WasapiLoopbackCapture.GetDefaultLoopbackCaptureDevice(),
            useEventSync: false,
            audioBufferMillisecondsLength: WindowsLatencyProfile.WasapiBufferMilliseconds(lowLatency))
    {
    }

    protected override AudioClientStreamFlags GetAudioClientStreamFlags() =>
        AudioClientStreamFlags.Loopback | base.GetAudioClientStreamFlags();
}
