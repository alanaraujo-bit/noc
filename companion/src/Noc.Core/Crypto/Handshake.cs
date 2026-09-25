using System.Buffers.Binary;
using System.Security.Cryptography;

namespace Noc.Core.Crypto;

public enum HandshakeMode : byte { Session = 0, PairQr = 1, PairCode = 2 }

/// <summary>Resultado do handshake do lado do servidor.</summary>
public sealed class HandshakeResult
{
    public required HandshakeMode Mode { get; init; }
    public required byte[] TranscriptHash { get; init; }
    public required SecureChannel Channel { get; init; }
    public required string Sas { get; init; }
    public required byte[] ServerHello { get; init; }
}

public static class Handshake
{
    public const int EphLen = 65;
    public const int NonceLen = 32;
    public const int ClientHelloLen = 1 + 1 + 1 + EphLen + NonceLen;

    public static byte[] EncodePoint(ECParameters p)
    {
        var buf = new byte[EphLen];
        buf[0] = 0x04;
        p.Q.X!.CopyTo(buf, 1);
        p.Q.Y!.CopyTo(buf, 33);
        return buf;
    }

    public static ECDiffieHellmanPublicKey DecodePoint(ReadOnlySpan<byte> point)
    {
        if (point.Length != EphLen || point[0] != 0x04) throw new ProtocolException("bad_point");
        var parameters = new ECParameters
        {
            Curve = ECCurve.NamedCurves.nistP256,
            Q = new ECPoint { X = point.Slice(1, 32).ToArray(), Y = point.Slice(33, 32).ToArray() },
        };
        // valida que o ponto está na curva
        using var tmp = ECDiffieHellman.Create(parameters);
        return tmp.PublicKey;
    }

    public static byte[] TranscriptHash(ReadOnlySpan<byte> clientHello, ReadOnlySpan<byte> serverHelloUnsigned) =>
        SHA256.HashData(Wire.Concat(Wire.LabelTranscript, Wire.Lp(clientHello), Wire.Lp(serverHelloUnsigned)));

    public static (byte[] c2s, byte[] s2c) DeriveKeys(byte[] z, byte[] th)
    {
        var okm = HKDF.DeriveKey(HashAlgorithmName.SHA256, z, 64, th, Wire.InfoKeys);
        return (okm[..32], okm[32..]);
    }

    public static string DeriveSas(byte[] z, byte[] th)
    {
        var b = HKDF.DeriveKey(HashAlgorithmName.SHA256, z, 4, th, Wire.InfoSas);
        return (BinaryPrimitives.ReadUInt32BigEndian(b) % 1_000_000).ToString("D6");
    }

    /// <summary>
    /// Processa o ClientHello e produz o ServerHello (já assinado).
    /// Nonce e chave efêmera podem ser injetados para testes determinísticos.
    /// </summary>
    public static HandshakeResult ServerRespond(
        ReadOnlySpan<byte> clientHello, ECDsa pcKey, byte[] pcSpki,
        ECDiffieHellman? ephemeral = null, byte[]? nonceS = null)
    {
        if (clientHello.Length != ClientHelloLen || clientHello[0] != Wire.FrameClientHello)
            throw new ProtocolException("bad_hello");
        if (clientHello[1] != Wire.ProtocolVersion) throw new ProtocolException("version");
        var mode = clientHello[2];
        if (mode > 2) throw new ProtocolException("bad_mode");
        var ephC = clientHello.Slice(3, EphLen);
        using var peer = DecodePoint(ephC);

        var eph = ephemeral ?? ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
        try
        {
            nonceS ??= RandomNumberGenerator.GetBytes(NonceLen);
            var ephS = EncodePoint(eph.ExportParameters(false));
            var sh0 = Wire.Concat([Wire.FrameServerHello], ephS, nonceS, Wire.Lp(pcSpki));
            var th = TranscriptHash(clientHello, sh0);
            var sig = pcKey.SignData(Wire.Concat(Wire.LabelServer, th), HashAlgorithmName.SHA256,
                DSASignatureFormat.Rfc3279DerSequence);
            var serverHello = Wire.Concat(sh0, Wire.Lp(sig));
            var z = eph.DeriveRawSecretAgreement(peer);
            var (c2s, s2c) = DeriveKeys(z, th);
            var sas = DeriveSas(z, th);
            CryptographicOperations.ZeroMemory(z);
            return new HandshakeResult
            {
                Mode = (HandshakeMode)mode,
                TranscriptHash = th,
                Channel = new SecureChannel(sendKey: s2c, recvKey: c2s, sendDir: 2, recvDir: 1),
                Sas = sas,
                ServerHello = serverHello,
            };
        }
        finally
        {
            if (ephemeral is null) eph.Dispose();
        }
    }

    public static bool VerifyClientSignature(byte[] clientSpki, byte[] th, byte[] sig)
    {
        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(clientSpki, out _);
            if (key.KeySize != 256) return false;
            return key.VerifyData(Wire.Concat(Wire.LabelClient, th), sig, HashAlgorithmName.SHA256,
                DSASignatureFormat.Rfc3279DerSequence);
        }
        catch (CryptographicException) { return false; }
    }

    public static byte[] PairMac(byte[] secret, byte[] th, byte[] clientSpki) =>
        HMACSHA256.HashData(secret, Wire.Concat(Wire.LabelPair, th, clientSpki));
}
