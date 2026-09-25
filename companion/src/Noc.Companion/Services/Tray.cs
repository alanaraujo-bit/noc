using System.Drawing;
using System.IO;
using System.Windows;
using Forms = System.Windows.Forms;

namespace Noc.Companion.Services;

/// <summary>Ícone na bandeja: o Companion fica rodando em silêncio depois que a janela fecha.</summary>
public sealed class Tray : IDisposable
{
    private readonly Forms.NotifyIcon _icon;
    private readonly Forms.ToolStripMenuItem _status;

    public Tray(Action open, Action pair, Action quit)
    {
        var menu = new Forms.ContextMenuStrip { ShowImageMargin = false };
        _status = new Forms.ToolStripMenuItem("Noc Companion") { Enabled = false };
        menu.Items.Add(_status);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Abrir o Noc", null, (_, _) => open());
        menu.Items.Add("Parear celular", null, (_, _) => pair());
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add("Sair", null, (_, _) => quit());

        _icon = new Forms.NotifyIcon
        {
            Text = "Noc Companion",
            Icon = LoadIcon(),
            Visible = true,
            ContextMenuStrip = menu,
        };
        _icon.MouseClick += (_, e) => { if (e.Button == Forms.MouseButtons.Left) open(); };
    }

    private static Icon LoadIcon()
    {
        var info = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/noc.ico"));
        using var s = info!.Stream;
        return new Icon(s, 32, 32);
    }

    public void SetStatus(string text)
    {
        var t = "Noc · " + text;
        _icon.Text = t.Length > 63 ? t[..63] : t;
        _status.Text = text;
    }

    public void Balloon(string title, string text) =>
        _icon.ShowBalloonTip(4000, title, text, Forms.ToolTipIcon.None);

    public void Dispose()
    {
        _icon.Visible = false;
        _icon.Dispose();
    }
}
