using System.IO;
using System.Runtime.InteropServices;
using FFmpeg.AutoGen;

namespace AndroidTabletCast;

public enum VideoDecodeMode
{
    Automatic,
    Software,
    D3D11Va,
    Dxva2,
}

public sealed unsafe class VideoDecoder : IDisposable
{
    private AVCodecContext* context;
    private AVFrame* frame;
    private AVPacket* packet;
    private AVFrame* transferFrame;
    private SwsContext* scaler;
    private int scaledWidth;
    private int scaledHeight;
    private AVPixelFormat scaledFormat;
    private bool open;
    private byte[] parameterSets = Array.Empty<byte>();
    private int codecId;
    private static int nativeReady;
    private readonly VideoDecodeMode decodeMode;
    private readonly AVCodecContext_get_format getFormatCallback;
    private AVPixelFormat hardwarePixelFormat = AVPixelFormat.AV_PIX_FMT_NONE;
    private bool hardwareSelected;

    public VideoDecoder(VideoDecodeMode decodeMode = VideoDecodeMode.Automatic)
    {
        this.decodeMode = decodeMode;
        getFormatCallback = SelectPixelFormat;
    }

    public bool HardwareAccelerated => hardwareSelected;

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
        context->thread_count = decodeMode is VideoDecodeMode.D3D11Va or VideoDecodeMode.Dxva2 ? 1 : 0;
        context->width = width;
        context->height = height;
        if (!ConfigureHardware(context) && decodeMode is VideoDecodeMode.D3D11Va or VideoDecodeMode.Dxva2)
        {
            Close();
            throw new InvalidOperationException($"{decodeMode} ハードウェアデコードを初期化できません。自動またはソフトウェアを選んでください。");
        }
        // annex-B を extradata に入れると FFmpeg が同期できず、画面が真っ黒になる。
        Check(ffmpeg.avcodec_open2(context, decoder, null));
        frame = ffmpeg.av_frame_alloc();
        transferFrame = ffmpeg.av_frame_alloc();
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
            if ((decodeMode is VideoDecodeMode.D3D11Va or VideoDecodeMode.Dxva2) && !hardwareSelected)
            {
                throw new InvalidOperationException($"{decodeMode} はこの映像コーデックまたはGPUで利用できません。");
            }
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

    private bool ConfigureHardware(AVCodecContext* codecContext)
    {
        if (decodeMode == VideoDecodeMode.Software)
        {
            return false;
        }

        var devices = decodeMode switch
        {
            VideoDecodeMode.D3D11Va => new[] { (AVHWDeviceType.AV_HWDEVICE_TYPE_D3D11VA, AVPixelFormat.AV_PIX_FMT_D3D11) },
            VideoDecodeMode.Dxva2 => new[] { (AVHWDeviceType.AV_HWDEVICE_TYPE_DXVA2, AVPixelFormat.AV_PIX_FMT_DXVA2_VLD) },
            _ => new[]
            {
                (AVHWDeviceType.AV_HWDEVICE_TYPE_D3D11VA, AVPixelFormat.AV_PIX_FMT_D3D11),
                (AVHWDeviceType.AV_HWDEVICE_TYPE_DXVA2, AVPixelFormat.AV_PIX_FMT_DXVA2_VLD),
            },
        };

        foreach (var device in devices)
        {
            AVBufferRef* hardwareDevice = null;
            var result = ffmpeg.av_hwdevice_ctx_create(
                &hardwareDevice,
                device.Item1,
                null,
                null,
                0);
            if (result < 0 || hardwareDevice == null)
            {
                continue;
            }

            codecContext->hw_device_ctx = hardwareDevice;
            codecContext->get_format = getFormatCallback;
            hardwarePixelFormat = device.Item2;
            return true;
        }

        return false;
    }

    private AVPixelFormat SelectPixelFormat(AVCodecContext* _, AVPixelFormat* formats)
    {
        for (var current = formats; *current != AVPixelFormat.AV_PIX_FMT_NONE; current++)
        {
            if (*current == hardwarePixelFormat)
            {
                hardwareSelected = true;
                return *current;
            }
        }

        if (decodeMode is VideoDecodeMode.D3D11Va or VideoDecodeMode.Dxva2)
        {
            return AVPixelFormat.AV_PIX_FMT_NONE;
        }

        return formats[0];
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
        AVFrame* source = frame;
        if (hardwareSelected && frame->format == (int)hardwarePixelFormat)
        {
            ffmpeg.av_frame_unref(transferFrame);
            Check(ffmpeg.av_hwframe_transfer_data(transferFrame, frame, 0));
            source = transferFrame;
        }

        width = source->width;
        height = source->height;
        var format = (AVPixelFormat)source->format;
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
            ffmpeg.sws_scale(scaler, source->data, source->linesize, 0, height, destData, destStride);
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
        hardwareSelected = false;
        hardwarePixelFormat = AVPixelFormat.AV_PIX_FMT_NONE;
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

        if (transferFrame != null)
        {
            var current = transferFrame;
            ffmpeg.av_frame_free(&current);
            transferFrame = null;
        }

        if (context != null)
        {
            var current = context;
            ffmpeg.avcodec_free_context(&current);
            context = null;
        }
    }
}
