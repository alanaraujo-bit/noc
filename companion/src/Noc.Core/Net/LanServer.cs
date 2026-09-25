using System.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Logging;
using Noc.Core.Sessions;

namespace Noc.Core.Net;

/// <summary>
/// Servidor WebSocket na rede local. O transporte é ws:// puro porque o conteúdo já vai cifrado
/// de ponta a ponta pelo protocolo Noc; sem o handshake ninguém lê nem injeta nada.
/// Só responde a IPs privados.
/// </summary>
public sealed class LanServer : IAsyncDisposable
{
    private readonly ISessionHost _host;
    private WebApplication? _app;
    private CancellationTokenSource? _cts;

    public int Port { get; private set; }
    public bool Running => _app is not null;
    public string? LastError { get; private set; }

    public LanServer(ISessionHost host) => _host = host;

    public async Task StartAsync(int preferredPort)
    {
        if (_app is not null) return;
        _cts = new CancellationTokenSource();
        foreach (var port in new[] { preferredPort, preferredPort + 1, preferredPort + 2 })
        {
            try
            {
                var builder = WebApplication.CreateSlimBuilder();
                builder.Logging.ClearProviders();
                builder.WebHost.ConfigureKestrel(o =>
                {
                    o.Listen(IPAddress.Any, port);
                    o.Limits.MaxRequestBodySize = 1024;
                    o.AddServerHeader = false;
                });
                var app = builder.Build();
                app.UseWebSockets(new WebSocketOptions { KeepAliveInterval = TimeSpan.FromSeconds(20) });
                app.MapGet("/v1/ping", () => Results.Json(new { noc = 1 }));
                app.Map("/v1/ws", async ctx =>
                {
                    var remote = ctx.Connection.RemoteIpAddress;
                    if (remote is null || !IsLocal(remote))
                    {
                        ctx.Response.StatusCode = 403;
                        return;
                    }
                    if (!ctx.WebSockets.IsWebSocketRequest)
                    {
                        ctx.Response.StatusCode = 400;
                        return;
                    }
                    var ws = await ctx.WebSockets.AcceptWebSocketAsync();
                    var session = new Session(ws, _host, "lan", "lan:" + remote);
                    await session.RunAsync(_cts.Token);
                });
                await app.StartAsync();
                _app = app;
                Port = port;
                LastError = null;
                return;
            }
            catch (IOException e)
            {
                LastError = e.Message; // porta ocupada: tenta a próxima
            }
            catch (Exception e) when (e.InnerException is IOException)
            {
                LastError = e.Message;
            }
        }
    }

    private static bool IsLocal(IPAddress ip)
    {
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        if (IPAddress.IsLoopback(ip)) return true;
        return ip.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && Platform.NetworkInfo.IsPrivate(ip)
               || ip.IsIPv6LinkLocal || ip.IsIPv6UniqueLocal;
    }

    public async Task StopAsync()
    {
        if (_app is null) return;
        _cts?.Cancel();
        try { using var t = new CancellationTokenSource(TimeSpan.FromSeconds(3)); await _app.StopAsync(t.Token); } catch { }
        await _app.DisposeAsync();
        _app = null;
    }

    public async ValueTask DisposeAsync() => await StopAsync();
}
