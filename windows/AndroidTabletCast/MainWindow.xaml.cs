using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace AndroidTabletCast;

public partial class MainWindow : Window
{
    private readonly LocalStore store = new();
    private readonly Discovery discovery = new();
    private readonly DispatcherTimer timer = new() { Interval = TimeSpan.FromSeconds(1) };
    private readonly object frameGate = new();
    private StreamClient? session;
    private VideoDecoder? decoder;
    private AudioOutput? audio;
    private WriteableBitmap? bitmap;
    private byte[]? latestFrame;
    private int latestWidth;
    private int latestHeight;
    private bool renderQueued;
    private bool dragging;
    private bool pinFallbackSent;
    private bool fullscreen;
    private bool refreshing;
    private long lastKeyframeRequest;
    private ObsWindow? obsWindow;
    private WindowStyle previousStyle;
    private WindowState previousState;

    public MainWindow()
    {
        InitializeComponent();
        var missing = VideoDecoder.ProbeNative();
        if (missing != null)
        {
            StatusText.Text = missing;
        }

        discovery.Changed += () => Dispatcher.BeginInvoke(RefreshDevices);
        discovery.Start();
        timer.Tick += (_, _) =>
        {
            RefreshDevices();
            if (session?.Authenticated == true)
            {
                session.Send(new PingPacket(DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
            }
        };
        timer.Start();
        Closed += (_, _) =>
        {
            timer.Stop();
            discovery.Dispose();
            CloseSession();
            CloseObs();
        };
    }

    private async void ConnectButton_Click(object sender, RoutedEventArgs e)
    {
        if (session != null && session.Hello != null && !session.Authenticated)
        {
            SendAuth(session, session.Hello);
            return;
        }

        var host = IpBox.Text.Trim();
        if (!int.TryParse(PortBox.Text.Trim(), out var port))
        {
            port = ProtocolLimits.TcpPort;
        }

        if (host.Length == 0)
        {
            Status("IP を入力するか、見つかったタブレットを選んでください");
            return;
        }

        var native = VideoDecoder.ProbeNative();
        if (native != null)
        {
            Status(native);
            return;
        }

        try
        {
            ConnectButton.IsEnabled = false;
            Status("接続しています");
            await OpenSessionAsync(host, port);
        }
        catch (Exception error)
        {
            Status(error.Message);
            CloseSession();
        }
        finally
        {
            ConnectButton.IsEnabled = true;
        }
    }

    private async Task OpenSessionAsync(string host, int port)
    {
        CloseSession();
        pinFallbackSent = false;
        var client = new StreamClient();
        decoder = new VideoDecoder();
        audio = new AudioOutput { Muted = MuteBox.IsChecked == true };
        client.PacketReceived += OnPacket;
        client.Closed += () => Dispatcher.BeginInvoke(() =>
        {
            if (session == client)
            {
                Status("切断しました");
                TrustButton.IsEnabled = false;
            }
        });
        session = client;
        await Task.Run(() => client.ConnectAsync(host, port, CancellationToken.None));
        var hello = client.Hello ?? throw new ProtocolException("HELLO がありません");
        SendAuth(client, hello);
    }

    private void SendAuth(StreamClient client, HelloPacket hello)
    {
        if (hello.TrustMode && store.TryGetToken(hello.TabletId, out var token))
        {
            // PIN 欄に前回の値が残っていても、登録済みなら信頼を先に使う。
            client.SendTrust(store.PcId, token);
            Status("信頼情報で認証しています");
            return;
        }

        var pin = PinBox.Text.Trim();
        if (pin.Length == 0)
        {
            Status(hello.TrustMode
                ? "この PC はまだ信頼されていません。表示中の PIN を入力してください"
                : "タブレットの信頼モードがオフです。表示中の PIN を入力してください");
            return;
        }

        client.SendPin(pin);
        Status("PIN を送信しました");
    }

    private void OnPacket(Packet packet)
    {
        switch (packet)
        {
            case AuthResultPacket result:
                Dispatcher.BeginInvoke(() => OnAuth(result));
                break;
            case VideoConfigPacket config:
                try
                {
                    decoder?.Open(config.Codec, config.AnnexB, config.Width, config.Height);
                }
                catch (Exception error)
                {
                    Dispatcher.BeginInvoke(() => Status(error.Message));
                }

                break;
            case VideoPacket video:
                byte[]? bgra = null;
                var width = 0;
                var height = 0;
                try
                {
                    bgra = decoder?.Decode(video.AnnexB, video.Keyframe, out width, out height);
                }
                catch (Exception)
                {
                    bgra = null;
                }

                if (bgra == null)
                {
                    RequestKeyframe();
                    return;
                }

                PublishFrame(bgra, width, height);
                break;
            case AudioConfigPacket audioConfig:
                try
                {
                    audio?.Open(audioConfig.SampleRate, audioConfig.Channels, audioConfig.SpecificConfig);
                }
                catch (Exception)
                {
                    // 音声が始まらなくても映像は続ける。
                }

                break;
            case AudioPacket audioPacket:
                try
                {
                    audio?.Write(audioPacket.Aac);
                }
                catch (Exception)
                {
                }

                break;
            case TrustGrantPacket grant:
                store.SaveTrust(grant.TabletId, grant.Token, "Tablet");
                Dispatcher.BeginInvoke(() => Status("この PC を信頼済みにしました"));
                break;
            case PongPacket pong:
                var rtt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - pong.ClientUnixMs;
                Dispatcher.BeginInvoke(() => LatencyText.Text = $"遅延 {Math.Max(0, rtt)} ms");
                break;
        }
    }

    private void OnAuth(AuthResultPacket result)
    {
        if (result.Code != ProtocolLimits.AuthOk && session?.Authenticated == true)
        {
            return;
        }

        if (result.Code == ProtocolLimits.AuthOk)
        {
            Status("接続中");
            TrustButton.IsEnabled = true;
            if (session?.Authenticated == true)
            {
                session.Send(new StreamConfigPacket(PresetBox.SelectedIndex));
            }

            return;
        }

        TrustButton.IsEnabled = false;
        Status(result.Code switch
        {
            ProtocolLimits.AuthBadPin => "PIN が違います",
            ProtocolLimits.AuthUntrusted => "未信頼です。PIN を送ってください",
            ProtocolLimits.AuthTrustModeOff => "タブレットの信頼モードがオフです",
            ProtocolLimits.AuthLocked => "試行が多すぎます。30 秒ほど待ってください",
            _ => "認証に失敗しました",
        });
        var pin = PinBox.Text.Trim();
        if (!pinFallbackSent && pin.Length > 0 &&
            result.Code is ProtocolLimits.AuthUntrusted or ProtocolLimits.AuthTrustModeOff)
        {
            pinFallbackSent = true;
            session?.SendPin(pin);
        }
    }

    private void PublishFrame(byte[] bgra, int width, int height)
    {
        var schedule = false;
        lock (frameGate)
        {
            latestFrame = bgra;
            latestWidth = width;
            latestHeight = height;
            if (!renderQueued)
            {
                renderQueued = true;
                schedule = true;
            }
        }

        if (schedule)
        {
            Dispatcher.BeginInvoke(RenderLatest, DispatcherPriority.Render);
        }
    }

    private void RenderLatest()
    {
        byte[]? frame;
        int width;
        int height;
        lock (frameGate)
        {
            frame = latestFrame;
            width = latestWidth;
            height = latestHeight;
            latestFrame = null;
            renderQueued = false;
        }

        if (frame == null || width <= 0 || height <= 0)
        {
            return;
        }

        if (bitmap == null || bitmap.PixelWidth != width || bitmap.PixelHeight != height)
        {
            bitmap = new WriteableBitmap(width, height, 96, 96, System.Windows.Media.PixelFormats.Bgra32, null);
            VideoImage.Source = bitmap;
            obsWindow?.ShowFrame(bitmap);
        }

        bitmap.WritePixels(new Int32Rect(0, 0, width, height), frame, width * 4, 0);
    }

    private void RequestKeyframe()
    {
        var now = Environment.TickCount64;
        if (now - lastKeyframeRequest < 500)
        {
            return;
        }

        lastKeyframeRequest = now;
        session?.Send(new RequestKeyframePacket());
    }

    private void DisconnectButton_Click(object sender, RoutedEventArgs e) => CloseSession();

    private void TrustButton_Click(object sender, RoutedEventArgs e)
    {
        if (session?.Authenticated != true)
        {
            return;
        }

        session.Send(new TrustRequestPacket(store.PcId, store.PcName));
        Status("タブレットで信頼の確認を出しています");
    }

    private void ForgetButton_Click(object sender, RoutedEventArgs e)
    {
        var tabletId = session?.Hello?.TabletId;
        if (tabletId == null && DeviceList.SelectedItem is DiscoveredTablet selected && selected.TabletId.Length == ProtocolLimits.IdBytes)
        {
            tabletId = selected.TabletId;
        }

        if (tabletId == null)
        {
            Status("消す対象のタブレットがまだ分かっていません");
            return;
        }

        store.Forget(tabletId);
        Status("この PC に保存した信頼トークンを消しました");
    }

    private void PresetBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (session?.Authenticated == true && PresetBox.SelectedIndex >= 0)
        {
            session.Send(new StreamConfigPacket(PresetBox.SelectedIndex));
        }
    }

    private void MuteBox_Changed(object sender, RoutedEventArgs e)
    {
        if (audio != null)
        {
            audio.Muted = MuteBox.IsChecked == true;
        }
    }

    private void DeviceList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (refreshing || DeviceList.SelectedItem is not DiscoveredTablet device)
        {
            return;
        }

        IpBox.Text = device.Host;
        PortBox.Text = device.Port.ToString();
    }

    private void RefreshDevices()
    {
        var selected = DeviceList.SelectedItem as DiscoveredTablet;
        var items = discovery.Snapshot();
        refreshing = true;
        DeviceList.ItemsSource = items;
        if (selected != null)
        {
            DeviceList.SelectedItem = items.FirstOrDefault(item => item.Host == selected.Host && item.Port == selected.Port);
        }

        refreshing = false;
    }

    private void FullscreenButton_Click(object sender, RoutedEventArgs e) => ToggleFullscreen();

    private void ObsButton_Click(object sender, RoutedEventArgs e)
    {
        if (obsWindow != null)
        {
            obsWindow.Activate();
            return;
        }

        var window = new ObsWindow();
        window.Closed += (_, _) =>
        {
            if (obsWindow == window)
            {
                obsWindow = null;
            }
        };
        obsWindow = window;
        if (bitmap != null)
        {
            window.ShowFrame(bitmap);
        }

        window.Show();
    }

    private void CloseObs()
    {
        var window = obsWindow;
        obsWindow = null;
        window?.Close();
    }

    private void ToggleFullscreen()
    {
        if (!fullscreen)
        {
            previousStyle = WindowStyle;
            previousState = WindowState;
            SideColumn.Width = new GridLength(0);
            SidePanel.Visibility = Visibility.Collapsed;
            WindowStyle = WindowStyle.None;
            WindowState = WindowState.Maximized;
            fullscreen = true;
            return;
        }

        SideColumn.Width = new GridLength(320);
        SidePanel.Visibility = Visibility.Visible;
        WindowStyle = previousStyle;
        WindowState = previousState;
        fullscreen = false;
    }

    private void Window_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape && fullscreen)
        {
            ToggleFullscreen();
            e.Handled = true;
            return;
        }

        if (Keyboard.FocusedElement is TextBox || session?.Authenticated != true)
        {
            return;
        }

        if (!KeyMap.TryMap(e.Key, Keyboard.Modifiers, out var code, out var meta))
        {
            return;
        }

        session.Send(new InputKeyPacket(0, code, (int)meta));
        e.Handled = true;
    }

    private void Window_PreviewKeyUp(object sender, KeyEventArgs e)
    {
        if (Keyboard.FocusedElement is TextBox || session?.Authenticated != true)
        {
            return;
        }

        if (!KeyMap.TryMap(e.Key, Keyboard.Modifiers, out var code, out var meta))
        {
            return;
        }

        session.Send(new InputKeyPacket(1, code, (int)meta));
        e.Handled = true;
    }

    private void Video_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (!TryMap(e.GetPosition(VideoImage), out var x, out var y))
        {
            return;
        }

        dragging = true;
        VideoImage.CaptureMouse();
        SendTouch(ProtocolLimits.TouchDown, x, y);
    }

    private void Video_MouseUp(object sender, MouseButtonEventArgs e)
    {
        if (!dragging)
        {
            return;
        }

        dragging = false;
        VideoImage.ReleaseMouseCapture();
        if (TryMap(e.GetPosition(VideoImage), out var x, out var y))
        {
            SendTouch(ProtocolLimits.TouchUp, x, y);
        }
    }

    private void Video_MouseMove(object sender, MouseEventArgs e)
    {
        var hover = HoverBox.IsChecked == true;
        if (!dragging && !hover)
        {
            return;
        }

        if (TryMap(e.GetPosition(VideoImage), out var x, out var y))
        {
            SendTouch(ProtocolLimits.TouchMove, x, y);
        }
    }

    private void Video_RightClick(object sender, MouseButtonEventArgs e) => SendKeyTap(4);

    private void Video_MiddleClick(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton == MouseButton.Middle)
        {
            SendKeyTap(3);
            e.Handled = true;
        }
    }

    private void Video_Wheel(object sender, MouseWheelEventArgs e)
    {
        if (!TryMap(e.GetPosition(VideoImage), out var x, out var y) || session?.Authenticated != true)
        {
            return;
        }

        session.Send(new InputScrollPacket(x, y, (short)Math.Clamp(e.Delta, short.MinValue, short.MaxValue)));
    }

    private void SendTouch(int action, ushort x, ushort y)
    {
        if (session?.Authenticated != true)
        {
            return;
        }

        session.Send(new InputTouchPacket(action, x, y));
    }

    private void SendKeyTap(ushort code)
    {
        if (session?.Authenticated != true)
        {
            return;
        }

        session.Send(new InputKeyPacket(0, code, 0));
        session.Send(new InputKeyPacket(1, code, 0));
    }

    private bool TryMap(Point point, out ushort x, out ushort y)
    {
        x = 0;
        y = 0;
        if (bitmap == null)
        {
            return false;
        }

        var area = new Size(VideoImage.ActualWidth, VideoImage.ActualHeight);
        if (area.Width <= 0 || area.Height <= 0)
        {
            return false;
        }

        var scale = Math.Min(area.Width / bitmap.PixelWidth, area.Height / bitmap.PixelHeight);
        var width = bitmap.PixelWidth * scale;
        var height = bitmap.PixelHeight * scale;
        var left = (area.Width - width) / 2;
        var top = (area.Height - height) / 2;
        if (point.X < left || point.Y < top || point.X > left + width || point.Y > top + height)
        {
            return false;
        }

        x = (ushort)Math.Clamp((point.X - left) / width * 65535, 0, 65535);
        y = (ushort)Math.Clamp((point.Y - top) / height * 65535, 0, 65535);
        return true;
    }

    private void CloseSession()
    {
        session?.Dispose();
        session = null;
        decoder?.Dispose();
        decoder = null;
        audio?.Dispose();
        audio = null;
        TrustButton.IsEnabled = false;
    }

    private void Status(string text) => StatusText.Text = text;
}
