using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Noc.Core.Storage;

namespace Noc.Core.Library;

public enum ImportState { Pending, Working, Done, Failed, Skipped }

/// <summary>Um arquivo de modelo encontrado fora do LM Studio e o que aconteceu com ele.</summary>
public sealed class LibraryItem
{
    public required string SourcePath { get; init; }
    public required long Size { get; init; }
    public DateTime Modified { get; init; }
    public string FileName => Path.GetFileName(SourcePath);
    public ImportState State { get; set; } = ImportState.Pending;
    /// <summary>Etapa atual em português ("Reparando", "Baixando componente de visão"...).</summary>
    public string? Step { get; set; }
    public double? Progress { get; set; }
    public string? Detail { get; set; }
    public string? TargetPath { get; set; }
    public string? ProjectorPath { get; set; }
    public bool Repaired { get; set; }
    public bool VisionAdded { get; set; }
    public DateTimeOffset? DoneAt { get; set; }

    public JsonObject ToJson() => new()
    {
        ["file"] = FileName, ["size"] = Size, ["state"] = State.ToString().ToLowerInvariant(), ["step"] = Step,
        ["progress"] = Progress is { } p ? Math.Round(p, 3) : null, ["detail"] = Detail, ["repaired"] = Repaired,
        ["visionAdded"] = VisionAdded,
    };
}

/// <summary>
/// Encontra GGUFs soltos (Downloads e pastas extras), repara os que o llama.cpp recusaria, junta o mmproj certo
/// e coloca tudo na biblioteca do LM Studio — sem o usuário mover arquivo nenhum.
/// Prefere hard link (instantâneo, sem ocupar espaço e sem tirar o arquivo do lugar).
/// </summary>
public sealed class ModelLibrary : IDisposable
{
    private static string IndexPath => Path.Combine(AppPaths.Root, "library.json");
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly Dictionary<string, LibraryItem> _items = new(StringComparer.OrdinalIgnoreCase);
    private readonly Lock _lock = new();
    private readonly List<FileSystemWatcher> _watchers = [];
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromMinutes(60) };
    private Timer? _debounce;
    private readonly Func<IReadOnlyList<string>> _extraFolders;
    private readonly Func<bool> _allowDownloads;

    public event Action? Changed;
    /// <summary>Texto para o registro de atividade do PC.</summary>
    public event Action<string, string>? Activity;
    /// <summary>Terminou uma importação: o LM Studio precisa reindexar.</summary>
    public event Action? Imported;

    public ModelLibrary(Func<IReadOnlyList<string>> extraFolders, Func<bool> allowDownloads)
    {
        _extraFolders = extraFolders;
        _allowDownloads = allowDownloads;
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("Noc-Companion/" + CompanionHost.Version);
        LoadIndex();
    }

    public IReadOnlyList<LibraryItem> Items
    {
        get { lock (_lock) return _items.Values.OrderByDescending(i => i.Modified).ToList(); }
    }

    public bool Busy { get { lock (_lock) return _items.Values.Any(i => i.State == ImportState.Working); } }

    public static string LmModelsDir
    {
        get
        {
            // O LM Studio permite mudar a pasta; a configuração fica em settings.json ("downloadsFolder").
            try
            {
                var f = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".lmstudio", "settings.json");
                if (File.Exists(f) && JsonNode.Parse(File.ReadAllText(f))?["downloadsFolder"]?.GetValue<string>() is { Length: > 0 } custom && Directory.Exists(custom))
                    return custom;
            }
            catch (Exception) { }
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".lmstudio", "models");
        }
    }

    public static string DownloadsDir
    {
        get
        {
            try
            {
                using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders");
                if (key?.GetValue("{374DE290-123F-4565-9164-39C4925E467B}") is string p)
                {
                    var expanded = Environment.ExpandEnvironmentVariables(p);
                    if (Directory.Exists(expanded)) return expanded;
                }
            }
            catch (Exception) { }
            return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        }
    }

    public IReadOnlyList<string> Folders => new[] { DownloadsDir }.Concat(_extraFolders()).Where(Directory.Exists).Distinct(StringComparer.OrdinalIgnoreCase).ToList();

    /// <summary>Observa as pastas: um GGUF novo terminado de baixar é importado sozinho.</summary>
    public void Watch()
    {
        foreach (var dir in Folders)
        {
            try
            {
                var w = new FileSystemWatcher(dir, "*.gguf") { IncludeSubdirectories = false, NotifyFilter = NotifyFilters.FileName | NotifyFilters.Size | NotifyFilters.LastWrite };
                w.Created += (_, _) => Poke();
                w.Renamed += (_, _) => Poke();
                w.Changed += (_, _) => Poke();
                w.EnableRaisingEvents = true;
                _watchers.Add(w);
            }
            catch (Exception) { }
        }
    }

    private void Poke()
    {
        // espera o arquivo parar de crescer antes de mexer
        _debounce?.Dispose();
        _debounce = new Timer(_ => _ = ScanAndImportAsync(CancellationToken.None), null, TimeSpan.FromSeconds(20), Timeout.InfiniteTimeSpan);
    }

    // ------------------------------------------------------------------ varredura

    private sealed record Found(string Path, long Size, DateTime Modified, GgufFile Header);

    public async Task ScanAndImportAsync(CancellationToken ct)
    {
        if (!await _gate.WaitAsync(0, ct)) return; // já está rodando
        try
        {
            var found = new List<Found>();
            foreach (var dir in Folders)
            {
                IEnumerable<string> files;
                try
                {
                    files = Directory.EnumerateFiles(dir, "*.gguf", new EnumerationOptions { RecurseSubdirectories = true, MaxRecursionDepth = 2, IgnoreInaccessible = true });
                }
                catch (Exception) { continue; }
                foreach (var path in files)
                {
                    try
                    {
                        var fi = new FileInfo(path);
                        // ainda baixando (ou recém-criado): deixa para a próxima varredura
                        if (DateTime.Now - fi.LastWriteTime < TimeSpan.FromSeconds(15)) { Poke(); continue; }
                        if (fi.Length < 1_000_000) continue;
                        var header = GgufFile.Read(path);
                        found.Add(new Found(path, fi.Length, fi.LastWriteTime, header));
                    }
                    catch (Exception) { /* não é GGUF válido: ignora */ }
                }
            }

            var existing = ExistingLibraryFiles();
            var projectors = found.Where(f => f.Header.IsProjector).ToList();
            foreach (var main in found.Where(f => !f.Header.IsProjector))
            {
                ct.ThrowIfCancellationRequested();
                LibraryItem item;
                lock (_lock)
                {
                    if (_items.TryGetValue(main.Path, out var known) && known.Size == main.Size && known.State is ImportState.Done or ImportState.Skipped &&
                        (known.TargetPath is null || File.Exists(known.TargetPath)))
                        continue;
                    item = new LibraryItem { SourcePath = main.Path, Size = main.Size, Modified = main.Modified };
                    _items[main.Path] = item;
                }
                // já existe na biblioteca do LM Studio (mesmo nome e tamanho, ou o mesmo arquivo físico)
                var twin = existing.FirstOrDefault(e => string.Equals(Path.GetFileName(e.Path), main.Path.Split('\\', '/')[^1], StringComparison.OrdinalIgnoreCase) && e.Size == main.Size)
                           ?? existing.FirstOrDefault(e => SameFile(e.Path, main.Path));
                if (twin is not null)
                {
                    item.State = ImportState.Skipped;
                    item.TargetPath = twin.Path;
                    item.Detail = "Já está na sua biblioteca";
                    SaveIndex();
                    continue;
                }
                // já importado antes (talvez numa versão corrigida, com outro tamanho)
                var planned = Path.Combine(LmModelsDir, "local", RepoNameFor(main.Path), Path.GetFileName(main.Path));
                if (File.Exists(planned) && !Path.GetFullPath(planned).Equals(Path.GetFullPath(main.Path), StringComparison.OrdinalIgnoreCase))
                {
                    try
                    {
                        if (!GgufRepair.Analyze(GgufFile.Read(planned)).NeedsRepair)
                        {
                            item.State = ImportState.Skipped;
                            item.TargetPath = planned;
                            item.Detail = "Já está na sua biblioteca";
                            SaveIndex();
                            continue;
                        }
                    }
                    catch (Exception) { /* arquivo de destino ruim: refaz */ }
                }
                await ImportAsync(item, main, projectors, ct);
            }
            SaveIndex();
        }
        finally
        {
            _gate.Release();
            Changed?.Invoke();
        }
    }

    private sealed record LibFile(string Path, long Size);

    private static List<LibFile> ExistingLibraryFiles()
    {
        try
        {
            return Directory.EnumerateFiles(LmModelsDir, "*.gguf", new EnumerationOptions { RecurseSubdirectories = true, MaxRecursionDepth = 3, IgnoreInaccessible = true })
                .Select(p => new LibFile(p, new FileInfo(p).Length)).ToList();
        }
        catch (Exception) { return []; }
    }

    // ------------------------------------------------------------------ importação

    private async Task ImportAsync(LibraryItem item, Found main, List<Found> projectors, CancellationToken ct)
    {
        var h = main.Header;
        var repoName = RepoNameFor(main.Path);
        var dir = Path.Combine(LmModelsDir, "local", repoName);
        var target = Path.Combine(dir, Path.GetFileName(main.Path));
        item.State = ImportState.Working;
        item.TargetPath = target;
        Changed?.Invoke();
        try
        {
            Directory.CreateDirectory(dir);
            var plan = GgufRepair.Analyze(h);
            if (plan.NeedsRepair)
            {
                item.Step = "Ajustando o arquivo para o LM Studio";
                item.Detail = "Correções: " + string.Join("; ", plan.Issues);
                Activity?.Invoke($"Ajustando {DisplayFile(main.Path)} para funcionar no LM Studio (o original fica intacto)", "model");
                Changed?.Invoke();
                var last = DateTime.MinValue;
                await GgufRepair.WriteAsync(h, plan, target, new Progress<double>(p =>
                {
                    item.Progress = p;
                    if (DateTime.Now - last > TimeSpan.FromMilliseconds(500)) { last = DateTime.Now; Changed?.Invoke(); }
                }), ct);
                item.Repaired = true;
            }
            else
            {
                item.Step = "Adicionando à biblioteca";
                Changed?.Invoke();
                await PlaceAsync(main.Path, target, ct);
            }
            item.Progress = null;

            // Visão: mmproj ao lado, ou o componente oficial que falta.
            var projector = PickProjector(main, projectors);
            if (projector is not null)
            {
                var pTarget = Path.Combine(dir, Path.GetFileName(projector.Path));
                if (!File.Exists(pTarget)) await PlaceAsync(projector.Path, pTarget, ct);
                item.ProjectorPath = pTarget;
            }
            else if (WantsVision(main) && !Directory.EnumerateFiles(dir, "mmproj*.gguf").Any())
            {
                if (_allowDownloads())
                {
                    item.Step = "Baixando o componente de visão";
                    Changed?.Invoke();
                    var got = await FetchOfficialProjectorAsync(main, dir, item, ct);
                    if (got is not null)
                    {
                        item.ProjectorPath = got;
                        item.VisionAdded = true;
                    }
                    else item.Detail = "Este modelo parece ter visão, mas o componente de visão (mmproj) não foi encontrado.";
                }
                else item.Detail = "Componente de visão ausente (downloads automáticos desligados).";
            }

            item.State = ImportState.Done;
            item.Step = null;
            item.DoneAt = DateTimeOffset.Now;
            Activity?.Invoke($"{DisplayFile(main.Path)} adicionado aos seus modelos" + (item.VisionAdded ? " (com visão)" : ""), "model");
            Imported?.Invoke();
        }
        catch (OperationCanceledException)
        {
            item.State = ImportState.Pending;
            item.Step = null;
            throw;
        }
        catch (Exception e)
        {
            item.State = ImportState.Failed;
            item.Step = null;
            item.Progress = null;
            item.Detail = e is IOException io && (io.HResult & 0xFFFF) == 112 ? "Sem espaço em disco para adicionar este modelo." : "Não foi possível adicionar: " + e.Message;
            Activity?.Invoke($"Falha ao adicionar {DisplayFile(main.Path)}: {item.Detail}", "error");
        }
        finally
        {
            SaveIndex();
            Changed?.Invoke();
        }
    }

    /// <summary>Hard link (mesmo disco) ou cópia. Nunca apaga o original.</summary>
    private static async Task PlaceAsync(string source, string target, CancellationToken ct)
    {
        if (File.Exists(target)) return;
        if (CreateHardLink(target, source, IntPtr.Zero)) return;
        var free = new DriveInfo(Path.GetPathRoot(target)!).AvailableFreeSpace;
        var size = new FileInfo(source).Length;
        if (free < size + 2L * 1024 * 1024 * 1024) throw new IOException("Sem espaço em disco", unchecked((int)0x80070070));
        var tmp = target + ".part";
        await using (var s = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1 << 20, true))
        await using (var d = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 20, true))
            await s.CopyToAsync(d, 8 << 20, ct);
        File.Move(tmp, target);
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateHardLink(string lpFileName, string lpExistingFileName, IntPtr lpSecurityAttributes);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetFileInformationByHandle(Microsoft.Win32.SafeHandles.SafeFileHandle hFile, out ByHandleFileInformation info);

    [StructLayout(LayoutKind.Sequential)]
    private struct ByHandleFileInformation
    {
        public uint Attributes; public long Created, Accessed, Written; public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }

    private static bool SameFile(string a, string b)
    {
        try
        {
            using var ha = File.OpenHandle(a, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var hb = File.OpenHandle(b, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            return GetFileInformationByHandle(ha, out var ia) && GetFileInformationByHandle(hb, out var ib) &&
                   ia.Volume == ib.Volume && ia.IndexHigh == ib.IndexHigh && ia.IndexLow == ib.IndexLow;
        }
        catch (Exception) { return false; }
    }

    // ------------------------------------------------------------------ nomes e pareamento

    private static readonly Regex QuantSuffix = new(@"[-_.](I?Q\d(_[A-Z0-9]+)*|F16|BF16|F32|MXFP4|UD-[A-Z0-9_]+)$", RegexOptions.IgnoreCase);

    /// <summary>Nome da pasta na biblioteca: o nome do arquivo sem a quantização, com o sufixo -GGUF usual.</summary>
    public static string RepoNameFor(string file)
    {
        var name = Path.GetFileNameWithoutExtension(file);
        name = QuantSuffix.Replace(name, "");
        name = Regex.Replace(name, @"[^\w.\-]+", "-").Trim('-');
        return (name.Length > 80 ? name[..80] : name) + "-GGUF";
    }

    private static string DisplayFile(string path) => ModelNames.Friendly(Path.GetFileNameWithoutExtension(path), null, null);

    /// <summary>Tokens significativos do nome (para casar modelo com mmproj).</summary>
    private static HashSet<string> Tokens(string name)
    {
        var n = Path.GetFileNameWithoutExtension(name).ToLowerInvariant();
        n = Regex.Replace(n, "^mmproj[-_]?", "");
        n = QuantSuffix.Replace(n, "");
        return Regex.Split(n, @"[^a-z0-9.]+").Where(t => t.Length > 0 && t is not ("gguf" or "model")).ToHashSet();
    }

    private static Found? PickProjector(Found main, List<Found> projectors)
    {
        var mt = Tokens(main.Path);
        var emb = main.Header.EmbeddingLength;
        return projectors
            .Where(p => p.Header.ProjectionDim is null || emb is null || p.Header.ProjectionDim == emb)
            .Select(p =>
            {
                var pt = Tokens(p.Path);
                var jaccard = (double)mt.Intersect(pt).Count() / Math.Max(1, mt.Union(pt).Count());
                var sameBase = p.Header.BaseName is { } b && main.Header.BaseName is { } mb && b == mb &&
                               p.Header.SizeLabel == main.Header.SizeLabel;
                return (p, score: jaccard + (sameBase ? 1 : 0) + (Path.GetDirectoryName(p.Path) == Path.GetDirectoryName(main.Path) ? 0.05 : 0));
            })
            .Where(x => x.score >= 0.5)
            .OrderByDescending(x => x.score)
            .Select(x => x.p)
            .FirstOrDefault();
    }

    private static bool WantsVision(Found main) =>
        main.Header.HasEmbeddedVision ||
        Regex.IsMatch(Path.GetFileName(main.Path), @"(^|[-_.])(vision|vl|mm|multimodal|omni)([-_.]|$)", RegexOptions.IgnoreCase);

    /// <summary>"Qwen3.5-9B-abliterated-vision-Q4_K_M" → "Qwen3.5-9B" (família + tamanho).</summary>
    public static string? BaseModelName(string file)
    {
        var parts = Regex.Split(Path.GetFileNameWithoutExtension(file), @"[-_ ]+");
        var outParts = new List<string>();
        for (var i = 0; i < parts.Length; i++)
        {
            outParts.Add(parts[i]);
            if (Regex.IsMatch(parts[i], @"^\d+(\.\d+)?[BbMm]$"))
            {
                if (i + 1 < parts.Length && Regex.IsMatch(parts[i + 1], @"^A\d+(\.\d+)?B$", RegexOptions.IgnoreCase)) outParts.Add(parts[i + 1]);
                return string.Join('-', outParts);
            }
        }
        return null;
    }

    /// <summary>
    /// Procura o mmproj oficial do modelo base no Hugging Face, confere pelo cabeçalho que ele encaixa
    /// (projection_dim = embedding do modelo) e baixa verificando o SHA-256.
    /// </summary>
    private async Task<string?> FetchOfficialProjectorAsync(Found main, string dir, LibraryItem item, CancellationToken ct)
    {
        var baseName = BaseModelName(main.Path);
        if (baseName is null) return null;
        var emb = main.Header.EmbeddingLength;
        string[] owners = ["lmstudio-community", "ggml-org", "unsloth", "bartowski"];
        foreach (var owner in owners)
        {
            var repo = $"{owner}/{baseName}-GGUF";
            JsonArray? tree;
            try
            {
                tree = JsonNode.Parse(await _http.GetStringAsync($"https://huggingface.co/api/models/{repo}/tree/main", ct)) as JsonArray;
            }
            catch (Exception) { continue; }
            if (tree is null) continue;
            var candidates = tree.OfType<JsonObject>()
                .Where(f => f["path"]?.GetValue<string>() is { } p && p.StartsWith("mmproj", StringComparison.OrdinalIgnoreCase) && p.EndsWith(".gguf", StringComparison.OrdinalIgnoreCase))
                .OrderBy(f => f["path"]!.GetValue<string>().Contains("F32", StringComparison.OrdinalIgnoreCase) ? 1 : 0)
                .ThenBy(f => f["size"]?.GetValue<long>() ?? long.MaxValue)
                .ToList();
            foreach (var c in candidates)
            {
                var file = c["path"]!.GetValue<string>();
                var sha = c["lfs"]?["oid"]?.GetValue<string>();
                var size = c["size"]?.GetValue<long>() ?? 0;
                var url = $"https://huggingface.co/{repo}/resolve/main/{file}";
                // confere o cabeçalho antes de baixar tudo
                if (!await HeaderFitsAsync(url, emb, ct)) continue;
                Activity?.Invoke($"Baixando o componente de visão de {baseName} ({size / 1_048_576} MB) do Hugging Face", "model");
                var dest = Path.Combine(dir, file);
                var tmp = dest + ".part";
                using (var sha256 = SHA256.Create())
                {
                    using var res = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct);
                    res.EnsureSuccessStatusCode();
                    await using var body = await res.Content.ReadAsStreamAsync(ct);
                    await using (var outFile = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 20, true))
                    {
                        var buf = new byte[1 << 20];
                        long got = 0;
                        var last = DateTime.MinValue;
                        int n;
                        while ((n = await body.ReadAsync(buf, ct)) > 0)
                        {
                            await outFile.WriteAsync(buf.AsMemory(0, n), ct);
                            sha256.TransformBlock(buf, 0, n, null, 0);
                            got += n;
                            item.Progress = size > 0 ? (double)got / size : null;
                            if (DateTime.Now - last > TimeSpan.FromMilliseconds(500)) { last = DateTime.Now; Changed?.Invoke(); }
                        }
                    }
                    sha256.TransformFinalBlock([], 0, 0);
                    var hex = Convert.ToHexString(sha256.Hash!).ToLowerInvariant();
                    if (sha is not null && hex != sha)
                    {
                        File.Delete(tmp);
                        throw new InvalidDataException("o download do componente de visão veio corrompido");
                    }
                }
                File.Move(tmp, dest, overwrite: true);
                item.Progress = null;
                return dest;
            }
        }
        return null;
    }

    private async Task<bool> HeaderFitsAsync(string url, long? embedding, CancellationToken ct)
    {
        var tmp = Path.Combine(Path.GetTempPath(), "noc-mmproj-" + Guid.NewGuid().ToString("N") + ".gguf");
        try
        {
            using var req = new HttpRequestMessage(HttpMethod.Get, url);
            req.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(0, 4 * 1024 * 1024 - 1);
            using var res = await _http.SendAsync(req, ct);
            if (!res.IsSuccessStatusCode) return false;
            await File.WriteAllBytesAsync(tmp, await res.Content.ReadAsByteArrayAsync(ct), ct);
            GgufFile h;
            try { h = GgufFile.Read(tmp); }
            catch (Exception) { return false; } // cabeçalho maior que 4 MB: raro para mmproj
            return h.IsProjector && (embedding is null || h.ProjectionDim is null || h.ProjectionDim == embedding);
        }
        catch (Exception) { return false; }
        finally { try { File.Delete(tmp); } catch { } }
    }

    // ------------------------------------------------------------------ índice

    private void LoadIndex()
    {
        try
        {
            if (!File.Exists(IndexPath)) return;
            var arr = JsonNode.Parse(File.ReadAllText(IndexPath)) as JsonArray;
            foreach (var n in arr?.OfType<JsonObject>() ?? [])
            {
                var item = new LibraryItem
                {
                    SourcePath = n["source"]!.GetValue<string>(), Size = n["size"]!.GetValue<long>(),
                    Modified = DateTime.FromBinary(n["modified"]?.GetValue<long>() ?? 0),
                    State = Enum.TryParse<ImportState>(n["state"]?.GetValue<string>(), out var s) ? s : ImportState.Pending,
                    Detail = n["detail"]?.GetValue<string>(), TargetPath = n["target"]?.GetValue<string>(),
                    ProjectorPath = n["projector"]?.GetValue<string>(), Repaired = n["repaired"]?.GetValue<bool>() ?? false,
                    VisionAdded = n["visionAdded"]?.GetValue<bool>() ?? false,
                };
                if (item.State == ImportState.Working) item.State = ImportState.Pending; // interrompido
                _items[item.SourcePath] = item;
            }
        }
        catch (Exception) { }
    }

    private void SaveIndex()
    {
        try
        {
            var arr = new JsonArray();
            lock (_lock)
                foreach (var i in _items.Values)
                    arr.Add(new JsonObject
                    {
                        ["source"] = i.SourcePath, ["size"] = i.Size, ["modified"] = i.Modified.ToBinary(), ["state"] = i.State.ToString(),
                        ["detail"] = i.Detail, ["target"] = i.TargetPath, ["projector"] = i.ProjectorPath, ["repaired"] = i.Repaired,
                        ["visionAdded"] = i.VisionAdded,
                    });
            AppPaths.WriteAtomic(IndexPath, System.Text.Encoding.UTF8.GetBytes(arr.ToJsonString(new JsonSerializerOptions { WriteIndented = true })));
        }
        catch (Exception) { }
    }

    public void Dispose()
    {
        foreach (var w in _watchers) w.Dispose();
        _debounce?.Dispose();
        _http.Dispose();
    }
}
