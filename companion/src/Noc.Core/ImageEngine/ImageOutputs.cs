using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.Storage;

namespace Noc.Core.ImageEngine;

/// <summary>
/// As imagens geradas, no PC: &lt;raiz&gt;\outputs\AAAA-MM\&lt;id&gt;.png (original, sem metadados dentro do arquivo),
/// &lt;id&gt;.thumb.jpg (miniatura para a conversa) e &lt;id&gt;.meta (prompt e parâmetros, cifrados com DPAPI).
/// O PC guarda o original para o celular baixar de novo quando precisar (outro aparelho, cache limpo).
/// </summary>
public sealed class ImageOutputs
{
    private static readonly byte[] Entropy = "Noc/images/v1"u8.ToArray();
    private readonly Dictionary<string, string> _index = new();
    private readonly Lock _lock = new();
    private bool _scanned;

    public static bool ValidId(string id) => id.Length is > 8 and < 100 && id.All(c => char.IsAsciiLetterOrDigit(c) || c == '-');

    /// <summary>Caminho-base (sem extensão) para uma imagem nova.</summary>
    public string NewBase(string id)
    {
        var dir = Path.Combine(ImagePaths.Outputs, DateTime.Now.ToString("yyyy-MM"));
        Directory.CreateDirectory(dir);
        return Path.Combine(dir, id);
    }

    private void Scan()
    {
        if (_scanned || !Directory.Exists(ImagePaths.Outputs)) { _scanned = true; return; }
        foreach (var f in Directory.EnumerateFiles(ImagePaths.Outputs, "*.png", SearchOption.AllDirectories))
        {
            var id = Path.GetFileNameWithoutExtension(f);
            _index[id] = Path.Combine(Path.GetDirectoryName(f)!, id);
        }
        _scanned = true;
    }

    public void Register(string id, string basePath)
    {
        lock (_lock) _index[id] = basePath;
    }

    public string? BaseOf(string id)
    {
        if (!ValidId(id)) return null;
        lock (_lock)
        {
            if (!_index.ContainsKey(id)) Scan();
            if (_index.TryGetValue(id, out var b) && File.Exists(b + ".png")) return b;
            _index.Remove(id);
            return null;
        }
    }

    public void SaveMeta(string basePath, JsonObject meta)
    {
        var plain = Encoding.UTF8.GetBytes(meta.ToJsonString());
        AppPaths.WriteAtomic(basePath + ".meta", ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser));
    }

    public JsonObject? ReadMeta(string id)
    {
        var b = BaseOf(id);
        if (b is null || !File.Exists(b + ".meta")) return null;
        try { return JsonNode.Parse(ProtectedData.Unprotect(File.ReadAllBytes(b + ".meta"), Entropy, DataProtectionScope.CurrentUser)) as JsonObject; }
        catch (Exception) { return null; }
    }

    public static string FileOf(string basePath, string kind) => basePath + (kind == "thumb" ? ".thumb.jpg" : ".png");

    public static string Sha256Of(string path)
    {
        using var fs = File.OpenRead(path);
        return Convert.ToHexString(SHA256.HashData(fs)).ToLowerInvariant();
    }

    /// <summary>Um pedaço do arquivo (o celular baixa em partes, pelo canal cifrado, e pode continuar de onde parou).</summary>
    public (byte[] Data, long Total)? Read(string id, string kind, long offset, int length)
    {
        var b = BaseOf(id);
        if (b is null) return null;
        var path = FileOf(b, kind);
        if (!File.Exists(path)) return null;
        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
        var total = fs.Length;
        if (offset < 0 || offset > total) throw new ArgumentOutOfRangeException(nameof(offset));
        var n = (int)Math.Min(length, total - offset);
        var buf = new byte[n];
        fs.Seek(offset, SeekOrigin.Begin);
        fs.ReadExactly(buf);
        return (buf, total);
    }

    public bool Delete(string id)
    {
        var b = BaseOf(id);
        if (b is null) return false;
        foreach (var ext in new[] { ".png", ".thumb.jpg", ".meta" })
            try { File.Delete(b + ext); } catch (Exception) { }
        lock (_lock) _index.Remove(id);
        return true;
    }

    /// <summary>Total ocupado pelas imagens no PC.</summary>
    public (int Count, long Bytes) Usage()
    {
        if (!Directory.Exists(ImagePaths.Outputs)) return (0, 0);
        var count = 0;
        long bytes = 0;
        foreach (var f in new DirectoryInfo(ImagePaths.Outputs).EnumerateFiles("*", SearchOption.AllDirectories))
        {
            bytes += f.Length;
            if (f.Extension == ".png") count++;
        }
        return (count, bytes);
    }
}
