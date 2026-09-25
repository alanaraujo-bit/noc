using System.Security.Cryptography;

namespace Noc.Core.Crypto;

/// <summary>
/// Lado cliente do handshake. O app Android tem a implementação equivalente em Kotlin.
/// Aqui ele serve para os testes e para o autoteste de acesso remoto do Companion.
/// </summary>
public sealed class HandshakeClient : IDisposable
{
    private readonly ECDiffieHellman _eph;
    public byte[] ClientHello { get; }

    public HandshakeClient(HandshakeMode mode, ECDiffieHellman? ephemeral = null, byte[]? nonce = null)
    {
        _eph = ephemeral ?? ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        nonce ??= RandomNumberGenerator.GetBytes(Handshake.NonceLen);
        ClientHello = Wire.Concat(
            [Wire.FrameClientHello, Wire.ProtocolVersion, (byte)mode],
            Handshake.EncodePoint(_eph.ExportParameters(false)),
            nonce);
    }

    public sealed record Completed(byte[] PcSpki, byte[] TranscriptHash, SecureChannel Channel, string Sas);

    public Completed Complete(ReadOnlySpan<byte> serverHello)
    {
        if (serverHello.Length < 1 + Handshake.EphLen + Handshake.NonceLen + 8 || serverHello[0] != Wire.FrameServerHello)
            throw new ProtocolException("bad_server_hello");
        var ephS = serverHello.Slice(1, Handshake.EphLen);
        var off = 1 + Handshake.EphLen + Handshake.NonceLen;
        var pcSpki = Wire.ReadLp(serverHello, ref off);
        var sh0Len = off;
        var sig = Wire.ReadLp(serverHello, ref off, 256);
        if (off != serverHello.Length) throw new ProtocolException("bad_server_hello");

        var th = Handshake.TranscriptHash(ClientHello, serverHello[..sh0Len]);
        using (var pcKey = ECDsa.Create())
        {
            pcKey.ImportSubjectPublicKeyInfo(pcSpki, out _);
            if (!pcKey.VerifyData(Wire.Concat(Wire.LabelServer, th), sig, HashAlgorithmName.SHA256,
                    DSASignatureFormat.Rfc3279DerSequence))
                throw new ProtocolException("server_signature");
        }
        using var peer = Handshake.DecodePoint(ephS);
        var z = _eph.DeriveRawSecretAgreement(peer);
        var (c2s, s2c) = Handshake.DeriveKeys(z, th);
        var sas = Handshake.DeriveSas(z, th);
        CryptographicOperations.ZeroMemory(z);
        return new Completed(pcSpki, th, new SecureChannel(sendKey: c2s, recvKey: s2c, sendDir: 1, recvDir: 2), sas);
    }

    public static byte[] SignClient(ECDsa deviceKey, byte[] th) =>
        deviceKey.SignData(Wire.Concat(Wire.LabelClient, th), HashAlgorithmName.SHA256,
            DSASignatureFormat.Rfc3279DerSequence);

    public void Dispose() => _eph.Dispose();
}
