using System.Collections.ObjectModel;
using System.Windows.Input;
using System.Windows.Media;
using Noc.Core;
using Noc.Core.Library;
using Noc.Core.LmStudio;

namespace Noc.Companion.ViewModels;

/// <summary>Opção de modelo dentro de um perfil (chip).</summary>
public sealed class TierOption : Observable
{
    private readonly Action<string> _choose;
    private bool _selected;
    public required string Key { get; init; }
    public required string Name { get; init; }
    public required string Group { get; init; }
    public bool Selected
    {
        get => _selected;
        set { if (Set(ref _selected, value) && value) _choose(Key); }
    }
    public TierOption(Action<string> choose, bool selected) { _choose = choose; _selected = selected; }
}

public sealed class TierRow
{
    public required string Symbol { get; init; }
    public required string Label { get; init; }
    public required string Blurb { get; init; }
    public required List<TierOption> Options { get; init; }
}

public sealed class ModelRow
{
    public required string Key { get; init; }
    public required string Name { get; init; }
    public required string Caps { get; init; }
    public required string Status { get; init; }
    public required Brush StatusColor { get; init; }
    public required bool Loaded { get; init; }
    public required bool Busy { get; init; }
    public required string Bench { get; init; }
    public required string Error { get; init; }
    public required string Badges { get; init; }
    public required string Star { get; init; }
    public required string Technical { get; init; }
    public string Signature => string.Join('|', Key, Name, Caps, Status, Loaded, Busy, Bench, Error, Badges, Star);
}

public sealed class LibraryRow
{
    public required string File { get; init; }
    public required string Detail { get; init; }
    public required double Progress { get; init; }
    public required bool Working { get; init; }
    public required bool Failed { get; init; }
}

public sealed partial class MainViewModel
{
    public ObservableCollection<TierRow> TierRows { get; } = [];
    public ObservableCollection<ModelRow> ModelRows { get; } = [];
    public ObservableCollection<LibraryRow> LibraryRows { get; } = [];
    public ObservableCollection<TierOption> DefaultOptions { get; } = [];

    private string _voiceTitle = "", _voiceDetail = "";
    public string VoiceTitle { get => _voiceTitle; set => Set(ref _voiceTitle, value); }
    public string VoiceDetail { get => _voiceDetail; set => Set(ref _voiceDetail, value); }
    private Brush _voiceColor = Brushes.Gray;
    public Brush VoiceColor { get => _voiceColor; set => Set(ref _voiceColor, value); }
    private bool _hasLibrary;
    public bool HasLibrary { get => _hasLibrary; set => Set(ref _hasLibrary, value); }
    private string _benchLine = "";
    public string BenchLine { get => _benchLine; set => Set(ref _benchLine, value); }
    private string _tierSig = "", _defaultSig = "";

    public ICommand LoadModel { get; private set; } = null!;
    public ICommand UnloadModel { get; private set; } = null!;
    public ICommand BenchModel { get; private set; } = null!;
    public ICommand FavoriteModel { get; private set; } = null!;
    public ICommand ScanModels { get; private set; } = null!;

    private void InitModels()
    {
        LoadModel = new Command(p =>
        {
            if (p is not string key) return;
            _ = Task.Run(async () =>
            {
                try { await _host.Models.EnsureLoadedAsync(key, null, CancellationToken.None, _host.Catalog.TierOf(key)); }
                catch (Exception) { /* a atividade e o cartão mostram o erro */ }
            });
        });
        UnloadModel = new Command(p => { if (p is string key) _ = Task.Run(() => _host.Models.UnloadAsync(key, CancellationToken.None)); });
        BenchModel = new Command(p =>
        {
            if (p is string key && !_host.Bench.TryStart(key, out var why)) BenchLine = why ?? "";
        });
        FavoriteModel = new Command(p =>
        {
            if (p is not string key) return;
            var prefs = _host.Catalog.Prefs(key);
            prefs.Favorite = !prefs.Favorite;
            _host.Catalog.Save();
        });
        ScanModels = new Command(() => _ = Task.Run(() => _host.Library.ScanAndImportAsync(CancellationToken.None)));
    }

    private static readonly System.Globalization.CultureInfo PtBr = new("pt-BR");
    private static string Pt(System.Text.Json.Nodes.JsonNode n) => n.GetValue<double>().ToString("#,0.#", PtBr);

    private void RefreshModels()
    {
        var all = _host.Models.Last.Models;
        var llms = all.Where(m => m.Type == "llm").ToList();
        var vramMb = _host.Gpu.Last?.VramTotalMb ?? 0;
        // perfis só oferecem o que cabe inteiro na GPU (um modelo que transborda fica dezenas de vezes mais lento)
        var fitting = llms.Where(m => !(_host.Catalog.Data.Models.TryGetValue(m.Key, out var p) && p.Hidden) &&
                                      (vramMb == 0 || m.SizeBytes / 1048576.0 < vramMb * 0.95)).ToList();

        // perfis: só reconstrói quando algo mudou (senão os chips piscam)
        var tierSig = string.Join(';', Tiers.All.Select(t => t + "=" + _host.Catalog.ModelForTier(t))) + "|" + string.Join(',', fitting.Select(m => m.Key + _host.Catalog.DisplayName(m, all)));
        if (tierSig != _tierSig)
        {
            _tierSig = tierSig;
            TierRows.Clear();
            foreach (var t in Tiers.All)
            {
                var current = _host.Catalog.ModelForTier(t);
                var tier = t;
                TierRows.Add(new TierRow
                {
                    Symbol = t switch { Tiers.Fast => "⚡", Tiers.Smart => "◆", _ => "◈" },
                    Label = Tiers.Label(t),
                    Blurb = t switch { Tiers.Fast => "Respostas imediatas", Tiers.Smart => "Equilíbrio entre qualidade e velocidade", _ => "Máxima qualidade, mais devagar" },
                    Options = fitting.Select(m => new TierOption(k => _host.Catalog.AssignTier(tier, k), m.Key == current)
                    {
                        Key = m.Key, Name = _host.Catalog.DisplayName(m, all), Group = "tier-" + t,
                    }).ToList(),
                });
            }
        }
        var defSig = _host.Catalog.Data.DefaultModel + "|" + string.Join(',', fitting.Select(m => m.Key));
        if (defSig != _defaultSig)
        {
            _defaultSig = defSig;
            DefaultOptions.Clear();
            foreach (var m in fitting)
                DefaultOptions.Add(new TierOption(k => _host.Catalog.SetDefault(k, true), m.Key == _host.Catalog.Data.DefaultModel)
                {
                    Key = m.Key, Name = _host.Catalog.DisplayName(m, all), Group = "default",
                });
        }

        // cartões dos modelos
        var gpuMb = _host.Gpu.Last?.VramTotalMb ?? 0;
        var rows = llms.OrderByDescending(m => m.Loaded).ThenByDescending(m => _host.Catalog.TierOf(m.Key) is not null).ThenBy(m => m.SizeBytes).Select(m =>
        {
            var prefs = _host.Catalog.Data.Models.TryGetValue(m.Key, out var p) ? p : new ModelPrefs();
            var loading = _host.Models.Op == ModelOpState.Loading && _host.Models.OpModel == m.Key;
            var testing = _host.Bench.RunningModel == m.Key;
            var tooBig = gpuMb > 0 && m.SizeBytes / 1048576.0 > gpuMb * 0.95;
            var (status, color) = loading ? ("Carregando…", B("Warn"))
                : testing ? (_host.Bench.Step ?? "Testando…", B("Warn"))
                : m.Loaded ? ($"Carregado · {m.Instances[0].ContextLength / 1024}k de contexto", B("Ok"))
                : prefs.LastError is not null ? ("Não carregou", B("Err"))
                : tooBig ? ("Não cabe na GPU", B("Warn"))
                : ("Pronto no disco", B("Text3"));
            var bench = prefs.Benchmark is { } b
                ? string.Join(" · ", new[]
                {
                    b["tps"] is { } tps ? $"{Pt(tps)} tokens/s" : null,
                    b["ttftMs"] is { } tt ? $"1º token {Pt(tt)} ms" : null,
                    b["loadSeconds"] is { } ls ? $"carga {Pt(ls)} s" : null,
                    b["prefillTps"] is { } pf ? $"lê {Pt(pf)} tokens/s" : null,
                    b["vision"]?["ok"]?.GetValue<bool>() == true ? "visão ok" : null,
                }.Where(x => x is not null))
                : "";
            var tier = _host.Catalog.TierOf(m.Key);
            return new ModelRow
            {
                Key = m.Key,
                Name = _host.Catalog.DisplayName(m, all),
                Technical = m.DisplayName + " · " + m.Key,
                Caps = string.Join(" · ", new[] { m.Vision ? "visão" : null, m.ReasoningOptions.Length > 0 ? "raciocínio" : null, m.Quantization, $"{m.SizeBytes / 1e9:0.0} GB" }.Where(x => x is not null)),
                Status = status, StatusColor = color, Loaded = m.Loaded, Busy = loading || testing,
                Bench = bench, Error = prefs.LastError ?? "",
                Badges = string.Join("  ·  ", new[] { tier is null ? null : Tiers.Label(tier), _host.Catalog.Data.DefaultModel == m.Key ? "Padrão" : null }.Where(x => x is not null)),
                Star = prefs.Favorite ? "★" : "☆",
            };
        }).ToList();
        if (!rows.Select(r => r.Signature).SequenceEqual(ModelRows.Select(r => r.Signature)))
        {
            ModelRows.Clear();
            foreach (var r in rows) ModelRows.Add(r);
        }

        // importações automáticas
        var lib = _host.Library.Items.Where(i => i.State is ImportState.Working or ImportState.Failed || i.Repaired || i.VisionAdded).Take(8).Select(i => new LibraryRow
        {
            File = i.FileName,
            Detail = i.State switch
            {
                ImportState.Working => (i.Step ?? "Adicionando…") + (i.Progress is { } pr ? $" · {pr * 100:0}%" : ""),
                ImportState.Failed => i.Detail ?? "Não foi possível adicionar",
                _ => string.Join(" · ", new[] { "Adicionado", i.Repaired ? "ajustado para o LM Studio" : null, i.VisionAdded ? "visão ativada" : null }.Where(x => x is not null)),
            },
            Progress = i.Progress ?? 0, Working = i.State == ImportState.Working, Failed = i.State == ImportState.Failed,
        }).ToList();
        LibraryRows.Clear();
        foreach (var r in lib) LibraryRows.Add(r);
        HasLibrary = LibraryRows.Count > 0;

        // voz
        (VoiceTitle, VoiceDetail, VoiceColor) = !_host.Settings.VoiceEnabled
            ? ("Ditado desligado", "Ative em Ajustes para ditar pelo celular.", B("Text3"))
            : _host.Stt.State switch
            {
                Core.Speech.SttState.Ready => ("Ditado pronto", "Whisper large-v3-turbo na GPU · " + (_host.Stt.Runtime ?? ""), B("Ok")),
                Core.Speech.SttState.Downloading => ("Baixando o modelo de voz", $"{(_host.Stt.DownloadProgress ?? 0) * 100:0}% de 874 MB (uma vez só)", B("Warn")),
                Core.Speech.SttState.Loading => ("Preparando o ditado…", "", B("Warn")),
                Core.Speech.SttState.Failed => ("Ditado indisponível", _host.Stt.Error ?? "", B("Err")),
                _ => ("Ditado", "Prepara quando o celular pedir.", B("Text3")),
            };
    }
}
