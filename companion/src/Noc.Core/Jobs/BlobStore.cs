using System.Security.Cryptography;
using Noc.Core.Storage;

namespace Noc.Core.Jobs;

/// <summary>
/// Imagens enviadas pelo celular, endereçadas pelo SHA-256 do conteúdo.
/// O celular envia cada imagem uma vez só (em pedaços, com progresso real) e depois só cita o hash —
/// o histórico de uma conversa com imagens não é reenviado a cada mensagem.
/// Em disco ficam cifradas com DPAPI; saem sozinhas depois de 7 dias sem uso.
/// </summary>
public sealed class BlobStore : IDisposable
{
    public const int MaxBlobBytes = 24 * 1024 * 1024;
    private static readonly byte[] Entropy = "Noc/blobs/v1"u8.ToArray();
    private static readonly TimeSpan Retention = TimeSpan.FromDays(7);
    private const long MaxDiskBytes = 1024L * 1024 * 1024;
    private static string Dir => Path.Combine(AppPaths.Root, "blobs");

    private readonly Dictionary<string, Upload> _uploads = new();
    private readonly Lock _lock = new();
    private readonly Timer _sweep;

    private sealed class Upload
    {
        public required byte[] Buffer { get; init; }
        public long Received;
        public DateTimeOffset Touched = DateTimeOffset.Now;
    }

    public BlobStore()
    {
        _sweep = new Timer(_ => Sweep(), null, TimeSpan.FromMinutes(2), TimeSpan.FromHours(1));
    }

    public static bool ValidHash(string h) => h.Length == 64 && h.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');

    private static string PathOf(string hash) => System.IO.Path.Combine(Dir, hash + ".blob");

    public bool Has(string hash)
    {
        if (!ValidHash(hash)) return false;
        var p = PathOf(hash);
        if (!File.Exists(p)) return false;
        try { File.SetLastWriteTimeUtc(p, DateTime.UtcNow); } catch { }
        return true;
    }

    /// <summary>Recebe um pedaço. Devolve true quando a imagem está completa e conferida.</summary>
    public bool Put(string hash, long total, long offset, byte[] data)
    {
        if (!ValidHash(hash)) throw new ArgumentException("hash inválido");
        if (total <= 0 || total > MaxBlobBytes) throw new ArgumentException("tamanho inválido");
        if (Has(hash)) return true;
        Upload up;
        lock (_lock)
        {
            if (!_uploads.TryGetValue(hash, out up!) || up.Buffer.Length != total)
                _uploads[hash] = up = new Upload { Buffer = new byte[total] };
            if (offset < 0 || offset + data.Length > total) throw new ArgumentException("pedaço fora do lugar");
            Array.Copy(data, 0, up.Buffer, offset, data.Length);
            up.Received = Math.Max(up.Received, offset + data.Length);
            up.Touched = DateTimeOffset.Now;
            if (up.Received < total) return false;
            _uploads.Remove(hash);
        }
        var actual = Convert.ToHexString(SHA256.HashData(up.Buffer)).ToLowerInvariant();
        if (actual != hash) throw new InvalidDataException("a imagem chegou corrompida");
        Directory.CreateDirectory(Dir);
        AppPaths.WriteAtomic(PathOf(hash), ProtectedData.Protect(up.Buffer, Entropy, DataProtectionScope.CurrentUser));
        return true;
    }

    /// <summary>Quanto já chegou de um envio interrompido (para o celular continuar de onde parou).</summary>
    public long Received(string hash)
    {
        if (Has(hash)) return -1;
        lock (_lock) return _uploads.TryGetValue(hash, out var u) ? u.Received : 0;
    }

    public byte[]? Get(string hash)
    {
        if (!Has(hash)) return null;
        try { return ProtectedData.Unprotect(File.ReadAllBytes(PathOf(hash)), Entropy, DataProtectionScope.CurrentUser); }
        catch (Exception) { return null; }
    }

    private void Sweep()
    {
        lock (_lock)
            foreach (var k in _uploads.Where(kv => DateTimeOffset.Now - kv.Value.Touched > TimeSpan.FromMinutes(30)).Select(kv => kv.Key).ToList())
                _uploads.Remove(k);
        try
        {
            if (!Directory.Exists(Dir)) return;
            long total = 0;
            foreach (var f in new DirectoryInfo(Dir).GetFiles("*.blob").OrderByDescending(f => f.LastWriteTimeUtc))
            {
                total += f.Length;
                if (DateTime.UtcNow - f.LastWriteTimeUtc > Retention || total > MaxDiskBytes) f.Delete();
            }
        }
        catch (Exception) { }
    }

    public void Dispose() => _sweep.Dispose();
}
