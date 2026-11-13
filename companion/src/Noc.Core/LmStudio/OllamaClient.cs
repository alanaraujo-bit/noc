using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace Noc.Core.LmStudio;

/// <summary>Qual programa roda os modelos neste PC.</summary>
public enum LlmBackend { LmStudio, Ollama }

/// <summary>
/// O motor de IA em uso (LM Studio ou Ollama). O resto do Companion fala com o mesmo cliente e os mesmos tipos;
/// aqui fica só o que muda de um para o outro: nome, instalação, porta e como ligar o servidor.
/// </summary>
public static class LlmRuntime
{
    public static LlmBackend Backend { get; private set; } = LlmBackend.LmStudio;
    public static bool IsOllama => Backend == LlmBackend.Ollama;
    public static string Name => IsOllama ? "Ollama" : "LM Studio";
    public static string Id => IsOllama ? "ollama" : "lmstudio";
    public static string DownloadUrl => IsOllama ? "https://ollama.com/download" : "https://lmstudio.ai";

    /// <summary>
    /// "ollama" | "lmstudio" | "auto" (ou vazio). No automático vale o que estiver instalado; com os dois,
    /// o LM Studio continua sendo o padrão (quem já usava o Noc não muda de motor sem pedir).
    /// NOC_BACKEND sobrescreve (testes).
    /// </summary>
    public static LlmBackend Resolve(string? setting)
    {
        var s = Environment.GetEnvironmentVariable("NOC_BACKEND") is { Length: > 0 } env ? env : setting;
        switch ((s ?? "auto").Trim().ToLowerInvariant())
        {
            case "ollama": return LlmBackend.Ollama;
            case "lmstudio" or "lm-studio" or "lm studio": return LlmBackend.LmStudio;
        }
        if (LmStudioLocator.IsInstalled()) return LlmBackend.LmStudio;
        return OllamaLocator.IsInstalled() ? LlmBackend.Ollama : LlmBackend.LmStudio;
    }

    public static void Use(string? setting) => Backend = Resolve(setting);

    public static bool IsInstalled() => IsOllama ? OllamaLocator.IsInstalled() : LmStudioLocator.IsInstalled();
    public static string? AppPath() => IsOllama ? OllamaLocator.AppPath() : LmStudioLocator.AppPath();
    public static int ConfiguredPort() => IsOllama ? OllamaLocator.ConfiguredPort() : LmStudioLocator.ConfiguredPort();

    public static Task<(bool Ok, string Output)> StartServerAsync(CancellationToken ct) =>
        IsOllama ? OllamaLocator.StartServerAsync(ct) : LmStudioLocator.StartServerAsync(ct);

    /// <summary>Quantas respostas do mesmo modelo podem rodar juntas (o Ollama segue OLLAMA_NUM_PARALLEL).</summary>
    public static int MaxParallel => IsOllama ? OllamaLocator.NumParallel() : 2;
}

/// <summary>Localiza a instalação do Ollama e liga o servidor quando ele está parado.</summary>
public static class OllamaLocator
{
    private static string InstallDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Ollama");

    /// <summary>ollama.exe (linha de comando / servidor).</summary>
    public static string? ExePath()
    {
        var p = Path.Combine(InstallDir, "ollama.exe");
        if (File.Exists(p)) return p;
        foreach (var dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            try
            {
                var c = Path.Combine(dir, "ollama.exe");
                if (File.Exists(c)) return c;
            }
            catch (ArgumentException) { }
        }
        return null;
    }

    /// <summary>O aplicativo da bandeja ("ollama app.exe"), que mantém o servidor ligado e atualizado.</summary>
    public static string? AppPath()
    {
        var p = Path.Combine(InstallDir, "ollama app.exe");
        return File.Exists(p) ? p : null;
    }

    public static bool IsInstalled() => ExePath() is not null;

    /// <summary>Variável do Ollama: a do processo ou, se o Companion abriu antes de ela existir, a do usuário.</summary>
    public static string? Env(string name)
    {
        var v = Environment.GetEnvironmentVariable(name);
        if (string.IsNullOrWhiteSpace(v))
        {
            try { v = Environment.GetEnvironmentVariable(name, EnvironmentVariableTarget.User); }
            catch (Exception) { v = null; }
        }
        return string.IsNullOrWhiteSpace(v) ? null : v.Trim();
    }

    /// <summary>Porta do OLLAMA_HOST (padrão 11434).</summary>
    public static int ConfiguredPort()
    {
        var host = Env("OLLAMA_HOST");
        if (host is not null)
        {
            var m = Regex.Match(host, @":(\d{2,5})(?:/|$)");
            if (m.Success && int.TryParse(m.Groups[1].Value, out var port) && port is > 0 and < 65536) return port;
        }
        return 11434;
    }

    public static int NumParallel() =>
        int.TryParse(Env("OLLAMA_NUM_PARALLEL"), out var n) ? Math.Clamp(n, 1, 4) : 1;

    /// <summary>Contexto padrão do servidor (OLLAMA_CONTEXT_LENGTH); 0 = o Ollama decide.</summary>
    public static int DefaultContext() =>
        int.TryParse(Env("OLLAMA_CONTEXT_LENGTH"), out var n) && n >= 2048 ? n : 0;

    /// <summary>Liga o Ollama: o app da bandeja quando existe, senão `ollama serve` escondido.</summary>
    public static async Task<(bool Ok, string Output)> StartServerAsync(CancellationToken ct)
    {
        var app = AppPath();
        var exe = ExePath();
        if (app is null && exe is null) return (false, "Ollama não encontrado");
        try
        {
            var psi = app is not null
                ? new ProcessStartInfo(app) { UseShellExecute = true, WindowStyle = ProcessWindowStyle.Minimized }
                : new ProcessStartInfo(exe!, "serve") { UseShellExecute = false, CreateNoWindow = true };
            using var p = Process.Start(psi);
        }
        catch (Exception e)
        {
            return (false, e.Message);
        }
        var port = ConfiguredPort();
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
        for (var i = 0; i < 40; i++)
        {
            try
            {
                using var res = await http.GetAsync($"http://127.0.0.1:{port}/api/version", ct);
                if (res.IsSuccessStatusCode) return (true, "Ollama ligado");
            }
            catch (Exception) when (!ct.IsCancellationRequested) { }
            await Task.Delay(750, ct);
        }
        return (false, "O Ollama não respondeu");
    }
}

/// <summary>
/// As mesmas operações do cliente do LM Studio, faladas na API nativa do Ollama (127.0.0.1):
/// /api/tags + /api/ps + /api/show para a lista, /api/generate para carregar/descarregar e /api/chat para conversar.
/// </summary>
internal sealed class OllamaBackend
{
    private sealed record ShowInfo(HashSet<string> Caps, int NumCtx, string? Basename, string? SizeLabel, string? Architecture, bool Chat, int MaxContext);

    private readonly HttpClient _http;
    private readonly HttpClient _stream;
    private readonly Func<int> _port;
    private readonly ConcurrentDictionary<string, ShowInfo> _show = new();
    private readonly ConcurrentDictionary<string, ShowInfo> _byName = new(StringComparer.OrdinalIgnoreCase);
    private readonly ConcurrentDictionary<string, int> _loadedContext = new(StringComparer.OrdinalIgnoreCase);

    public OllamaBackend(HttpClient http, HttpClient stream, Func<int> port)
    {
        _http = http;
        _stream = stream;
        _port = port;
    }

    private Uri Url(string path) => new($"http://127.0.0.1:{_port()}{path}");

    private static StringContent Json(JsonNode node) => new(node.ToJsonString(), Encoding.UTF8, "application/json");

    private static string Norm(string name) => (name.Contains(':') ? name : name + ":latest").ToLowerInvariant();

    // ------------------------------------------------------------------ leitura

    public async Task<LmProbe> ProbeAsync(CancellationToken ct)
    {
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(4));
            JsonNode tags, ps;
            using (var res = await _http.GetAsync(Url("/api/tags"), cts.Token))
            {
                if (!res.IsSuccessStatusCode)
                    return new LmProbe(LmState.Running, [], $"HTTP {(int)res.StatusCode}: {ExtractError(await res.Content.ReadAsStringAsync(cts.Token))}");
                tags = JsonNode.Parse(await res.Content.ReadAsStringAsync(cts.Token))!;
            }
            using (var res = await _http.GetAsync(Url("/api/ps"), cts.Token))
                ps = res.IsSuccessStatusCode ? JsonNode.Parse(await res.Content.ReadAsStringAsync(cts.Token))! : new JsonObject();

            var loaded = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
            foreach (var m in ps["models"]?.AsArray() ?? [])
                if (m?["name"]?.GetValue<string>() is { } n)
                    loaded[Norm(n)] = m["context_length"]?.GetValue<int>() ?? 0;
            _loadedContext.Clear();
            foreach (var kv in loaded) _loadedContext[kv.Key] = kv.Value;

            var entries = (tags["models"]?.AsArray() ?? []).OfType<JsonObject>()
                .Where(m => m["name"] is not null).ToList();

            // Um perfil criado em cima de um modelo (FROM qwen3.8:27b + parâmetros) é o mesmo arquivo com outro nome:
            // mostra só o perfil, senão o mesmo modelo apareceria duas vezes e poderia ser carregado duas vezes.
            var shadowed = new HashSet<string>();
            foreach (var m in entries)
            {
                var parent = m["details"]?["parent_model"]?.GetValue<string>();
                if (string.IsNullOrWhiteSpace(parent)) continue;
                var p = entries.FirstOrDefault(o => !ReferenceEquals(o, m) && Norm(o["name"]!.GetValue<string>()) == Norm(parent));
                if (p is null) continue;
                var a = m["size"]?.GetValue<long>() ?? 0;
                var b = p["size"]?.GetValue<long>() ?? 0;
                if (Math.Abs(a - b) < 4 * 1024 * 1024) shadowed.Add(Norm(parent));
            }

            var models = new List<LmModel>();
            foreach (var m in entries)
            {
                var name = m["name"]!.GetValue<string>();
                if (shadowed.Contains(Norm(name))) continue;
                var details = m["details"];
                var info = await ShowAsync(name, m["digest"]?.GetValue<string>() ?? "", ct);
                if (info is not null) _byName[Norm(name)] = info;

                var family = details?["family"]?.GetValue<string>();
                var paramSize = details?["parameter_size"]?.GetValue<string>();
                var caps = info?.Caps ?? [];
                var embedding = caps.Contains("embedding") || (info is null && name.Contains("embed", StringComparison.OrdinalIgnoreCase));
                var type = embedding ? "embedding" : info is { Chat: false } ? "completion" : "llm";
                var thinking = caps.Contains("thinking");
                var maxContext = details?["context_length"]?.GetValue<int>() ?? info?.MaxContext ?? 0;
                var defaultContext = info is { NumCtx: > 0 } ? info.NumCtx : OllamaLocator.DefaultContext();
                if (maxContext > 0 && defaultContext > maxContext) defaultContext = maxContext;
                var display = info?.Basename is { Length: > 0 } bn
                    ? bn + (info.SizeLabel is { Length: > 0 } sl ? " " + sl : "")
                    : name.Replace(":latest", "", StringComparison.OrdinalIgnoreCase).Replace(':', ' ');
                var instances = loaded.TryGetValue(Norm(name), out var ctxLoaded)
                    ? new List<LmInstance> { new(name, ctxLoaded) }
                    : [];
                models.Add(new LmModel(
                    Key: name,
                    DisplayName: display,
                    Type: type,
                    Architecture: info?.Architecture ?? family,
                    Quantization: details?["quantization_level"]?.GetValue<string>(),
                    SizeBytes: m["size"]?.GetValue<long>() ?? 0,
                    Params: info?.SizeLabel ?? paramSize,
                    MaxContext: maxContext,
                    Vision: caps.Contains("vision"),
                    ToolUse: caps.Contains("tools"),
                    ReasoningOptions: thinking ? ["off", "on"] : [],
                    ReasoningDefault: thinking ? "on" : null,
                    Instances: instances,
                    DefaultContext: defaultContext));
            }
            return new LmProbe(LmState.Running, models, null);
        }
        catch (Exception e) when (e is TaskCanceledException or OperationCanceledException && !ct.IsCancellationRequested)
        {
            // porta aberta mas sem resposta a tempo: ocupado (carregando um modelo grande), não parado
            return new LmProbe(LmState.Running, [], "sem resposta a tempo", Busy: true);
        }
        catch (HttpRequestException e)
        {
            if (ct.IsCancellationRequested) throw;
            return new LmProbe(OllamaLocator.IsInstalled() ? LmState.Stopped : LmState.NotInstalled, [], e.Message);
        }
        catch (JsonException e)
        {
            return new LmProbe(LmState.Running, [], "resposta inválida: " + e.Message);
        }
    }

    /// <summary>Capacidades e parâmetros do modelo (/api/show). Não mudam enquanto o digest for o mesmo.</summary>
    private async Task<ShowInfo?> ShowAsync(string name, string digest, CancellationToken ct)
    {
        var cacheKey = name + "|" + digest;
        if (_show.TryGetValue(cacheKey, out var cached)) return cached;
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(8));
            using var res = await _http.PostAsync(Url("/api/show"), Json(new JsonObject { ["model"] = name }), cts.Token);
            if (!res.IsSuccessStatusCode) return null;
            var json = JsonNode.Parse(await res.Content.ReadAsStringAsync(cts.Token))!;
            var caps = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var c in json["capabilities"]?.AsArray() ?? [])
                if (c?.GetValue<string>() is { } s) caps.Add(s);
            var numCtx = 0;
            if (json["parameters"]?.GetValue<string>() is { } prm)
            {
                var m = Regex.Match(prm, @"(?m)^\s*num_ctx\s+(\d+)\s*$");
                if (m.Success) numCtx = int.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture);
            }
            var mi = json["model_info"] as JsonObject;
            var arch = Text(mi?["general.architecture"]);
            var maxCtx = arch is not null && mi?[arch + ".context_length"] is JsonValue cv && cv.TryGetValue<int>(out var cl) ? cl : 0;
            var template = json["template"]?.GetValue<string>() ?? "";
            var hasRenderer = !string.IsNullOrWhiteSpace(Text(json["renderer"]));
            // Modelo só de autocompletar (FIM): completa código, mas não tem formato de conversa.
            var chatTemplate = hasRenderer || Regex.IsMatch(template, @"\.Messages|\.System|im_start|start_header_id|\[INST\]|messages", RegexOptions.IgnoreCase);
            var chat = caps.Contains("completion") && !caps.Contains("embedding") &&
                       !(caps.Contains("insert") && !caps.Contains("tools") && !chatTemplate);
            var info = new ShowInfo(caps, numCtx, Text(mi?["general.basename"]), Text(mi?["general.size_label"]), arch, chat, maxCtx);
            _show[cacheKey] = info;
            return info;
        }
        catch (Exception) when (!ct.IsCancellationRequested)
        {
            return null; // tenta de novo na próxima leitura
        }
    }

    private static string? Text(JsonNode? n) => n is JsonValue v && v.TryGetValue<string>(out var s) && s.Length > 0 ? s : null;

    // ------------------------------------------------------------------ carregar / descarregar

    /// <summary>Carrega o modelo. contextLength &lt;= 0: o contexto do próprio modelo/servidor (o mesmo que os outros programas usam).</summary>
    public async Task<(string InstanceId, double Seconds, int Context)> LoadAsync(string key, int contextLength, CancellationToken ct)
    {
        var body = new JsonObject { ["model"] = key };
        if (contextLength > 0) body["options"] = new JsonObject { ["num_ctx"] = contextLength };
        var sw = Stopwatch.StartNew();
        HttpResponseMessage res;
        try { res = await _http.PostAsync(Url("/api/generate"), Json(body), ct); }
        catch (HttpRequestException e) { throw new LmStudioException("lm_unreachable", e.Message); }
        using (res)
        {
            var text = await res.Content.ReadAsStringAsync(ct);
            if (!res.IsSuccessStatusCode) throw new LmStudioException("load_failed", ExtractError(text));
        }
        var seconds = sw.Elapsed.TotalSeconds;
        var effective = contextLength;
        try
        {
            using var psRes = await _http.GetAsync(Url("/api/ps"), ct);
            var ps = JsonNode.Parse(await psRes.Content.ReadAsStringAsync(ct));
            foreach (var m in ps?["models"]?.AsArray() ?? [])
                if (m?["name"]?.GetValue<string>() is { } n && Norm(n) == Norm(key))
                    effective = m["context_length"]?.GetValue<int>() ?? contextLength;
        }
        catch (Exception) when (!ct.IsCancellationRequested) { }
        if (effective > 0) _loadedContext[Norm(key)] = effective;
        return (key, seconds, effective);
    }

    public async Task UnloadAsync(string instanceId, CancellationToken ct)
    {
        using var res = await _http.PostAsync(Url("/api/generate"), Json(new JsonObject { ["model"] = instanceId, ["keep_alive"] = 0 }), ct);
        if (!res.IsSuccessStatusCode && res.StatusCode != HttpStatusCode.NotFound)
            throw new LmStudioException("unload_failed", ExtractError(await res.Content.ReadAsStringAsync(ct)));
        _loadedContext.TryRemove(Norm(instanceId), out _);
    }

    // ------------------------------------------------------------------ conversa

    /// <summary>
    /// Converte o pedido no formato OpenAI (o que o resto do Companion monta) para /api/chat e devolve os mesmos deltas.
    /// O contexto enviado é sempre o que já está carregado: um num_ctx diferente faria o Ollama recarregar o modelo.
    /// </summary>
    public async IAsyncEnumerable<ChatDelta> StreamChatAsync(JsonObject request, int contextLength, [EnumeratorCancellation] CancellationToken ct)
    {
        var model = request["model"]?.GetValue<string>() ?? throw new LmStudioException("lm_rejected", "pedido sem modelo");
        _byName.TryGetValue(Norm(model), out var info);
        if (contextLength <= 0) _loadedContext.TryGetValue(Norm(model), out contextLength);
        var body = ToOllama(request, model, contextLength, info);

        using var msg = new HttpRequestMessage(HttpMethod.Post, Url("/api/chat")) { Content = Json(body) };
        HttpResponseMessage res;
        try
        {
            res = await _stream.SendAsync(msg, HttpCompletionOption.ResponseHeadersRead, ct);
        }
        catch (HttpRequestException e)
        {
            throw new LmStudioException("lm_unreachable", e.Message);
        }
        using (res)
        {
            if (!res.IsSuccessStatusCode)
            {
                var err = ExtractError(await res.Content.ReadAsStringAsync(ct));
                // 4xx: o pedido não serve para este modelo (não adianta tentar de novo); 5xx: o servidor tropeçou
                throw new LmStudioException((int)res.StatusCode < 500 ? "lm_rejected" : "lm_error", err);
            }
            await using var stream = await res.Content.ReadAsStreamAsync(ct);
            using var reader = new StreamReader(stream, Encoding.UTF8);
            while (true)
            {
                var line = await reader.ReadLineAsync(ct);
                if (line is null) yield break;
                if (line.Length == 0) continue;
                JsonNode? node;
                try { node = JsonNode.Parse(line); }
                catch (JsonException) { continue; }
                if (node?["error"] is { } e)
                    throw new LmStudioException("lm_error", e is JsonValue ev && ev.TryGetValue<string>(out var es) ? es : e.ToJsonString());
                var m = node?["message"];
                var r = Text(m?["thinking"]);
                var c = Text(m?["content"]);
                var done = node?["done"]?.GetValue<bool>() ?? false;
                string? finish = null;
                JsonObject? usage = null;
                if (done)
                {
                    finish = Text(node!["done_reason"]) ?? "stop";
                    usage = new JsonObject
                    {
                        ["prompt_tokens"] = node["prompt_eval_count"]?.GetValue<int>() ?? 0,
                        ["completion_tokens"] = node["eval_count"]?.GetValue<int>() ?? 0,
                    };
                }
                if (r is null && c is null && !done) continue;
                yield return new ChatDelta(r, c, finish, usage);
                if (done) yield break;
            }
        }
    }

    private static JsonObject ToOllama(JsonObject request, string model, int contextLength, ShowInfo? info)
    {
        var messages = new JsonArray();
        foreach (var m in request["messages"] as JsonArray ?? [])
        {
            var role = m?["role"]?.GetValue<string>() ?? "user";
            var content = m?["content"];
            var text = new StringBuilder();
            JsonArray? images = null;
            if (content is JsonValue v && v.TryGetValue<string>(out var s)) text.Append(s);
            else if (content is JsonArray parts)
            {
                foreach (var part in parts)
                {
                    switch (part?["type"]?.GetValue<string>())
                    {
                        case "text":
                            text.Append(part!["text"]?.GetValue<string>());
                            break;
                        case "image_url":
                            var url = part!["image_url"]?["url"]?.GetValue<string>() ?? "";
                            var comma = url.IndexOf(',');
                            if (url.StartsWith("data:", StringComparison.Ordinal) && comma > 0) (images ??= []).Add(url[(comma + 1)..]);
                            break;
                    }
                }
            }
            var o = new JsonObject { ["role"] = role, ["content"] = text.ToString() };
            if (images is not null) o["images"] = images;
            messages.Add(o);
        }

        var options = new JsonObject();
        if (contextLength > 0) options["num_ctx"] = contextLength;
        Copy(request, options, "temperature", "temperature");
        Copy(request, options, "top_p", "top_p");
        Copy(request, options, "top_k", "top_k");
        Copy(request, options, "min_p", "min_p");
        Copy(request, options, "repeat_penalty", "repeat_penalty");
        Copy(request, options, "presence_penalty", "presence_penalty");
        Copy(request, options, "frequency_penalty", "frequency_penalty");
        Copy(request, options, "seed", "seed");
        Copy(request, options, "max_tokens", "num_predict");
        if (request["stop"] is JsonArray stop && stop.Count > 0) options["stop"] = stop.DeepClone();

        var body = new JsonObject { ["model"] = model, ["messages"] = messages, ["stream"] = true };
        if (options.Count > 0) body["options"] = options;

        // Raciocínio: só modelos que pensam aceitam o campo. "none" desliga; low/medium/high ligam
        // (o nível só existe em alguns modelos; nos outros vale como ligado).
        if (info is not null && info.Caps.Contains("thinking"))
        {
            switch (request["reasoning_effort"]?.GetValue<string>())
            {
                case "none": body["think"] = false; break;
                case "low" or "medium" or "high" when info.Architecture is "gptoss":
                    body["think"] = request["reasoning_effort"]!.GetValue<string>();
                    break;
                case "low" or "medium" or "high": body["think"] = true; break;
            }
        }
        return body;
    }

    private static void Copy(JsonObject from, JsonObject to, string key, string target)
    {
        if (from[key] is JsonValue v) to[target] = v.DeepClone();
    }

    private static string ExtractError(string body)
    {
        try
        {
            var e = JsonNode.Parse(body)?["error"];
            if (e is JsonValue v && v.TryGetValue<string>(out var s)) return s;
            return e?["message"]?.GetValue<string>() ?? body;
        }
        catch (JsonException)
        {
            return body.Length > 300 ? body[..300] : body;
        }
    }
}
