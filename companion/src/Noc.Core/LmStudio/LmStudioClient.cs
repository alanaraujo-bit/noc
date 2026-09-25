using System.Diagnostics;
using System.Net.Http.Headers;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Noc.Core.LmStudio;

public enum LmState { Unknown, Running, Stopped, NotInstalled }

public sealed record LmModel(
    string Key,
    string DisplayName,
    string Type,
    string? Architecture,
    string? Quantization,
    long SizeBytes,
    string? Params,
    int MaxContext,
    bool Vision,
    bool ToolUse,
    string[] ReasoningOptions,
    string? ReasoningDefault,
    IReadOnlyList<LmInstance> Instances)
{
    public bool Loaded => Instances.Count > 0;
}

public sealed record LmInstance(string Id, int ContextLength);

public sealed record LmProbe(LmState State, IReadOnlyList<LmModel> Models, string? Error);

/// <summary>Um pedaço do streaming de /v1/chat/completions.</summary>
public readonly record struct ChatDelta(string? Reasoning, string? Content, string? FinishReason, JsonObject? Usage);

public sealed class LmStudioException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

/// <summary>
/// Cliente do LM Studio local. Só fala com 127.0.0.1: o LM Studio nunca fica exposto na rede.
/// </summary>
public sealed class LmStudioClient : IDisposable
{
    private readonly HttpClient _http;
    private readonly HttpClient _stream;
    private readonly Func<int> _port;

    public LmStudioClient(Func<int> port)
    {
        _port = port;
        var handler = new SocketsHttpHandler { PooledConnectionLifetime = TimeSpan.FromMinutes(5), ConnectTimeout = TimeSpan.FromSeconds(3) };
        _http = new HttpClient(handler, disposeHandler: false) { Timeout = TimeSpan.FromMinutes(10) };
        _stream = new HttpClient(handler) { Timeout = Timeout.InfiniteTimeSpan };
    }

    private Uri Url(string path) => new($"http://127.0.0.1:{_port()}{path}");

    public async Task<LmProbe> ProbeAsync(CancellationToken ct = default)
    {
        try
        {
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
            cts.CancelAfter(TimeSpan.FromSeconds(4));
            using var res = await _http.GetAsync(Url("/api/v1/models"), cts.Token);
            if (!res.IsSuccessStatusCode)
                return new LmProbe(LmState.Running, [], $"HTTP {(int)res.StatusCode}");
            var json = JsonNode.Parse(await res.Content.ReadAsStringAsync(cts.Token))!;
            var models = json["models"]!.AsArray().Select(ParseModel).Where(m => m is not null).Select(m => m!).ToList();
            return new LmProbe(LmState.Running, models, null);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or OperationCanceledException)
        {
            if (ct.IsCancellationRequested) throw;
            return new LmProbe(LmStudioLocator.IsInstalled() ? LmState.Stopped : LmState.NotInstalled, [], e.Message);
        }
        catch (JsonException e)
        {
            return new LmProbe(LmState.Running, [], "resposta inválida: " + e.Message);
        }
    }

    private static LmModel? ParseModel(JsonNode? n)
    {
        if (n is null) return null;
        var caps = n["capabilities"];
        var reasoning = caps?["reasoning"];
        var instances = n["loaded_instances"]?.AsArray()
            .Select(i => new LmInstance(
                i!["id"]!.GetValue<string>(),
                i["config"]?["context_length"]?.GetValue<int>() ?? 0))
            .ToList() ?? [];
        return new LmModel(
            Key: n["key"]!.GetValue<string>(),
            DisplayName: n["display_name"]?.GetValue<string>() ?? n["key"]!.GetValue<string>(),
            Type: n["type"]?.GetValue<string>() ?? "llm",
            Architecture: n["architecture"]?.GetValue<string>(),
            Quantization: n["quantization"]?["name"]?.GetValue<string>(),
            SizeBytes: n["size_bytes"]?.GetValue<long>() ?? 0,
            Params: n["params_string"]?.GetValue<string?>(),
            MaxContext: n["max_context_length"]?.GetValue<int>() ?? 0,
            Vision: caps?["vision"]?.GetValue<bool>() ?? false,
            ToolUse: caps?["trained_for_tool_use"]?.GetValue<bool>() ?? false,
            ReasoningOptions: reasoning?["allowed_options"]?.AsArray().Select(x => x!.GetValue<string>()).ToArray() ?? [],
            ReasoningDefault: reasoning?["default"]?.GetValue<string>(),
            Instances: instances);
    }

    public async Task<(string InstanceId, double Seconds, int Context)> LoadAsync(string key, int contextLength, CancellationToken ct)
    {
        var body = new JsonObject
        {
            ["model"] = key,
            ["context_length"] = contextLength,
            ["flash_attention"] = true,
            ["echo_load_config"] = true,
        };
        using var res = await _http.PostAsync(Url("/api/v1/models/load"), Json(body), ct);
        var text = await res.Content.ReadAsStringAsync(ct);
        if (!res.IsSuccessStatusCode) throw new LmStudioException("load_failed", ExtractError(text));
        var json = JsonNode.Parse(text)!;
        return (json["instance_id"]!.GetValue<string>(),
            json["load_time_seconds"]?.GetValue<double>() ?? 0,
            json["load_config"]?["context_length"]?.GetValue<int>() ?? contextLength);
    }

    public async Task UnloadAsync(string instanceId, CancellationToken ct)
    {
        using var res = await _http.PostAsync(Url("/api/v1/models/unload"), Json(new JsonObject { ["instance_id"] = instanceId }), ct);
        if (!res.IsSuccessStatusCode) throw new LmStudioException("unload_failed", ExtractError(await res.Content.ReadAsStringAsync(ct)));
    }

    /// <summary>Streaming SSE de /v1/chat/completions. Cancelar o token interrompe a geração no LM Studio.</summary>
    public async IAsyncEnumerable<ChatDelta> StreamChatAsync(JsonObject request, [EnumeratorCancellation] CancellationToken ct)
    {
        request["stream"] = true;
        request["stream_options"] = new JsonObject { ["include_usage"] = true };
        using var msg = new HttpRequestMessage(HttpMethod.Post, Url("/v1/chat/completions")) { Content = Json(request) };
        msg.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("text/event-stream"));
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
                throw new LmStudioException("lm_error", ExtractError(await res.Content.ReadAsStringAsync(ct)));
            await using var body = await res.Content.ReadAsStreamAsync(ct);
            using var reader = new StreamReader(body, Encoding.UTF8);
            while (true)
            {
                var line = await reader.ReadLineAsync(ct);
                if (line is null) yield break;
                if (!line.StartsWith("data:", StringComparison.Ordinal)) continue;
                var payload = line.AsSpan(5).Trim();
                if (payload.SequenceEqual("[DONE]")) yield break;
                JsonNode? node;
                try { node = JsonNode.Parse(payload.ToString()); }
                catch (JsonException) { continue; }
                if (node?["error"] is { } err)
                    throw new LmStudioException("lm_error", err["message"]?.GetValue<string>() ?? err.ToJsonString());
                var choice = node?["choices"]?.AsArray().FirstOrDefault();
                var delta = choice?["delta"];
                var usage = node?["usage"] as JsonObject;
                var r = delta?["reasoning_content"]?.GetValue<string>();
                var c = delta?["content"]?.GetValue<string>();
                var finish = choice?["finish_reason"]?.GetValue<string>();
                if (r is null && c is null && finish is null && usage is null) continue;
                yield return new ChatDelta(r, c, finish, usage?.DeepClone() as JsonObject);
            }
        }
    }

    private static StringContent Json(JsonNode node) => new(node.ToJsonString(), Encoding.UTF8, "application/json");

    private static string ExtractError(string body)
    {
        try
        {
            var n = JsonNode.Parse(body);
            var e = n?["error"];
            if (e is JsonValue v) return v.GetValue<string>();
            return e?["message"]?.GetValue<string>() ?? body;
        }
        catch (JsonException)
        {
            return body.Length > 300 ? body[..300] : body;
        }
    }

    public void Dispose()
    {
        _http.Dispose();
        _stream.Dispose();
    }
}

/// <summary>Localiza a instalação do LM Studio e controla o servidor via CLI `lms`.</summary>
public static class LmStudioLocator
{
    private static string LmHome => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".lmstudio");
    public static string LmsPath => Path.Combine(LmHome, "bin", "lms.exe");

    public static bool IsInstalled() => File.Exists(LmsPath) || AppPath() is not null;

    public static string? AppPath()
    {
        try
        {
            var f = Path.Combine(LmHome, ".internal", "app-install-location.json");
            if (!File.Exists(f)) return null;
            var p = JsonNode.Parse(File.ReadAllText(f))?["path"]?.GetValue<string>();
            return p is not null && File.Exists(p) ? p : null;
        }
        catch (Exception) { return null; }
    }

    /// <summary>Porta configurada no LM Studio (padrão 1234).</summary>
    public static int ConfiguredPort()
    {
        try
        {
            var f = Path.Combine(LmHome, ".internal", "http-server-config.json");
            if (File.Exists(f) && JsonNode.Parse(File.ReadAllText(f))?["port"]?.GetValue<int>() is int p and > 0) return p;
        }
        catch (Exception) { }
        return 1234;
    }

    /// <summary>Executa `lms server start` (acorda o serviço do LM Studio se preciso).</summary>
    public static async Task<(bool Ok, string Output)> StartServerAsync(CancellationToken ct)
    {
        if (!File.Exists(LmsPath)) return (false, "lms não encontrado");
        var psi = new ProcessStartInfo(LmsPath, "server start")
        {
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
        };
        using var p = Process.Start(psi)!;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(90));
        var output = p.StandardOutput.ReadToEndAsync(timeout.Token);
        var error = p.StandardError.ReadToEndAsync(timeout.Token);
        try
        {
            await p.WaitForExitAsync(timeout.Token);
        }
        catch (OperationCanceledException)
        {
            try { p.Kill(true); } catch { }
            return (false, "tempo esgotado");
        }
        var text = (await output) + (await error);
        return (p.ExitCode == 0, text);
    }
}
