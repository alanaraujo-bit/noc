using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Noc.Core;
using Noc.Core.LmStudio;
using Noc.Core.Net;
using Noc.Core.Security;
using Noc.Core.Storage;
using Noc.Companion.Services;
using QRCoder;

namespace Noc.Companion.ViewModels;

public abstract class Observable : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
        return true;
    }

    protected void Raise([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

public sealed class Command(Action<object?> run, Func<bool>? can = null) : ICommand
{
    public Command(Action run) : this(_ => run()) { }
    public event EventHandler? CanExecuteChanged { add => CommandManager.RequerySuggested += value; remove => CommandManager.RequerySuggested -= value; }
    public bool CanExecute(object? parameter) => can?.Invoke() ?? true;
    public void Execute(object? parameter) => run(parameter);
}

public sealed class DeviceItem
{
    public required string Id { get; init; }
    public required string Name { get; init; }
    public required string Detail { get; init; }
    public required bool Online { get; init; }
    public required bool Revoked { get; init; }
}

public sealed class LogItem
{
    public required string Time { get; init; }
    public required string Text { get; init; }
    public required string Level { get; init; } // info | warn | alert
}

/// <summary>Estado da janela do Companion. Atualiza a partir dos eventos do CompanionHost.</summary>
public sealed partial class MainViewModel : Observable
{
    private readonly CompanionHost _host;
    private readonly Dispatcher _ui;
    private readonly DispatcherTimer _tick;
    private bool _refreshQueued;

    public MainViewModel(CompanionHost host)
    {
        _host = host;
        _ui = Application.Current.Dispatcher;

        OpenPairing = new Command(() => { _ = ShowPairingAsync(); });
        ClosePairing = new Command(() => { IsPairingOpen = false; _ = _host.Relay.CancelPairCodeAsync(); _host.Pairing.CancelCode(); });
        RenewPairing = new Command(() => { if (PairMode == "qr") NewQr(); else _ = SwitchToCodeAsync(); });
        StartLm = new Command(_ => { _ = StartLmAsync(); }, () => !StartingLm);
        Approve = new Command(() => { _pending?.Approve(); IsApprovalOpen = false; });
        Reject = new Command(() => { _pending?.Reject(); IsApprovalOpen = false; });
        Revoke = new Command(p => { if (p is string id) { _host.Devices.Revoke(id); _host.Security.Write(SecurityLevel.Alert, "device.revoke", "Aparelho revogado no PC", id); } });
        Forget = new Command(p => { if (p is string id) _host.Devices.Forget(id); });
        Disconnect = new Command(p => { if (p is string id) _ = _host.DisconnectDeviceAsync(id); });
        OpenData = new Command(() => Shell.Open(AppPaths.Root));
        OpenLmStudio = new Command(() => { if (LmStudioLocator.AppPath() is { } p) Shell.Open(p); else Shell.Open("https://lmstudio.ai"); });
        TestRemote = new Command(() => { _ = TestRemoteAsync(); });
        Navigate = new Command(p => Page = p as string ?? "overview");
        InitModels();

        _host.Changed += QueueRefresh;
        _host.ActivityAdded += e => _ui.BeginInvoke(() => PrependActivity(e));
        _host.Security.Added += e => _ui.BeginInvoke(() => PrependSecurity(e));
        _host.Pairing.ApprovalRequested += req => _ui.BeginInvoke(() => ShowApproval(req));
        _host.Pairing.OffersChanged += QueueRefresh;

        foreach (var a in _host.RecentActivity.Take(150)) Activity.Add(ToLog(a));
        foreach (var s in _host.Security.Recent(150)) Security.Add(ToLog(s));

        _tick = new DispatcherTimer(TimeSpan.FromSeconds(1), DispatcherPriority.Background, (_, _) => Tick(), _ui);
        _tick.Start();
        LoadSettings();
        Refresh();
    }

    // ------------------------------------------------------------------ navegação

    private string _page = "overview";
    public string Page { get => _page; set => Set(ref _page, value); }
    public ICommand Navigate { get; }

    // ------------------------------------------------------------------ visão geral

    private string _greeting = "";
    public string Greeting { get => _greeting; set => Set(ref _greeting, value); }
    private string _headline = "";
    public string Headline { get => _headline; set => Set(ref _headline, value); }
    private string _pcName = "";
    public string PcName { get => _pcName; set => Set(ref _pcName, value); }
    public string Fingerprint => _host.Identity.Fingerprint;
    public string PcIdShort => string.Join(' ', Enumerable.Range(0, 4).Select(i => _host.Identity.PcId.Substring(i * 4, 4)));
    public string Version => "v" + CompanionHost.Version;

    private string _lmTitle = "", _lmDetail = "";
    public string LmTitle { get => _lmTitle; set => Set(ref _lmTitle, value); }
    public string LmDetail { get => _lmDetail; set => Set(ref _lmDetail, value); }
    private Brush _lmColor = Brushes.Gray;
    public Brush LmColor { get => _lmColor; set => Set(ref _lmColor, value); }
    private bool _lmStopped;
    public bool LmStopped { get => _lmStopped; set => Set(ref _lmStopped, value); }
    private bool _startingLm;
    public bool StartingLm { get => _startingLm; set { Set(ref _startingLm, value); CommandManager.InvalidateRequerySuggested(); } }

    private string _modelTitle = "", _modelDetail = "";
    public string ModelTitle { get => _modelTitle; set => Set(ref _modelTitle, value); }
    public string ModelDetail { get => _modelDetail; set => Set(ref _modelDetail, value); }

    private string _remoteTitle = "", _remoteDetail = "";
    public string RemoteTitle { get => _remoteTitle; set => Set(ref _remoteTitle, value); }
    public string RemoteDetail { get => _remoteDetail; set => Set(ref _remoteDetail, value); }
    private Brush _remoteColor = Brushes.Gray;
    public Brush RemoteColor { get => _remoteColor; set => Set(ref _remoteColor, value); }

    private string _lanTitle = "", _lanDetail = "";
    public string LanTitle { get => _lanTitle; set => Set(ref _lanTitle, value); }
    public string LanDetail { get => _lanDetail; set => Set(ref _lanDetail, value); }

    private string _phonesTitle = "", _phonesDetail = "";
    public string PhonesTitle { get => _phonesTitle; set => Set(ref _phonesTitle, value); }
    public string PhonesDetail { get => _phonesDetail; set => Set(ref _phonesDetail, value); }
    private Brush _phonesColor = Brushes.Gray;
    public Brush PhonesColor { get => _phonesColor; set => Set(ref _phonesColor, value); }

    private string _gpuTitle = "", _gpuDetail = "";
    public string GpuTitle { get => _gpuTitle; set => Set(ref _gpuTitle, value); }
    public string GpuDetail { get => _gpuDetail; set => Set(ref _gpuDetail, value); }
    private double _vram;
    public double Vram { get => _vram; set => Set(ref _vram, value); }
    private bool _hasGpu;
    public bool HasGpu { get => _hasGpu; set => Set(ref _hasGpu, value); }

    private string _activityNow = "";
    public string ActivityNow { get => _activityNow; set => Set(ref _activityNow, value); }
    private bool _generating;
    public bool Generating { get => _generating; set => Set(ref _generating, value); }

    private string _trayStatus = "";
    public string TrayStatus { get => _trayStatus; set => Set(ref _trayStatus, value); }

    public ObservableCollection<DeviceItem> Devices { get; } = [];
    public ObservableCollection<LogItem> Activity { get; } = [];
    public ObservableCollection<LogItem> Security { get; } = [];
    private bool _noDevices;
    public bool NoDevices { get => _noDevices; set => Set(ref _noDevices, value); }

    public ICommand StartLm { get; }
    public ICommand OpenLmStudio { get; }

    private Brush B(string key) => (Brush)Application.Current.Resources[key];

    private void QueueRefresh()
    {
        if (_refreshQueued) return;
        _refreshQueued = true;
        _ui.BeginInvoke(DispatcherPriority.Background, () =>
        {
            _refreshQueued = false;
            Refresh();
        });
    }

    public void Refresh()
    {
        var hour = DateTime.Now.Hour;
        Greeting = hour is >= 5 and < 12 ? "Bom dia." : hour is >= 12 and < 18 ? "Boa tarde." : "Boa noite.";
        PcName = _host.Settings.PcName;
        var probe = _host.Models.Last;
        var sessions = _host.Sessions;
        var loaded = probe.Models.Where(m => m.Loaded && m.Type == "llm").ToList();

        // LM Studio
        LmStopped = probe.State == LmState.Stopped;
        switch (probe.State)
        {
            case LmState.Running:
                LmTitle = "LM Studio ativo";
                LmDetail = $"{probe.Models.Count(m => m.Type == "llm")} modelos de conversa · porta {(_host.Settings.LmStudioPort > 0 ? _host.Settings.LmStudioPort : LmStudioLocator.ConfiguredPort())}";
                LmColor = B("Ok");
                break;
            case LmState.Stopped:
                LmTitle = "LM Studio parado";
                LmDetail = "O servidor local do LM Studio está desligado.";
                LmColor = B("Warn");
                break;
            case LmState.NotInstalled:
                LmTitle = "LM Studio não encontrado";
                LmDetail = "Instale o LM Studio e baixe um modelo.";
                LmColor = B("Err");
                break;
            default:
                LmTitle = "Procurando o LM Studio…";
                LmDetail = "";
                LmColor = B("Text3");
                break;
        }

        // Modelo
        if (_host.Models.Op == ModelOpState.Loading)
        {
            ModelTitle = "Carregando " + _host.Models.DisplayName(_host.Models.OpModel ?? "") + "…";
            ModelDetail = _host.Models.OpStarted is { } s ? $"há {(int)(DateTimeOffset.Now - s).TotalSeconds} s" : "";
        }
        else if (loaded.Count > 0)
        {
            var m = loaded[0];
            ModelTitle = _host.Models.DisplayName(m.Key);
            ModelDetail = string.Join(" · ", new[] { m.Params, m.Quantization, $"{m.Instances[0].ContextLength / 1024}k de contexto" }.Where(x => !string.IsNullOrEmpty(x)));
        }
        else
        {
            ModelTitle = "Nenhum modelo carregado";
            ModelDetail = probe.State == LmState.Running ? "Carrega sozinho quando o celular enviar uma mensagem." : "";
        }

        // Remoto
        switch (_host.Relay.State)
        {
            case RelayState.Online:
                RemoteTitle = "Acesso remoto ativo";
                RemoteDetail = "Cifrado de ponta a ponta" + (_host.Relay.LastRtt is { } rtt ? $" · {(int)rtt.TotalMilliseconds} ms" : "");
                RemoteColor = B("Ok");
                break;
            case RelayState.Connecting:
                RemoteTitle = "Conectando ao acesso remoto…";
                RemoteDetail = "";
                RemoteColor = B("Warn");
                break;
            case RelayState.Offline:
                RemoteTitle = "Acesso remoto indisponível";
                RemoteDetail = "Sem internet ou serviço fora do ar. Tentando de novo…";
                RemoteColor = B("Err");
                break;
            default:
                RemoteTitle = "Acesso remoto desligado";
                RemoteDetail = "Só funciona na mesma rede Wi-Fi.";
                RemoteColor = B("Text3");
                break;
        }

        // LAN
        var lan = _host.LanEndpoints;
        LanTitle = _host.Lan.Running ? "Rede local" : "Rede local desligada";
        LanDetail = lan.Count > 0 ? string.Join("  ", lan) : _host.Lan.Running ? "Sem rede local detectada" : (_host.Lan.LastError ?? "");

        // Celulares
        var online = sessions.Where(s => s.DeviceId is not null).GroupBy(s => s.DeviceId).Select(g => g.First()).ToList();
        PhonesTitle = online.Count switch
        {
            0 => "Nenhum celular conectado",
            1 => online[0].DeviceName + " conectado",
            _ => $"{online.Count} celulares conectados",
        };
        PhonesDetail = online.Count > 0
            ? string.Join(" · ", online.Select(s => s.Route == "lan" ? "rede local" : "remoto").Distinct())
            : _host.Devices.Active.Count == 0 ? "Nenhum aparelho pareado ainda." : $"{_host.Devices.Active.Count} aparelho(s) autorizado(s)";
        PhonesColor = online.Count > 0 ? B("Ok") : B("Text3");

        // GPU
        if (_host.Gpu.Last is { } g)
        {
            HasGpu = true;
            GpuTitle = g.Name.Replace("NVIDIA GeForce ", "");
            GpuDetail = $"{g.VramUsedMb / 1024.0:0.0} de {g.VramTotalMb / 1024.0:0.0} GB · {g.UtilPercent}% · {g.TempC}°C";
            Vram = g.VramTotalMb > 0 ? (double)g.VramUsedMb / g.VramTotalMb : 0;
        }
        else HasGpu = false;

        // Atividade
        var jobs = _host.Jobs.Active;
        Generating = jobs.Count > 0;
        var runningJob = jobs.FirstOrDefault(j => j.State != Core.Jobs.JobState.Queued) ?? jobs.FirstOrDefault();
        var queued = jobs.Count(j => j.State == Core.Jobs.JobState.Queued);
        ActivityNow = runningJob is null ? "" :
            (runningJob.Phase switch
            {
                "queued" => "Na fila",
                "loading" or "starting" => "Carregando " + _host.Models.DisplayName(runningJob.Model) + " para responder",
                "preparing" => runningJob.Images > 0 ? "Analisando imagem com " + _host.Models.DisplayName(runningJob.Model) : "Lendo a conversa",
                "thinking" => _host.Models.DisplayName(runningJob.Model) + " está pensando",
                "recovering" => "Retomando uma resposta",
                _ => _host.Models.DisplayName(runningJob.Model) + " está respondendo",
            }) +
            (runningJob.LiveTokensPerSecond is { } tps ? $" · {tps:0} tokens/s" : "") + (queued > 0 ? $" · {queued} na fila" : "");

        Headline = probe.State switch
        {
            LmState.NotInstalled => "Falta instalar o LM Studio.",
            LmState.Stopped => "O LM Studio está parado.",
            _ when Generating => "Respondendo ao seu celular.",
            _ when _host.Devices.Active.Count == 0 => "Pareie seu celular para começar.",
            _ when _host.Relay.State == RelayState.Offline => "Funcionando só na rede local.",
            _ => "Tudo pronto. Pode esquecer que eu existo.",
        };
        TrayStatus = Generating ? "respondendo" : online.Count > 0 ? PhonesTitle.ToLowerInvariant() : probe.State == LmState.Running ? "pronto" : LmTitle.ToLowerInvariant();

        // Dispositivos
        var onlineIds = online.Select(s => s.DeviceId).ToHashSet();
        var items = _host.Devices.All.OrderBy(d => d.Revoked).ThenByDescending(d => d.LastSeen).Select(d => new DeviceItem
        {
            Id = d.Id,
            Name = d.Name,
            Online = onlineIds.Contains(d.Id),
            Revoked = d.Revoked,
            Detail = d.Revoked
                ? $"Revogado {Rel(d.RevokedAt)}"
                : string.Join(" · ", new[]
                {
                    string.IsNullOrEmpty(d.Model) ? null : d.Model,
                    onlineIds.Contains(d.Id) ? "online agora" : d.LastSeen is { } ls ? "visto " + Rel(ls) : null,
                    d.LastRoute is null ? null : d.LastRoute == "lan" ? "rede local" : "remoto",
                    "pareado " + d.PairedAt.ToString("dd/MM/yyyy"),
                }.Where(x => x is not null)),
        }).ToList();
        if (!items.Select(i => i.Id + i.Online + i.Revoked + i.Detail + i.Name).SequenceEqual(Devices.Select(i => i.Id + i.Online + i.Revoked + i.Detail + i.Name)))
        {
            Devices.Clear();
            foreach (var i in items) Devices.Add(i);
        }
        NoDevices = Devices.Count == 0;
        RefreshModels();
        RefreshPairing();
    }

    private static string Rel(DateTimeOffset? t)
    {
        if (t is null) return "";
        var d = DateTimeOffset.Now - t.Value;
        return d.TotalSeconds < 60 ? "agora" : d.TotalMinutes < 60 ? $"há {(int)d.TotalMinutes} min" : d.TotalHours < 24 ? $"há {(int)d.TotalHours} h" : t.Value.ToString("dd/MM HH:mm");
    }

    private static LogItem ToLog(ActivityEntry a) => new()
    {
        Time = a.At.ToString("HH:mm"), Text = a.Text,
        Level = a.Kind is "error" ? "alert" : a.Kind is "warn" ? "warn" : "info",
    };

    private static LogItem ToLog(SecurityEvent e) => new()
    {
        Time = e.At.ToString("dd/MM HH:mm"), Text = e.Message + (e.Route is null ? "" : e.Route == "lan" ? " · rede local" : " · remoto"),
        Level = e.Level switch { SecurityLevel.Alert => "alert", SecurityLevel.Warning => "warn", _ => "info" },
    };

    private void PrependActivity(ActivityEntry e)
    {
        Activity.Insert(0, ToLog(e));
        while (Activity.Count > 200) Activity.RemoveAt(Activity.Count - 1);
    }

    private void PrependSecurity(SecurityEvent e)
    {
        Security.Insert(0, ToLog(e));
        while (Security.Count > 200) Security.RemoveAt(Security.Count - 1);
    }

    private async Task StartLmAsync()
    {
        StartingLm = true;
        try { await _host.StartLmServerAsync(CancellationToken.None); }
        finally { StartingLm = false; Refresh(); }
    }

    public ICommand Revoke { get; }
    public ICommand Forget { get; }
    public ICommand Disconnect { get; }

    // ------------------------------------------------------------------ pareamento

    private bool _isPairingOpen;
    public bool IsPairingOpen { get => _isPairingOpen; set => Set(ref _isPairingOpen, value); }
    private string _pairMode = "qr";
    /// <summary>qr | code. Trocar o modo gera a oferta correspondente.</summary>
    public string PairMode
    {
        get => _pairMode;
        set
        {
            if (!Set(ref _pairMode, value) || !IsPairingOpen) return;
            if (value == "qr") NewQr(); else _ = SwitchToCodeAsync();
        }
    }
    private BitmapSource? _qr;
    public BitmapSource? QrImage { get => _qr; set => Set(ref _qr, value); }
    private string _pairCode = "";
    public string PairCode { get => _pairCode; set => Set(ref _pairCode, value); }
    private string _pairExpires = "";
    public string PairExpires { get => _pairExpires; set => Set(ref _pairExpires, value); }
    private bool _pairExpired;
    public bool PairExpired { get => _pairExpired; set => Set(ref _pairExpired, value); }
    private string _pairHint = "";
    public string PairHint { get => _pairHint; set => Set(ref _pairHint, value); }

    public ICommand OpenPairing { get; }
    public ICommand ClosePairing { get; }
    public ICommand RenewPairing { get; }

    public async Task ShowPairingAsync()
    {
        _pairMode = "qr";
        Raise(nameof(PairMode));
        NewQr();
        IsPairingOpen = true;
        await Task.CompletedTask;
    }

    private int _devicesAtOpen;

    private void NewQr()
    {
        var offer = _host.Pairing.NewQrOffer();
        var uri = _host.BuildPairingUri(offer);
        _devicesAtOpen = _host.Devices.Active.Count;
        using var gen = new QRCodeGenerator();
        using var data = gen.CreateQrCode(uri, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(10, [0x19, 0x18, 0x16], [0xFF, 0xFF, 0xFF], drawQuietZones: true);
        var img = new BitmapImage();
        img.BeginInit();
        img.CacheOption = BitmapCacheOption.OnLoad;
        img.StreamSource = new MemoryStream(png);
        img.EndInit();
        img.Freeze();
        QrImage = img;
        PairHint = "No app Noc do celular, toque em “Parear computador” e aponte a câmera para o código.";
        RefreshPairing();
    }

    private async Task SwitchToCodeAsync()
    {
        _devicesAtOpen = _host.Devices.Active.Count;
        if (_host.Relay.State != RelayState.Online)
        {
            PairCode = "";
            PairHint = "O pareamento por código precisa do acesso remoto ativo. Use o QR Code ou verifique a internet do PC.";
            return;
        }
        var offer = await _host.NewPairCodeAsync();
        PairCode = offer.Display;
        PairHint = "No celular, toque em “Digitar código” e informe este código. Depois confirme aqui o número de verificação.";
        RefreshPairing();
    }

    private void RefreshPairing()
    {
        if (!IsPairingOpen) return;
        DateTimeOffset? exp = PairMode == "qr" ? _host.Pairing.CurrentQr?.ExpiresAt : _host.Pairing.CurrentCode?.ExpiresAt;
        PairExpired = exp is null || (PairMode == "code" && string.IsNullOrEmpty(PairCode));
        if (exp is { } e)
        {
            var left = e - DateTimeOffset.Now;
            PairExpires = left.TotalSeconds > 0 ? $"Expira em {(int)left.TotalMinutes}:{left.Seconds:00}" : "Expirado";
        }
        else PairExpires = PairMode == "qr" ? "Já usado ou expirado" : "";
        // Pareou pelo QR: fecha sozinho e comemora.
        if (_host.Devices.Active.Count > _devicesAtOpen && IsPairingOpen && PairMode == "qr")
        {
            IsPairingOpen = false;
            Page = "devices";
        }
    }

    private void Tick()
    {
        if (IsPairingOpen) RefreshPairing();
        if (_host.Models.Op != ModelOpState.Idle || Generating) Refresh();
    }

    // ------------------------------------------------------------------ aprovação (pareamento por código)

    private ApprovalRequest? _pending;
    private bool _isApprovalOpen;
    public bool IsApprovalOpen { get => _isApprovalOpen; set => Set(ref _isApprovalOpen, value); }
    private string _approvalDevice = "", _approvalSas = "";
    public string ApprovalDevice { get => _approvalDevice; set => Set(ref _approvalDevice, value); }
    public string ApprovalSas { get => _approvalSas; set => Set(ref _approvalSas, value); }
    public ICommand Approve { get; }
    public ICommand Reject { get; }
    public event Action? AttentionNeeded;

    private void ShowApproval(ApprovalRequest req)
    {
        _pending = req;
        IsPairingOpen = false;
        ApprovalDevice = string.IsNullOrWhiteSpace(req.DeviceModel) ? req.DeviceName : $"{req.DeviceName} ({req.DeviceModel})";
        ApprovalSas = req.Sas[..3] + " " + req.Sas[3..];
        IsApprovalOpen = true;
        AttentionNeeded?.Invoke();
    }

    // ------------------------------------------------------------------ ajustes

    private bool _loading;
    private string _setName = "";
    public string SetName { get => _setName; set { if (Set(ref _setName, value)) SaveSoon(); } }
    private bool _setAutostart;
    public bool SetAutostart { get => _setAutostart; set { if (Set(ref _setAutostart, value) && !_loading) Autostart.Set(value); } }
    private bool _setMinimized;
    public bool SetMinimized { get => _setMinimized; set { if (Set(ref _setMinimized, value)) SaveSoon(); } }
    private bool _setRemote;
    public bool SetRemote { get => _setRemote; set { if (Set(ref _setRemote, value)) SaveSoon(); } }
    private bool _setLan;
    public bool SetLan { get => _setLan; set { if (Set(ref _setLan, value)) SaveSoon(); } }
    private bool _setAutoLm;
    public bool SetAutoLm { get => _setAutoLm; set { if (Set(ref _setAutoLm, value)) SaveSoon(); } }
    private bool _setSingle;
    public bool SetSingle { get => _setSingle; set { if (Set(ref _setSingle, value)) SaveSoon(); } }
    private string _setContext = "";
    public string SetContext { get => _setContext; set { if (Set(ref _setContext, value)) SaveSoon(); } }
    private string _setTheme = "system";
    public string SetTheme { get => _setTheme; set { if (Set(ref _setTheme, value)) { ThemeManager.Apply(value); SaveSoon(); Refresh(); } } }
    public string[] ContextOptions { get; } = ["4096", "8192", "16384", "32768", "65536"];
    public ICommand OpenData { get; }
    public ICommand TestRemote { get; }
    private string _testResult = "";
    public string TestResult { get => _testResult; set => Set(ref _testResult, value); }

    private bool _setVoice, _setAutoImport, _setDownloads, _setKeepLm;
    public bool SetVoice { get => _setVoice; set { if (Set(ref _setVoice, value)) SaveSoon(); } }
    public bool SetAutoImport { get => _setAutoImport; set { if (Set(ref _setAutoImport, value)) SaveSoon(); } }
    public bool SetDownloads { get => _setDownloads; set { if (Set(ref _setDownloads, value)) SaveSoon(); } }
    public bool SetKeepLm { get => _setKeepLm; set { if (Set(ref _setKeepLm, value)) SaveSoon(); } }

    private void LoadSettings()
    {
        _loading = true;
        var s = _host.Settings;
        SetName = s.PcName;
        SetAutostart = Autostart.IsEnabled;
        SetMinimized = s.StartMinimized;
        SetRemote = s.RemoteEnabled;
        SetLan = s.LanEnabled;
        SetAutoLm = s.AutoStartLmServer;
        SetSingle = s.SingleModel;
        SetContext = s.DefaultContextLength.ToString();
        SetVoice = s.VoiceEnabled;
        SetAutoImport = s.AutoImportModels;
        SetDownloads = s.AllowComponentDownloads;
        SetKeepLm = s.KeepLmServerAlive;
        SetTheme = s.Theme;
        _loading = false;
    }

    private DispatcherTimer? _saveTimer;

    private void SaveSoon()
    {
        if (_loading) return;
        _saveTimer?.Stop();
        _saveTimer = new DispatcherTimer(TimeSpan.FromMilliseconds(600), DispatcherPriority.Background, async (_, _) =>
        {
            _saveTimer?.Stop();
            var s = _host.Settings;
            var nameChanged = SetName.Trim().Length > 0 && SetName.Trim() != s.PcName;
            if (nameChanged) s.PcName = SetName.Trim();
            s.StartMinimized = SetMinimized;
            s.RemoteEnabled = SetRemote;
            s.LanEnabled = SetLan;
            s.AutoStartLmServer = SetAutoLm;
            s.SingleModel = SetSingle;
            var voiceOn = SetVoice && !s.VoiceEnabled;
            s.VoiceEnabled = SetVoice;
            s.AutoImportModels = SetAutoImport;
            s.AllowComponentDownloads = SetDownloads;
            s.KeepLmServerAlive = SetKeepLm;
            if (voiceOn) _ = Task.Run(() => _host.Stt.EnsureReadyAsync(CancellationToken.None));
            s.Theme = SetTheme;
            if (int.TryParse(SetContext, out var ctx)) s.DefaultContextLength = ctx;
            await _host.ApplySettingsAsync();
            Refresh();
        }, _ui);
        _saveTimer.Start();
    }

    private async Task TestRemoteAsync()
    {
        TestResult = "Testando…";
        TestResult = await Noc.Core.Net.RemoteSelfTest.RunAsync(_host);
    }
}
