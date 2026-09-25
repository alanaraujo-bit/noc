using System.Collections.Concurrent;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Noc.Core.ImageEngine;

/// <summary>
/// O processo Python do motor de imagem. Conversa só por stdin/stdout (JSON por linha) — nenhuma porta.
/// Fica num Job Object do Windows com "matar ao fechar": se o Companion morrer, o worker morre junto
/// (nada de processo órfão segurando VRAM). O stderr fica só na memória, para diagnóstico.
/// </summary>
public sealed class WorkerProcess : IDisposable
{
    private readonly Process _p;
    private readonly ConcurrentDictionary<string, TaskCompletionSource<JsonObject>> _waiters = new();
    private readonly LinkedList<string> _stderr = new();
    private readonly Lock _errLock = new();
    private readonly Lock _writeLock = new();
    private readonly TaskCompletionSource<JsonObject> _ready = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private volatile bool _exited;

    /// <summary>Todo evento do worker (progresso, prévias, estágios de carga).</summary>
    public event Action<JsonObject>? Event;
    public event Action<int>? Exited;

    public int Pid => _p.Id;
    public bool Alive => !_exited;
    public DateTimeOffset StartedAt { get; } = DateTimeOffset.Now;
    public JsonObject? ReadyInfo { get; private set; }

    private WorkerProcess(Process p) => _p = p;

    public static async Task<WorkerProcess> StartAsync(string python, string script, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(python)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
            StandardInputEncoding = new UTF8Encoding(false), StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8,
            WorkingDirectory = Path.GetDirectoryName(script)!,
        };
        psi.ArgumentList.Add("-u");
        psi.ArgumentList.Add(script);
        psi.Environment["PYTHONUTF8"] = "1";
        psi.Environment["PYTHONIOENCODING"] = "utf-8";
        psi.Environment["PYTHONNOUSERSITE"] = "1";
        psi.Environment["HF_HUB_OFFLINE"] = "1";
        psi.Environment["TRANSFORMERS_OFFLINE"] = "1";
        psi.Environment["HF_HUB_DISABLE_TELEMETRY"] = "1";
        psi.Environment["DISABLE_TELEMETRY"] = "1";
        psi.Environment["DO_NOT_TRACK"] = "1";
        psi.Environment["TQDM_DISABLE"] = "1";
        psi.Environment["HF_HOME"] = Path.Combine(ImagePaths.Cache, "hf");
        psi.Environment["TORCH_HOME"] = Path.Combine(ImagePaths.Cache, "torch");
        psi.Environment["TEMP"] = ImagePaths.Tmp;
        psi.Environment["TMP"] = ImagePaths.Tmp;
        psi.Environment.Remove("HF_TOKEN");
        psi.Environment.Remove("HUGGING_FACE_HUB_TOKEN");
        Directory.CreateDirectory(ImagePaths.Tmp);
        var p = Process.Start(psi) ?? throw new InvalidOperationException("não foi possível iniciar o worker");
        JobObject.Assign(p);
        var w = new WorkerProcess(p);
        p.EnableRaisingEvents = true;
        p.Exited += (_, _) => w.OnExit();
        _ = Task.Run(w.ReadStdoutAsync);
        _ = Task.Run(w.ReadStderrAsync);
        using var reg = ct.Register(() => w._ready.TrySetCanceled());
        var timeout = Task.Delay(TimeSpan.FromMinutes(3), ct);
        var done = await Task.WhenAny(w._ready.Task, timeout);
        if (done != w._ready.Task)
        {
            w.Kill();
            ct.ThrowIfCancellationRequested();
            throw new WorkerException("worker_start", "o motor de imagem não respondeu ao iniciar");
        }
        w.ReadyInfo = await w._ready.Task;
        return w;
    }

    private void OnExit()
    {
        if (_exited) return;
        _exited = true;
        var code = 0;
        try { code = _p.ExitCode; } catch { }
        _ready.TrySetException(new WorkerException("worker_crash", $"o motor de imagem fechou (código {code})"));
        foreach (var w in _waiters.Values) w.TrySetException(new WorkerException("worker_crash", $"o motor de imagem fechou (código {code})"));
        Exited?.Invoke(code);
    }

    private async Task ReadStdoutAsync()
    {
        try
        {
            while (await _p.StandardOutput.ReadLineAsync() is { } line)
            {
                JsonObject? e;
                try { e = JsonNode.Parse(line) as JsonObject; }
                catch (JsonException) { AddErr("(saída inválida no protocolo)"); continue; }
                if (e is null) continue;
                var ev = e["ev"]?.GetValue<string>();
                if (ev == "ready") { _ready.TrySetResult(e); continue; }
                if (ev == "fatal") AddErr("fatal: " + e["message"]);
                try { Event?.Invoke(e); } catch (Exception) { }
                var id = e["id"]?.GetValue<string>();
                if (id is not null && ev is "loaded" or "done" or "error" or "cancelled" or "rewritten" or "unloaded" or "status" or "hello" &&
                    _waiters.TryRemove(id, out var tcs))
                    tcs.TrySetResult(e);
            }
        }
        catch (Exception) { }
        finally { OnExit(); }
    }

    private async Task ReadStderrAsync()
    {
        try
        {
            while (await _p.StandardError.ReadLineAsync() is { } line) AddErr(line);
        }
        catch (Exception) { }
    }

    private void AddErr(string line)
    {
        lock (_errLock)
        {
            _stderr.AddLast(line.Length > 500 ? line[..500] : line);
            while (_stderr.Count > 300) _stderr.RemoveFirst();
        }
    }

    public IReadOnlyList<string> RecentStderr { get { lock (_errLock) return _stderr.ToList(); } }

    public void Send(JsonObject cmd)
    {
        if (_exited) throw new WorkerException("worker_crash", "o motor de imagem não está rodando");
        var line = cmd.ToJsonString();
        lock (_writeLock)
        {
            _p.StandardInput.WriteLine(line);
            _p.StandardInput.Flush();
        }
    }

    /// <summary>Envia um comando e espera a resposta final dele (loaded/done/error/cancelled/...).</summary>
    public async Task<JsonObject> RequestAsync(JsonObject cmd, CancellationToken ct, TimeSpan? timeout = null)
    {
        var id = cmd["id"]?.GetValue<string>() ?? Guid.NewGuid().ToString("N");
        cmd["id"] = id;
        var tcs = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        _waiters[id] = tcs;
        Send(cmd);
        using var reg = ct.Register(() => tcs.TrySetCanceled(ct));
        if (timeout is { } t)
        {
            var done = await Task.WhenAny(tcs.Task, Task.Delay(t, CancellationToken.None));
            if (done != tcs.Task)
            {
                _waiters.TryRemove(id, out _);
                throw new WorkerException("worker_timeout", "o motor de imagem parou de responder");
            }
        }
        return await tcs.Task;
    }

    /// <summary>Pede para sair (libera a GPU) e, se não sair em alguns segundos, encerra à força.</summary>
    public async Task ShutdownAsync(TimeSpan grace)
    {
        if (_exited) return;
        try { Send(new JsonObject { ["cmd"] = "shutdown" }); _p.StandardInput.Close(); } catch (Exception) { }
        try { await _p.WaitForExitAsync().WaitAsync(grace); }
        catch (Exception) { Kill(); }
    }

    /// <summary>Prioridade baixa (tarefas de manutenção, como otimizar um modelo, não atrapalham o PC).</summary>
    public void LowerPriority()
    {
        try { _p.PriorityClass = ProcessPriorityClass.BelowNormal; } catch (Exception) { }
    }

    public void Kill()
    {
        try { if (!_p.HasExited) _p.Kill(entireProcessTree: true); } catch (Exception) { }
        try { _p.WaitForExit(5000); } catch (Exception) { }
        OnExit();
    }

    public void Dispose()
    {
        Kill();
        _p.Dispose();
    }

    /// <summary>Workers que sobraram de um Companion que caiu (não deveria acontecer com o Job Object, mas custa pouco).</summary>
    public static int KillStrays()
    {
        var killed = 0;
        if (!ImagePaths.HasRoot) return 0;
        var runtime = Path.GetFullPath(ImagePaths.Runtime);
        foreach (var p in Process.GetProcessesByName("python"))
        {
            try
            {
                var path = p.MainModule?.FileName;
                if (path is not null && path.StartsWith(runtime, StringComparison.OrdinalIgnoreCase))
                {
                    p.Kill(true);
                    killed++;
                }
            }
            catch (Exception) { }
            finally { p.Dispose(); }
        }
        return killed;
    }

    /// <summary>Job Object com KILL_ON_JOB_CLOSE: o handle vive enquanto o Companion vive.</summary>
    private static class JobObject
    {
        private static readonly nint Handle = Create();

        private static nint Create()
        {
            var h = CreateJobObject(0, null);
            if (h == 0) return 0;
            var info = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
            info.BasicLimitInformation.LimitFlags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            var len = Marshal.SizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>();
            var ptr = Marshal.AllocHGlobal(len);
            try
            {
                Marshal.StructureToPtr(info, ptr, false);
                SetInformationJobObject(h, 9, ptr, (uint)len); // JobObjectExtendedLimitInformation
            }
            finally { Marshal.FreeHGlobal(ptr); }
            return h;
        }

        public static void Assign(Process p)
        {
            if (Handle != 0) AssignProcessToJobObject(Handle, p.Handle);
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] private static extern nint CreateJobObject(nint a, string? name);
        [DllImport("kernel32.dll")] private static extern bool SetInformationJobObject(nint job, int cls, nint info, uint len);
        [DllImport("kernel32.dll")] private static extern bool AssignProcessToJobObject(nint job, nint process);

        [StructLayout(LayoutKind.Sequential)]
        private struct JOBOBJECT_BASIC_LIMIT_INFORMATION
        {
            public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
            public uint LimitFlags;
            public nuint MinimumWorkingSetSize, MaximumWorkingSetSize;
            public uint ActiveProcessLimit;
            public nuint Affinity;
            public uint PriorityClass, SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct IO_COUNTERS
        {
            public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION
        {
            public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
            public IO_COUNTERS IoInfo;
            public nuint ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
        }
    }
}

public sealed class WorkerException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}
