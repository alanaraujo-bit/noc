using System.Collections.ObjectModel;
using System.Globalization;
using System.Windows.Input;
using System.Windows.Media;
using Noc.Companion.Services;
using Noc.Core.ImageEngine;

namespace Noc.Companion.ViewModels;

/// <summary>Um cartão de modelo de imagem (Z-Image Turbo / FLUX.1 Dev) na página Imagens.</summary>
public sealed class ImageModelRow
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public required string Role { get; init; }
    public required string Status { get; init; }
    public required Brush StatusColor { get; init; }
    public string Detail { get; init; } = "";
    public string Error { get; init; } = "";
    public double Progress { get; init; }
    public bool ShowProgress { get; init; }
    public string? Action { get; init; }
    public string? SecondaryAction { get; init; }
    public bool NeedsToken { get; init; }
    public string LicenseUrl { get; init; } = "";
}

public sealed partial class MainViewModel
{
    public ObservableCollection<ImageModelRow> ImageModels { get; } = [];

    private string _imageStatus = "", _imageDetail = "", _imageRoot = "", _imageUsage = "", _hfLine = "", _hfTokenInput = "", _hfMessage = "";
    public string ImageStatus { get => _imageStatus; set => Set(ref _imageStatus, value); }
    public string ImageDetail { get => _imageDetail; set => Set(ref _imageDetail, value); }
    public string ImageRoot { get => _imageRoot; set => Set(ref _imageRoot, value); }
    public string ImageUsage { get => _imageUsage; set => Set(ref _imageUsage, value); }
    public string HfLine { get => _hfLine; set => Set(ref _hfLine, value); }
    public string HfTokenInput { get => _hfTokenInput; set => Set(ref _hfTokenInput, value); }
    public string HfMessage { get => _hfMessage; set => Set(ref _hfMessage, value); }
    private bool _hfConnected, _imagesEnabled;
    public bool HfConnected { get => _hfConnected; set => Set(ref _hfConnected, value); }
    public bool ImagesEnabled
    {
        get => _imagesEnabled;
        set { if (Set(ref _imagesEnabled, value) && _host.Settings.ImageEnabled != value) { _host.Settings.ImageEnabled = value; _host.Settings.Save(); if (value) _host.Images.AutoSetup(); } }
    }

    public ICommand ImageAction { get; private set; } = null!;
    public ICommand ImageSecondary { get; private set; } = null!;
    public ICommand SaveHfToken { get; private set; } = null!;
    public ICommand DisconnectHf { get; private set; } = null!;
    public ICommand OpenImageFolder { get; private set; } = null!;
    public ICommand OpenUrl { get; private set; } = null!;
    private int _imageUsageTick;

    private void InitImages()
    {
        ImageAction = new Command(p =>
        {
            if (p is "aux") { foreach (var a in ImageCatalog.AuxPackages.Where(a => !_host.Images.Store.IsInstalled(a))) _host.Images.QueueInstall(a); return; }
            if (p is not string id || ImageCatalog.Find(id) is not { } m) return;
            var st = _host.Images.Store.StateOf(m);
            if (st is ImageModelState.Downloading or ImageModelState.Checking or ImageModelState.Verifying) _host.Images.Store.Pause(m);
            else if (st == ImageModelState.Installed && _host.Images.Store.LatestRevision(m) is { } latest) _host.Images.Store.StartInstall(m, latest);
            else if (st == ImageModelState.Installed) _ = Task.Run(() => _host.Images.Store.VerifyInstalledAsync(m, CancellationToken.None));
            else _host.Images.QueueInstall(m);
        });
        ImageSecondary = new Command(p =>
        {
            if (p is not string id || ImageCatalog.Find(id) is not { } m) return;
            var ok = System.Windows.MessageBox.Show(m.Local
                    ? $"Remover o {m.Name} da lista de especialistas? O arquivo que você importou não é apagado."
                    : $"Remover o {m.Name} deste PC? Os arquivos do modelo ({ImageModelStore.Gb(m.ApproxBytes)}) serão apagados; as imagens já criadas continuam.",
                "Noc", System.Windows.MessageBoxButton.YesNo, System.Windows.MessageBoxImage.Question);
            if (ok == System.Windows.MessageBoxResult.Yes) _host.Images.Store.Delete(m);
        });
        SaveHfToken = new Command(() => _ = SaveHfTokenAsync());
        DisconnectHf = new Command(() => _ = Task.Run(async () => { await _host.SetHfTokenAsync(null); _ui.Invoke(() => HfMessage = ""); }));
        OpenImageFolder = new Command(() => Shell.Open(ImagePaths.Outputs));
        OpenUrl = new Command(p => { if (p is string u) Shell.Open(u); });
        _imagesEnabled = _host.Settings.ImageEnabled;
    }

    private async Task SaveHfTokenAsync()
    {
        HfMessage = "Conferindo…";
        var (ok, msg) = await _host.SetHfTokenAsync(HfTokenInput);
        HfMessage = msg;
        if (ok) HfTokenInput = "";
    }

    private static readonly CultureInfo Br = new("pt-BR");

    private void RefreshImages()
    {
        var eng = _host.Images;
        var gpu = _host.Gpu.Last?.Name ?? "GPU";
        ImageStatus = eng.Optimizing is { } opt
            ? $"Otimizando {ImageCatalog.Find(opt)?.Name} para a sua GPU… {(eng.OptimizeProgress ?? 0) * 100:0}%"
            : eng.Runtime.State == RuntimeState.Installing ? $"Preparando o motor ({eng.Runtime.StepIndex}/{ImageRuntime.StepCount}): {eng.Runtime.Step}"
            : eng.State switch
            {
                EngineState.Working => $"Gerando com {eng.Loaded?.Name}",
                EngineState.Loading or EngineState.Starting => "Preparando o gerador…",
                EngineState.Ready => $"Pronto · {eng.Loaded?.Name} aquecido",
                _ => ImageCatalog.Models.Any(eng.Ready) ? "Pronto" : "Instalando o primeiro modelo",
            };
        ImageDetail = $"{gpu} · motor padrão: Z-Image Turbo" + (eng.Displaced is { } d ? $" · {_host.Models.DisplayName(d.Key)} volta quando o motor ficar ocioso" : "");
        ImageRoot = ImagePaths.HasRoot ? ImagePaths.Root : "";
        HfConnected = eng.HfUser is { Length: > 0 };
        HfLine = HfConnected ? $"Conectado como {eng.HfUser}" : "Não conectado";
        if (_imageUsageTick++ % 10 == 0)
        {
            var (count, bytes) = eng.Outputs.Usage();
            var loras = eng.Library.Loras.Count;
            ImageUsage = (count == 0 ? "Nenhuma imagem criada ainda" : $"{count} imagens · {ImageModelStore.Gb(bytes)}") +
                         (loras > 0 ? $" · {loras} LoRA{(loras > 1 ? "s" : "")} na biblioteca" : "");
        }

        var rows = new List<ImageModelRow>();
        foreach (var m in ImageCatalog.Models)
        {
            var st = eng.Store.StateOf(m);
            var p = eng.Store.LiveProgress(m);
            var installed = eng.Store.IsInstalled(m);
            var update = eng.Store.LatestRevision(m) is not null;
            var optimizing = eng.Optimizing == m.Id;
            string status;
            Brush color;
            string detail = "";
            string? action = null;
            switch (st)
            {
                case ImageModelState.Downloading or ImageModelState.Checking or ImageModelState.Verifying:
                    status = st == ImageModelState.Verifying ? "Conferindo os arquivos" : st == ImageModelState.Checking ? "Preparando o download" : "Baixando";
                    color = Brush("Accent");
                    if (p is not null && p.Total > 0)
                        detail = $"{ImageModelStore.Gb(p.Done)} / {ImageModelStore.Gb(p.Total)} · {100.0 * p.Done / p.Total:0}%" +
                                 (p.BytesPerSecond > 1 ? $" · {p.BytesPerSecond / 1e6:0} MB/s" : "") + (p.Detail is { } dt ? " · " + dt : "");
                    action = "Pausar";
                    break;
                case ImageModelState.Installed:
                    status = optimizing ? "Instalado · otimizando para a sua GPU" : eng.Loaded == m ? "Instalado · carregado" : "Instalado · pronto";
                    color = Brush("Ok");
                    detail = (m.Local ? $"{ImageModelStore.Gb(m.ApproxBytes)} · importado · {m.Family}" : $"{ImageModelStore.Gb(m.ApproxBytes)} · revisão {eng.Store.InstalledRevision(m)?[..Math.Min(8, eng.Store.InstalledRevision(m)!.Length)]} · {m.License}") +
                             (eng.Store.LoadSeconds(m) is { } ls ? $" · carrega em ~{ls.ToString("0", Br)} s" : "");
                    action = update ? "Atualizar" : m.Local ? null : "Verificar";
                    if (!eng.Ready(m)) { status = "Instalado · falta a configuração do SDXL"; color = Brush("Warn"); action = "Instalar"; }
                    if (update) status = "Atualização disponível";
                    break;
                case ImageModelState.AuthRequired or ImageModelState.TermsRequired:
                    status = st == ImageModelState.AuthRequired ? "Autorização necessária" : "Aceite os termos no Hugging Face";
                    color = Brush("Warn");
                    detail = p?.Detail ?? "";
                    action = "Tentar de novo";
                    break;
                case ImageModelState.NoSpace:
                    status = "Espaço insuficiente";
                    color = Brush("Err");
                    detail = p?.Detail ?? "";
                    action = "Tentar de novo";
                    break;
                case ImageModelState.Paused or ImageModelState.Failed:
                    status = st == ImageModelState.Paused ? "Pausado" : "Falhou";
                    color = Brush(st == ImageModelState.Paused ? "Text3" : "Err");
                    detail = p?.Detail ?? (p is not null && p.Total > 0 ? $"{ImageModelStore.Gb(p.Done)} / {ImageModelStore.Gb(p.Total)}" : "");
                    action = "Continuar";
                    break;
                default:
                    status = m.Gated && !HfConnected ? "Autorização necessária" : eng.InstallQueue.Contains(m.Id) ? "Na fila de instalação" : "Não instalado";
                    color = Brush(m.Gated && !HfConnected ? "Warn" : "Text3");
                    detail = $"Download de {ImageModelStore.Gb(m.ApproxBytes)} · {m.License}";
                    action = m.Gated && !HfConnected ? null : "Instalar";
                    break;
            }
            rows.Add(new ImageModelRow
            {
                Id = m.Id, Name = m.Name, Role = m.Profile switch { "fast" => "⚡ Rápido", "quality" => "◆ Qualidade", _ => "◈ " + m.Tagline },
                Status = status, StatusColor = color, Detail = detail,
                Progress = p is not null && p.Total > 0 ? (double)p.Done / p.Total : optimizing ? eng.OptimizeProgress ?? 0 : 0,
                ShowProgress = st is ImageModelState.Downloading or ImageModelState.Verifying || optimizing,
                Action = action, SecondaryAction = installed ? "Remover" : null,
                NeedsToken = m.Gated && !installed && (!HfConnected || st is ImageModelState.AuthRequired or ImageModelState.TermsRequired),
                LicenseUrl = m.LicenseUrl,
            });
        }
        // componentes de edição (ControlNet, IP-Adapter, detectores, ampliação): uma linha só
        var aux = ImageCatalog.AuxPackages;
        var auxDone = aux.Count(eng.Store.IsInstalled);
        var auxBusy = aux.FirstOrDefault(eng.Store.Busy);
        var auxP = auxBusy is null ? null : eng.Store.LiveProgress(auxBusy);
        rows.Add(new ImageModelRow
        {
            Id = "aux", Name = "Componentes de edição", Role = "ControlNet · referência · rostos e mãos · ampliação",
            Status = auxDone == aux.Length ? "Instalados · prontos" : auxBusy is not null ? $"Instalando {auxBusy.Name}" : $"{auxDone} de {aux.Length} instalados",
            StatusColor = Brush(auxDone == aux.Length ? "Ok" : auxBusy is not null ? "Accent" : "Text3"),
            Detail = $"{ImageModelStore.Gb(aux.Sum(a => a.ApproxBytes))} · Apache 2.0 / BSD · só .safetensors",
            Progress = auxP is not null && auxP.Total > 0 ? (double)auxP.Done / auxP.Total : 0, ShowProgress = auxBusy is not null,
            Action = auxDone == aux.Length || auxBusy is not null ? null : "Instalar",
        });
        if (!rows.Select(Sig).SequenceEqual(ImageModels.Select(Sig)))
        {
            ImageModels.Clear();
            foreach (var r in rows) ImageModels.Add(r);
        }
    }

    private static string Sig(ImageModelRow r) => $"{r.Id}|{r.Status}|{r.Detail}|{r.Progress:0.000}|{r.Action}|{r.NeedsToken}|{r.SecondaryAction}";

    private static Brush Brush(string key) => System.Windows.Application.Current.TryFindResource(key) as Brush ?? Brushes.Gray;
}
