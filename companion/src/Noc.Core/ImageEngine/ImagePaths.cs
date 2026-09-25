using System.Security.Cryptography;
using System.Text;
using Noc.Core.Storage;

namespace Noc.Core.ImageEngine;

/// <summary>
/// Onde fica cada coisa do motor de imagem — tudo debaixo de uma única pasta, previsível:
/// <code>
/// &lt;raiz&gt;\runtime   Python isolado + bibliotecas + worker
/// &lt;raiz&gt;\models    modelos oficiais (uma pasta por revisão)
/// &lt;raiz&gt;\cache     otimizações derivadas localmente (ex.: transformer em fp8)
/// &lt;raiz&gt;\outputs   imagens geradas (PNG original + miniatura + metadados)
/// &lt;raiz&gt;\tmp       temporários
/// </code>
/// A raiz é escolhida uma vez (o disco local com mais espaço livre) e guardada nos ajustes.
/// </summary>
public static class ImagePaths
{
    private static string? _root;

    public static string Root
    {
        get => _root ?? throw new InvalidOperationException("raiz do motor de imagem não definida");
        set { _root = value; Directory.CreateDirectory(value); }
    }

    public static bool HasRoot => _root is not null;

    public static string Runtime => Path.Combine(Root, "runtime");
    public static string Models => Path.Combine(Root, "models");
    public static string Cache => Path.Combine(Root, "cache");
    public static string Outputs => Path.Combine(Root, "outputs");
    public static string Tmp => Path.Combine(Root, "tmp");

    public static string ModelDir(ImageModelDef m, string revision) => Path.Combine(Models, m.Id, revision);
    public static string CacheDir(ImageModelDef m, string revision) => Path.Combine(Cache, m.Id, revision[..12]);

    /// <summary>Estado dos modelos instalados (fica com os outros dados do Companion).</summary>
    public static string StateFile => Path.Combine(AppPaths.Root, "image-models.json");
    public static string TokenFile => Path.Combine(AppPaths.Root, "hf-token.bin");

    /// <summary>
    /// Escolhe a raiz: o disco fixo local (SSD/HD interno) com mais espaço livre. Em empate técnico (diferença &lt; 20 GB),
    /// fica no disco do sistema, junto com o resto do Noc.
    /// </summary>
    public static string ChooseDefaultRoot()
    {
        var system = Path.GetPathRoot(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData))!;
        var drives = DriveInfo.GetDrives().Where(d => d.DriveType == DriveType.Fixed && d.IsReady).ToList();
        var best = drives.OrderByDescending(d => d.AvailableFreeSpace).FirstOrDefault();
        var sys = drives.FirstOrDefault(d => string.Equals(d.RootDirectory.FullName, system, StringComparison.OrdinalIgnoreCase));
        if (best is null || sys is null) return Path.Combine(AppPaths.DefaultRoot, "ImageEngine");
        if (best.AvailableFreeSpace - sys.AvailableFreeSpace < 20L * 1024 * 1024 * 1024)
            return Path.Combine(AppPaths.DefaultRoot, "ImageEngine");
        return Path.Combine(best.RootDirectory.FullName, "Noc", "ImageEngine");
    }

    public static long FreeBytes(string path)
    {
        try { return new DriveInfo(Path.GetPathRoot(Path.GetFullPath(path))!).AvailableFreeSpace; }
        catch (Exception) { return -1; }
    }
}

/// <summary>Token do Hugging Face (só para modelos com termos, como o FLUX.1 Dev). Guardado cifrado com DPAPI.</summary>
public static class HfToken
{
    private static readonly byte[] Entropy = "Noc/hf-token/v1"u8.ToArray();

    public static string? Load()
    {
        try
        {
            if (!File.Exists(ImagePaths.TokenFile)) return null;
            return Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(ImagePaths.TokenFile), Entropy, DataProtectionScope.CurrentUser));
        }
        catch (Exception) { return null; }
    }

    public static void Save(string? token)
    {
        if (string.IsNullOrWhiteSpace(token))
        {
            try { File.Delete(ImagePaths.TokenFile); } catch { }
            return;
        }
        AppPaths.WriteAtomic(ImagePaths.TokenFile, ProtectedData.Protect(Encoding.UTF8.GetBytes(token.Trim()), Entropy, DataProtectionScope.CurrentUser));
    }
}
