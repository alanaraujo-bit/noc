using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.Jobs;
using Noc.Core.LmStudio;
using Noc.Core.Platform;

namespace Noc.Core.Library;

/// <summary>
/// Teste rápido e honesto de um modelo neste PC: tempo de carga, tempo até o primeiro token, tokens/s,
/// velocidade de leitura de um texto longo, VRAM ocupada, estabilidade (3 rodadas) e, se tiver visão,
/// se ele lê de verdade uma imagem de teste. Todos os números são medidos, nenhum é estimado.
/// </summary>
public sealed class Benchmark
{
    private readonly LmStudioClient _lm;
    private readonly ModelManager _models;
    private readonly JobManager _jobs;
    private readonly GpuMonitor _gpu;
    private readonly SemaphoreSlim _gate = new(1, 1);

    public string? RunningModel { get; private set; }
    public string? Step { get; private set; }
    public double Progress { get; private set; }
    /// <summary>{model, step, progress, done?, result?, error?}</summary>
    public event Action<JsonObject>? Update;
    public event Action<string, string>? Log;

    public Benchmark(LmStudioClient lm, ModelManager models, JobManager jobs, GpuMonitor gpu)
    {
        _lm = lm;
        _models = models;
        _jobs = jobs;
        _gpu = gpu;
    }

    public bool Busy => RunningModel is not null;

    public bool TryStart(string key, out string? error)
    {
        error = null;
        if (_jobs.Active.Count > 0) { error = "Espere as respostas em andamento terminarem para testar."; return false; }
        if (!_gate.Wait(0)) { error = "Já há um teste rodando."; return false; }
        RunningModel = key;
        _jobs.Paused = true;
        _ = Task.Run(async () =>
        {
            try { await RunAsync(key, CancellationToken.None); }
            finally
            {
                _jobs.Paused = false;
                RunningModel = null;
                Step = null;
                _gate.Release();
            }
        });
        return true;
    }

    private void Report(string key, string step, double progress)
    {
        Step = step;
        Progress = progress;
        Update?.Invoke(new JsonObject { ["model"] = key, ["step"] = step, ["progress"] = Math.Round(progress, 2) });
    }

    private async Task RunAsync(string key, CancellationToken ct)
    {
        var name = _models.DisplayName(key);
        Log?.Invoke($"Testando {name}…", "model");
        var result = new JsonObject { ["at"] = DateTimeOffset.Now.ToUnixTimeMilliseconds() };
        try
        {
            // 1) carga do zero
            Report(key, "Carregando o modelo", 0.05);
            var model = _models.Find(key) ?? throw new JobFailure("model_not_found", "Modelo não encontrado");
            if (model.Loaded) await _models.UnloadAsync(key, ct, byUser: false);
            var sw = Stopwatch.StartNew();
            var (instance, ctx) = await _models.EnsureLoadedAsync(key, null, ct);
            result["loadSeconds"] = Math.Round(sw.Elapsed.TotalSeconds, 1);
            result["context"] = ctx;
            await Task.Delay(1500, ct); // deixa a VRAM assentar para a leitura do nvidia-smi
            if (_gpu.Last is { } g) result["vramUsedGiB"] = Math.Round(g.VramUsedMb / 1024.0, 1);
            result["vramTotalGiB"] = _gpu.Last is { } g2 ? Math.Round(g2.VramTotalMb / 1024.0, 1) : null;

            // 2) três respostas curtas: TTFT e tokens/s (e se ficam estáveis)
            var ttfts = new List<double>();
            var tpss = new List<double>();
            const string prompt = "Explique em um parágrafo, em português, como funciona uma VLAN e para que serve uma porta trunk.";
            for (var i = 0; i < 3; i++)
            {
                Report(key, $"Medindo velocidade ({i + 1}/3)", 0.2 + i * 0.15);
                var (ttft, tps, _) = await RunOnceAsync(instance, [Text("user", prompt)], 220, ct);
                ttfts.Add(ttft);
                if (tps > 0) tpss.Add(tps);
            }
            result["ttftMs"] = Math.Round(Median(ttfts));
            result["tps"] = Math.Round(Median(tpss), 1);
            var spread = tpss.Count > 1 ? (tpss.Max() - tpss.Min()) / Math.Max(1, tpss.Average()) : 0;
            result["stable"] = tpss.Count == 3 && spread < 0.25;

            // 3) texto longo (~6 mil tokens): quanto tempo para "ler" antes de responder
            Report(key, "Lendo um texto longo", 0.7);
            var longText = LongDocument(ctx);
            var (lttft, _, promptTokens) = await RunOnceAsync(instance, [Text("user", longText + "\n\nResuma o texto acima em uma frase.")], 40, ct);
            result["longPromptTokens"] = promptTokens;
            result["longTtftMs"] = Math.Round(lttft);
            if (promptTokens > 0 && lttft > 0) result["prefillTps"] = Math.Round(promptTokens / (lttft / 1000.0));

            // 4) visão: lê uma imagem com texto conhecido
            model = _models.Find(key) ?? model;
            if (model.Vision)
            {
                Report(key, "Testando visão", 0.85);
                var png = LoadFixture();
                var content = new JsonArray
                {
                    new JsonObject { ["type"] = "text", ["text"] = "Qual número aparece logo depois da palavra VLAN nesta imagem? Responda só o número." },
                    new JsonObject { ["type"] = "image_url", ["image_url"] = new JsonObject { ["url"] = "data:image/png;base64," + Convert.ToBase64String(png) } },
                };
                var vsw = Stopwatch.StartNew();
                var (_, _, _, answer) = await RunOnceFullAsync(instance, [new JsonObject { ["role"] = "user", ["content"] = content }], 20, ct);
                result["vision"] = new JsonObject { ["ok"] = answer.Contains("102"), ["ms"] = vsw.ElapsedMilliseconds };
            }

            var prefs = _models.Catalog.Prefs(key);
            prefs.Benchmark = result;
            _models.Catalog.Save();
            Report(key, "Concluído", 1);
            Update?.Invoke(new JsonObject { ["model"] = key, ["done"] = true, ["result"] = result.DeepClone() });
            Log?.Invoke($"{name}: {result["tps"]} tokens/s, primeiro token em {result["ttftMs"]} ms, carga em {result["loadSeconds"]} s", "model");
        }
        catch (Exception e)
        {
            var msg = e is LmStudioException or JobFailure ? e.Message : "O teste falhou: " + e.Message;
            Update?.Invoke(new JsonObject { ["model"] = key, ["done"] = true, ["error"] = msg });
            Log?.Invoke($"Teste de {name} falhou: {msg}", "error");
        }
    }

    private static JsonObject Text(string role, string content) => new() { ["role"] = role, ["content"] = content };

    private async Task<(double TtftMs, double Tps, int PromptTokens)> RunOnceAsync(string instance, JsonObject[] messages, int maxTokens, CancellationToken ct)
    {
        var (t, tps, pt, _) = await RunOnceFullAsync(instance, messages, maxTokens, ct);
        return (t, tps, pt);
    }

    private async Task<(double TtftMs, double Tps, int PromptTokens, string Answer)> RunOnceFullAsync(string instance, JsonObject[] messages, int maxTokens, CancellationToken ct)
    {
        var req = new JsonObject
        {
            ["model"] = instance, ["messages"] = new JsonArray(messages.Select(m => (JsonNode)m.DeepClone()).ToArray()),
            ["max_tokens"] = maxTokens, ["temperature"] = 0, ["reasoning_effort"] = "none",
        };
        var sw = Stopwatch.StartNew();
        double? first = null;
        var tokens = 0;
        JsonObject? usage = null;
        var sb = new StringBuilder();
        await foreach (var d in _lm.StreamChatAsync(req, ct))
        {
            if (d.Content is not null || d.Reasoning is not null)
            {
                first ??= sw.Elapsed.TotalMilliseconds;
                tokens++;
                sb.Append(d.Content);
            }
            if (d.Usage is not null) usage = d.Usage;
        }
        var total = sw.Elapsed.TotalMilliseconds;
        var completion = usage?["completion_tokens"]?.GetValue<int>() ?? tokens;
        var genSecs = (total - (first ?? total)) / 1000.0;
        return (first ?? total, genSecs > 0.05 ? completion / genSecs : 0, usage?["prompt_tokens"]?.GetValue<int>() ?? 0, sb.ToString());
    }

    private static double Median(List<double> v) => v.Count == 0 ? 0 : v.OrderBy(x => x).ElementAt(v.Count / 2);

    private static string LongDocument(int ctx)
    {
        // ~6 mil tokens (ou menos, se o contexto for pequeno), texto variado para não ser trivial de comprimir
        var targetChars = Math.Min(24000, (int)(ctx * 0.5 * 3.5));
        var sb = new StringBuilder();
        var topics = new[] { "roteamento", "switches", "firewall", "VPN", "DNS", "DHCP", "VLANs", "QoS", "Wi-Fi", "backups" };
        for (var i = 0; sb.Length < targetChars; i++)
            sb.Append($"Seção {i + 1}. Sobre {topics[i % topics.Length]}: a equipe registrou {i * 7 % 97} ocorrências no mês {i % 12 + 1}, " +
                      $"com tempo médio de resolução de {i * 13 % 50 + 5} minutos e {i % 5} escalonamentos para o nível dois. ");
        return sb.ToString();
    }

    private static byte[] LoadFixture()
    {
        using var s = typeof(Benchmark).Assembly.GetManifestResourceStream("bench-vision.png")!;
        using var ms = new MemoryStream();
        s.CopyTo(ms);
        return ms.ToArray();
    }
}
