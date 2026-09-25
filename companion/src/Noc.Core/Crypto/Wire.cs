using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Noc.Core.Crypto;

/// <summary>Primitivas de codificação usadas pelo protocolo (ver protocol/PROTOCOL.md).</summary>
public static class Wire
{
    public const byte FrameClientHello = 0x01;
    public const byte FrameServerHello = 0x02;
    public const byte FrameEncrypted = 0x10;
    public const byte FrameError = 0x7F;
    public const byte ProtocolVersion = 1;

    public static readonly byte[] LabelTranscript = Encoding.ASCII.GetBytes("NOC/1/transcript");
    public static readonly byte[] LabelServer = Encoding.ASCII.GetBytes("NOC/1/server");
    public static readonly byte[] LabelClient = Encoding.ASCII.GetBytes("NOC/1/client");
    public static readonly byte[] LabelPair = Encoding.ASCII.GetBytes("NOC/1/pair");
    public static readonly byte[] LabelRelay = Encoding.ASCII.GetBytes("NOC/1/relay");
    public static readonly byte[] InfoKeys = Encoding.ASCII.GetBytes("NOC/1/keys");
    public static readonly byte[] InfoSas = Encoding.ASCII.GetBytes("NOC/1/sas");

    public static byte[] Lp(ReadOnlySpan<byte> data)
    {
        var buf = new byte[4 + data.Length];
        BinaryPrimitives.WriteUInt32BigEndian(buf, (uint)data.Length);
        data.CopyTo(buf.AsSpan(4));
        return buf;
    }

    public static byte[] Concat(params byte[][] parts)
    {
        var total = 0;
        foreach (var p in parts) total += p.Length;
        var buf = new byte[total];
        var o = 0;
        foreach (var p in parts) { p.CopyTo(buf, o); o += p.Length; }
        return buf;
    }

    /// <summary>Lê um campo lp a partir de <paramref name="offset"/>, avançando-o.</summary>
    public static byte[] ReadLp(ReadOnlySpan<byte> data, ref int offset, int maxLen = 4096)
    {
        if (data.Length - offset < 4) throw new ProtocolException("truncated");
        var len = BinaryPrimitives.ReadUInt32BigEndian(data[offset..]);
        offset += 4;
        if (len > maxLen || data.Length - offset < len) throw new ProtocolException("truncated");
        var result = data.Slice(offset, (int)len).ToArray();
        offset += (int)len;
        return result;
    }

    private const string B32 = "abcdefghijklmnopqrstuvwxyz234567";

    public static string Base32(ReadOnlySpan<byte> data)
    {
        var sb = new StringBuilder((data.Length * 8 + 4) / 5);
        int bits = 0, value = 0;
        foreach (var b in data)
        {
            value = (value << 8) | b;
            bits += 8;
            while (bits >= 5)
            {
                sb.Append(B32[(value >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) sb.Append(B32[(value << (5 - bits)) & 31]);
        return sb.ToString();
    }

    /// <summary>pcId / deviceId: 26 primeiros caracteres de base32(SHA256(SPKI)).</summary>
    public static string IdFromSpki(ReadOnlySpan<byte> spki) => Base32(SHA256.HashData(spki))[..26];

    public static string B64Url(ReadOnlySpan<byte> data) =>
        Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    public static byte[] FromB64Url(string s)
    {
        var t = s.Replace('-', '+').Replace('_', '/');
        switch (t.Length % 4) { case 2: t += "=="; break; case 3: t += "="; break; }
        return Convert.FromBase64String(t);
    }
}

public sealed class ProtocolException(string code) : Exception(code)
{
    public string Code { get; } = code;
}
