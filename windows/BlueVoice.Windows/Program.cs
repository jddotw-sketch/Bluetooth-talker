using System.IO.Ports;
using NAudio.CoreAudioApi;
using NAudio.Wave;
using System.Threading.Channels;

namespace BlueVoice.Windows;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainWindow());
    }
}

internal sealed class MainWindow : Form
{
    private readonly ComboBox ports = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 130 };
    private readonly ComboBox qualities = new() { DropDownStyle = ComboBoxStyle.DropDownList, Width = 180 };
    private readonly Button refresh = new() { Text = "Refresh ports", AutoSize = true };
    private readonly Button start = new() { Text = "Connect + stream", AutoSize = true };
    private readonly Button stop = new() { Text = "Stop", AutoSize = true, Enabled = false };
    private readonly Label status = new() { Text = "Pair Android receiver in Windows Bluetooth settings, then select its outgoing COM port.", AutoSize = true, MaximumSize = new Size(480, 0) };
    private LoopbackSender? sender;

    public MainWindow()
    {
        Text = "BlueVoice - Windows 11 audio sender";
        ClientSize = new Size(540, 235);
        MinimumSize = new Size(560, 280);
        MaximizeBox = false;
        FormBorderStyle = FormBorderStyle.FixedSingle;
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(16), ColumnCount = 1, RowCount = 5 };
        root.Controls.Add(new Label { Text = "Stream all computer audio to your Android music receiver", AutoSize = true, Font = new Font(Font.FontFamily, 12, FontStyle.Bold) }, 0, 0);

        var row1 = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Top };
        row1.Controls.Add(new Label { Text = "Outgoing Bluetooth COM port:", AutoSize = true, Margin = new Padding(0, 7, 6, 0) });
        row1.Controls.Add(ports);
        row1.Controls.Add(refresh);
        root.Controls.Add(row1, 0, 1);

        var row2 = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Top };
        row2.Controls.Add(new Label { Text = "Audio quality:", AutoSize = true, Margin = new Padding(0, 7, 6, 0) });
        row2.Controls.Add(qualities);
        row2.Controls.Add(start);
        row2.Controls.Add(stop);
        root.Controls.Add(row2, 0, 2);
        root.Controls.Add(status, 0, 3);
        root.Controls.Add(new Label {
            Text = "Requires a Bluetooth SPP serial connection to BlueVoice on Android. This is not standard A2DP pairing.",
            AutoSize = true, MaximumSize = new Size(490, 0),
            ForeColor = Color.DimGray
        }, 0, 4);
        Controls.Add(root);
        qualities.Items.Add("16 kHz mono - stable voice");
        qualities.Items.Add("44.1 kHz mono - higher quality");
        qualities.SelectedIndex = 0;
        refresh.Click += (_, _) => RefreshPorts();
        start.Click += (_, _) => BeginStreaming();
        stop.Click += (_, _) => StopStreaming();
        FormClosing += (_, _) => StopStreaming();
        RefreshPorts();
    }

    private void RefreshPorts()
    {
        var selected = ports.SelectedItem?.ToString();
        ports.Items.Clear();
        foreach (var port in SerialPort.GetPortNames().OrderBy(x => x)) ports.Items.Add(port);
        if (ports.Items.Count > 0) {
            ports.SelectedItem = selected is not null && ports.Items.Contains(selected) ? selected : ports.Items[0];
        }
    }

    private void BeginStreaming()
    {
        if (ports.SelectedItem is not string port) {
            status.Text = "No COM port found. Pair the Android receiver and check Windows Bluetooth COM Ports.";
            return;
        }
        try {
            var rate = qualities.SelectedIndex == 1 ? 44100 : 16000;
            sender = new LoopbackSender(port, rate);
            sender.Stopped += error => {
                if (IsHandleCreated) BeginInvoke((Action)(() => {
                    status.Text = error is null ? "Audio capture stopped." : $"Stream stopped: {error.Message}";
                    StopStreaming();
                }));
            };
            sender.Start();
            status.Text = $"Streaming ADPCM audio to Android on {port} at {rate} Hz. Other PC apps continue playing normally.";
            start.Enabled = false; stop.Enabled = true; ports.Enabled = false; qualities.Enabled = false;
        } catch (Exception ex) {
            sender?.Dispose(); sender = null;
            status.Text = $"Unable to connect: {ex.Message}";
        }
    }

    private void StopStreaming()
    {
        sender?.Dispose();
        sender = null;
        start.Enabled = true; stop.Enabled = false; ports.Enabled = true; qualities.Enabled = true;
    }
}

internal sealed class LoopbackSender : IDisposable
{
    private const byte Magic = 0x5A, AudioAdpcm = 0x06, StartPtt = 0x02, EndPtt = 0x03, Config = 0x07;
    private readonly SerialPort serial;
    private readonly AdpcmEncoder encoder = new();
    private readonly WasapiLoopbackCapture capture;
    private readonly Channel<byte[]> frames;
    private readonly CancellationTokenSource cancel = new();
    private readonly int targetRate;
    private Task? writer;
    private readonly object sampleLock = new();
    private readonly List<byte> pcm = new();
    private double inputPosition;
    private float previousSample;
    private bool havePrevious;
    private bool disposed;
    public event Action<Exception?>? Stopped;

    public LoopbackSender(string port, int sampleRate)
    {
        targetRate = sampleRate;
        serial = new SerialPort(port, 921600, Parity.None, 8, StopBits.One) {
            Handshake = Handshake.None,
            ReadTimeout = 1500,
            WriteTimeout = 1500
        };
        capture = new WasapiLoopbackCapture();
        frames = Channel.CreateBounded<byte[]>(new BoundedChannelOptions(20) {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = true
        });
        capture.DataAvailable += OnDataAvailable;
        capture.RecordingStopped += (_, e) => {
            if (!disposed) Stopped?.Invoke(e.Exception);
        };
    }

    public void Start()
    {
        serial.Open();
        WritePacket(Config, BitConverter.IsLittleEndian
            ? new byte[] { (byte)(targetRate >> 24), (byte)(targetRate >> 16), (byte)(targetRate >> 8), (byte)targetRate }
            : BitConverter.GetBytes(targetRate));
        WritePacket(StartPtt, Array.Empty<byte>());
        writer = Task.Run(async () => {
            try {
                await foreach (var frame in frames.Reader.ReadAllAsync(cancel.Token)) {
                    WritePacket(AudioAdpcm, encoder.Encode(frame));
                }
            } catch (OperationCanceledException) { }
              catch (Exception ex) {
                if (!disposed) Stopped?.Invoke(ex);
            }
        });
        try { capture.StartRecording(); }
        catch { Dispose(); throw; }
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs args)
    {
        if (disposed) return;
        try {
            var format = capture.WaveFormat;
            int bytesPerSample = format.BitsPerSample / 8;
            if (bytesPerSample <= 0 || format.Channels <= 0) return;
            bool float32 = bytesPerSample == 4 && (
                format.Encoding == WaveFormatEncoding.IeeeFloat ||
                (format is WaveFormatExtensible f && f.SubFormat ==
                    new Guid("00000003-0000-0010-8000-00aa00389b71")));
            bool pcm16 = bytesPerSample == 2 && (
                format.Encoding == WaveFormatEncoding.Pcm ||
                (format is WaveFormatExtensible p && p.SubFormat ==
                    new Guid("00000001-0000-0010-8000-00aa00389b71")));
            if (!float32 && !pcm16) {
                throw new NotSupportedException($"Unsupported Windows output format: {format}");
            }
            int frameBytes = bytesPerSample * format.Channels;
            int frameCount = args.BytesRecorded / frameBytes;
            var buffer = args.Buffer;
            lock (sampleLock) {
                // Resample the system mix to mono, maintaining fractional phase across callbacks.
                var ratio = (double)format.SampleRate / targetRate;
                for (int frame = 0; frame < frameCount; frame++) {
                    float current = 0f;
                    for (int channel = 0; channel < format.Channels; channel++) {
                        int pos = frame * frameBytes + channel * bytesPerSample;
                        float value;
                        value = float32
                            ? BitConverter.ToSingle(buffer, pos)
                            : BitConverter.ToInt16(buffer, pos) / 32768f;
                        current += value;
                    }
                    current /= format.Channels;
                    if (!havePrevious) { previousSample = current; havePrevious = true; }
                    // Virtual segment [frame-1, frame]; interpolate for fractional output positions.
                    while (inputPosition <= frame) {
                        var fraction = Math.Clamp(inputPosition - (frame - 1.0), 0.0, 1.0);
                        var sample = previousSample + (current - previousSample) * (float)fraction;
                        int signed = (int)Math.Round(Math.Clamp(sample, -1f, 1f) * 32767f);
                        pcm.Add((byte)signed);
                        pcm.Add((byte)(signed >> 8));
                        int packetBytes = targetRate * 2 / 50; // 20 ms
                        if (pcm.Count >= packetBytes) {
                            frames.Writer.TryWrite(pcm.GetRange(0, packetBytes).ToArray());
                            pcm.RemoveRange(0, packetBytes);
                        }
                        inputPosition += ratio;
                    }
                    previousSample = current;
                }
                inputPosition -= frameCount;
            }
        } catch (Exception ex) {
            if (!disposed) Stopped?.Invoke(ex);
        }
    }

    private void WritePacket(byte type, byte[] payload)
    {
        if (payload.Length > 32768) throw new InvalidOperationException("Packet too large");
        serial.Write(new[] { Magic, type, (byte)(payload.Length >> 8), (byte)payload.Length }, 0, 4);
        if (payload.Length != 0) serial.Write(payload, 0, payload.Length);
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        frames.Writer.TryComplete();
        cancel.Cancel();
        try { capture.StopRecording(); } catch { }
        try { if (serial.IsOpen) WritePacket(EndPtt, Array.Empty<byte>()); } catch { }
        try { serial.Close(); } catch { }
        capture.Dispose();
        serial.Dispose();
        cancel.Dispose();
    }
}
