using System.Diagnostics;
using System.IO;
using System.Windows;
using Microsoft.Win32;

namespace Noc.Companion.Services;

/// <summary>Tema claro/escuro seguindo o Windows (ou a escolha do usuário).</summary>
public static class ThemeManager
{
    private static ResourceDictionary? _current;

    public static bool SystemIsDark()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            return key?.GetValue("AppsUseLightTheme") is int v && v == 0;
        }
        catch { return false; }
    }

    public static bool IsDark(string mode) => mode switch
    {
        "dark" => true,
        "light" => false,
        _ => SystemIsDark(),
    };

    public static void Apply(string mode)
    {
        var dict = new ResourceDictionary
        {
            Source = new Uri($"pack://application:,,,/Themes/{(IsDark(mode) ? "Dark" : "Light")}.xaml"),
        };
        var merged = Application.Current.Resources.MergedDictionaries;
        if (_current is not null) merged.Remove(_current);
        merged.Insert(0, dict);
        _current = dict;
    }
}

/// <summary>Início automático com o Windows (HKCU\...\Run, sem precisar de administrador).</summary>
public static class Autostart
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string Name = "Noc Companion";

    private static string Command => $"\"{Environment.ProcessPath}\" --minimized";

    public static bool IsEnabled
    {
        get
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue(Name) is string v && v.Contains(Path.GetFileName(Environment.ProcessPath ?? "Noc.exe"), StringComparison.OrdinalIgnoreCase);
        }
    }

    public static void Set(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue(Name, Command);
        else key.DeleteValue(Name, throwOnMissingValue: false);
    }

    /// <summary>Se o executável mudou de lugar (atualização), corrige o caminho registrado.</summary>
    public static void Refresh()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true);
        if (key?.GetValue(Name) is string v && !v.Equals(Command, StringComparison.OrdinalIgnoreCase)) key.SetValue(Name, Command);
    }
}

public static class Shell
{
    public static void Open(string target)
    {
        try { Process.Start(new ProcessStartInfo(target) { UseShellExecute = true }); }
        catch { /* sem associação: ignora */ }
    }
}
