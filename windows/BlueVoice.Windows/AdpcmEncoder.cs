namespace BlueVoice.Windows;

/// <summary>
/// IMA ADPCM encoder compatible with the Android BlueVoice TYPE_AUDIO_ADPCM
/// decoder. Every 20 ms packet has a self-contained predictor/index header.
/// Persistent state avoids a click at each packet boundary.
/// </summary>
internal sealed class AdpcmEncoder
{
    private static readonly int[] IndexTable = {
        -1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8
    };

    private static readonly int[] StepTable = {
        7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,
        50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,
        230,253,279,307,337,371,408,449,494,544,598,658,724,796,
        876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,
        2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,
        8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,
        22385,24623,27086,29794,32767
    };

    private int predictor;
    private int index;
    private bool initialized;

    public byte[] Encode(byte[] pcm)
    {
        int samples = pcm.Length / 2;
        if (samples == 0) return Array.Empty<byte>();
        int first = (short)(pcm[0] | pcm[1] << 8);
        if (!initialized) {
            predictor = first;
            index = 0;
            initialized = true;
        }
        var output = new byte[4 + (samples + 1) / 2];
        output[0] = (byte)predictor;
        output[1] = (byte)(predictor >> 8);
        output[2] = (byte)index;
        // output[3] stays zero (reserved)
        for (int i = 0; i < samples; i++) {
            int sample = (short)(pcm[i * 2] | pcm[i * 2 + 1] << 8);
            int step = StepTable[index];
            int diff = sample - predictor;
            int code = 0;
            if (diff < 0) { code = 8; diff = -diff; }
            int change = step >> 3;
            if (diff >= step) {
                code |= 4;
                diff -= step;
                change += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 2;
                diff -= step;
                change += step;
            }
            step >>= 1;
            if (diff >= step) {
                code |= 1;
                change += step;
            }
            predictor = Math.Clamp(predictor + ((code & 8) != 0 ? -change : change), -32768, 32767);
            index = Math.Clamp(index + IndexTable[code], 0, 88);
            if ((i & 1) == 0) output[4 + i / 2] = (byte)(code & 15);
            else output[4 + i / 2] |= (byte)(code << 4);
        }
        return output;
    }
}
