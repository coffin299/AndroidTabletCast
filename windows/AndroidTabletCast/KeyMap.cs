using System.Windows.Input;

namespace AndroidTabletCast;

public static class KeyMap
{
    public static bool TryMap(Key key, ModifierKeys modifiers, out ushort keyCode, out uint meta)
    {
        meta = 0;
        if (modifiers.HasFlag(ModifierKeys.Shift))
        {
            meta |= 1;
        }

        if (modifiers.HasFlag(ModifierKeys.Alt))
        {
            meta |= 2;
        }

        if (modifiers.HasFlag(ModifierKeys.Control))
        {
            meta |= 0x1000;
        }

        keyCode = key switch
        {
            >= Key.A and <= Key.Z => (ushort)(29 + (key - Key.A)),
            Key.D0 => 7,
            >= Key.D1 and <= Key.D9 => (ushort)(8 + (key - Key.D1)),
            Key.Space => 62,
            Key.Enter => 66,
            Key.Back => 67,
            Key.Tab => 61,
            Key.Escape => 4,
            Key.Delete => 112,
            Key.Left => 21,
            Key.Right => 22,
            Key.Up => 19,
            Key.Down => 20,
            Key.Home => 122,
            Key.End => 123,
            Key.PageUp => 92,
            Key.PageDown => 93,
            _ => 0,
        };
        return keyCode != 0;
    }
}
