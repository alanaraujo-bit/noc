using System.Diagnostics;
using System.Globalization;
using System.Text.Json.Nodes;

namespace Noc.Core.Platform;

public sealed record GpuSample(string Name, int VramTotalMb, int VramUsedMb, int UtilPercent, int TempC)
{
    public JsonObject ToJson() => new()
    {
        ["name"] = Name, ["vramTotalMb"] = VramTotalMb, ["vramUsedMb"] = VramUsedMb,
        ["util"] = UtilPercent, ["tempC"] = TempC,
    };
}

/// <summary>Lê a GPU via nvidia-smi (se existir). Sem NVIDIA, simplesmente não informa GPU.</summary>
public sealed class GpuMonitor : IDisposable
{
    private readonly Timer _timer;
    private bool _available = true;
    private int _busy;
    public GpuSample? Last { get; private set; }
    public event Action? Changed;

    public GpuMonitor()
    {
        _timer = new Timer(_ => Sample(), null, 0, 5000);
    }

    /// <summary>Leitura imediata (bloqueia até ~1 s). Usada antes de decidir quanto contexto cabe.</summary>
    public GpuSample? SampleNow()
    {
        for (var i = 0; i < 20 && Volatile.Read(ref _busy) == 1; i++) Thread.Sleep(50);
        Sample();
        return Last;
    }

    private void Sample()
    {
        if (!_available || Interlocked.Exchange(ref _busy, 1) == 1) return;
        try
        {
            var psi = new ProcessStartInfo("nvidia-smi",
                "--query-gpu=name,memory.total,memory.used,utilization.gpu,temperature.gpu --format=csv,noheader,nounits")
            {
                CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true,
            };
            using var p = Process.Start(psi);
            if (p is null) { _available = false; return; }
            var line = p.StandardOutput.ReadLine();
            if (!p.WaitForExit(4000)) { try { p.Kill(); } catch { } return; }
            if (string.IsNullOrWhiteSpace(line)) return;
            var parts = line.Split(',').Select(s => s.Trim()).ToArray();
            if (parts.Length < 5) return;
            int I(string s) => int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var v) ? v : 0;
            var sample = new GpuSample(parts[0], I(parts[1]), I(parts[2]), I(parts[3]), I(parts[4]));
            var changed = Last is null || Math.Abs(Last.VramUsedMb - sample.VramUsedMb) > 64 ||
                          Math.Abs(Last.UtilPercent - sample.UtilPercent) > 5 || Last.TempC != sample.TempC;
            Last = sample;
            if (changed) Changed?.Invoke();
        }
        catch (System.ComponentModel.Win32Exception)
        {
            _available = false; // nvidia-smi não existe nesta máquina
        }
        catch (Exception)
        {
            // amostra perdida; tenta de novo no próximo ciclo
        }
        finally
        {
            Interlocked.Exchange(ref _busy, 0);
        }
    }

    public void Dispose() => _timer.Dispose();
}
