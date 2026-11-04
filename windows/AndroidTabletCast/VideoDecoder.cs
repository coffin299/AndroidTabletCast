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

    public void Open(byte[] annexB, int width, int height)
    {
        EnsureNative();
        Close();
        var codec = ffmpeg.avcodec_find_decoder(AVCodecID.AV_CODEC_ID_H264);
        if (codec == null)
        {
            throw new InvalidOperationException("H.264 デコーダがありません");
        }

        context = ffmpeg.avcodec_alloc_context3(codec);
        context->flags |= ffmpeg.AV_CODEC_FLAG_LOW_DELAY;
        context->thread_count = 1;
        context->width = width;
        context->height = height;
        if (annexB.Length > 0)
        {
            context->extradata_size = annexB.Length;
            context->extradata = (byte*)ffmpeg.av_malloc((ulong)(annexB.Length + ffmpeg.AV_INPUT_BUFFER_PADDING_SIZE));
            Marshal.Copy(annexB, 0, (IntPtr)context->extradata, annexB.Length);
        }

        Check(ffmpeg.avcodec_open2(context, codec, null));
        frame = ffmpeg.av_frame_alloc();
        packet = ffmpeg.av_packet_alloc();
        open = true;
    }

    public byte[]? Decode(byte[] annexB, out int width, out int height)
    {
        width = 0;
        height = 0;
        if (!open)
        {
            return null;
        }

        ffmpeg.av_packet_unref(packet);
        Check(ffmpeg.av_new_packet(packet, annexB.Length));
        Marshal.Copy(annexB, 0, (IntPtr)packet->data, annexB.Length);
        var sent = ffmpeg.avcodec_send_packet(context, packet);
        if (sent < 0)
        {
            return null;
        }

        var received = ffmpeg.avcodec_receive_frame(context, frame);
        if (received < 0)
        {
            return null;
        }

        return ToBgra(out width, out height);
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
