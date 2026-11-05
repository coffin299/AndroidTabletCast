using System.Windows;
using System.Windows.Media.Imaging;

namespace AndroidTabletCast;

public partial class ObsWindow : Window
{
    public ObsWindow()
    {
        InitializeComponent();
    }

    public void ShowFrame(WriteableBitmap bitmap)
    {
        VideoImage.Source = bitmap;
    }
}
