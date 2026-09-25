using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.LmStudio;

namespace Noc.Core.Jobs;

public enum JobState { Starting, Loading, Generating, Finished }

/// <summary>
/// Uma geração em andamento. A geração pertence ao PC, não à conexão:
/// se o celular cair, ela continua e o celular retoma a partir do último seq recebido.
/// </summary>
public sealed class ChatJob
{
    private readonly Lock _lock = new();
    private readonly List<JsonObject> _events = [];
    private readonly List<Action<JsonObject>> _subscribers = [];
    private readonly StringBuilder _pendingR = new();
    private readonly StringBuilder _pendingC = new();
    private int _seq;
    internal readonly CancellationTokenSource Cts = new();

    public required string Id { get; init; }
    public required string Model { get; init; }
    public required string DeviceId { get; init; }
    public DateTimeOffset CreatedAt { get; } = DateTimeOffset.Now;
    public DateTimeOffset? FinishedAt { get; private set; }
    public JobState State { get; private set; } = JobState.Starting;
    public int Tokens { get; private set; }
    public long ContentChars { get; private set; }
    public double? LiveTokensPerSecond { get; private set; }
    internal Stopwatch Clock { get; } = Stopwatch.StartNew();
    internal long? FirstTokenMs { get; set; }

    public bool IsFinished => State == JobState.Finished;

    internal void SetState(JobState s, string? phase = null, JsonObject? extra = null)
    {
        State = s;
        if (phase is null) return;
        var e = extra ?? new JsonObject();
        e["phase"] = phase;
        Publish(e);
    }

    internal void Append(string? reasoning, string? content)
    {
        lock (_lock)
        {
            if (reasoning is not null) _pendingR.Append(reasoning);
            if (content is not null)
            {
                _pendingC.Append(content);
                ContentChars += content.Length;
            }
            Tokens++;
            if (FirstTokenMs is { } ft)
            {
                var secs = (Clock.ElapsedMilliseconds - ft) / 1000.0;
                if (secs > 0.25) LiveTokensPerSecond = Tokens / secs;
            }
        }
    }

    /// <summary>Envia os deltas acumulados como um evento (chamado a cada ~40 ms).</summary>
    internal void Flush()
    {
        JsonObject? e = null;
        lock (_lock)
        {
            if (_pendingR.Length == 0 && _pendingC.Length == 0) return;
            e = new JsonObject();
            if (_pendingR.Length > 0) { e["r"] = _pendingR.ToString(); _pendingR.Clear(); }
            if (_pendingC.Length > 0) { e["c"] = _pendingC.ToString(); _pendingC.Clear(); }
            e["n"] = Tokens;
            if (LiveTokensPerSecond is { } tps) e["tps"] = Math.Round(tps, 1);
        }
        Publish(e);
    }

    internal void Finish(JsonObject end)
    {
        Flush();
        FinishedAt = DateTimeOffset.Now;
        State = JobState.Finished;
        Publish(new JsonObject { ["end"] = end });
    }

    private void Publish(JsonObject e)
    {
        Action<JsonObject>[] subs;
        lock (_lock)
        {
            e["job"] = Id;
            e["seq"] = ++_seq;
            _events.Add(e);
            subs = [.. _subscribers];
        }
        foreach (var s in subs)
        {
            try { s((JsonObject)e.DeepClone()); } catch { /* sessão caiu: ela mesma se remove */ }
        }
    }

    /// <summary>Reenvia tudo depois de <paramref name="fromSeq"/> e passa a enviar ao vivo, sem buracos.</summary>
    public IDisposable Subscribe(int fromSeq, Action<JsonObject> onEvent)
    {
        lock (_lock)
        {
            foreach (var e in _events.Where(e => e["seq"]!.GetValue<int>() > fromSeq))
                onEvent((JsonObject)e.DeepClone());
            _subscribers.Add(onEvent);
        }
        return new Unsubscriber(this, onEvent);
    }

    public int LastSeq { get { lock (_lock) return _seq; } }

    /** Todos os eventos (para gravar em disco quando o job termina). */
    internal JsonArray Snapshot()
    {
        lock (_lock)
        {
            var arr = new JsonArray();
            foreach (var e in _events) arr.Add(e.DeepClone());
            return arr;
        }
    }

    /** Recria um job já terminado a partir do disco (Companion reiniciou ou a memória foi liberada). */
    internal static ChatJob Restore(string id, string model, string device, DateTimeOffset finishedAt, JsonArray events)
    {
        var job = new ChatJob { Id = id, Model = model, DeviceId = device };
        foreach (var e in events.OfType<JsonObject>()) job._events.Add((JsonObject)e.DeepClone());
        job._seq = job._events.Count == 0 ? 0 : job._events[^1]["seq"]!.GetValue<int>();
        job.State = JobState.Finished;
        job.FinishedAt = finishedAt;
        return job;
    }

    public void Cancel() => Cts.Cancel();

    private sealed class Unsubscriber(ChatJob job, Action<JsonObject> a) : IDisposable
    {
        public void Dispose() { lock (job._lock) job._subscribers.Remove(a); }
    }
}

public sealed class JobManager : IDisposable
{
    private const int MaxActive = 4;
    /// <summary>
    /// Por quanto tempo uma resposta pronta espera o celular buscá-la. Longo de propósito: o celular pode ficar
    /// horas sem rede, e a resposta não pode se perder. Na memória ficam as recentes; no disco, cifradas (DPAPI).
    /// </summary>
    private static readonly TimeSpan Retention = TimeSpan.FromHours(24);
    private static readonly TimeSpan MemoryRetention = TimeSpan.FromMinutes(30);
    private const int MaxFinishedInMemory = 60;
    private const long MaxDiskBytes = 200L * 1024 * 1024;
    private static readonly byte[] Entropy = "Noc/jobs/v1"u8.ToArray();
    private static string JobDir => Path.Combine(Storage.AppPaths.Root, "jobs");

    private readonly LmStudioClient _lm;
    private readonly ModelManager _models;
    private readonly Dictionary<string, ChatJob> _jobs = new();
    private readonly Lock _lock = new();
    private readonly Timer _flushTimer;
    private readonly Timer _sweepTimer;

    public event Action? Changed;

    public JobManager(LmStudioClient lm, ModelManager models)
    {
        _lm = lm;
        _models = models;
        _flushTimer = new Timer(_ => FlushAll(), null, 40, 40);
        _sweepTimer = new Timer(_ => Sweep(), null, TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(1));
    }

    public IReadOnlyList<ChatJob> Active
    {
        get { lock (_lock) return _jobs.Values.Where(j => !j.IsFinished).ToList(); }
    }

    public ChatJob? Get(string id)
    {
        lock (_lock)
        {
            if (_jobs.TryGetValue(id, out var j)) return j;
        }
        var restored = LoadFromDisk(id);
        if (restored is null) return null;
        lock (_lock) _jobs.TryAdd(id, restored);
        return restored;
    }

    private static string SafeName(string id) => new string(id.Where(c => char.IsLetterOrDigit(c) || c == '-').ToArray());

    private static void SaveToDisk(ChatJob job)
    {
        try
        {
            Directory.CreateDirectory(JobDir);
            var doc = new JsonObject
            {
                ["id"] = job.Id, ["model"] = job.Model, ["device"] = job.DeviceId,
                ["finishedAt"] = (job.FinishedAt ?? DateTimeOffset.Now).ToUnixTimeMilliseconds(),
                ["events"] = job.Snapshot(),
            };
            var plain = System.Text.Encoding.UTF8.GetBytes(doc.ToJsonString());
            var sealedBytes = System.Security.Cryptography.ProtectedData.Protect(plain, Entropy, System.Security.Cryptography.DataProtectionScope.CurrentUser);
            Storage.AppPaths.WriteAtomic(Path.Combine(JobDir, SafeName(job.Id) + ".job"), sealedBytes);
        }
        catch (Exception)
        {
            // sem disco: a resposta ainda fica na memória
        }
    }

    private static ChatJob? LoadFromDisk(string id)
    {
        try
        {
            var path = Path.Combine(JobDir, SafeName(id) + ".job");
            if (!File.Exists(path)) return null;
            var plain = System.Security.Cryptography.ProtectedData.Unprotect(File.ReadAllBytes(path), Entropy, System.Security.Cryptography.DataProtectionScope.CurrentUser);
            var doc = JsonNode.Parse(plain)!.AsObject();
            var finished = DateTimeOffset.FromUnixTimeMilliseconds(doc["finishedAt"]!.GetValue<long>());
            if (DateTimeOffset.Now - finished > Retention) { File.Delete(path); return null; }
            return ChatJob.Restore(doc["id"]!.GetValue<string>(), doc["model"]!.GetValue<string>(), doc["device"]!.GetValue<string>(), finished, doc["events"]!.AsArray());
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>
    /// Inicia (ou devolve, se já existir) o job com este id. Idempotente: reenviar o mesmo pedido
    /// depois de uma queda não gera duas respostas.
    /// </summary>
    public (ChatJob Job, bool Existing) Start(string id, string deviceId, string model, int? contextLength, JsonObject request)
    {
        lock (_lock)
        {
            if (_jobs.TryGetValue(id, out var existing)) return (existing, true);
            if (_jobs.Values.Count(j => !j.IsFinished) >= MaxActive)
                throw new LmStudioException("busy", "Muitas gerações simultâneas");
            var job = new ChatJob { Id = id, Model = model, DeviceId = deviceId };
            _jobs[id] = job;
            _ = Task.Run(() => RunAsync(job, contextLength, request));
            Changed?.Invoke();
            return (job, false);
        }
    }

    private async Task RunAsync(ChatJob job, int? contextLength, JsonObject request)
    {
        var ct = job.Cts.Token;
        string reason = "stop";
        JsonObject? usage = null;
        string? errorCode = null, errorMsg = null;
        var context = 0;
        try
        {
            await _models.RefreshAsync(ct);
            var current = _models.Find(job.Model);
            // O contexto pedido só vale se for preciso carregar: nunca recarrega um modelo já pronto
            // no meio de uma conversa (isso levaria dezenas de segundos). Recarregar é ação explícita.
            int? wantContext = null;
            if (current is null || !current.Loaded)
            {
                wantContext = contextLength;
                job.SetState(JobState.Loading, "loading", new JsonObject { ["model"] = job.Model });
            }
            var (instanceId, ctx) = await _models.EnsureLoadedAsync(job.Model, wantContext, ct);
            context = ctx;
            request["model"] = instanceId;
            job.SetState(JobState.Generating, "generating", new JsonObject { ["context"] = ctx });
            job.Clock.Restart();
            Changed?.Invoke();

            await foreach (var d in _lm.StreamChatAsync(request, ct))
            {
                if (d.Reasoning is not null || d.Content is not null)
                {
                    job.FirstTokenMs ??= job.Clock.ElapsedMilliseconds;
                    job.Append(d.Reasoning, d.Content);
                }
                if (d.FinishReason is not null) reason = d.FinishReason;
                if (d.Usage is not null) usage = d.Usage;
            }
        }
        catch (OperationCanceledException)
        {
            reason = "cancelled";
        }
        catch (LmStudioException e)
        {
            reason = "error";
            errorCode = e.Code;
            errorMsg = e.Message;
        }
        catch (Exception e)
        {
            reason = "error";
            errorCode = "internal";
            errorMsg = e.Message;
        }

        var totalMs = job.Clock.ElapsedMilliseconds;
        var end = new JsonObject { ["reason"] = reason, ["context"] = context };
        var stats = new JsonObject { ["totalMs"] = totalMs, ["tokens"] = job.Tokens };
        if (job.FirstTokenMs is { } ft)
        {
            stats["ttftMs"] = ft;
            var genSecs = (totalMs - ft) / 1000.0;
            var completion = usage?["completion_tokens"]?.GetValue<int>() ?? job.Tokens;
            if (genSecs > 0.05) stats["tps"] = Math.Round(completion / genSecs, 1);
        }
        if (usage is not null)
        {
            stats["promptTokens"] = usage["prompt_tokens"]?.GetValue<int>();
            stats["completionTokens"] = usage["completion_tokens"]?.GetValue<int>();
            stats["reasoningTokens"] = usage["completion_tokens_details"]?["reasoning_tokens"]?.GetValue<int>();
        }
        end["stats"] = stats;
        if (errorCode is not null)
        {
            end["error"] = errorCode;
            end["detail"] = errorMsg;
        }
        job.Finish(end);
        SaveToDisk(job);
        Changed?.Invoke();
    }

    private void FlushAll()
    {
        ChatJob[] active;
        lock (_lock) active = _jobs.Values.Where(j => j.State == JobState.Generating).ToArray();
        foreach (var j in active) j.Flush();
    }

    private void Sweep()
    {
        lock (_lock)
        {
            var now = DateTimeOffset.Now;
            // memória: só as recentes (o disco guarda o resto por 24 h)
            foreach (var j in _jobs.Values.Where(j => j.FinishedAt is { } f && now - f > MemoryRetention).ToList())
                _jobs.Remove(j.Id);
            foreach (var j in _jobs.Values.Where(j => j.IsFinished).OrderByDescending(j => j.CreatedAt).Skip(MaxFinishedInMemory).ToList())
                _jobs.Remove(j.Id);
        }
        try
        {
            if (!Directory.Exists(JobDir)) return;
            var files = new DirectoryInfo(JobDir).GetFiles("*.job").OrderByDescending(f => f.LastWriteTimeUtc).ToList();
            long total = 0;
            foreach (var f in files)
            {
                total += f.Length;
                if (DateTime.UtcNow - f.LastWriteTimeUtc > Retention || total > MaxDiskBytes) f.Delete();
            }
        }
        catch (Exception) { }
    }

    public void Dispose()
    {
        _flushTimer.Dispose();
        _sweepTimer.Dispose();
        lock (_lock) foreach (var j in _jobs.Values) j.Cancel();
    }
}
