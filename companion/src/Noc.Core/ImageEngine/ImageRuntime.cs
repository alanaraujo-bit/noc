using System.Diagnostics;
using System.IO.Compression;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace Noc.Core.ImageEngine;

public enum RuntimeState { Unknown, Missing, Installing, Ready, Failed }

/// <summary>
/// Prepara sozinho o ambiente que roda o worker: uv (gerenciador oficial da Astral, versão fixada e conferida),
/// Python 3.12 isolado, PyTorch com CUDA e as bibliotecas com versões fixadas. Tudo dentro de &lt;raiz&gt;\runtime —
/// nada é instalado no sistema, nada mexe em outro Python do PC. O worker e a lista de versões vêm embutidos no Noc.exe.
/// </summary>
public sealed class ImageRuntime
{
    private const string UvVersion = "0.12.19";
    private const string UvZipSha256 = "6dbb02d79e419522f1c500f0adb1cddcff0cda7d59b0d66ea7f5e3b4a1b2f5f0";
    private const string TorchVersion = "2.14.0";
    private const string PythonVersion = "3.12";
    private readonly SemaphoreSlim _gate = new(1, 1);

    public RuntimeState State { get; private set; } = RuntimeState.Unknown;
    public string? Step { get; private set; }
    public int StepIndex { get; private set; }
    public const int StepCount = 5;
    public string? Error { get; private set; }
    public string? CudaIndex { get; private set; }
    public event Action? Changed;
    public event Action<string>? Trace;

    public static string UvExe => Path.Combine(ImagePaths.Runtime, "uv", "uv.exe");
    public static string VenvDir => Path.Combine(ImagePaths.Runtime, "venv");
    public static string PythonExe => Path.Combine(VenvDir, "Scripts", "python.exe");
    public static string WorkerDir => Path.Combine(ImagePaths.Runtime, "worker");
    public static string WorkerScript => Path.Combine(WorkerDir, "noc_image_worker.py");
    private static string StampFile => Path.Combine(ImagePaths.Runtime, "noc-runtime.json");

    private static string Embedded(string name)
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name) ?? throw new InvalidOperationException("recurso ausente: " + name);
        using var r = new StreamReader(s, Encoding.UTF8);
        return r.ReadToEnd();
    }

    public static string WorkerSource => Embedded("noc_image_worker.py");
    public static string Requirements => Embedded("noc_image_requirements.txt");

    /// <summary>Assinatura do ambiente esperado: muda quando o Noc traz outras versões de bibliotecas.</summary>
    private static string ExpectedStamp(string cudaIndex) =>
        Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes($"{PythonVersion}|{TorchVersion}|{cudaIndex}|{Requirements}"))).ToLowerInvariant()[..16];

    /// <summary>Confere rápido (sem rodar nada) se o ambiente está pronto e o worker atualizado.</summary>
    public bool QuickCheck()
    {
        try
        {
            if (!File.Exists(PythonExe) || !File.Exists(StampFile)) { State = RuntimeState.Missing; return false; }
            var stamp = JsonNode.Parse(File.ReadAllText(StampFile));
            var idx = stamp?["cudaIndex"]?.GetValue<string>() ?? "";
            if (stamp?["stamp"]?.GetValue<string>() != ExpectedStamp(idx)) { State = RuntimeState.Missing; return false; }
            CudaIndex = idx;
            SyncWorkerScript();
            State = RuntimeState.Ready;
            return true;
        }
        catch (Exception) { State = RuntimeState.Missing; return false; }
    }

    /// <summary>O worker embutido no Noc.exe é a fonte da verdade: regrava se mudou.</summary>
    private static void SyncWorkerScript()
    {
        Directory.CreateDirectory(WorkerDir);
        var src = WorkerSource;
        if (!File.Exists(WorkerScript) || File.ReadAllText(WorkerScript) != src)
            File.WriteAllText(WorkerScript, src, new UTF8Encoding(false));
    }

    /// <summary>Garante o ambiente (instala o que falta). Seguro chamar várias vezes.</summary>
    public async Task EnsureAsync(CancellationToken ct)
    {
        if (State == RuntimeState.Ready || QuickCheck()) return;
        await _gate.WaitAsync(ct);
        try
        {
            if (QuickCheck()) return;
            State = RuntimeState.Installing;
            Error = null;
            var sw = Stopwatch.StartNew();
            Directory.CreateDirectory(ImagePaths.Runtime);
            var cacheDir = Path.Combine(ImagePaths.Cache, ImagePaths.HostTag is null ? "uv" : "uv-" + ImagePaths.HostTag);
            var env = new Dictionary<string, string>
            {
                ["UV_PYTHON_INSTALL_DIR"] = Path.Combine(ImagePaths.Runtime, "python"),
                ["UV_CACHE_DIR"] = cacheDir,
                ["UV_NO_PROGRESS"] = "1",
                ["UV_PYTHON_PREFERENCE"] = "only-managed",
            };

            SetStep(1, "Baixando o instalador de pacotes");
            await EnsureUvAsync(ct);

            SetStep(2, "Preparando o Python isolado");
            await RunAsync(UvExe, ["python", "install", PythonVersion], env, ct);
            if (!File.Exists(PythonExe)) await RunAsync(UvExe, ["venv", "--python", PythonVersion, VenvDir], env, ct);

            var index = await ChooseCudaIndexAsync(ct);
            CudaIndex = index;
            SetStep(3, "Baixando o PyTorch com CUDA (≈2,7 GB)");
            await RunAsync(UvExe, ["pip", "install", "--python", PythonExe, $"torch=={TorchVersion}", "--index-url", $"https://download.pytorch.org/whl/{index}"], env, ct);

            SetStep(4, "Instalando as bibliotecas de geração");
            var req = Path.Combine(ImagePaths.Runtime, "requirements.txt");
            await File.WriteAllTextAsync(req, Requirements, new UTF8Encoding(false), ct);
            await RunAsync(UvExe, ["pip", "install", "--python", PythonExe, "-r", req], env, ct);

            SetStep(5, "Conferindo a GPU");
            var probe = await RunAsync(PythonExe, ["-c", "import torch,diffusers;print(torch.cuda.is_available());print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else '')"], env, ct);
            if (!probe.Contains("True")) throw new RuntimeSetupException("cuda_unavailable", "O PyTorch não encontrou a GPU NVIDIA.");
            SyncWorkerScript();
            await File.WriteAllTextAsync(StampFile, new JsonObject
            {
                ["stamp"] = ExpectedStamp(index), ["cudaIndex"] = index, ["torch"] = TorchVersion, ["python"] = PythonVersion,
                ["installedAt"] = DateTimeOffset.Now.ToUnixTimeMilliseconds(),
            }.ToJsonString(), ct);
            // os pacotes baixados já foram instalados: o cache só ocuparia espaço (≈3 GB)
            try { Directory.Delete(cacheDir, true); } catch (Exception) { }
            State = RuntimeState.Ready;
            Step = null;
            Trace?.Invoke($"ambiente do motor de imagem pronto em {sw.Elapsed.TotalSeconds:0} s ({index})");
        }
        catch (OperationCanceledException) { State = RuntimeState.Missing; throw; }
        catch (RuntimeSetupException e)
        {
            State = RuntimeState.Failed;
            Error = e.Code;
            Trace?.Invoke($"preparação do ambiente falhou: {e.Code} {e.Message}");
            throw;
        }
        catch (Exception e)
        {
            State = RuntimeState.Failed;
            Error = "runtime_failed";
            Trace?.Invoke($"preparação do ambiente falhou: {e.GetType().Name} {e.Message}");
            throw new RuntimeSetupException("runtime_failed", e.Message);
        }
        finally
        {
            _gate.Release();
            Changed?.Invoke();
        }
    }

    private void SetStep(int i, string label)
    {
        StepIndex = i;
        Step = label;
        Trace?.Invoke($"ambiente do motor de imagem: {label}");
        Changed?.Invoke();
    }

    private static async Task EnsureUvAsync(CancellationToken ct)
    {
        if (File.Exists(UvExe)) return;
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Noc-Companion/" + CompanionHost.Version);
        var bytes = await http.GetByteArrayAsync($"https://github.com/astral-sh/uv/releases/download/{UvVersion}/uv-x86_64-pc-windows-msvc.zip", ct);
        var sha = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        if (sha != UvZipSha256) throw new RuntimeSetupException("runtime_failed", "o instalador baixado não confere (SHA-256)");
        var dir = Path.GetDirectoryName(UvExe)!;
        Directory.CreateDirectory(dir);
        using var zip = new ZipArchive(new MemoryStream(bytes));
        foreach (var e in zip.Entries.Where(e => e.Name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)))
            e.ExtractToFile(Path.Combine(dir, e.Name), overwrite: true);
    }

    /// <summary>CUDA 13 precisa de driver 580+; com driver mais antigo usa o build CUDA 12.6.</summary>
    private static async Task<string> ChooseCudaIndexAsync(CancellationToken ct)
    {
        var v = await DriverVersionAsync(ct);
        if (v is null) throw new RuntimeSetupException("cuda_unavailable", "Nenhuma GPU NVIDIA encontrada (nvidia-smi).");
        if (v.Major >= 580) return "cu130";
        if (v.Major >= 560) return "cu126";
        throw new RuntimeSetupException("driver_incompatible", $"Driver NVIDIA {v} é antigo demais.");
    }

    public static async Task<Version?> DriverVersionAsync(CancellationToken ct)
    {
        try
        {
            var psi = new ProcessStartInfo("nvidia-smi", "--query-gpu=driver_version --format=csv,noheader")
            { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true };
            using var p = Process.Start(psi)!;
            var line = (await p.StandardOutput.ReadToEndAsync(ct)).Trim().Split('\n')[0].Trim();
            await p.WaitForExitAsync(ct);
            return Version.TryParse(line, out var v) ? v : null;
        }
        catch (Exception) when (!ct.IsCancellationRequested) { return null; }
    }

    private async Task<string> RunAsync(string exe, string[] args, Dictionary<string, string> env, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(exe)
        {
            CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var a in args) psi.ArgumentList.Add(a);
        foreach (var (k, v) in env) psi.Environment[k] = v;
        psi.Environment["PYTHONUTF8"] = "1";
        using var p = Process.Start(psi) ?? throw new RuntimeSetupException("runtime_failed", "não foi possível iniciar " + Path.GetFileName(exe));
        var outTask = p.StandardOutput.ReadToEndAsync(ct);
        var errTask = p.StandardError.ReadToEndAsync(ct);
        try { await p.WaitForExitAsync(ct); }
        catch (OperationCanceledException) { try { p.Kill(true); } catch { } throw; }
        var output = await outTask;
        var err = await errTask;
        if (p.ExitCode != 0)
        {
            var tail = string.Join(" | ", err.Split('\n').Select(l => l.Trim()).Where(l => l.Length > 0).TakeLast(4));
            Trace?.Invoke($"{Path.GetFileName(exe)} {string.Join(' ', args.Take(3))} saiu com {p.ExitCode}: {tail}");
            if (err.Contains("No space left", StringComparison.OrdinalIgnoreCase) || err.Contains("os error 112", StringComparison.OrdinalIgnoreCase))
                throw new RuntimeSetupException("no_space", "espaço em disco insuficiente");
            if (err.Contains("dns error", StringComparison.OrdinalIgnoreCase) || err.Contains("connect", StringComparison.OrdinalIgnoreCase))
                throw new RuntimeSetupException("network", "sem conexão para baixar os componentes");
            throw new RuntimeSetupException("runtime_failed", tail);
        }
        return output;
    }

    public JsonObject ToJson() => new()
    {
        ["state"] = State.ToString().ToLowerInvariant(), ["step"] = Step, ["stepIndex"] = StepIndex, ["steps"] = StepCount,
        ["error"] = Error, ["cuda"] = CudaIndex,
    };
}

public sealed class RuntimeSetupException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}
