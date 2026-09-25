using System.IO;
using System.Windows;
using System.Windows.Threading;
using Noc.Companion.Services;
using Noc.Companion.ViewModels;
using Noc.Core;
using Noc.Core.Storage;

namespace Noc.Companion;

public partial class App : Application
{
    private const string MutexName = "Noc.Companion.SingleInstance";
    private const string ShowEventName = "Noc.Companion.Show";

    private Mutex? _mutex;
    private EventWaitHandle? _showEvent;
    private CompanionHost? _host;
    private Tray? _tray;
    private MainWindow? _window;
    private MainViewModel? _vm;
    private bool _quitting;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        // Uma instância só: abrir de novo apenas traz a janela existente para frente.
        _mutex = new Mutex(true, MutexName, out var first);
        if (!first)
        {
            try { EventWaitHandle.OpenExisting(ShowEventName).Set(); } catch { }
            Shutdown();
            return;
        }
        _showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        new Thread(() =>
        {
            while (_showEvent.WaitOne()) Dispatcher.BeginInvoke(ShowWindow);
        }) { IsBackground = true, Name = "noc-show" }.Start();

        DispatcherUnhandledException += (_, ex) =>
        {
            Log("ui", ex.Exception);
            ex.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, ex) => Log("domain", ex.ExceptionObject as Exception);
        TaskScheduler.UnobservedTaskException += (_, ex) => { Log("task", ex.Exception); ex.SetObserved(); };

        try
        {
            _host = new CompanionHost();
        }
        catch (Exception ex)
        {
            Log("start", ex);
            MessageBox.Show("Não foi possível iniciar o Noc Companion:\n\n" + ex.Message, "Noc", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown();
            return;
        }

        ThemeManager.Apply(_host.Settings.Theme);
        Autostart.Refresh();
        if (!_host.Settings.FirstRunDone)
        {
            // Primeira execução: liga o início automático (o objetivo é "ligar o PC e esquecer").
            Autostart.Set(true);
            _host.Settings.FirstRunDone = true;
            _host.Settings.Save();
        }

        _vm = new MainViewModel(_host);
        _vm.AttentionNeeded += () => { ShowWindow(); _tray?.Balloon("Novo celular quer se conectar", "Confira o código de verificação e aprove."); };
        _vm.PropertyChanged += (_, p) => { if (p.PropertyName == nameof(MainViewModel.TrayStatus)) _tray?.SetStatus(_vm.TrayStatus); };
        _tray = new Tray(ShowWindow, () => { ShowWindow(); _ = _vm.ShowPairingAsync(); }, Quit);

        var minimized = e.Args.Contains("--minimized") && _host.Settings.StartMinimized;
        if (!minimized) ShowWindow();

        await _host.StartAsync();
        _vm.Refresh();
        _tray.SetStatus(_vm.TrayStatus);
    }

    private void ShowWindow()
    {
        if (_vm is null) return;
        if (_window is null)
        {
            _window = new MainWindow { DataContext = _vm };
            _window.Closing += (_, args) =>
            {
                if (_quitting) return;
                // Fechar a janela não para o serviço: o Companion continua na bandeja.
                args.Cancel = true;
                _window.Hide();
                if (!_host!.Settings.FirstRunDone || !_hintShown)
                {
                    _hintShown = true;
                    _tray?.Balloon("O Noc continua ligado", "Seu celular segue acessando o PC. Para sair, use o ícone na bandeja.");
                }
            };
        }
        _window.Show();
        if (_window.WindowState == WindowState.Minimized) _window.WindowState = WindowState.Normal;
        _window.Activate();
        _window.Topmost = true;
        _window.Topmost = false;
    }

    private bool _hintShown;

    private async void Quit()
    {
        _quitting = true;
        _tray?.Dispose();
        _window?.Close();
        if (_host is not null)
        {
            try { await _host.DisposeAsync(); } catch { }
        }
        _mutex?.ReleaseMutex();
        Shutdown();
    }

    private static void Log(string where, Exception? ex)
    {
        try
        {
            File.AppendAllText(Path.Combine(AppPaths.Root, "crash.log"), $"{DateTime.Now:O} [{where}] {ex}\n\n");
        }
        catch { }
    }
}
