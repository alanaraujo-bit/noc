using System.Security.Cryptography;
using Noc.Core.Crypto;
using Noc.Core.Storage;

namespace Noc.Core.Security;

/// <summary>
/// Identidade do PC: um par ECDSA P-256 persistido com DPAPI (escopo do usuário do Windows).
/// A chave privada nunca sai deste processo nem trafega pela rede.
/// </summary>
public sealed class PcIdentity : IDisposable
{
    private static readonly byte[] Entropy = "Noc/identity/v1"u8.ToArray();

    public ECDsa Key { get; }
    public byte[] PublicSpki { get; }
    public string PcId { get; }

    private PcIdentity(ECDsa key)
    {
        Key = key;
        PublicSpki = key.ExportSubjectPublicKeyInfo();
        PcId = Wire.IdFromSpki(PublicSpki);
    }

    public static PcIdentity LoadOrCreate()
    {
        var path = AppPaths.Identity;
        if (File.Exists(path))
        {
            var pkcs8 = ProtectedData.Unprotect(File.ReadAllBytes(path), Entropy, DataProtectionScope.CurrentUser);
            try
            {
                var key = ECDsa.Create();
                key.ImportPkcs8PrivateKey(pkcs8, out _);
                return new PcIdentity(key);
            }
            finally
            {
                CryptographicOperations.ZeroMemory(pkcs8);
            }
        }

        var created = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var raw = created.ExportPkcs8PrivateKey();
        AppPaths.WriteAtomic(path, ProtectedData.Protect(raw, Entropy, DataProtectionScope.CurrentUser));
        CryptographicOperations.ZeroMemory(raw);
        return new PcIdentity(created);
    }

    public byte[] Sign(byte[] data) =>
        Key.SignData(data, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);

    /// <summary>Impressão digital curta para mostrar ao usuário (ex.: "k3f9 2mxa").</summary>
    public string Fingerprint => $"{PcId[..4]} {PcId[4..8]}";

    public void Dispose() => Key.Dispose();
}
