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
    private readonly int preferredGpu;
    private readonly AVCodecContext_get_format getFormatCallback;
    private AVPixelFormat hardwarePixelFormat = AVPixelFormat.AV_PIX_FMT_NONE;
    private bool hardwareSelected;
    private bool hardwareRejected;
    private bool softwareFallback;
    private bool gaveUp;
    private int[] gpuPlan = Array.Empty<int>();
    private int gpuSlot;
    private string activeGpuName = "規定のGPU";
    private int pendingWidth;
    private int pendingHeight;

    public VideoDecoder(VideoDecodeMode decodeMode = VideoDecodeMode.Automatic, int preferredGpu = -1)
    {
        this.decodeMode = decodeMode;
        this.preferredGpu = preferredGpu;
        getFormatCallback = SelectPixelFormat;
    }

    public bool HardwareAccelerated => hardwareSelected && !softwareFallback;

    public string Describe()
    {
        if (softwareFallback && gaveUp)
        {
            return $"ソフトウェア（{activeGpuName} はこのコーデックをGPUで解けません）";
        }

        if (softwareFallback || decodeMode == VideoDecodeMode.Software)
        {
            return "ソフトウェア";
        }

        var mode = decodeMode switch
        {
            VideoDecodeMode.Dxva2 => "DXVA2",
            VideoDecodeMode.D3D11Va => "D3D11VA",
            _ => "ハードウェア",
        };
        return hardwareSelected ? $"{mode} / {activeGpuName}" : $"{mode} / {activeGpuName} を準備中";
    }

    public static string? ProbeNative()
    {
        var dll = Path.Combine(FfmpegDirectory, "avcodec-61.dll");
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
            ffmpeg.RootPath = FfmpegDirectory;
            ffmpeg.av_log_set_level(ffmpeg.AV_LOG_ERROR);
        }
        catch
        {
            Interlocked.Exchange(ref nativeReady, 0);
            throw;
        }
    }

    private static string FfmpegDirectory => Path.Combine(AppContext.BaseDirectory, "ffmpeg");

    public void Open(int codec, byte[] annexB, int width, int height)
    {
        EnsureNative();
        Close();
        codecId = codec;
        pendingWidth = width;
        pendingHeight = height;
        parameterSets = annexB;
        softwareFallback = decodeMode == VideoDecodeMode.Software;
        gaveUp = false;
        hardwareRejected = false;
        gpuPlan = softwareFallback ? Array.Empty<int>() : BuildGpuPlan();
        gpuSlot = 0;
        if (softwareFallback)
        {
            if (!OpenContext(-1))
            {
                throw new InvalidOperationException("映像デコーダを初期化できません");
            }

            return;
        }

        while (gpuSlot < gpuPlan.Length)
        {
            // パラメータセットだけで GPU が拒否したときは、次のアダプタで作り直す。
            if (OpenContext(gpuPlan[gpuSlot]) && !hardwareRejected)
            {
                return;
            }

            gpuSlot++;
        }

        var failedName = activeGpuName;
        softwareFallback = true;
        gaveUp = true;
        if (!OpenContext(-1))
        {
            throw new InvalidOperationException("映像デコーダを初期化できません");
        }

        activeGpuName = failedName;
    }

    public byte[]? Decode(
        byte[] annexB,
        bool keyframe,
        bool present,
        byte[]? reuse,
        out int width,
        out int height,
        out bool consumed)
    {
        width = 0;
        height = 0;
        consumed = false;
        if (!open || annexB.Length == 0)
        {
            return null;
        }

        var payload = keyframe ? WithParameterSets(annexB) : annexB;
        var sent = SendPacket(payload, keyframe);
        if (hardwareRejected && !softwareFallback)
        {
            // この GPU がコーデックを拒否した。明示指定でなければ次の GPU を開く。
            if (MoveToNextGpu())
            {
                if (!keyframe)
                {
                    return null;
                }

                sent = SendPacket(payload, true);
            }
            else
            {
                var failedName = activeGpuName;
                softwareFallback = true;
                gaveUp = true;
                if (!OpenContext(-1))
                {
                    return null;
                }

                activeGpuName = failedName;
                if (!keyframe)
                {
                    return null;
                }

                sent = SendPacket(payload, true);
            }
        }

        if (sent < 0)
        {
            if (open && context != null)
            {
                ffmpeg.avcodec_flush_buffers(context);
            }

            return null;
        }

        var received = ffmpeg.avcodec_receive_frame(context, frame);
        if (received < 0)
        {
            return null;
        }

        consumed = true;
        if (!present)
        {
            // 画面へ出す前のコマが残っているときは、GPU から BGRA へのコピーをしない。
            ffmpeg.av_frame_unref(frame);
            return null;
        }

        var bgra = ToBgra(reuse, out width, out height);
        ffmpeg.av_frame_unref(frame);
        return bgra;
    }

    private int[] BuildGpuPlan()
    {
        if (preferredGpu >= 0)
        {
            return new[] { preferredGpu };
        }

        var plan = new List<int> { -1 };
        foreach (var adapter in DxgiAdapters.List())
        {
            if (adapter.Name.Contains("Basic Render", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            plan.Add(adapter.Index);
        }

        return plan.ToArray();
    }

    private bool MoveToNextGpu()
    {
        if (preferredGpu >= 0)
        {
            return false;
        }

        gpuSlot++;
        while (gpuSlot < gpuPlan.Length)
        {
            if (OpenContext(gpuPlan[gpuSlot]) && !hardwareRejected)
            {
                return true;
            }

            gpuSlot++;
        }

        return false;
    }

    private bool OpenContext(int gpuIndex)
    {
        ReleaseNative();
        hardwareRejected = false;
        hardwareSelected = false;
        activeGpuName = gpuIndex < 0 ? "規定のGPU" : DxgiAdapters.NameOf(gpuIndex);
        var codecIdFfmpeg = codecId == ProtocolLimits.CodecHevc
            ? AVCodecID.AV_CODEC_ID_HEVC
            : AVCodecID.AV_CODEC_ID_H264;
        var decoder = ffmpeg.avcodec_find_decoder(codecIdFfmpeg);
        if (decoder == null)
        {
            return false;
        }

        context = ffmpeg.avcodec_alloc_context3(decoder);
        if (context == null)
        {
            return false;
        }

        context->flags |= ffmpeg.AV_CODEC_FLAG_LOW_DELAY;
        context->thread_count = softwareFallback ? 0 : 1;
        context->width = pendingWidth;
        context->height = pendingHeight;
        if (!softwareFallback && !ConfigureHardware(context, gpuIndex))
        {
            ReleaseNative();
            return false;
        }

        // annex-B を extradata に入れると FFmpeg が同期できず、画面が真っ黒になる。
        if (ffmpeg.avcodec_open2(context, decoder, null) < 0)
        {
            ReleaseNative();
            return false;
        }

        frame = ffmpeg.av_frame_alloc();
        transferFrame = ffmpeg.av_frame_alloc();
        packet = ffmpeg.av_packet_alloc();
        open = true;
        if (!softwareFallback && parameterSets.Length > 0)
        {
            // ここで GPU がフォーマットを拒否したら、呼び出し側が次のアダプタへ進む。
            SendPacket(parameterSets, false);
            if (hardwareRejected)
            {
                return false;
            }
        }

        return true;
    }

    private int SendPacket(byte[] payload, bool keyframe)
    {
        if (!open || context == null || packet == null || payload.Length == 0)
        {
            return -1;
        }

        ffmpeg.av_packet_unref(packet);
        if (ffmpeg.av_new_packet(packet, payload.Length) < 0)
        {
            return -1;
        }

        Marshal.Copy(payload, 0, (IntPtr)packet->data, payload.Length);
        if (keyframe)
        {
            packet->flags |= ffmpeg.AV_PKT_FLAG_KEY;
        }

        return ffmpeg.avcodec_send_packet(context, packet);
    }

    private bool ConfigureHardware(AVCodecContext* codecContext, int gpuIndex)
    {
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

        // FFmpeg はアダプタ番号の文字列で GPU を選ぶ。-1 のときはドライバの規定 GPU。
        var deviceName = gpuIndex >= 0 ? gpuIndex.ToString() : null;
        foreach (var device in devices)
        {
            AVBufferRef* hardwareDevice = null;
            var result = ffmpeg.av_hwdevice_ctx_create(
                &hardwareDevice,
                device.Item1,
                deviceName,
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
        AVPixelFormat? compatible = null;
        for (var current = formats; *current != AVPixelFormat.AV_PIX_FMT_NONE; current++)
        {
            if (*current == hardwarePixelFormat)
            {
                hardwareSelected = true;
                hardwareRejected = false;
                return *current;
            }

            if (hardwarePixelFormat == AVPixelFormat.AV_PIX_FMT_D3D11 &&
                *current == AVPixelFormat.AV_PIX_FMT_D3D11VA_VLD)
            {
                compatible = *current;
            }
        }

        if (compatible != null)
        {
            hardwarePixelFormat = compatible.Value;
            hardwareSelected = true;
            hardwareRejected = false;
            return compatible.Value;
        }

        // NONE を返すとデコード全体が失敗する。拒否だけ記録し、次の GPU かソフトウェアへ渡す。
        hardwareRejected = true;
        hardwareSelected = false;
        return AVPixelFormat.AV_PIX_FMT_NONE;
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

    private byte[] ToBgra(byte[]? reuse, out int width, out int height)
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

        var bytes = width * height * 4;
        var bgra = reuse != null && reuse.Length == bytes ? reuse : new byte[bytes];
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
        parameterSets = Array.Empty<byte>();
        ReleaseNative();
    }

    private void ReleaseNative()
    {
        open = false;
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
