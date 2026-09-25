using System.Buffers.Binary;
using System.Security.Cryptography;

namespace Noc.Core.Crypto;

/// <summary>
/// Canal AES-256-GCM com contadores estritos por direção.
/// Frame: 0x10 ‖ counter(u64 BE) ‖ ciphertext ‖ tag(16).
/// </summary>
public sealed class SecureChannel : IDisposable
{
    private const int TagLen = 16;
    private const int HeaderLen = 9;
    private readonly AesGcm _send;
    private readonly AesGcm _recv;
    private readonly uint _sendDir;
    private readonly uint _recvDir;
    private ulong _sendCounter;
    private ulong _recvCounter;
    private readonly Lock _sendLock = new();
    private readonly Lock _recvLock = new();
    private static readonly byte[] Aad = [Wire.FrameEncrypted];

    public SecureChannel(byte[] sendKey, byte[] recvKey, uint sendDir, uint recvDir)
    {
        _send = new AesGcm(sendKey, TagLen);
        _recv = new AesGcm(recvKey, TagLen);
        _sendDir = sendDir;
        _recvDir = recvDir;
        CryptographicOperations.ZeroMemory(sendKey);
        CryptographicOperations.ZeroMemory(recvKey);
    }

    private static byte[] Nonce(uint dir, ulong counter)
    {
        var n = new byte[12];
        BinaryPrimitives.WriteUInt32BigEndian(n, dir);
        BinaryPrimitives.WriteUInt64BigEndian(n.AsSpan(4), counter);
        return n;
    }

    public byte[] Seal(ReadOnlySpan<byte> plaintext)
    {
        lock (_sendLock)
        {
            var frame = new byte[HeaderLen + plaintext.Length + TagLen];
            frame[0] = Wire.FrameEncrypted;
            BinaryPrimitives.WriteUInt64BigEndian(frame.AsSpan(1), _sendCounter);
            _send.Encrypt(Nonce(_sendDir, _sendCounter), plaintext,
                frame.AsSpan(HeaderLen, plaintext.Length),
                frame.AsSpan(HeaderLen + plaintext.Length, TagLen), Aad);
            _sendCounter++;
            return frame;
        }
    }

    public byte[] Open(ReadOnlySpan<byte> frame)
    {
        lock (_recvLock)
        {
            if (frame.Length < HeaderLen + TagLen || frame[0] != Wire.FrameEncrypted)
                throw new ProtocolException("bad_frame");
            var counter = BinaryPrimitives.ReadUInt64BigEndian(frame[1..]);
            if (counter != _recvCounter) throw new ProtocolException("replay");
            var ctLen = frame.Length - HeaderLen - TagLen;
            var pt = new byte[ctLen];
            try
            {
                _recv.Decrypt(Nonce(_recvDir, counter), frame.Slice(HeaderLen, ctLen),
                    frame.Slice(HeaderLen + ctLen, TagLen), pt, Aad);
            }
            catch (AuthenticationTagMismatchException)
            {
                throw new ProtocolException("auth_tag");
            }
            _recvCounter++;
            return pt;
        }
    }

    public void Dispose()
    {
        _send.Dispose();
        _recv.Dispose();
    }
}
