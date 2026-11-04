using System.Runtime.InteropServices;
using FFmpeg.AutoGen;
using NAudio.Wave;

namespace AndroidTabletCast;

public sealed unsafe class AudioOutput : IDisposable
{
    private AVCodecContext* context;
    private AVFrame* frame;
    private AVPacket* packet;
    private BufferedWaveProvider? buffer;
    private WaveOutEvent? output;
    private bool open;

    public bool Muted { get; set; }

    public void Open(int sampleRate, int channels, byte[] specificConfig)
    {
        VideoDecoder.EnsureNative();
        Close();
        var codec = ffmpeg.avcodec_find_decoder(AVCodecID.AV_CODEC_ID_AAC);
        if (codec == null)
        {
            return;
        }

        context = ffmpeg.avcodec_alloc_context3(codec);
        context->sample_rate = sampleRate;
        ffmpeg.av_channel_layout_default(&context->ch_layout, channels);
        if (specificConfig.Length > 0)
        {
            context->extradata_size = specificConfig.Length;
            context->extradata = (byte*)ffmpeg.av_malloc((ulong)(specificConfig.Length + ffmpeg.AV_INPUT_BUFFER_PADDING_SIZE));
            Marshal.Copy(specificConfig, 0, (IntPtr)context->extradata, specificConfig.Length);
        }

        Check(ffmpeg.avcodec_open2(context, codec, null));
        frame = ffmpeg.av_frame_alloc();
        packet = ffmpeg.av_packet_alloc();
        buffer = new BufferedWaveProvider(new WaveFormat(sampleRate, 16, channels))
        {
            DiscardOnBufferOverflow = true,
            BufferDuration = TimeSpan.FromMilliseconds(80),
        };
        output = new WaveOutEvent { DesiredLatency = 50 };
        output.Init(buffer);
        output.Play();
        open = true;
    }

    public void Write(byte[] aac)
    {
        if (!open || Muted)
        {
            return;
        }

        ffmpeg.av_packet_unref(packet);
        Check(ffmpeg.av_new_packet(packet, aac.Length));
        Marshal.Copy(aac, 0, (IntPtr)packet->data, aac.Length);
        if (ffmpeg.avcodec_send_packet(context, packet) < 0)
        {
            return;
        }

        while (ffmpeg.avcodec_receive_frame(context, frame) == 0)
        {
            var pcm = ToPcm();
            if (pcm != null && buffer != null)
            {
                buffer.AddSamples(pcm, 0, pcm.Length);
            }
        }
    }

    public void Dispose() => Close();

    private byte[]? ToPcm()
    {
        var samples = frame->nb_samples;
        var channels = frame->ch_layout.nb_channels;
        if (samples <= 0 || channels <= 0)
        {
            return null;
        }

        var format = (AVSampleFormat)frame->format;
        var pcm = new byte[samples * channels * 2];
        if (format == AVSampleFormat.AV_SAMPLE_FMT_FLTP)
        {
            for (var channel = 0; channel < channels; channel++)
            {
                var plane = (float*)frame->data[(uint)channel];
                for (var index = 0; index < samples; index++)
                {
                    var value = (int)Math.Clamp(plane[index] * 32767f, short.MinValue, short.MaxValue);
                    var offset = (index * channels + channel) * 2;
                    pcm[offset] = (byte)value;
                    pcm[offset + 1] = (byte)(value >> 8);
                }
            }

            return pcm;
        }

        if (format == AVSampleFormat.AV_SAMPLE_FMT_S16)
        {
            Marshal.Copy((IntPtr)frame->data[0], pcm, 0, Math.Min(pcm.Length, frame->linesize[0]));
            return pcm;
        }

        return null;
    }

    private static void Check(int code)
    {
        if (code < 0)
        {
            throw new InvalidOperationException($"FFmpeg エラー {code}");
        }
    }

    private void Close()
    {
        open = false;
        output?.Stop();
        output?.Dispose();
        output = null;
        buffer = null;
        if (packet != null)
        {
            var current = packet;
            ffmpeg.av_packet_free(&current);
            packet = null;
        }

        if (frame != null)
        {
            var current = frame;
            ffmpeg.av_frame_free(&current);
            frame = null;
        }

        if (context != null)
        {
            var current = context;
            ffmpeg.avcodec_free_context(&current);
            context = null;
        }
    }
}
