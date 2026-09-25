namespace Noc.Core.Storage;

public static class AppPaths
{
    private static string? _root;

    /// <summary>%LOCALAPPDATA%\Noc (pode ser sobrescrito por NOC_DATA_DIR para testes).</summary>
    public static string Root
    {
        get
        {
            if (_root is not null) return _root;
            var overrideDir = Environment.GetEnvironmentVariable("NOC_DATA_DIR");
            _root = string.IsNullOrWhiteSpace(overrideDir)
                ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Noc")
                : overrideDir;
            Directory.CreateDirectory(_root);
            return _root;
        }
        set { _root = value; Directory.CreateDirectory(value); }
    }

    public static string Identity => Path.Combine(Root, "identity.bin");
    public static string Devices => Path.Combine(Root, "devices.json");
    public static string Settings => Path.Combine(Root, "settings.json");
    public static string SecurityLog => Path.Combine(Root, "security.log");
    public static string ActivityLog => Path.Combine(Root, "activity.log");

    /// <summary>Escrita atômica: grava num temporário e troca, para nunca deixar arquivo pela metade.</summary>
    public static void WriteAtomic(string path, byte[] data)
    {
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, data);
        File.Move(tmp, path, overwrite: true);
    }
}
