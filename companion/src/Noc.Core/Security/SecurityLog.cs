using System.Text.Json;
using Noc.Core.Storage;

namespace Noc.Core.Security;

public enum SecurityLevel { Info, Warning, Alert }

public sealed record SecurityEvent(DateTimeOffset At, SecurityLevel Level, string Kind, string Message, string? Device = null, string? Route = null);

/// <summary>
/// Registro de segurança: pareamentos, logins, recusas, revogações.
/// Nunca contém conteúdo de conversa. Arquivo JSONL com rotação simples.
/// </summary>
public sealed class SecurityLog
{
    private const int MemoryLimit = 300;
    private const long FileLimit = 2 * 1024 * 1024;
    private readonly Lock _lock = new();
    private readonly LinkedList<SecurityEvent> _recent = new();

    public event Action<SecurityEvent>? Added;

    public SecurityLog()
    {
        try
        {
            if (!File.Exists(AppPaths.SecurityLog)) return;
            foreach (var line in File.ReadLines(AppPaths.SecurityLog).TakeLast(MemoryLimit))
            {
                var ev = JsonSerializer.Deserialize<SecurityEvent>(line);
                if (ev is not null) _recent.AddLast(ev);
            }
        }
        catch (Exception)
        {
            // log ilegível não pode impedir o Companion de abrir
        }
    }

    public IReadOnlyList<SecurityEvent> Recent(int max = 100)
    {
        lock (_lock) return _recent.Reverse().Take(max).ToList();
    }

    public void Write(SecurityLevel level, string kind, string message, string? device = null, string? route = null)
    {
        var ev = new SecurityEvent(DateTimeOffset.Now, level, kind, message, device, route);
        lock (_lock)
        {
            _recent.AddLast(ev);
            while (_recent.Count > MemoryLimit) _recent.RemoveFirst();
            try
            {
                var fi = new FileInfo(AppPaths.SecurityLog);
                if (fi.Exists && fi.Length > FileLimit)
                    File.Move(AppPaths.SecurityLog, AppPaths.SecurityLog + ".1", overwrite: true);
                File.AppendAllText(AppPaths.SecurityLog, JsonSerializer.Serialize(ev) + "\n");
            }
            catch (IOException)
            {
                // disco cheio/bloqueado: mantém em memória
            }
        }
        Added?.Invoke(ev);
    }
}
