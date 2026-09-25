using System.Security.Cryptography;
using System.Text;
using Noc.Core.Crypto;

namespace Noc.Core.Security;

public sealed class QrOffer
{
    public required string PairingId { get; init; }
    public required byte[] Secret { get; init; }
    public required DateTimeOffset ExpiresAt { get; init; }
    public bool Used { get; set; }
}

public sealed class CodeOffer
{
    public required string Lookup { get; init; }
    public required string Secret { get; init; }
    public required DateTimeOffset ExpiresAt { get; init; }
    public int Failures { get; set; }
    public bool Used { get; set; }
    public string Display => $"{Lookup}-{Secret}";
}

/// <summary>Pedido de pareamento por código aguardando o "Aprovar" no PC.</summary>
public sealed class ApprovalRequest
{
    public required string DeviceName { get; init; }
    public required string DeviceModel { get; init; }
    public required string Sas { get; init; }
    public required string Route { get; init; }
    internal TaskCompletionSource<bool> Decision { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public void Approve() => Decision.TrySetResult(true);
    public void Reject() => Decision.TrySetResult(false);
}

public enum PairVerdict { Ok, Unknown, Expired, BadProof, Rejected }

/// <summary>
/// Ofertas de pareamento de uso único.
/// QR: a chave pública do PC vai no próprio QR, então não há como haver intermediário.
/// Código: o celular só descobre o PC pelo relay, então o PC exige aprovação com o código de verificação (SAS).
/// </summary>
public sealed class PairingManager
{
    public static readonly TimeSpan QrTtl = TimeSpan.FromMinutes(10);
    public static readonly TimeSpan CodeTtl = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan ApprovalTimeout = TimeSpan.FromMinutes(2);
    private const string CodeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private readonly Lock _lock = new();
    private QrOffer? _qr;
    private CodeOffer? _code;

    public event Action<ApprovalRequest>? ApprovalRequested;
    public event Action? OffersChanged;

    public QrOffer NewQrOffer()
    {
        lock (_lock)
        {
            _qr = new QrOffer
            {
                PairingId = Wire.B64Url(RandomNumberGenerator.GetBytes(9)),
                Secret = RandomNumberGenerator.GetBytes(16),
                ExpiresAt = DateTimeOffset.Now + QrTtl,
            };
        }
        OffersChanged?.Invoke();
        return _qr;
    }

    public QrOffer? CurrentQr
    {
        get { lock (_lock) return _qr is { Used: false } q && q.ExpiresAt > DateTimeOffset.Now ? q : null; }
    }

    public CodeOffer NewCodeOffer()
    {
        lock (_lock)
        {
            _code = new CodeOffer
            {
                Lookup = RandomCode(4),
                Secret = RandomCode(4),
                ExpiresAt = DateTimeOffset.Now + CodeTtl,
            };
        }
        OffersChanged?.Invoke();
        return _code;
    }

    public CodeOffer? CurrentCode
    {
        get { lock (_lock) return _code is { Used: false } c && c.ExpiresAt > DateTimeOffset.Now ? c : null; }
    }

    public void CancelCode()
    {
        lock (_lock) _code = null;
        OffersChanged?.Invoke();
    }

    private static string RandomCode(int n)
    {
        var sb = new StringBuilder(n);
        for (var i = 0; i < n; i++) sb.Append(CodeAlphabet[RandomNumberGenerator.GetInt32(CodeAlphabet.Length)]);
        return sb.ToString();
    }

    public PairVerdict VerifyQr(string pairingId, byte[] th, byte[] deviceSpki, byte[] mac)
    {
        lock (_lock)
        {
            if (_qr is null || !CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(_qr.PairingId), Encoding.ASCII.GetBytes(pairingId)))
                return PairVerdict.Unknown;
            if (_qr.Used || _qr.ExpiresAt < DateTimeOffset.Now) return PairVerdict.Expired;
            var expected = Handshake.PairMac(_qr.Secret, th, deviceSpki);
            if (!CryptographicOperations.FixedTimeEquals(expected, mac)) return PairVerdict.BadProof;
            _qr.Used = true;
        }
        OffersChanged?.Invoke();
        return PairVerdict.Ok;
    }

    public async Task<PairVerdict> VerifyCodeAsync(string lookup, byte[] th, byte[] deviceSpki, byte[] mac,
        string deviceName, string deviceModel, string sas, string route, CancellationToken ct)
    {
        lock (_lock)
        {
            if (_code is null || _code.Lookup != lookup) return PairVerdict.Unknown;
            if (_code.Used || _code.ExpiresAt < DateTimeOffset.Now) return PairVerdict.Expired;
            var expected = Handshake.PairMac(Encoding.UTF8.GetBytes(_code.Secret), th, deviceSpki);
            if (!CryptographicOperations.FixedTimeEquals(expected, mac))
            {
                if (++_code.Failures >= 5) _code = null; // força um código novo depois de tentativas erradas
                return PairVerdict.BadProof;
            }
            _code.Used = true;
        }
        OffersChanged?.Invoke();

        var request = new ApprovalRequest { DeviceName = deviceName, DeviceModel = deviceModel, Sas = sas, Route = route };
        if (ApprovalRequested is null) return PairVerdict.Rejected;
        ApprovalRequested.Invoke(request);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(ApprovalTimeout);
        try
        {
            return await request.Decision.Task.WaitAsync(timeout.Token) ? PairVerdict.Ok : PairVerdict.Rejected;
        }
        catch (OperationCanceledException)
        {
            request.Reject();
            return PairVerdict.Rejected;
        }
    }
}
