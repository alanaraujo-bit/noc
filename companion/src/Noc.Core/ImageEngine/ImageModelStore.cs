using System.Diagnostics;
using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Noc.Core.Storage;

namespace Noc.Core.ImageEngine;

public enum ImageModelState { NotInstalled, Checking, Downloading, Verifying, Installed, Paused, Failed, AuthRequired, TermsRequired, NoSpace }

/// <summary>Um arquivo do repositório oficial, com o que é preciso para conferir a integridade.</summary>
public sealed record RepoFile(string Path, long Size, string? Sha256, string? GitOid);

/// <summary>Estado persistido de um modelo instalado (ou em instalação).</summary>
public sealed class InstalledModel
{
    public string? Revision { get; set; }
    public bool Complete { get; set; }
    /// <summary>A instalação estava em andamento quando o Companion fechou: retoma sozinha.</summary>
    public bool Resume { get; set; }
    public long TotalBytes { get; set; }
    public DateTimeOffset? InstalledAt { get; set; }
    public DateTimeOffset? LastUsed { get; set; }
    public DateTimeOffset? VerifiedAt { get; set; }
    /// <summary>Última carga medida (segundos), para a estimativa "Preparando… ~12 s".</summary>
    public double? LoadSeconds { get; set; }
    public string? LatestRevision { get; set; }
    public DateTimeOffset? CheckedAt { get; set; }
    public Dictionary<string, FileRecord> Files { get; set; } = new();
}

public sealed class FileRecord
{
    public long Size { get; set; }
    public string? Sha256 { get; set; }
    public string? GitOid { get; set; }
    /// <summary>Conferido (hash bate) com esta data de modificação — não precisa conferir de novo.</summary>
    public long VerifiedMtime { get; set; }
}

public sealed class StoreState
{
    public Dictionary<string, InstalledModel> Models { get; set; } = new();
}

/// <summary>
/// Instala os modelos de imagem a partir da distribuição oficial no Hugging Face:
/// lista os arquivos da revisão fixada, confere espaço em disco, baixa em paralelo com retomada (Range),
/// confere SHA-256 (arquivos LFS) ou o hash git (arquivos pequenos) e só então marca como instalado.
/// Se a internet cair, continua de onde parou; se o Companion fechar, retoma na próxima partida.
/// </summary>
public sealed class ImageModelStore : IDisposable
{
    private const int ParallelFiles = 4;
    private const long SpaceMargin = 6L * 1024 * 1024 * 1024;
    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    private readonly HttpClient _http;
    private readonly Lock _lock = new();
    private StoreState _state;
    private readonly Dictionary<string, Progress> _progress = new();
    private readonly Dictionary<string, CancellationTokenSource> _running = new();

    public event Action? Changed;
    /// <summary>Linha para o registro de atividade do PC.</summary>
    public event Action<string, string>? Activity;
    /// <summary>Rastro técnico para o diagnóstico.</summary>
    public event Action<string>? Trace;

    /// <summary>Progresso ao vivo de uma instalação.</summary>
    public sealed class Progress
    {
        public ImageModelState State;
        public long Done;
        public long Total;
        public double BytesPerSecond;
        public string? Error;
        public string? Detail;
        public int FilesDone;
        public int FilesTotal;
        internal long LastBytes;
        internal DateTimeOffset LastTick = DateTimeOffset.Now;
    }

    public ImageModelStore()
    {
        var handler = new SocketsHttpHandler
        {
            AllowAutoRedirect = true, MaxAutomaticRedirections = 10, AutomaticDecompression = DecompressionMethods.None,
            PooledConnectionLifetime = TimeSpan.FromMinutes(5), ConnectTimeout = TimeSpan.FromSeconds(20),
        };
        _http = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("Noc-Companion/" + CompanionHost.Version);
        _state = LoadState();
    }

    // ------------------------------------------------------------------ estado

    private static StoreState LoadState()
    {
        try
        {
            if (File.Exists(ImagePaths.StateFile))
                return JsonSerializer.Deserialize<StoreState>(File.ReadAllText(ImagePaths.StateFile)) ?? new();
        }
        catch (Exception) { }
        return new StoreState();
    }

    private void SaveState()
    {
        lock (_lock) AppPaths.WriteAtomic(ImagePaths.StateFile, JsonSerializer.SerializeToUtf8Bytes(_state, Json));
    }

    private InstalledModel Rec(ImageModelDef m)
    {
        lock (_lock)
        {
            if (!_state.Models.TryGetValue(m.Id, out var r)) _state.Models[m.Id] = r = new InstalledModel();
            return r;
        }
    }

    public bool IsInstalled(ImageModelDef m)
    {
        var r = Rec(m);
        return r.Complete && r.Revision is not null && Directory.Exists(ImagePaths.ModelDir(m, r.Revision));
    }

    public string? InstalledDir(ImageModelDef m)
    {
        var r = Rec(m);
        return IsInstalled(m) ? ImagePaths.ModelDir(m, r.Revision!) : null;
    }

    public string? InstalledRevision(ImageModelDef m) => IsInstalled(m) ? Rec(m).Revision : null;

    public void MarkUsed(ImageModelDef m)
    {
        Rec(m).LastUsed = DateTimeOffset.Now;
        SaveState();
    }

    public double? LoadSeconds(ImageModelDef m) => Rec(m).LoadSeconds;

    public void SetLoadSeconds(ImageModelDef m, double seconds)
    {
        Rec(m).LoadSeconds = seconds;
        SaveState();
    }

    public Progress? LiveProgress(ImageModelDef m)
    {
        lock (_lock) return _progress.TryGetValue(m.Id, out var p) ? p : null;
    }

    public bool Busy(ImageModelDef m)
    {
        lock (_lock) return _running.ContainsKey(m.Id);
    }

    public ImageModelState StateOf(ImageModelDef m)
    {
        var p = LiveProgress(m);
        if (p is not null && (Busy(m) || p.State is ImageModelState.AuthRequired or ImageModelState.TermsRequired or ImageModelState.NoSpace or ImageModelState.Failed or ImageModelState.Paused))
            return p.State;
        return IsInstalled(m) ? ImageModelState.Installed : ImageModelState.NotInstalled;
    }

    public JsonObject ToJson(ImageModelDef m)
    {
        var r = Rec(m);
        var p = LiveProgress(m);
        var st = StateOf(m);
        var installed = IsInstalled(m);
        return new JsonObject
        {
            ["id"] = m.Id, ["name"] = m.Name, ["profile"] = m.Profile, ["engine"] = m.Engine,
            ["state"] = st switch
            {
                ImageModelState.NotInstalled => "not_installed", ImageModelState.Checking => "checking",
                ImageModelState.Downloading => "downloading", ImageModelState.Verifying => "verifying",
                ImageModelState.Installed => "installed", ImageModelState.Paused => "paused", ImageModelState.Failed => "failed",
                ImageModelState.AuthRequired => "auth_required", ImageModelState.TermsRequired => "terms_required",
                ImageModelState.NoSpace => "no_space", _ => "unknown",
            },
            ["installed"] = installed,
            ["revision"] = r.Revision is { } rev ? rev[..Math.Min(12, rev.Length)] : null,
            ["sizeBytes"] = installed ? r.TotalBytes : m.ApproxBytes,
            ["downloadBytes"] = m.ApproxBytes,
            ["lastUsed"] = r.LastUsed?.ToUnixTimeMilliseconds(),
            ["updateAvailable"] = installed && r.LatestRevision is { } lr && lr != r.Revision,
            ["gated"] = m.Gated, ["license"] = m.License, ["licenseUrl"] = m.LicenseUrl,
            ["progress"] = p is null || st is ImageModelState.Installed or ImageModelState.NotInstalled ? null : new JsonObject
            {
                ["done"] = p.Done, ["total"] = p.Total, ["bps"] = Math.Round(p.BytesPerSecond),
                ["files"] = p.FilesDone, ["filesTotal"] = p.FilesTotal,
                ["etaSeconds"] = p.BytesPerSecond > 1 && p.Total > p.Done ? (long)((p.Total - p.Done) / p.BytesPerSecond) : null,
            },
            ["error"] = p?.Error,
            ["detail"] = p?.Detail,
        };
    }

    // ------------------------------------------------------------------ Hugging Face

    private HttpRequestMessage Req(HttpMethod method, string url, string? token)
    {
        var req = new HttpRequestMessage(method, url);
        // O .NET remove o Authorization ao seguir o redirecionamento para a CDN (URL assinada, outro domínio).
        if (!string.IsNullOrEmpty(token)) req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return req;
    }

    /// <summary>Confere o token (conta do Hugging Face). Devolve o nome do usuário ou null se inválido.</summary>
    public async Task<string?> WhoAmIAsync(string token, CancellationToken ct)
    {
        using var res = await _http.SendAsync(Req(HttpMethod.Get, "https://huggingface.co/api/whoami-v2", token), ct);
        if (!res.IsSuccessStatusCode) return null;
        var j = JsonNode.Parse(await res.Content.ReadAsStringAsync(ct));
        return j?["name"]?.GetValue<string>() ?? "?";
    }

    /// <summary>Lista os arquivos da revisão (com hashes). Em repositório com termos, exige token e termos aceitos.</summary>
    public async Task<List<RepoFile>> ListAsync(ImageModelDef m, string revision, string? token, CancellationToken ct)
    {
        using var res = await _http.SendAsync(Req(HttpMethod.Get, $"https://huggingface.co/api/models/{m.Repo}/tree/{revision}?recursive=true", token), ct);
        if (res.StatusCode == HttpStatusCode.Unauthorized) throw new ImageStoreException(ImageModelState.AuthRequired, "auth_required");
        if (res.StatusCode == HttpStatusCode.Forbidden) throw new ImageStoreException(ImageModelState.TermsRequired, "terms_required");
        res.EnsureSuccessStatusCode();
        var arr = JsonNode.Parse(await res.Content.ReadAsStringAsync(ct)) as JsonArray ?? [];
        var files = new List<RepoFile>();
        foreach (var n in arr.OfType<JsonObject>())
        {
            if (n["type"]?.GetValue<string>() != "file") continue;
            var path = n["path"]!.GetValue<string>();
            if (!m.Wants(path)) continue;
            var lfs = n["lfs"] as JsonObject;
            var sha = lfs?["oid"]?.GetValue<string>();
            var git = n["oid"]?.GetValue<string>();
            if (sha is not null && !IsHex(sha, 64)) sha = null; // repositório com termos sem acesso: hashes mascarados
            if (git is not null && !IsHex(git, 40)) git = null;
            files.Add(new RepoFile(path, lfs?["size"]?.GetValue<long>() ?? n["size"]!.GetValue<long>(), sha, lfs is null ? git : null));
        }
        if (files.Count == 0) throw new ImageStoreException(ImageModelState.Failed, "repo_empty");
        if (m.Gated && files.Any(f => f.Sha256 is null && f.GitOid is null))
        {
            // sem acesso ainda, o Hugging Face lista o repositório mas esconde os hashes: confirma pelo download
            await ProbeAccessAsync(m, revision, token, ct);
        }
        return files;
    }

    /// <summary>Tenta um arquivo pequeno: 401 = sem token (ou inválido); 403 = termos não aceitos nesta conta.</summary>
    public async Task ProbeAccessAsync(ImageModelDef m, string revision, string? token, CancellationToken ct)
    {
        using var res = await _http.SendAsync(Req(HttpMethod.Get, $"https://huggingface.co/{m.Repo}/resolve/{revision}/model_index.json", token),
            HttpCompletionOption.ResponseHeadersRead, ct);
        if (res.StatusCode == HttpStatusCode.Unauthorized) throw new ImageStoreException(ImageModelState.AuthRequired, "auth_required");
        if (res.StatusCode == HttpStatusCode.Forbidden) throw new ImageStoreException(ImageModelState.TermsRequired, "terms_required");
        res.EnsureSuccessStatusCode();
    }

    private static bool IsHex(string s, int len) => s.Length == len && s.All(Uri.IsHexDigit);

    /// <summary>Revisão atual do repositório (para avisar de atualização, nunca para atualizar sozinho).</summary>
    public async Task CheckUpdateAsync(ImageModelDef m, CancellationToken ct)
    {
        var r = Rec(m);
        if (!IsInstalled(m)) return;
        try
        {
            using var res = await _http.SendAsync(Req(HttpMethod.Get, $"https://huggingface.co/api/models/{m.Repo}/revision/main", HfToken.Load()), ct);
            if (!res.IsSuccessStatusCode) return;
            var sha = JsonNode.Parse(await res.Content.ReadAsStringAsync(ct))?["sha"]?.GetValue<string>();
            if (sha is null) return;
            r.CheckedAt = DateTimeOffset.Now;
            if (sha != r.Revision)
            {
                // só é atualização se algum arquivo que usamos mudou
                var now = await ListAsync(m, sha, HfToken.Load(), ct);
                var same = now.Count == r.Files.Count && now.All(f => r.Files.TryGetValue(f.Path, out var old) && old.Size == f.Size &&
                                                                    (old.Sha256 ?? old.GitOid) == (f.Sha256 ?? f.GitOid));
                r.LatestRevision = same ? r.Revision : sha;
            }
            else r.LatestRevision = sha;
            SaveState();
            Changed?.Invoke();
        }
        catch (Exception e) when (e is not OperationCanceledException) { Trace?.Invoke($"verificação de atualização de {m.Id} falhou: {e.GetType().Name}"); }
    }

    // ------------------------------------------------------------------ instalar

    /// <summary>Instala (ou continua a instalação). Idempotente: se já está rodando, não faz nada.</summary>
    public void StartInstall(ImageModelDef m, string? revision = null)
    {
        CancellationTokenSource cts;
        lock (_lock)
        {
            if (_running.ContainsKey(m.Id)) return;
            _running[m.Id] = cts = new CancellationTokenSource();
            _progress[m.Id] = new Progress { State = ImageModelState.Checking };
        }
        Changed?.Invoke();
        _ = Task.Run(async () =>
        {
            try { await InstallAsync(m, revision ?? m.Revision, cts.Token); }
            finally
            {
                lock (_lock) _running.Remove(m.Id);
                cts.Dispose();
                Changed?.Invoke();
            }
        });
    }

    public void Pause(ImageModelDef m)
    {
        lock (_lock)
            if (_running.TryGetValue(m.Id, out var c)) c.Cancel();
    }

    /// <summary>Instalações que estavam em andamento quando o Companion fechou.</summary>
    public void ResumePending()
    {
        foreach (var m in ImageCatalog.All)
            if (Rec(m) is { Resume: true, Complete: false }) StartInstall(m, Rec(m).Revision);
    }

    private async Task InstallAsync(ImageModelDef m, string revision, CancellationToken ct)
    {
        var p = LiveProgress(m)!;
        var r = Rec(m);
        var sw = Stopwatch.StartNew();
        var backoff = TimeSpan.FromSeconds(2);
        while (true)
        {
            try
            {
                var token = m.Gated ? HfToken.Load() : null;
                p.State = ImageModelState.Checking;
                p.Error = null;
                Changed?.Invoke();
                var files = await ListAsync(m, revision, token, ct);
                var dir = ImagePaths.ModelDir(m, revision);
                Directory.CreateDirectory(dir);
                if (r.Revision != revision)
                {
                    // revisão nova (atualização): começa um registro limpo; a antiga continua valendo até terminar
                    if (r.Complete && r.Revision is not null) r.LatestRevision = revision;
                    else { r.Files.Clear(); r.Revision = revision; }
                }
                var updating = r.Complete && r.Revision != revision;
                var target = updating ? new InstalledModel { Revision = revision } : r;
                target.Resume = true;
                SaveState();

                // espaço: o que falta baixar + otimização local + margem
                long missing = 0;
                foreach (var f in files)
                {
                    var final = Path.Combine(dir, f.Path.Replace('/', '\\'));
                    var have = File.Exists(final) ? new FileInfo(final).Length : File.Exists(final + ".part") ? new FileInfo(final + ".part").Length : 0;
                    missing += Math.Max(0, f.Size - (have == f.Size ? f.Size : File.Exists(final) ? 0 : have));
                }
                var free = ImagePaths.FreeBytes(dir);
                var need = missing + m.DerivedBytes + SpaceMargin;
                if (free >= 0 && free < need)
                {
                    p.State = ImageModelState.NoSpace;
                    p.Error = "no_space";
                    p.Detail = $"Faltam {Gb(need - free)} no disco {Path.GetPathRoot(dir)} (precisa de {Gb(need)} livres, há {Gb(free)}).";
                    Activity?.Invoke($"{m.Name}: espaço insuficiente para instalar ({p.Detail})", "error");
                    target.Resume = false;
                    SaveState();
                    Changed?.Invoke();
                    return;
                }

                p.Total = files.Sum(f => f.Size);
                p.FilesTotal = files.Count;
                p.Done = 0;
                p.FilesDone = 0;
                p.State = ImageModelState.Downloading;
                Activity?.Invoke($"Instalando {m.Name} ({Gb(p.Total)})", "model");
                Trace?.Invoke($"instalação de {m.Id}@{revision[..12]}: {files.Count} arquivos, {p.Total} bytes, faltam {missing}");
                Changed?.Invoke();

                using var ticker = new Timer(_ => Tick(p), null, 1000, 1000);
                using var gate = new SemaphoreSlim(ParallelFiles);
                // maiores primeiro: aproveita melhor as conexões paralelas
                var tasks = files.OrderByDescending(f => f.Size).Select(async f =>
                {
                    await gate.WaitAsync(ct);
                    try { await EnsureFileAsync(m, revision, dir, f, target, token, p, ct); }
                    finally { gate.Release(); }
                }).ToList();
                await Task.WhenAll(tasks);

                target.Files = files.ToDictionary(f => f.Path, f => target.Files.TryGetValue(f.Path, out var fr) ? fr : new FileRecord { Size = f.Size, Sha256 = f.Sha256, GitOid = f.GitOid });
                target.TotalBytes = p.Total;
                target.Complete = true;
                target.Resume = false;
                target.InstalledAt = DateTimeOffset.Now;
                target.VerifiedAt = DateTimeOffset.Now;
                target.LatestRevision = revision;
                if (updating)
                {
                    var old = r.Revision;
                    lock (_lock) _state.Models[m.Id] = target;
                    if (old is not null && old != revision)
                        try { Directory.Delete(ImagePaths.ModelDir(m, old), true); } catch (Exception) { }
                }
                SaveState();
                p.State = ImageModelState.Installed;
                Activity?.Invoke($"{m.Name} instalado e conferido ({Gb(p.Total)} em {sw.Elapsed.TotalMinutes:0.0} min)", "model");
                Trace?.Invoke($"{m.Id} instalado em {sw.Elapsed.TotalSeconds:0} s");
                Changed?.Invoke();
                return;
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                p.State = ImageModelState.Paused;
                r.Resume = false;
                SaveState();
                Activity?.Invoke($"Instalação de {m.Name} pausada (continua de onde parou)", "model");
                Changed?.Invoke();
                return;
            }
            catch (ImageStoreException e)
            {
                p.State = e.State;
                p.Error = e.Message;
                p.Detail = e.State switch
                {
                    ImageModelState.AuthRequired => "Este modelo exige uma conta do Hugging Face com os termos aceitos. Informe um token de acesso.",
                    ImageModelState.TermsRequired => "A conta do Hugging Face ainda não aceitou os termos do FLUX.1 Dev. Aceite na página do modelo.",
                    ImageModelState.Failed when e.Message == "checksum" => "Um arquivo chegou corrompido duas vezes. Tente de novo mais tarde.",
                    _ => "Não foi possível instalar o modelo.",
                };
                r.Resume = e.State is not (ImageModelState.AuthRequired or ImageModelState.TermsRequired);
                SaveState();
                Trace?.Invoke($"instalação de {m.Id} parou: {e.Message}");
                Changed?.Invoke();
                return;
            }
            catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException)
            {
                // rede caiu / servidor instável: espera e continua do ponto (os .part ficam)
                p.State = ImageModelState.Downloading;
                p.Error = "network";
                p.Detail = "Sem conexão com o Hugging Face. Continuando assim que a internet voltar…";
                p.BytesPerSecond = 0;
                Trace?.Invoke($"instalação de {m.Id}: {e.GetType().Name} ({e.Message}); nova tentativa em {backoff.TotalSeconds:0} s");
                Changed?.Invoke();
                if (e is IOException io && IsDiskFull(io))
                {
                    p.State = ImageModelState.NoSpace;
                    p.Error = "no_space";
                    p.Detail = "O disco encheu durante a instalação. Libere espaço e toque em Instalar para continuar.";
                    r.Resume = false;
                    SaveState();
                    Changed?.Invoke();
                    return;
                }
                await Task.Delay(backoff, ct).ContinueWith(_ => { });
                if (ct.IsCancellationRequested) continue;
                backoff = TimeSpan.FromSeconds(Math.Min(60, backoff.TotalSeconds * 2));
            }
        }
    }

    private static bool IsDiskFull(IOException e) => (e.HResult & 0xFFFF) is 0x70 or 0x27;

    private void Tick(Progress p)
    {
        var now = DateTimeOffset.Now;
        var dt = (now - p.LastTick).TotalSeconds;
        if (dt <= 0) return;
        var done = Interlocked.Read(ref p.Done);
        var inst = (done - p.LastBytes) / dt;
        p.BytesPerSecond = p.BytesPerSecond <= 0 ? inst : p.BytesPerSecond * 0.7 + inst * 0.3;
        p.LastBytes = done;
        p.LastTick = now;
        if (p.Error == "network" && inst > 0) { p.Error = null; p.Detail = null; }
        Changed?.Invoke();
    }

    private async Task EnsureFileAsync(ImageModelDef m, string revision, string dir, RepoFile f, InstalledModel rec, string? token, Progress p, CancellationToken ct)
    {
        var final = Path.Combine(dir, f.Path.Replace('/', '\\'));
        Directory.CreateDirectory(Path.GetDirectoryName(final)!);
        if (File.Exists(final))
        {
            var fi = new FileInfo(final);
            FileRecord? known;
            lock (_lock) rec.Files.TryGetValue(f.Path, out known);
            if (fi.Length == f.Size && known is not null && known.VerifiedMtime == fi.LastWriteTimeUtc.Ticks &&
                (known.Sha256 ?? known.GitOid) == (f.Sha256 ?? f.GitOid))
            {
                Interlocked.Add(ref p.Done, f.Size);
                Interlocked.Increment(ref p.FilesDone);
                return;
            }
            if (fi.Length == f.Size && await VerifyAsync(final, f, p, ct))
            {
                Record(rec, f, fi);
                Interlocked.Increment(ref p.FilesDone);
                return;
            }
            // arquivo antigo ou corrompido: baixa de novo
            File.Delete(final);
        }
        for (var attempt = 0; ; attempt++)
        {
            await DownloadAsync(m, revision, f, final + ".part", token, p, ct);
            if (await VerifyAsync(final + ".part", f, p, ct, countBytes: false))
            {
                File.Move(final + ".part", final, overwrite: true);
                Record(rec, f, new FileInfo(final));
                Interlocked.Increment(ref p.FilesDone);
                return;
            }
            Trace?.Invoke($"{m.Id}/{f.Path}: hash não confere (tentativa {attempt + 1}); baixando de novo");
            Interlocked.Add(ref p.Done, -f.Size);
            File.Delete(final + ".part");
            if (attempt >= 1) throw new ImageStoreException(ImageModelState.Failed, "checksum");
        }
    }

    private void Record(InstalledModel rec, RepoFile f, FileInfo fi)
    {
        lock (_lock) rec.Files[f.Path] = new FileRecord { Size = f.Size, Sha256 = f.Sha256, GitOid = f.GitOid, VerifiedMtime = fi.LastWriteTimeUtc.Ticks };
        SaveState();
    }

    private async Task DownloadAsync(ImageModelDef m, string revision, RepoFile f, string part, string? token, Progress p, CancellationToken ct)
    {
        var have = File.Exists(part) ? new FileInfo(part).Length : 0;
        if (have > f.Size) { File.Delete(part); have = 0; }
        Interlocked.Add(ref p.Done, have);
        if (have == f.Size) return;
        // a URL "resolve" é pedida de novo a cada tentativa: o link assinado da CDN expira
        var url = $"https://huggingface.co/{m.Repo}/resolve/{revision}/{Uri.EscapeDataString(f.Path).Replace("%2F", "/")}";
        using var req = Req(HttpMethod.Get, url, token);
        if (have > 0) req.Headers.Range = new RangeHeaderValue(have, null);
        using var res = await _http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead, ct);
        if (res.StatusCode == HttpStatusCode.Unauthorized) throw new ImageStoreException(ImageModelState.AuthRequired, "auth_required");
        if (res.StatusCode == HttpStatusCode.Forbidden) throw new ImageStoreException(ImageModelState.TermsRequired, "terms_required");
        if (res.StatusCode == HttpStatusCode.RequestedRangeNotSatisfiable) { File.Delete(part); Interlocked.Add(ref p.Done, -have); throw new IOException("range"); }
        res.EnsureSuccessStatusCode();
        var append = have > 0 && res.StatusCode == HttpStatusCode.PartialContent;
        if (have > 0 && !append) { Interlocked.Add(ref p.Done, -have); have = 0; }
        await using var body = await res.Content.ReadAsStreamAsync(ct);
        await using var fs = new FileStream(part, append ? FileMode.Append : FileMode.Create, FileAccess.Write, FileShare.Read, 1 << 20, true);
        var buf = new byte[1 << 20];
        using var idle = CancellationTokenSource.CreateLinkedTokenSource(ct);
        while (true)
        {
            // sem receber nada por 60 s: considera a conexão morta (a próxima tentativa continua do ponto)
            idle.CancelAfter(TimeSpan.FromSeconds(60));
            int n;
            try { n = await body.ReadAsync(buf, idle.Token); }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new IOException("stalled"); }
            if (n == 0) break;
            await fs.WriteAsync(buf.AsMemory(0, n), ct);
            Interlocked.Add(ref p.Done, n);
        }
        await fs.FlushAsync(ct);
        if (fs.Length != f.Size) throw new IOException($"incompleto ({fs.Length}/{f.Size})");
    }

    /// <summary>Confere o arquivo: SHA-256 (LFS) ou o hash git (arquivos pequenos). Sem hash conhecido, confere o tamanho.</summary>
    private static async Task<bool> VerifyAsync(string path, RepoFile f, Progress p, CancellationToken ct, bool countBytes = true)
    {
        var fi = new FileInfo(path);
        if (fi.Length != f.Size) return false;
        if (f.Sha256 is null && f.GitOid is null) { if (countBytes) Interlocked.Add(ref p.Done, f.Size); return true; }
        var before = p.State;
        if (countBytes && f.Size > 64 * 1024 * 1024) p.State = ImageModelState.Verifying;
        using HashAlgorithm h = f.Sha256 is not null ? SHA256.Create() : SHA1.Create();
        if (f.Sha256 is null)
        {
            var header = Encoding.ASCII.GetBytes($"blob {f.Size}\0");
            h.TransformBlock(header, 0, header.Length, null, 0);
        }
        await using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1 << 20, true))
        {
            var buf = new byte[4 << 20];
            int n;
            while ((n = await fs.ReadAsync(buf, ct)) > 0)
            {
                h.TransformBlock(buf, 0, n, null, 0);
                if (countBytes) Interlocked.Add(ref p.Done, n);
            }
        }
        h.TransformFinalBlock([], 0, 0);
        if (countBytes && p.State == ImageModelState.Verifying) p.State = before == ImageModelState.Verifying ? ImageModelState.Downloading : before;
        var hex = Convert.ToHexString(h.Hash!).ToLowerInvariant();
        var ok = hex == (f.Sha256 ?? f.GitOid);
        if (!ok && countBytes) Interlocked.Add(ref p.Done, -f.Size);
        return ok;
    }

    /// <summary>Confere de novo tudo que está instalado (Modelo → Detalhes → Verificar).</summary>
    public async Task<bool> VerifyInstalledAsync(ImageModelDef m, CancellationToken ct)
    {
        var r = Rec(m);
        var dir = InstalledDir(m);
        if (dir is null) return false;
        var p = new Progress { State = ImageModelState.Verifying, Total = r.TotalBytes, FilesTotal = r.Files.Count };
        lock (_lock) _progress[m.Id] = p;
        Changed?.Invoke();
        var ok = true;
        foreach (var (path, fr) in r.Files)
        {
            var full = Path.Combine(dir, path.Replace('/', '\\'));
            if (!File.Exists(full) || !await VerifyAsync(full, new RepoFile(path, fr.Size, fr.Sha256, fr.GitOid), p, ct)) { ok = false; fr.VerifiedMtime = 0; }
            p.FilesDone++;
        }
        r.Complete = ok;
        r.VerifiedAt = DateTimeOffset.Now;
        SaveState();
        p.State = ok ? ImageModelState.Installed : ImageModelState.Failed;
        p.Error = ok ? null : "checksum";
        p.Detail = ok ? null : "Arquivos do modelo corrompidos ou ausentes. Toque em Instalar para reparar (só o que falta é baixado).";
        Changed?.Invoke();
        return ok;
    }

    /// <summary>Remove o modelo (arquivos e otimizações derivadas).</summary>
    public void Delete(ImageModelDef m)
    {
        Pause(m);
        var r = Rec(m);
        try { Directory.Delete(Path.Combine(ImagePaths.Models, m.Id), true); } catch (Exception) { }
        try { Directory.Delete(Path.Combine(ImagePaths.Cache, m.Id), true); } catch (Exception) { }
        lock (_lock)
        {
            _state.Models.Remove(m.Id);
            _progress.Remove(m.Id);
        }
        SaveState();
        Activity?.Invoke($"{m.Name} removido do PC", "model");
        Changed?.Invoke();
        _ = r;
    }

    public static string Gb(long bytes) => (bytes / 1e9).ToString(bytes < 10e9 ? "0.0" : "0", System.Globalization.CultureInfo.GetCultureInfo("pt-BR")) + " GB";

    public void Dispose()
    {
        lock (_lock) foreach (var c in _running.Values) c.Cancel();
        _http.Dispose();
    }
}

public sealed class ImageStoreException(ImageModelState state, string message) : Exception(message)
{
    public ImageModelState State { get; } = state;
}
