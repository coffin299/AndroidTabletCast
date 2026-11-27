using System.Runtime.InteropServices;

namespace AndroidTabletCast;

public readonly record struct GpuAdapter(int Index, string Name);

public static class DxgiAdapters
{
    public static IReadOnlyList<GpuAdapter> List()
    {
        var found = new List<GpuAdapter>();
        var factoryId = new Guid("770aae78-f26f-4dba-a829-253c83d1b387");
        if (CreateDXGIFactory1(ref factoryId, out var factory) < 0 || factory == IntPtr.Zero)
        {
            return found;
        }

        var release = default(ReleaseDelegate);
        try
        {
            var factoryTable = Marshal.ReadIntPtr(factory);
            var enumAdapters = Marshal.GetDelegateForFunctionPointer<EnumAdaptersDelegate>(ReadSlot(factoryTable, 7));
            release = Marshal.GetDelegateForFunctionPointer<ReleaseDelegate>(ReadSlot(factoryTable, 2));
            for (var index = 0; ; index++)
            {
                var result = enumAdapters(factory, (uint)index, out var adapter);
                if (result == unchecked((int)0x887A0002) || result < 0 || adapter == IntPtr.Zero)
                {
                    break;
                }

                var name = ReadName(adapter);
                Release(adapter);
                if (name.Length > 0)
                {
                    found.Add(new GpuAdapter(index, name));
                }
            }
        }
        catch (Exception)
        {
            return found;
        }
        finally
        {
            if (release != null && factory != IntPtr.Zero)
            {
                release(factory);
            }
        }

        return found;
    }

    public static string NameOf(int index)
    {
        if (index < 0)
        {
            return "規定のGPU";
        }

        foreach (var adapter in List())
        {
            if (adapter.Index == index)
            {
                return adapter.Name;
            }
        }

        return $"GPU {index}";
    }

    private static string ReadName(IntPtr adapter)
    {
        var table = Marshal.ReadIntPtr(adapter);
        var getDesc = Marshal.GetDelegateForFunctionPointer<GetDescDelegate>(ReadSlot(table, 8));
        var desc = new DxgiAdapterDesc();
        if (getDesc(adapter, ref desc) < 0)
        {
            return "";
        }

        return (desc.Description ?? "").Trim();
    }

    private static void Release(IntPtr pointer)
    {
        if (pointer == IntPtr.Zero)
        {
            return;
        }

        var table = Marshal.ReadIntPtr(pointer);
        var release = Marshal.GetDelegateForFunctionPointer<ReleaseDelegate>(ReadSlot(table, 2));
        release(pointer);
    }

    private static IntPtr ReadSlot(IntPtr table, int slot)
    {
        return Marshal.ReadIntPtr(table, slot * IntPtr.Size);
    }

    [DllImport("dxgi.dll")]
    private static extern int CreateDXGIFactory1(ref Guid riid, out IntPtr factory);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct DxgiAdapterDesc
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)]
        public string Description;
        public uint VendorId;
        public uint DeviceId;
        public uint SubSysId;
        public uint Revision;
        public nuint DedicatedVideoMemory;
        public nuint DedicatedSystemMemory;
        public nuint SharedSystemMemory;
        public uint LuidLow;
        public int LuidHigh;
    }

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int EnumAdaptersDelegate(IntPtr factory, uint index, out IntPtr adapter);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int GetDescDelegate(IntPtr adapter, ref DxgiAdapterDesc desc);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint ReleaseDelegate(IntPtr pointer);
}
