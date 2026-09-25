using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace Noc.Core.Platform;

public static class NetworkInfo
{
    private static readonly string[] VirtualHints = ["vEthernet", "VirtualBox", "VMware", "Hyper-V", "WSL", "Loopback", "Bluetooth", "TAP", "Tailscale", "ZeroTier", "Npcap"];

    /// <summary>
    /// Endereços IPv4 privados das interfaces físicas ativas (Wi-Fi/Ethernet), em ordem de preferência.
    /// São enviados ao celular (dentro do canal cifrado e no QR) para a conexão direta na rede local.
    /// </summary>
    public static IReadOnlyList<string> LanAddresses()
    {
        var result = new List<(string Ip, int Rank)>();
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
                if (VirtualHints.Any(h => ni.Name.Contains(h, StringComparison.OrdinalIgnoreCase) || ni.Description.Contains(h, StringComparison.OrdinalIgnoreCase))) continue;
                var props = ni.GetIPProperties();
                var hasGateway = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                foreach (var ua in props.UnicastAddresses)
                {
                    if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                    if (!IsPrivate(ua.Address)) continue;
                    var rank = (hasGateway ? 0 : 10) + (ni.NetworkInterfaceType == NetworkInterfaceType.Ethernet ? 0 : 1);
                    result.Add((ua.Address.ToString(), rank));
                }
            }
        }
        catch (NetworkInformationException) { }
        return result.OrderBy(r => r.Rank).Select(r => r.Ip).Distinct().Take(4).ToList();
    }

    public static bool IsPrivate(IPAddress ip)
    {
        var b = ip.GetAddressBytes();
        return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) || (b[0] == 192 && b[1] == 168) || (b[0] == 100 && b[1] >= 64 && b[1] <= 127);
    }
}
