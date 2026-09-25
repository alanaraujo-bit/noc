using System.Diagnostics;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Noc.Core.Storage;
using Whisper.net;
using Whisper.net.LibraryLoader;

namespace Noc.Core.Speech;

public enum SttState { Off, Downloading, Loading, Ready, Failed }

/// <summary>Resultado de uma transcrição.</summary>
public sealed record SttResult(string Text, bool Empty, bool Quiet, long AudioMs, long ProcessMs, string? Hint);

/// <summary>
/// Ditado: o áudio vem do celular pelo canal cifrado e é transcrito aqui, na GPU, pelo Whisper large-v3-turbo.
/// Nada vai para a nuvem e nada fica gravado: o áudio vive só na memória até virar texto.
/// O modelo fica carregado (≈1 GB de VRAM) para a primeira palavra sair em menos de um segundo.
/// </summary>
public sealed class SttService : IDisposable
{
    public const int SampleRate = 16000;
    public const int MaxSeconds = 10 * 60;
    private const string ModelFile = "ggml-large-v3-turbo-q8_0.bin";
    private const string ModelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q8_0.bin";
    private const string ModelSha256 = "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1";
    private const long ModelBytes = 874188075;

    /// <summary>Frases que o Whisper "inventa" em silêncio (legendas de vídeos do treino).</summary>
    private static readonly string[] Phantoms =
    [
        "legendas pela comunidade amara.org", "obrigado por assistir", "obrigada por assistir", "inscreva-se no canal",
        "legenda adriana zanotto", "tchau, tchau", "até a próxima", "legendas pela comunidade", "sous-titres", "subtitles by",
        "thank you for watching", "obrigado.", "obrigada.",
    ];

    private readonly SemaphoreSlim _gpu = new(1, 1);
    private readonly SemaphoreSlim _loadGate = new(1, 1);
    private readonly Dictionary<string, Capture> _captures = new();
    private readonly Lock _lock = new();
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromMinutes(30) };
    private WhisperFactory? _factory;
    private readonly Timer _sweep;

    public SttState State { get; private set; } = SttState.Off;
    public double? DownloadProgress { get; private set; }
    public string? Error { get; private set; }
    public string? Runtime { get; private set; }
    public long? LastProcessMs { get; private set; }
    public event Action? Changed;
    public event Action<string, string>? Log;

    private sealed class Capture
    {
        public required string DeviceId { get; init; }
        public MemoryStream Pcm { get; } = new();
        public string[] Vocab { get; init; } = [];
        public DateTimeOffset Started { get; } = DateTimeOffset.Now;
        public DateTimeOffset Touched { get; set; } = DateTimeOffset.Now;
        public int NextSeq { get; set; }
    }

    public SttService()
    {
        _http.DefaultRequestHeaders.UserAgent.ParseAdd("Noc-Companion/" + CompanionHost.Version);
        _sweep = new Timer(_ => Sweep(), null, TimeSpan.FromMinutes(1), TimeSpan.FromMinutes(1));
    }

    private static string Dir => Path.Combine(AppPaths.Root, "stt");
    public static string ModelPath => Path.Combine(Dir, ModelFile);
    public bool Ready => State == SttState.Ready;

    public JsonObject ToJson() => new()
    {
        ["state"] = State.ToString().ToLowerInvariant(), ["progress"] = DownloadProgress is { } p ? Math.Round(p, 3) : null,
        ["error"] = Error, ["runtime"] = Runtime, ["engine"] = "Whisper large-v3-turbo", ["local"] = true,
    };

    /// <summary>Baixa (se preciso), carrega e aquece o modelo. Seguro chamar várias vezes.</summary>
    public async Task EnsureReadyAsync(CancellationToken ct)
    {
        if (State == SttState.Ready) return;
        await _loadGate.WaitAsync(ct);
        try
        {
            if (State == SttState.Ready) return;
            Error = null;
            Directory.CreateDirectory(Dir);
            if (!File.Exists(ModelPath) || new FileInfo(ModelPath).Length != ModelBytes)
                await DownloadAsync(ct);

            State = SttState.Loading;
            Changed?.Invoke();
            var sw = Stopwatch.StartNew();
            // Vulkan usa a GPU sem exigir o CUDA Toolkit; se a GPU não estiver disponível, cai para a CPU.
            RuntimeOptions.RuntimeLibraryOrder = [RuntimeLibrary.Vulkan, RuntimeLibrary.Cpu];
            _factory = WhisperFactory.FromPath(ModelPath);
            Runtime = RuntimeOptions.LoadedLibrary?.ToString();
            // aquecimento: a primeira inferência compila os shaders (≈10 s); o usuário não deve pagar isso
            await using (var p = _factory.CreateBuilder().WithLanguage("pt").WithNoContext().Build())
                await foreach (var _ in p.ProcessAsync(new float[SampleRate], ct)) { }
            State = SttState.Ready;
            Log?.Invoke($"Transcrição de voz pronta ({Runtime}, {sw.Elapsed.TotalSeconds:0.0} s)", "voice");
        }
        catch (OperationCanceledException) { State = SttState.Off; throw; }
        catch (Exception e)
        {
            State = SttState.Failed;
            Error = "Não foi possível preparar a transcrição de voz: " + e.Message;
            Log?.Invoke(Error, "error");
        }
        finally
        {
            _loadGate.Release();
            Changed?.Invoke();
        }
    }

    private async Task DownloadAsync(CancellationToken ct)
    {
        State = SttState.Downloading;
        DownloadProgress = 0;
        Changed?.Invoke();
        Log?.Invoke("Baixando o modelo de transcrição de voz (874 MB, uma vez só)", "voice");
        var tmp = ModelPath + ".part";
        using (var sha = SHA256.Create())
        {
            using var res = await _http.GetAsync(ModelUrl, HttpCompletionOption.ResponseHeadersRead, ct);
            res.EnsureSuccessStatusCode();
            await using var body = await res.Content.ReadAsStreamAsync(ct);
            await using (var f = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 20, true))
            {
                var buf = new byte[1 << 20];
                long got = 0;
                var last = DateTime.MinValue;
                int n;
                while ((n = await body.ReadAsync(buf, ct)) > 0)
                {
                    await f.WriteAsync(buf.AsMemory(0, n), ct);
                    sha.TransformBlock(buf, 0, n, null, 0);
                    got += n;
                    DownloadProgress = (double)got / ModelBytes;
                    if (DateTime.Now - last > TimeSpan.FromMilliseconds(500)) { last = DateTime.Now; Changed?.Invoke(); }
                }
            }
            sha.TransformFinalBlock([], 0, 0);
            if (Convert.ToHexString(sha.Hash!).ToLowerInvariant() != ModelSha256)
            {
                File.Delete(tmp);
                throw new InvalidDataException("o download veio corrompido");
            }
        }
        File.Move(tmp, ModelPath, overwrite: true);
        DownloadProgress = null;
    }

    // ------------------------------------------------------------------ captura em pedaços

    public void Begin(string id, string deviceId, IEnumerable<string>? vocab)
    {
        if (id.Length is < 8 or > 64) throw new ArgumentException("id inválido");
        lock (_lock)
        {
            if (_captures.Count > 16) throw new InvalidOperationException("muitas gravações abertas");
            _captures[id] = new Capture
            {
                DeviceId = deviceId,
                Vocab = (vocab ?? []).Select(v => v.Trim()).Where(v => v.Length is > 1 and <= 40).Distinct(StringComparer.OrdinalIgnoreCase).Take(80).ToArray(),
            };
        }
    }

    /// <summary>Recebe PCM 16 bits mono 16 kHz. Pedaços chegam em ordem; repetidos são ignorados.</summary>
    public void Append(string id, string deviceId, int seq, byte[] pcm)
    {
        lock (_lock)
        {
            if (!_captures.TryGetValue(id, out var c) || c.DeviceId != deviceId) throw new KeyNotFoundException("gravação desconhecida");
            if (seq < c.NextSeq) return;
            if (seq > c.NextSeq) throw new InvalidDataException("pedaço fora de ordem");
            if (c.Pcm.Length + pcm.Length > (long)MaxSeconds * SampleRate * 2) throw new InvalidDataException("áudio longo demais");
            c.Pcm.Write(pcm);
            c.NextSeq++;
            c.Touched = DateTimeOffset.Now;
        }
    }

    public void Cancel(string id)
    {
        lock (_lock) _captures.Remove(id);
    }

    public async Task<SttResult> EndAsync(string id, string deviceId, CancellationToken ct)
    {
        Capture c;
        lock (_lock)
        {
            if (!_captures.Remove(id, out c!) || c.DeviceId != deviceId) throw new KeyNotFoundException("gravação desconhecida");
        }
        var bytes = c.Pcm.ToArray();
        c.Pcm.Dispose();
        return await TranscribeAsync(bytes, c.Vocab, ct);
    }

    /// <summary>Transcreve PCM16 mono 16 kHz.</summary>
    public async Task<SttResult> TranscribeAsync(byte[] pcm16, IReadOnlyList<string> vocab, CancellationToken ct)
    {
        var samples = new float[pcm16.Length / 2];
        for (var i = 0; i < samples.Length; i++) samples[i] = BitConverter.ToInt16(pcm16, i * 2) / 32768f;
        var audioMs = samples.Length * 1000L / SampleRate;

        var (speechMs, peakRms) = Energy(samples);
        var quiet = peakRms < 0.006;
        if (speechMs < 250)
            return new SttResult("", true, quiet, audioMs, 0, quiet ? "Não consegui ouvir. Fale um pouco mais perto do celular." : "Não ouvi nenhuma fala.");

        await EnsureReadyAsync(ct);
        if (_factory is null) throw new InvalidOperationException(Error ?? "transcrição indisponível");

        // voz muito baixa: normaliza o volume antes (o Whisper lida melhor com sinal cheio)
        if (peakRms < 0.05)
        {
            var gain = (float)Math.Min(20, 0.1 / Math.Max(peakRms, 1e-4));
            for (var i = 0; i < samples.Length; i++) samples[i] = Math.Clamp(samples[i] * gain, -1f, 1f);
        }

        var sw = Stopwatch.StartNew();
        var sb = new StringBuilder();
        await _gpu.WaitAsync(ct);
        try
        {
            var builder = _factory.CreateBuilder().WithLanguage("pt").WithNoContext();
            var prompt = BuildPrompt(vocab);
            if (prompt is not null) builder = builder.WithPrompt(prompt);
            await using var p = builder.Build();
            await foreach (var seg in p.ProcessAsync(samples, ct))
            {
                if (seg.NoSpeechProbability > 0.8f && seg.Text.Trim().Length < 40) continue;
                sb.Append(seg.Text);
            }
        }
        finally
        {
            _gpu.Release();
        }
        LastProcessMs = sw.ElapsedMilliseconds;
        var text = Clean(sb.ToString(), vocab);
        var empty = text.Length == 0;
        return new SttResult(text, empty, quiet, audioMs, sw.ElapsedMilliseconds,
            empty ? "Não ouvi nenhuma fala." : quiet ? "O áudio chegou bem baixo; confira o texto." : null);
    }

    /// <summary>Duração com fala (janelas de 30 ms bem acima do ruído) e o pico de energia.</summary>
    private static (long SpeechMs, double PeakRms) Energy(float[] s)
    {
        const int frame = SampleRate * 30 / 1000;
        var rms = new List<double>();
        for (var i = 0; i + frame <= s.Length; i += frame)
        {
            double acc = 0;
            for (var j = i; j < i + frame; j++) acc += s[j] * s[j];
            rms.Add(Math.Sqrt(acc / frame));
        }
        if (rms.Count == 0) return (0, 0);
        var sorted = rms.OrderBy(x => x).ToList();
        var floor = sorted[sorted.Count / 5];
        var threshold = Math.Max(floor * 3, 0.0012);
        var speechFrames = rms.Count(r => r > threshold);
        return (speechFrames * 30L, sorted[^1]);
    }

    private static string? BuildPrompt(IReadOnlyList<string> vocab)
    {
        // O prompt inicial orienta grafia de nomes e termos técnicos; limitado para não estourar os ~224 tokens.
        if (vocab.Count == 0) return "Transcrição em português do Brasil, com pontuação.";
        var sb = new StringBuilder("Transcrição em português do Brasil, com pontuação. Termos: ");
        foreach (var v in vocab)
        {
            if (sb.Length + v.Length > 600) break;
            sb.Append(v).Append(", ");
        }
        return sb.ToString().TrimEnd(' ', ',') + ".";
    }

    /// <summary>Limpa o texto: remove frases-fantasma e corrige termos do dicionário pessoal com grafia próxima.</summary>
    public static string Clean(string raw, IReadOnlyList<string> vocab)
    {
        var t = Regex.Replace(raw, @"\s+", " ").Trim();
        t = Regex.Replace(t, @"\[(música|musica|risos|aplausos|silêncio|inaudível)\]", "", RegexOptions.IgnoreCase).Trim();
        var lower = t.ToLowerInvariant().Trim(' ', '.', '!', '?', ',');
        if (lower.Length == 0) return "";
        foreach (var ph in Phantoms)
            if (lower == ph.Trim('.') || (lower.StartsWith(ph.Trim('.')) && lower.Length < ph.Length + 4)) return "";
        if (t.All(c => char.IsPunctuation(c) || char.IsWhiteSpace(c))) return "";
        return ApplyVocab(t, vocab);
    }

    /// <summary>"Ionix" → "Aionix" quando o termo está no dicionário e a grafia é muito parecida.</summary>
    public static string ApplyVocab(string text, IReadOnlyList<string> vocab)
    {
        if (vocab.Count == 0) return text;
        var singles = vocab.Where(v => !v.Contains(' ') && v.Length >= 4).ToList();
        return Regex.Replace(text, @"[\p{L}\p{N}][\p{L}\p{N}\-]*", m =>
        {
            var w = m.Value;
            if (w.Length < 4) return w;
            string? best = null;
            var bestScore = 0.0;
            foreach (var v in singles)
            {
                if (string.Equals(v, w, StringComparison.OrdinalIgnoreCase)) return v; // mesma palavra: só acerta a grafia
                var d = Levenshtein(Norm(w), Norm(v));
                var score = 1.0 - (double)d / Math.Max(w.Length, v.Length);
                if (d <= 2 && score >= 0.8 && score > bestScore) { best = v; bestScore = score; }
                // termos curtos: uma letra de diferença, mesma inicial ("Quen" → "Qwen")
                if (d == 1 && v.Length <= 5 && char.ToLowerInvariant(w[0]) == char.ToLowerInvariant(v[0]) && 0.79 > bestScore) { best = v; bestScore = 0.79; }
                // mesmo som, grafia diferente ("Ioniqs" ~ "Aionix")
                var pw = Phonetic(w);
                var pv = Phonetic(v);
                var pd = Levenshtein(pw, pv);
                var pscore = 1.0 - (double)pd / Math.Max(pw.Length, pv.Length);
                if (pv.Length >= 5 && pd <= 1 && pscore > bestScore) { best = v; bestScore = pscore; }
            }
            // só troca palavras que não são comuns em português (começam com maiúscula ou não têm acento/forma comum)
            return best is not null && char.IsUpper(w[0]) ? best : w;
        });
    }

    private static string Norm(string s)
    {
        var d = s.ToLowerInvariant().Normalize(NormalizationForm.FormD);
        return new string(d.Where(c => CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark).ToArray());
    }

    /// <summary>Chave fonética simples para português/inglês técnico: aproxima grafias que soam igual.</summary>
    private static string Phonetic(string s)
    {
        var t = Norm(s);
        t = Regex.Replace(t, "ph", "f");
        t = Regex.Replace(t, "qu|q|ck|c(?=[aou])|k", "k");
        t = Regex.Replace(t, "x", "ks");
        t = Regex.Replace(t, "(?<![cln])h", "");
        t = t.Replace('w', 'u').Replace('y', 'i').Replace('z', 's');
        t = Regex.Replace(t, "(.)\\1+", "$1");
        return t;
    }

    private static int Levenshtein(string a, string b)
    {
        var dp = new int[b.Length + 1];
        for (var j = 0; j <= b.Length; j++) dp[j] = j;
        for (var i = 1; i <= a.Length; i++)
        {
            var prev = dp[0];
            dp[0] = i;
            for (var j = 1; j <= b.Length; j++)
            {
                var tmp = dp[j];
                dp[j] = Math.Min(Math.Min(dp[j] + 1, dp[j - 1] + 1), prev + (a[i - 1] == b[j - 1] ? 0 : 1));
                prev = tmp;
            }
        }
        return dp[b.Length];
    }

    private void Sweep()
    {
        // gravações esquecidas (celular caiu no meio) somem da memória
        lock (_lock)
            foreach (var k in _captures.Where(kv => DateTimeOffset.Now - kv.Value.Touched > TimeSpan.FromMinutes(5)).Select(kv => kv.Key).ToList())
            {
                _captures[k].Pcm.Dispose();
                _captures.Remove(k);
            }
    }

    public void Dispose()
    {
        _sweep.Dispose();
        _factory?.Dispose();
        _http.Dispose();
    }
}
