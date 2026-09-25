// Companion sem interface, para desenvolvimento e testes automatizados.
// Uso: Noc.DevHost [--data <dir>] [--approve]   (--approve aprova pareamentos por código automaticamente)
using Noc.Core;
using Noc.Core.Storage;

var dataIdx = Array.IndexOf(args, "--data");
if (dataIdx >= 0) AppPaths.Root = args[dataIdx + 1];
var autoApprove = args.Contains("--approve");

await using var host = new CompanionHost();
host.Pairing.ApprovalRequested += req =>
{
    Console.WriteLine($"[approval] {req.DeviceName} ({req.DeviceModel}) SAS={req.Sas} via {req.Route}");
    if (autoApprove) req.Approve();
};
host.ActivityAdded += a => Console.WriteLine($"[activity] {a.Text}");
host.Security.Added += e => Console.WriteLine($"[security] {e.Level} {e.Kind}: {e.Message}");
await host.StartAsync();
for (var i = 0; i < 40 && host.Relay.State != Noc.Core.Net.RelayState.Online; i++) await Task.Delay(250);
Console.WriteLine($"[info] pcId={host.Identity.PcId} lan={string.Join(',', host.LanEndpoints)} relay={host.Relay.State}");
var offer = host.Pairing.NewQrOffer();
Console.WriteLine("[pair-uri] " + host.BuildPairingUri(offer));
var code = await host.NewPairCodeAsync();
Console.WriteLine("[pair-code] " + code.Display);
File.WriteAllText(Path.Combine(AppPaths.Root, "devhost-pair.txt"), host.BuildPairingUri(offer) + "\n" + code.Display + "\n");

// Comandos por arquivo (para automação): escreva "qr", "code" ou "quit" em devhost-cmd.txt.
var cmdFile = Path.Combine(AppPaths.Root, "devhost-cmd.txt");
while (true)
{
    await Task.Delay(500);
    if (!File.Exists(cmdFile)) continue;
    var cmd = File.ReadAllText(cmdFile).Trim();
    File.Delete(cmdFile);
    switch (cmd)
    {
        case "qr":
            var uri = host.BuildPairingUri(host.Pairing.NewQrOffer());
            Console.WriteLine("[pair-uri] " + uri);
            File.WriteAllText(Path.Combine(AppPaths.Root, "devhost-pair.txt"), uri + "\n");
            break;
        case "code":
            var c = (await host.NewPairCodeAsync()).Display;
            Console.WriteLine("[pair-code] " + c);
            File.WriteAllText(Path.Combine(AppPaths.Root, "devhost-pair.txt"), c + "\n");
            break;
        case "quit":
            return;
    }
}
