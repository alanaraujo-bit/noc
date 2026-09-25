using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.LmStudio;
using Noc.Core.Sessions;
using Noc.Core.Storage;

namespace Noc.Core.Jobs;

public enum JobState { Queued, Loading, Preparing, Generating, Finished }

/// <summary>Erro previsível de uma tarefa, com código para o celular e texto para o usuário.</summary>
public sealed class JobFailure(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

/// <summary>
/// Uma tarefa de geração. Pertence ao PC, não à conexão: se o celular cair, ela continua, e o celular
/// retoma a partir do último seq recebido. O pedido e o progresso ficam num diário cifrado em disco,
/// então nem um reinício do Companion perde a tarefa.
/// </summary>
public sealed class ChatJob
{
    private readonly Lock _lock = new();
    private readonly List<JsonObject> _events = [];
    private readonly List<Action<JsonObject>> _subscribers = [];
    private readonly StringBuilder _pendingR = new();
    private readonly StringBuilder _pendingC = new();
    private readonly StringBuilder _content = new();
    private readonly StringBuilder _reasoning = new();
    private int _seq;
    internal CancellationTokenSource Cts = new();

    public required string Id { get; init; }
    public required string Model { get; set; }
    public required string DeviceId { get; init; }
    public string? Tier { get; init; }
    public int? ContextLength { get; init; }
    /// <summary>Pedido (com imagens por referência). Null em tarefas restauradas já terminadas.</summary>
    public JsonObject? Request { get; init; }
    /// <summary>Dados opacos do celular (conversa/mensagem), devolvidos na lista de tarefas.</summary>
    public JsonObject? Meta { get; init; }
    /// <summary>Resumo curto do pedido para a lista de atividades.</summary>
    public string? Label { get; init; }
    public bool Background { get; init; }
    public DateTimeOffset CreatedAt { get; init; } = DateTimeOffset.Now;
    public DateTimeOffset? StartedAt { get; private set; }
    public DateTimeOffset? FinishedAt { get; private set; }
    public JobState State { get; private set; } = JobState.Queued;
    /// <summary>Etapa visível: queued | loading | preparing | thinking | generating.</summary>
    public string Phase { get; private set; } = "queued";
    public int Tokens { get; private set; }
    public double? LiveTokensPerSecond { get; private set; }
    public string? EndReason { get; private set; }
    public string? EndError { get; private set; }
    public JsonObject? EndStats { get; private set; }
    public int Images { get; init; }
    public int Attempts { get; set; }
    internal Stopwatch Clock { get; } = new();
    internal long? FirstTokenMs { get; set; }
    internal DateTimeOffset LastActivity { get; set; } = DateTimeOffset.Now;
    internal bool CancelRequested { get; set; }
    internal DateTimeOffset LastCheckpoint { get; set; } = DateTimeOffset.MinValue;
    /// <summary>Tokens que já existiam quando esta rodada começou (a velocidade ao vivo conta só os novos).</summary>
    internal int BaseTokens { get; set; }

    public bool IsFinished => State == JobState.Finished;
    public string Content { get { lock (_lock) return _content.ToString(); } }
    public string Reasoning { get { lock (_lock) return _reasoning.ToString(); } }

    internal void SetPhase(JobState s, string phase, JsonObject? extra = null)
    {
        State = s;
        if (s is JobState.Loading or JobState.Preparing && StartedAt is null) StartedAt = DateTimeOffset.Now;
        if (Phase == phase && extra is null) return;
        Phase = phase;
        var e = extra ?? new JsonObject();
        e["phase"] = phase;
        Publish(e);
    }

    /// <summary>Posição na fila mudou: avisa só quem está acompanhando (não vai para o diário).</summary>
    internal int? LastAhead { get; set; }

    internal void AnnounceQueue(int ahead)
    {
        if (LastAhead == ahead && Phase == "queued") return;
        LastAhead = ahead;
        Phase = "queued";
        Publish(new JsonObject { ["phase"] = "queued", ["ahead"] = ahead });
    }

    internal void Append(string? reasoning, string? content)
    {
        lock (_lock)
        {
            if (reasoning is not null) { _pendingR.Append(reasoning); _reasoning.Append(reasoning); }
            if (content is not null) { _pendingC.Append(content); _content.Append(content); }
            Tokens++;
            LastActivity = DateTimeOffset.Now;
            if (FirstTokenMs is { } ft)
            {
                var secs = (Clock.ElapsedMilliseconds - ft) / 1000.0;
                if (secs > 0.25) LiveTokensPerSecond = (Tokens - BaseTokens) / secs;
            }
        }
    }

    /// <summary>Envia os deltas acumulados como um evento (chamado a cada ~40 ms).</summary>
    internal void Flush()
    {
        JsonObject? e;
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

    /// <summary>
    /// Depois de um reinício do Companion, o diário pode estar alguns segundos atrás do que o celular já viu.
    /// Pula a numeração bem para frente e manda o texto completo que vale: o celular substitui a cópia dele.
    /// </summary>
    internal void Rebase(string why)
    {
        JsonObject e;
        lock (_lock)
        {
            _seq += 10_000;
            e = new JsonObject { ["reset"] = true, ["why"] = why, ["n"] = Tokens };
            if (_reasoning.Length > 0) e["r"] = _reasoning.ToString();
            if (_content.Length > 0) e["c"] = _content.ToString();
        }
        Publish(e);
    }

    /// <summary>Recomeça do zero (antes de sair conteúdo): o celular descarta o raciocínio parcial.</summary>
    internal void Reset(string why, bool bumpSeq = false)
    {
        lock (_lock)
        {
            if (bumpSeq) _seq += 10_000;
            _pendingR.Clear(); _pendingC.Clear(); _content.Clear(); _reasoning.Clear();
            Tokens = 0;
            FirstTokenMs = null;
            LiveTokensPerSecond = null;
        }
        Publish(new JsonObject { ["reset"] = true, ["why"] = why });
    }

    internal void Finish(JsonObject end)
    {
        Flush();
        FinishedAt = DateTimeOffset.Now;
        State = JobState.Finished;
        EndReason = end["reason"]?.GetValue<string>();
        EndError = end["error"]?.GetValue<string>();
        EndStats = end["stats"]?.DeepClone() as JsonObject;
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
    public int Subscribers { get { lock (_lock) return _subscribers.Count; } }

    internal JsonArray Snapshot()
    {
        lock (_lock)
        {
            var arr = new JsonArray();
            foreach (var e in _events) arr.Add(e.DeepClone());
            return arr;
        }
    }

    /// <summary>Recria a tarefa a partir do diário (Companion reiniciou ou a memória foi liberada).</summary>
    internal static ChatJob Restore(JsonObject doc)
    {
        var job = new ChatJob
        {
            Id = doc["id"]!.GetValue<string>(), Model = doc["model"]!.GetValue<string>(), DeviceId = doc["device"]!.GetValue<string>(),
            Tier = doc["tier"]?.GetValue<string>(), ContextLength = doc["context"]?.GetValue<int>(),
            Request = doc["request"]?.DeepClone() as JsonObject, Meta = doc["meta"]?.DeepClone() as JsonObject,
            Label = doc["label"]?.GetValue<string>(), Images = doc["images"]?.GetValue<int>() ?? 0,
            Background = doc["background"]?.GetValue<bool>() ?? false,
            CreatedAt = DateTimeOffset.FromUnixTimeMilliseconds(doc["createdAt"]?.GetValue<long>() ?? 0),
        };
        job.Attempts = doc["attempts"]?.GetValue<int>() ?? 0;
        foreach (var e in (doc["events"] as JsonArray ?? []).OfType<JsonObject>())
        {
            var ev = (JsonObject)e.DeepClone();
            job._events.Add(ev);
            if (ev["reset"] is not null) { job._content.Clear(); job._reasoning.Clear(); job.Tokens = 0; }
            if (ev["r"]?.GetValue<string>() is { } r) job._reasoning.Append(r);
            if (ev["c"]?.GetValue<string>() is { } c) job._content.Append(c);
            if (ev["n"]?.GetValue<int>() is { } n) job.Tokens = n;
            if (ev["phase"]?.GetValue<string>() is { } ph && ph != "queued") job.Phase = ph;
            if (ev["end"] is JsonObject end)
            {
                job.State = JobState.Finished;
                job.EndReason = end["reason"]?.GetValue<string>();
                job.EndError = end["error"]?.GetValue<string>();
                job.EndStats = end["stats"]?.DeepClone() as JsonObject;
            }
        }
        job._seq = job._events.Count == 0 ? 0 : job._events[^1]["seq"]!.GetValue<int>();
        if (doc["startedAt"]?.GetValue<long>() is { } st) job.StartedAt = DateTimeOffset.FromUnixTimeMilliseconds(st);
        if (doc["finishedAt"]?.GetValue<long>() is { } ft) job.FinishedAt = DateTimeOffset.FromUnixTimeMilliseconds(ft);
        if (job.State != JobState.Finished) { job.State = JobState.Queued; job.Phase = "queued"; }
        return job;
    }

    internal JsonObject ToDiary() => new()
    {
        ["v"] = 2, ["id"] = Id, ["model"] = Model, ["device"] = DeviceId, ["tier"] = Tier, ["context"] = ContextLength,
        ["request"] = IsFinished ? null : Request?.DeepClone(), ["meta"] = Meta?.DeepClone(), ["label"] = Label, ["images"] = Images,
        ["background"] = Background, ["attempts"] = Attempts,
        ["createdAt"] = CreatedAt.ToUnixTimeMilliseconds(), ["startedAt"] = StartedAt?.ToUnixTimeMilliseconds(),
        ["finishedAt"] = FinishedAt?.ToUnixTimeMilliseconds(), ["events"] = Snapshot(),
    };

    public void Cancel()
    {
        CancelRequested = true;
        try { Cts.Cancel(); } catch (ObjectDisposedException) { }
    }

    private sealed class Unsubscriber(ChatJob job, Action<JsonObject> a) : IDisposable
    {
        public void Dispose() { lock (job._lock) job._subscribers.Remove(a); }
    }
}

/// <summary>
/// Fila e execução das gerações. Regras:
/// - ordem de chegada; tarefas seguidas do mesmo modelo rodam juntas (até 2 ao mesmo tempo);
/// - uma tarefa que precisa de outro modelo espera as atuais terminarem (nada de trocar modelo no meio);
/// - diário cifrado (DPAPI) com checkpoints: sobrevive a reinício do Companion, do LM Studio ou do modelo;
/// - recuperação automática quando dá (recomeça ou continua do ponto), senão "interrompida" com o parcial.
/// </summary>
public sealed class JobManager : IDisposable
{
    public const int MaxParallelSameModel = 2;
    private const int MaxQueued = 32;
    private const int MaxAutoRecover = 3;
    private static readonly TimeSpan Retention = TimeSpan.FromHours(24);
    private static readonly TimeSpan MemoryRetention = TimeSpan.FromMinutes(30);
    private static readonly TimeSpan StallTimeout = TimeSpan.FromMinutes(3);
    private const int MaxFinishedInMemory = 80;
    private const long MaxDiskBytes = 300L * 1024 * 1024;
    private static readonly byte[] Entropy = "Noc/jobs/v1"u8.ToArray();
    private static string JobDir => Path.Combine(AppPaths.Root, "jobs");

    private readonly LmStudioClient _lm;
    private readonly ModelManager _models;
    private readonly BlobStore _blobs;
    private readonly Dictionary<string, ChatJob> _jobs = new();
    private readonly List<ChatJob> _queue = [];
    private readonly Lock _lock = new();
    private readonly Timer _flushTimer;
    private readonly Timer _sweepTimer;
    private readonly Timer _watchdog;
    private volatile bool _disposed;
    private volatile bool _paused;

    /// <summary>Segura a fila (ex.: teste de desempenho usando a GPU). As tarefas esperam, sem se perder.</summary>
    public bool Paused
    {
        get => _paused;
        set { _paused = value; if (!value) Pump(); }
    }

    public event Action? Changed;
    /// <summary>Linha para o registro de atividade/diagnóstico do PC (sem conteúdo das conversas).</summary>
    public event Action<string, string>? Log;
    /// <summary>Rastro técnico do ciclo de vida das tarefas (ids, tempos, estados; nunca conteúdo).</summary>
    public event Action<string>? Trace;

    private static string Short(string id) => id.Length > 8 ? id[..8] : id;

    public JobManager(LmStudioClient lm, ModelManager models, BlobStore blobs)
    {
        _lm = lm;
        _models = models;
        _blobs = blobs;
        _flushTimer = new Timer(_ => FlushAll(), null, 40, 40);
        _sweepTimer = new Timer(_ => Sweep(), null, TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(1));
        _watchdog = new Timer(_ => Watchdog(), null, TimeSpan.FromSeconds(15), TimeSpan.FromSeconds(15));
    }

    public IReadOnlyList<ChatJob> Active
    {
        get { lock (_lock) return _jobs.Values.Where(j => !j.IsFinished).OrderBy(j => j.CreatedAt).ToList(); }
    }

    public IReadOnlyList<ChatJob> Recent(TimeSpan window)
    {
        lock (_lock) return _jobs.Values.Where(j => !j.IsFinished || DateTimeOffset.Now - (j.FinishedAt ?? j.CreatedAt) < window)
            .OrderByDescending(j => j.CreatedAt).ToList();
    }

    public int QueuePosition(ChatJob job)
    {
        lock (_lock) return _queue.IndexOf(job);
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

    // ------------------------------------------------------------------ diário

    private static string SafeName(string id) => new(id.Where(c => char.IsLetterOrDigit(c) || c == '-').ToArray());

    private void SaveToDisk(ChatJob job)
    {
        try
        {
            Directory.CreateDirectory(JobDir);
            var plain = Encoding.UTF8.GetBytes(job.ToDiary().ToJsonString());
            var sealedBytes = ProtectedData.Protect(plain, Entropy, DataProtectionScope.CurrentUser);
            AppPaths.WriteAtomic(Path.Combine(JobDir, SafeName(job.Id) + ".job"), sealedBytes);
            job.LastCheckpoint = DateTimeOffset.Now;
        }
        catch (Exception)
        {
            // sem disco: a tarefa ainda fica na memória
        }
    }

    private static JsonObject? ReadDiary(string path)
    {
        try
        {
            var plain = ProtectedData.Unprotect(File.ReadAllBytes(path), Entropy, DataProtectionScope.CurrentUser);
            return JsonNode.Parse(plain)?.AsObject();
        }
        catch (Exception) { return null; }
    }

    private static ChatJob? LoadFromDisk(string id)
    {
        var path = Path.Combine(JobDir, SafeName(id) + ".job");
        if (!File.Exists(path)) return null;
        var doc = ReadDiary(path);
        if (doc is null) return null;
        // formato antigo (v1): só jobs terminados
        if (doc["v"] is null && doc["finishedAt"] is not null && doc["events"] is JsonArray)
        {
            doc["createdAt"] = doc["finishedAt"]!.GetValue<long>();
        }
        var job = ChatJob.Restore(doc);
        if (job.IsFinished && job.FinishedAt is { } f && DateTimeOffset.Now - f > Retention)
        {
            try { File.Delete(path); } catch { }
            return null;
        }
        return job;
    }

    /// <summary>
    /// Na partida: tarefas que estavam na fila voltam para a fila; as que estavam gerando retomam
    /// sozinhas (recomeçam ou continuam do ponto) quando for seguro, senão terminam como interrompidas.
    /// </summary>
    public void RecoverFromDisk()
    {
        if (!Directory.Exists(JobDir)) return;
        foreach (var f in new DirectoryInfo(JobDir).GetFiles("*.job"))
        {
            var doc = ReadDiary(f.FullName);
            if (doc is null || doc["v"]?.GetValue<int>() != 2) continue;
            var job = ChatJob.Restore(doc);
            if (job.IsFinished) continue;
            var age = DateTimeOffset.Now - job.CreatedAt;
            lock (_lock)
            {
                if (_jobs.ContainsKey(job.Id)) continue;
                _jobs[job.Id] = job;
            }
            var wasRunning = job.StartedAt is not null;
            if (job.Request is null || age > TimeSpan.FromMinutes(30) || job.Attempts >= MaxAutoRecover)
            {
                job.Finish(Interrupted("pc_restarted", "Seu PC foi reiniciado antes da conclusão.", job));
                SaveToDisk(job);
                continue;
            }
            job.Attempts++;
            Trace?.Invoke($"tarefa {Short(job.Id)} recuperada do diário (estava {(wasRunning ? "gerando" : "na fila")}, {job.Content.Length} caracteres prontos)");
            if (job.Content.Length == 0 && job.Tokens > 0) job.Reset("pc_restarted", bumpSeq: true);
            else if (wasRunning) job.Rebase("pc_restarted");
            Log?.Invoke(wasRunning ? "Retomando uma resposta interrompida pelo reinício do PC" : "Tarefa na fila restaurada após reinício", "task");
            job.SetPhase(JobState.Queued, "queued", new JsonObject { ["recovered"] = true });
            lock (_lock) _queue.Add(job);
        }
        Pump();
    }

    private static JsonObject Interrupted(string code, string msg, ChatJob job) => new()
    {
        ["reason"] = "interrupted", ["error"] = code, ["detail"] = msg,
        ["stats"] = new JsonObject { ["tokens"] = job.Tokens },
    };

    // ------------------------------------------------------------------ entrada

    /// <summary>
    /// Enfileira (ou devolve, se já existir) a tarefa com este id. Idempotente: reenviar o mesmo pedido
    /// depois de uma queda não gera duas respostas.
    /// </summary>
    public (ChatJob Job, bool Existing) Start(string id, string deviceId, string model, int? contextLength, JsonObject request,
        string? tier = null, JsonObject? meta = null, string? label = null, bool background = false)
    {
        var existing = Get(id);
        if (existing is not null) return (existing, true);
        ChatJob job;
        lock (_lock)
        {
            if (_jobs.TryGetValue(id, out var again)) return (again, true);
            if (_queue.Count >= MaxQueued) throw new LmStudioException("busy", "A fila do seu PC está cheia. Tente de novo em instantes.");
            job = new ChatJob
            {
                Id = id, Model = model, DeviceId = deviceId, ContextLength = contextLength, Request = request, Tier = tier,
                Meta = meta, Label = label, Background = background, Images = ChatRequestBuilder.CountImages(request),
            };
            _jobs[id] = job;
            _queue.Add(job);
        }
        SaveToDisk(job);
        Log?.Invoke($"Tarefa recebida ({_models.DisplayName(model)})", "task");
        Trace?.Invoke($"tarefa {Short(id)} criada: modelo={model} perfil={tier ?? "-"} imagens={job.Images} aparelho={Short(deviceId)} segundo_plano={background}");
        Pump();
        return (job, false);
    }

    public bool CancelQueued(ChatJob job)
    {
        lock (_lock)
        {
            if (!_queue.Remove(job)) return false;
        }
        Trace?.Invoke($"tarefa {Short(job.Id)} cancelada na fila");
        job.Finish(new JsonObject { ["reason"] = "cancelled", ["stats"] = new JsonObject { ["tokens"] = 0 } });
        SaveToDisk(job);
        Changed?.Invoke();
        Pump();
        return true;
    }

    public void Cancel(ChatJob job)
    {
        if (CancelQueued(job)) return;
        Trace?.Invoke($"tarefa {Short(job.Id)}: cancelamento pedido durante {job.Phase}");
        job.Cancel();
    }

    /// <summary>Passa a tarefa para a frente da fila (ela ainda respeita o que já está rodando).</summary>
    public bool Prioritize(ChatJob job)
    {
        lock (_lock)
        {
            if (!_queue.Remove(job)) return false;
            _queue.Insert(0, job);
        }
        Pump();
        return true;
    }

    // ------------------------------------------------------------------ agendamento

    private void Pump()
    {
        var toStart = new List<ChatJob>();
        // modelo que já está na GPU (tarefas de fundo nunca trocam de modelo: usam o que estiver carregado)
        var loadedLlm = _models.Last.Models.FirstOrDefault(m => m.Loaded && m.Type == "llm")?.Key;
        lock (_lock)
        {
            // o que a pessoa pediu vem antes de tarefas de fundo (títulos etc.), mantendo a ordem de chegada
            var ordered = _queue.OrderBy(j => j.Background ? 1 : 0).ToList();
            _queue.Clear();
            _queue.AddRange(ordered);
            var running = _jobs.Values.Where(j => !j.IsFinished && j.State != JobState.Queued).ToList();
            var runningModel = running.FirstOrDefault()?.Model;
            var slots = _paused ? 0 : MaxParallelSameModel - running.Count;
            foreach (var j in _queue.ToList())
            {
                if (j.Background)
                {
                    var target = runningModel ?? loadedLlm;
                    if (target is not null && j.Model != target)
                    {
                        Trace?.Invoke($"tarefa de fundo {Short(j.Id)} usa {target} em vez de trocar de modelo");
                        j.Model = target;
                    }
                }
                if (slots <= 0) break;
                // mesmo modelo que já está rodando (ou nada rodando): pode ir; outro modelo: espera a vez
                if (runningModel is not null && j.Model != runningModel) break;
                runningModel = j.Model;
                _queue.Remove(j);
                toStart.Add(j);
                slots--;
            }
            for (var i = 0; i < _queue.Count; i++)
            {
                var ahead = running.Count + toStart.Count + i;
                _queue[i].AnnounceQueue(ahead);
            }
        }
        foreach (var j in toStart)
        {
            Trace?.Invoke($"tarefa {Short(j.Id)} começou após {(DateTimeOffset.Now - j.CreatedAt).TotalMilliseconds:0} ms na fila");
            j.SetPhase(JobState.Loading, "starting");
            _ = Task.Run(() => RunAsync(j));
        }
        Changed?.Invoke();
    }

    private async Task RunAsync(ChatJob job)
    {
        if (job.Cts.IsCancellationRequested && !job.CancelRequested) job.Cts = new CancellationTokenSource();
        var ct = job.Cts.Token;
        string reason = "stop";
        JsonObject? usage = null;
        string? errorCode = null, errorMsg = null;
        var context = 0;
        var resumeFromContent = job.Content;
        try
        {
            await WaitForLmAsync(job, ct);
            var current = _models.Find(job.Model);
            // logo depois de ligar, o LM Studio pode levar alguns segundos para listar todos os modelos
            for (var i = 0; current is null && i < 10; i++)
            {
                await Task.Delay(1500, ct);
                await _models.RefreshAsync(ct);
                current = _models.Find(job.Model);
            }
            if (current is null) throw new JobFailure("model_not_found", "Este modelo não está mais no PC.");
            int? wantContext = null;
            if (!current.Loaded)
            {
                // O contexto pedido só vale se for preciso carregar: nunca recarrega um modelo pronto no meio da conversa.
                wantContext = job.ContextLength;
                var prefs = _models.Catalog.Prefs(job.Model);
                job.SetPhase(JobState.Loading, "loading", new JsonObject
                {
                    ["model"] = job.Model, ["name"] = _models.DisplayName(job.Model), ["expectedSeconds"] = prefs.LastLoadSeconds,
                });
                Changed?.Invoke();
            }
            var (instanceId, ctx) = await _models.EnsureLoadedAsync(job.Model, wantContext, ct, job.Tier);
            context = ctx;
            current = _models.Find(job.Model) ?? current;

            var request = ChatRequestBuilder.ResolveImages(job.Request!, _blobs.Get, current.Vision);
            request["model"] = instanceId;
            if (resumeFromContent.Length > 0)
            {
                // continua do ponto em que parou: a resposta parcial vira a última mensagem do assistente
                ((JsonArray)request["messages"]!).Add(new JsonObject { ["role"] = "assistant", ["content"] = resumeFromContent });
            }
            job.SetPhase(JobState.Preparing, "preparing", new JsonObject { ["context"] = ctx, ["images"] = job.Images, ["name"] = _models.DisplayName(job.Model) });
            job.Clock.Restart();
            job.FirstTokenMs = null;
            job.BaseTokens = job.Tokens;
            job.LastActivity = DateTimeOffset.Now;
            SaveToDisk(job);
            Changed?.Invoke();
            Log?.Invoke($"Gerando com {_models.DisplayName(job.Model)}", "task");

            await foreach (var d in _lm.StreamChatAsync(request, ct))
            {
                if (d.Reasoning is not null || d.Content is not null)
                {
                    job.FirstTokenMs ??= job.Clock.ElapsedMilliseconds;
                    if (d.Reasoning is not null && job.Phase != "thinking" && job.Content.Length == 0) job.SetPhase(JobState.Generating, "thinking");
                    if (d.Content is not null && job.Phase != "generating") job.SetPhase(JobState.Generating, "generating");
                    job.Append(d.Reasoning, d.Content);
                    if (DateTimeOffset.Now - job.LastCheckpoint > TimeSpan.FromSeconds(3)) { job.Flush(); SaveToDisk(job); }
                }
                if (d.FinishReason is not null) reason = d.FinishReason;
                if (d.Usage is not null) usage = d.Usage;
            }
        }
        catch (OperationCanceledException) when (job.CancelRequested)
        {
            reason = "cancelled";
        }
        catch (OperationCanceledException)
        {
            reason = "error";
            errorCode = "stalled";
            errorMsg = "O modelo parou de responder.";
        }
        catch (JobFailure e)
        {
            reason = "error";
            errorCode = e.Code;
            errorMsg = e.Message;
        }
        catch (LmStudioException e)
        {
            // LM Studio caiu ou o modelo foi descarregado no meio: tenta recuperar sozinho
            if (e.Code is "lm_unreachable" or "lm_error" or "lm_offline" && !job.CancelRequested && job.Attempts < MaxAutoRecover &&
                await TryRecoverAsync(job, e))
                return;
            reason = "error";
            errorCode = e.Code;
            errorMsg = e.Message;
        }
        catch (Exception e) when (!job.CancelRequested && !_disposed && e is IOException or HttpRequestException)
        {
            // conexão com o LM Studio caiu no meio do streaming (servidor reiniciou, modelo descarregado)
            if (job.Attempts < MaxAutoRecover && await TryRecoverAsync(job, new LmStudioException("lm_unreachable", e.Message)))
                return;
            reason = "error";
            errorCode = "lm_unreachable";
            errorMsg = "O LM Studio parou de responder no meio da resposta.";
        }
        catch (Exception e)
        {
            reason = "error";
            errorCode = "internal";
            errorMsg = e.Message;
        }

        if (_disposed) return; // desligando: o diário fica para a próxima partida retomar
        if (job.CancelRequested && reason == "error") { reason = "cancelled"; errorCode = null; errorMsg = null; }
        var totalMs = job.Clock.ElapsedMilliseconds;
        var end = new JsonObject { ["reason"] = reason, ["context"] = context, ["model"] = job.Model, ["name"] = _models.DisplayName(job.Model) };
        var stats = new JsonObject { ["totalMs"] = totalMs, ["tokens"] = job.Tokens };
        if (job.StartedAt is { } started) stats["wallMs"] = (long)(DateTimeOffset.Now - started).TotalMilliseconds;
        stats["queuedMs"] = (long)((job.StartedAt ?? DateTimeOffset.Now) - job.CreatedAt).TotalMilliseconds;
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
        Trace?.Invoke($"tarefa {Short(job.Id)} terminou: {reason}{(errorCode is null ? "" : "/" + errorCode)} tokens={job.Tokens} ttft={job.FirstTokenMs?.ToString() ?? "-"}ms total={totalMs}ms tentativas={job.Attempts}" +
                      (errorMsg is null ? "" : $" erro=\"{errorMsg}\""));
        Log?.Invoke(reason switch
        {
            "cancelled" => "Tarefa interrompida pelo usuário",
            "error" => $"Tarefa falhou: {errorMsg}",
            _ => $"Resposta concluída em {totalMs / 1000.0:0.0} s",
        }, reason == "error" ? "error" : "task");
        Changed?.Invoke();
        Pump();
    }

    /// <summary>Espera o LM Studio voltar e recoloca a tarefa na frente da fila (ela continua do ponto).</summary>
    private async Task<bool> TryRecoverAsync(ChatJob job, LmStudioException e)
    {
        job.Attempts++;
        Trace?.Invoke($"tarefa {Short(job.Id)} caiu ({e.Code}: {e.Message}); tentativa de recuperação {job.Attempts}");
        Log?.Invoke("A geração foi interrompida no PC; retomando sozinho", "warn");
        job.SetPhase(JobState.Loading, "recovering", new JsonObject { ["why"] = "lm" });
        await Task.Delay(1500); // a próxima rodada espera o LM Studio voltar (WaitForLmAsync)
        if (job.CancelRequested) return false;
        if (job.Content.Length == 0 && job.Tokens > 0) job.Reset("recovering");
        job.Cts = new CancellationTokenSource();
        lock (_lock) _queue.Insert(0, job);
        job.SetPhase(JobState.Queued, "queued");
        Pump();
        return true;
    }

    /// <summary>
    /// Espera o LM Studio estar de pé (depois de ligar o PC ou se o servidor caiu). A tarefa fica em
    /// "Aguardando o LM Studio" — sem falhar — enquanto o monitor de saúde religa o servidor.
    /// </summary>
    private async Task WaitForLmAsync(ChatJob job, CancellationToken ct)
    {
        var probe = await _models.RefreshAsync(ct);
        if (probe.State == LmState.Running) return;
        job.SetPhase(JobState.Loading, "waiting_lm");
        Changed?.Invoke();
        var until = DateTimeOffset.Now + TimeSpan.FromMinutes(10);
        while (DateTimeOffset.Now < until)
        {
            await Task.Delay(2000, ct);
            probe = await _models.RefreshAsync(ct);
            if (probe.State == LmState.Running) return;
        }
        throw new LmStudioException("lm_offline_final", "O LM Studio não voltou a funcionar no PC.");
    }

    /// <summary>Alguma tarefa esperando o LM Studio voltar (o monitor de saúde pode religá-lo sem atrapalhar nada).</summary>
    public bool WaitingForLm
    {
        get { lock (_lock) return _jobs.Values.Any(j => !j.IsFinished && j.Phase is "waiting_lm" or "recovering"); }
    }

    private void Watchdog()
    {
        ChatJob[] running;
        lock (_lock) running = _jobs.Values.Where(j => j.State is JobState.Preparing or JobState.Generating).ToArray();
        foreach (var j in running)
            if (DateTimeOffset.Now - j.LastActivity > StallTimeout)
            {
                Log?.Invoke("Uma geração parou de responder e foi encerrada", "warn");
                j.Cts.Cancel(); // vira erro "stalled" (não é cancelamento do usuário)
            }
    }

    private void FlushAll()
    {
        ChatJob[] active;
        lock (_lock) active = _jobs.Values.Where(j => j.State is JobState.Generating or JobState.Preparing).ToArray();
        foreach (var j in active) j.Flush();
    }

    private void Sweep()
    {
        lock (_lock)
        {
            var now = DateTimeOffset.Now;
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

    public JsonObject Describe(ChatJob j, string? forDevice = null) => new()
    {
        ["job"] = j.Id, ["model"] = j.Model, ["name"] = _models.DisplayName(j.Model), ["tier"] = j.Tier,
        ["state"] = j.State.ToString().ToLowerInvariant(), ["phase"] = j.IsFinished ? "done" : j.Phase,
        ["position"] = j.State == JobState.Queued ? QueuePosition(j) : null,
        ["createdAt"] = j.CreatedAt.ToUnixTimeMilliseconds(), ["startedAt"] = j.StartedAt?.ToUnixTimeMilliseconds(),
        ["finishedAt"] = j.FinishedAt?.ToUnixTimeMilliseconds(), ["tokens"] = j.Tokens,
        ["tps"] = j.LiveTokensPerSecond is { } t ? Math.Round(t, 1) : null, ["images"] = j.Images,
        ["reason"] = j.EndReason, ["error"] = j.EndError, ["lastSeq"] = j.LastSeq,
        ["mine"] = forDevice is not null ? j.DeviceId == forDevice : null,
        ["meta"] = forDevice is not null && j.DeviceId == forDevice ? j.Meta?.DeepClone() : null,
        ["label"] = forDevice is not null && j.DeviceId == forDevice ? j.Label : null,
        ["background"] = j.Background,
    };

    public void Dispose()
    {
        _disposed = true;
        _flushTimer.Dispose();
        _sweepTimer.Dispose();
        _watchdog.Dispose();
        List<ChatJob> open;
        lock (_lock) open = _jobs.Values.Where(j => !j.IsFinished).ToList();
        foreach (var j in open)
        {
            // desligando: grava o ponto atual para a próxima partida retomar, e solta o LM Studio
            j.Flush();
            SaveToDisk(j);
            try { j.Cts.Cancel(); } catch (ObjectDisposedException) { }
        }
    }
}
