using System.Text;
using System.Text.Json.Nodes;
using Noc.Core.Storage;

namespace Noc.Core.Diagnostics;

/// <summary>
/// Registro técnico para diagnóstico: ciclo de vida das tarefas (criada, na fila, gerando, concluída, erro),
/// cargas de modelo, erros crus do LM Studio, voz. Nunca grava o conteúdo das conversas nem áudio.
/// Arquivo diag.log com rotação (2 MB) e os últimos 500 itens na memória para a tela de diagnóstico.
/// </summary>
public sealed class DiagLog
{
    private const long MaxBytes = 2 * 1024 * 1024;
    private readonly LinkedList<(DateTimeOffset At, string Kind, string Text)> _recent = new();
    private readonly Lock _lock = new();
    private static string FilePath => Path.Combine(AppPaths.Root, "diag.log");

    public void Write(string kind, string text)
    {
        var at = DateTimeOffset.Now;
        lock (_lock)
        {
            _recent.AddLast((at, kind, text));
            while (_recent.Count > 500) _recent.RemoveFirst();
            try
            {
                var fi = new FileInfo(FilePath);
                if (fi.Exists && fi.Length > MaxBytes) File.Move(FilePath, FilePath + ".1", overwrite: true);
                File.AppendAllText(FilePath, $"{at:yyyy-MM-dd HH:mm:ss.fff} [{kind}] {text}\n", Encoding.UTF8);
            }
            catch (Exception) { }
        }
    }

    public JsonArray Recent(int max, string? kind = null)
    {
        var arr = new JsonArray();
        lock (_lock)
            foreach (var e in _recent.Reverse().Where(e => kind is null || e.Kind == kind).Take(max))
                arr.Add(new JsonObject { ["at"] = e.At.ToUnixTimeMilliseconds(), ["kind"] = e.Kind, ["text"] = e.Text });
        return arr;
    }
}
