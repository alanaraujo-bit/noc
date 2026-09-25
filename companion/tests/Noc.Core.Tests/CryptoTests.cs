using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using Noc.Core.Crypto;

namespace Noc.Core.Tests;

public class CryptoTests
{
    private static byte[] Hex(string s) => Convert.FromHexString(s);
    private static string ToHex(byte[] b) => Convert.ToHexString(b).ToLowerInvariant();

    // Escalares privados fixos (apenas para vetores de teste).
    private const string DephC = "1b9a2c1e5a6f4f0d8e7c6b5a4938271605f4e3d2c1b0a9f8e7d6c5b4a3928170";
    private const string DephS = "2c8b1f7e6d5c4b3a29180706f5e4d3c2b1a0918f7e6d5c4b3a2918070605f4e3";
    private const string Dpc = "3d7a0e6d5c4b3a2918f7e6d5c4b3a291807f6e5d4c3b2a1908f7e6d5c4b3a291";
    private const string Ddev = "4e690d5c4b3a2918f7e6d5c4b3a291807f6e5d4c3b2a1908f7e6d5c4b3a29180";
    private const string NonceC = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private const string NonceS = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    private static ECParameters Priv(string d) => new() { Curve = ECCurve.NamedCurves.nistP256, D = Hex(d) };

    [Fact]
    public void Base32_matches_rfc4648()
    {
        Assert.Equal("my", Wire.Base32("f"u8));
        Assert.Equal("mzxw6ytboi", Wire.Base32("foobar"u8));
    }

    [Fact]
    public void Full_handshake_and_channel_roundtrip()
    {
        using var pcKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var pcSpki = pcKey.ExportSubjectPublicKeyInfo();
        using var devKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var devSpki = devKey.ExportSubjectPublicKeyInfo();

        using var client = new HandshakeClient(HandshakeMode.Session);
        var server = Handshake.ServerRespond(client.ClientHello, pcKey, pcSpki);
        var done = client.Complete(server.ServerHello);

        Assert.Equal(server.TranscriptHash, done.TranscriptHash);
        Assert.Equal(server.Sas, done.Sas);
        Assert.Equal(pcSpki, done.PcSpki);

        var sig = HandshakeClient.SignClient(devKey, done.TranscriptHash);
        Assert.True(Handshake.VerifyClientSignature(devSpki, server.TranscriptHash, sig));

        for (var i = 0; i < 5; i++)
        {
            var msg = Encoding.UTF8.GetBytes($"mensagem {i} — olá");
            Assert.Equal(msg, server.Channel.Open(done.Channel.Seal(msg)));
            Assert.Equal(msg, done.Channel.Open(server.Channel.Seal(msg)));
        }
    }

    [Fact]
    public void Tampered_server_hello_is_rejected()
    {
        using var pcKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var pcSpki = pcKey.ExportSubjectPublicKeyInfo();
        using var client = new HandshakeClient(HandshakeMode.Session);
        var server = Handshake.ServerRespond(client.ClientHello, pcKey, pcSpki);
        var bad = (byte[])server.ServerHello.Clone();
        bad[40] ^= 1;
        Assert.ThrowsAny<Exception>(() => client.Complete(bad));
    }

    [Fact]
    public void Replay_and_reorder_are_rejected()
    {
        using var pcKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        using var client = new HandshakeClient(HandshakeMode.Session);
        var server = Handshake.ServerRespond(client.ClientHello, pcKey, pcKey.ExportSubjectPublicKeyInfo());
        var done = client.Complete(server.ServerHello);
        var f1 = done.Channel.Seal("a"u8);
        var f2 = done.Channel.Seal("b"u8);
        Assert.Throws<ProtocolException>(() => server.Channel.Open(f2));
        server.Channel.Open(f1);
        Assert.Throws<ProtocolException>(() => server.Channel.Open(f1));
        var tampered = (byte[])f2.Clone();
        tampered[^1] ^= 0x80;
        Assert.Throws<ProtocolException>(() => server.Channel.Open(tampered));
    }

    [Fact]
    public void Wrong_client_key_signature_fails()
    {
        using var devKey = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        using var other = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var th = RandomNumberGenerator.GetBytes(32);
        var sig = HandshakeClient.SignClient(devKey, th);
        Assert.False(Handshake.VerifyClientSignature(other.ExportSubjectPublicKeyInfo(), th, sig));
    }

    /// <summary>
    /// Gera/verifica protocol/vectors.json, que o Kotlin também consome.
    /// Com NOC_WRITE_VECTORS=1 o arquivo é reescrito.
    /// </summary>
    [Fact]
    public void Deterministic_vectors()
    {
        using var ephC = ECDiffieHellman.Create(Priv(DephC));
        using var ephS = ECDiffieHellman.Create(Priv(DephS));
        using var pcKey = ECDsa.Create(Priv(Dpc));
        using var devKey = ECDsa.Create(Priv(Ddev));
        var pcSpki = pcKey.ExportSubjectPublicKeyInfo();
        var devSpki = devKey.ExportSubjectPublicKeyInfo();

        using var client = new HandshakeClient(HandshakeMode.PairQr, ephC, Hex(NonceC));
        var server = Handshake.ServerRespond(client.ClientHello, pcKey, pcSpki, ephS, Hex(NonceS));
        var done = client.Complete(server.ServerHello);

        var z = ephC.DeriveRawSecretAgreement(ephS.PublicKey);
        var (c2s, s2c) = Handshake.DeriveKeys(z, server.TranscriptHash);
        var frame0 = done.Channel.Seal("{\"k\":\"hello\"}"u8);
        var frameS0 = server.Channel.Seal("olá do PC"u8);
        var secret = Hex("0f0e0d0c0b0a09080706050403020100");
        var mac = Handshake.PairMac(secret, server.TranscriptHash, devSpki);
        var devSig = HandshakeClient.SignClient(devKey, server.TranscriptHash);

        var node = new JsonObject
        {
            ["note"] = "Vetores determinísticos do protocolo Noc v1. Gerados por Noc.Core.Tests.",
            ["ephC_d"] = DephC, ["ephS_d"] = DephS, ["pc_d"] = Dpc, ["dev_d"] = Ddev,
            ["nonceC"] = NonceC, ["nonceS"] = NonceS,
            ["pcSpki"] = ToHex(pcSpki), ["devSpki"] = ToHex(devSpki),
            ["pcId"] = Wire.IdFromSpki(pcSpki), ["deviceId"] = Wire.IdFromSpki(devSpki),
            ["clientHello"] = ToHex(client.ClientHello),
            ["serverHelloUnsigned"] = ToHex(server.ServerHello[..(1 + 65 + 32 + 4 + pcSpki.Length)]),
            ["serverHello"] = ToHex(server.ServerHello),
            ["transcriptHash"] = ToHex(server.TranscriptHash),
            ["z"] = ToHex(z), ["kC2S"] = ToHex(c2s), ["kS2C"] = ToHex(s2c), ["sas"] = server.Sas,
            ["frameC2S0_plain"] = "{\"k\":\"hello\"}", ["frameC2S0"] = ToHex(frame0),
            ["frameS2C0_plain"] = "olá do PC", ["frameS2C0"] = ToHex(frameS0),
            ["pairSecret"] = "0f0e0d0c0b0a09080706050403020100", ["pairMac"] = ToHex(mac),
            ["deviceSig"] = ToHex(devSig),
        };
        Assert.Equal(server.Sas, done.Sas);
        Assert.True(Handshake.VerifyClientSignature(devSpki, server.TranscriptHash, devSig));

        var path = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../../../protocol/vectors.json"));
        if (Environment.GetEnvironmentVariable("NOC_WRITE_VECTORS") == "1" || !File.Exists(path))
        {
            File.WriteAllText(path, node.ToJsonString(new JsonSerializerOptions { WriteIndented = true, Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }));
            return;
        }
        // O arquivo existente precisa bater nos campos determinísticos.
        var existing = JsonNode.Parse(File.ReadAllText(path))!;
        foreach (var key in new[] { "pcSpki", "devSpki", "pcId", "deviceId", "clientHello", "serverHelloUnsigned", "transcriptHash", "z", "kC2S", "kS2C", "sas", "frameC2S0", "frameS2C0", "pairMac" })
            Assert.Equal(existing[key]!.GetValue<string>(), node[key]!.GetValue<string>());
        // A assinatura ECDSA é aleatória: a do arquivo (possivelmente gerada por outro lado) tem que verificar.
        Assert.True(Handshake.VerifyClientSignature(devSpki, server.TranscriptHash, Hex(existing["deviceSig"]!.GetValue<string>())));
        // O ServerHello salvo (assinatura antiga) também tem que ser aceito por um cliente com o mesmo ClientHello.
        using var ephC2 = ECDiffieHellman.Create(Priv(DephC));
        using var client2 = new HandshakeClient(HandshakeMode.PairQr, ephC2, Hex(NonceC));
        client2.Complete(Hex(existing["serverHello"]!.GetValue<string>()));
    }
}
