using System.IO;
using System.Runtime.InteropServices;
using FFmpeg.AutoGen;

namespace AndroidTabletCast;

public sealed unsafe class VideoDecoder : IDisposable
{
    private AVCodecContext* context;
    private AVFrame* frame;
    private AVPacket* packet;
    private SwsContext* scaler;
    private int scaledWidth;
    private int scaledHeight;
    private AVPixelFormat scaledFormat;
    private bool open;
    private byte[] parameterSets = Array.Empty<byte>();
    private int codecId;
    private static int nativeReady;

    public static string? ProbeNative()
    {
        var dll = Path.Combine(AppContext.BaseDirectory, "avcodec-61.dll");
        if (!File.Exists(dll))
        {
            return "FFmpeg の DLL がありません。windows/tools/fetch-ffmpeg.ps1 を実行してください。";
        }

        return null;
    }

    public static void EnsureNative()
    {
        if (Interlocked.Exchange(ref nativeReady, 1) == 1)
        {
            return;
        }

        try
        {
            ffmpeg.RootPath = AppContext.BaseDirectory;
            ffmpeg.av_log_set_level(ffmpeg.AV_LOG_ERROR);
        }
        catch
        {
            Interlocked.Exchange(ref nativeReady, 0);
            throw;
        }
    }

    public void Open(int codec, byte[] annexB, int width, int height)
    {
        EnsureNative();
        Close();
        codecId = codec;
        var codecIdFfmpeg = codec == ProtocolLimits.CodecHevc
            ? AVCodecID.AV_CODEC_ID_HEVC
            : AVCodecID.AV_CODEC_ID_H264;
        var decoder = ffmpeg.avcodec_find_decoder(codecIdFfmpeg);
        if (decoder == null)
        {
            throw new InvalidOperationException("映像デコーダがありません");
        }

        context = ffmpeg.avcodec_alloc_context3(decoder);
        context->flags |= ffmpeg.AV_CODEC_FLAG_LOW_DELAY;
        context->thread_count = 1;
        context->width = width;
        context->height = height;
        // annex-B を extradata に入れると FFmpeg が同期できず、画面が真っ黒になる。
        Check(ffmpeg.avcodec_open2(context, decoder, null));
        frame = ffmpeg.av_frame_alloc();
        packet = ffmpeg.av_packet_alloc();
        open = true;
        parameterSets = annexB;
        if (annexB.Length > 0)
        {
            Decode(annexB, false, out _, out _);
        }
    }

    public byte[]? Decode(byte[] annexB, bool keyframe, out int width, out int height)
    {
        width = 0;
        height = 0;
        if (!open || annexB.Length == 0)
        {
            return null;
        }

        var payload = keyframe ? WithParameterSets(annexB) : annexB;
        ffmpeg.av_packet_unref(packet);
        Check(ffmpeg.av_new_packet(packet, payload.Length));
        Marshal.Copy(payload, 0, (IntPtr)packet->data, payload.Length);
        if (keyframe)
        {
            packet->flags |= ffmpeg.AV_PKT_FLAG_KEY;
        }

        var sent = ffmpeg.avcodec_send_packet(context, packet);
        if (sent < 0)
        {
            ffmpeg.avcodec_flush_buffers(context);
            return null;
        }

        var received = ffmpeg.avcodec_receive_frame(context, frame);
        if (received < 0)
        {
            return null;
        }

        return ToBgra(out width, out height);
    }

    private byte[] WithParameterSets(byte[] annexB)
    {
        if (parameterSets.Length == 0 || StartsWithParameterSet(annexB))
        {
            return annexB;
        }

        var merged = new byte[parameterSets.Length + annexB.Length];
        Buffer.BlockCopy(parameterSets, 0, merged, 0, parameterSets.Length);
        Buffer.BlockCopy(annexB, 0, merged, parameterSets.Length, annexB.Length);
        return merged;
    }

    private bool StartsWithParameterSet(byte[] data)
    {
        var index = 0;
        if (data.Length >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1)
        {
            index = 4;
        }
        else if (data.Length >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1)
        {
            index = 3;
        }
        else
        {
            return false;
        }

        if (index >= data.Length)
        {
            return false;
        }

        var header = data[index];
        if (codecId == ProtocolLimits.CodecHevc)
        {
            var hevc = (header >> 1) & 0x3F;
            return hevc is 32 or 33 or 34;
        }

        var nal = header & 0x1F;
        return nal is 7 or 8;
    }

    public void Dispose() => Close();

    private byte[] ToBgra(out int width, out int height)
    {
        width = frame->width;
        height = frame->height;
        var format = (AVPixelFormat)frame->format;
        if (scaler == null || scaledWidth != width || scaledHeight != height || scaledFormat != format)
        {
            if (scaler != null)
            {
                ffmpeg.sws_freeContext(scaler);
            }

            scaler = ffmpeg.sws_getContext(
                width,
                height,
                format,
                width,
                height,
                AVPixelFormat.AV_PIX_FMT_BGRA,
                ffmpeg.SWS_FAST_BILINEAR,
                null,
                null,
                null);
            scaledWidth = width;
            scaledHeight = height;
            scaledFormat = format;
        }

        var bgra = new byte[width * height * 4];
        fixed (byte* destination = bgra)
        {
            var destData = new byte_ptrArray8();
            var destStride = new int_array8();
            destData[0] = destination;
            destStride[0] = width * 4;
            ffmpeg.sws_scale(scaler, frame->data, frame->linesize, 0, height, destData, destStride);
        }

        return bgra;
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
        parameterSets = Array.Empty<byte>();
        if (scaler != null)
        {
            ffmpeg.sws_freeContext(scaler);
            scaler = null;
        }

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
