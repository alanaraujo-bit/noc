using System.Text;

namespace Noc.Core.Library;

public enum GgufType : uint
{
    U8 = 0, I8 = 1, U16 = 2, I16 = 3, U32 = 4, I32 = 5, F32 = 6, Bool = 7, String = 8, Array = 9, U64 = 10, I64 = 11, F64 = 12,
}

/// <summary>Um par chave/valor do cabeçalho. Arrays grandes (tokenizer) não são materializados, só contados.</summary>
public sealed record GgufKv(string Key, GgufType Type, object? Value, GgufType? ElementType, long Count, long RawStart, long RawEnd);

public sealed record GgufTensor(string Name, ulong[] Dims, uint Type, ulong Offset);

/// <summary>
/// Leitura do cabeçalho de um arquivo GGUF (metadados + tabela de tensores), sem carregar os pesos.
/// Guarda a posição bruta de cada chave para permitir reescrever o cabeçalho sem perder nada (reparo).
/// </summary>
public sealed class GgufFile
{
    public required string Path { get; init; }
    public required long FileSize { get; init; }
    public required uint Version { get; init; }
    public required IReadOnlyDictionary<string, GgufKv> Kv { get; init; }
    public required IReadOnlyList<GgufKv> KvOrder { get; init; }
    public required IReadOnlyList<GgufTensor> Tensors { get; init; }
    public required long DataOffset { get; init; }
    public required int Alignment { get; init; }

    public string? Str(string key) => Kv.TryGetValue(key, out var v) ? v.Value as string : null;

    public long? Int(string key) => Kv.TryGetValue(key, out var v) ? v.Value switch
    {
        byte b => b, sbyte sb => sb, ushort us => us, short s => s, uint u => u, int i => i, ulong ul => (long)ul, long l => l,
        _ => null,
    } : null;

    public object[]? Arr(string key) => Kv.TryGetValue(key, out var v) ? v.Value as object[] : null;

    public string? Architecture => Str("general.architecture");
    public bool IsProjector => Architecture == "clip" || Str("general.type") == "mmproj";
    public string? Name => Str("general.name");
    public string? BaseName => Str("general.basename");
    public string? SizeLabel => Str("general.size_label");
    public long? ParameterCount => Int("general.parameter_count");
    public long? FileType => Int("general.file_type");
    public long? ContextLength => Architecture is { } a ? Int(a + ".context_length") : null;
    public long? EmbeddingLength => Architecture is { } a ? Int(a + ".embedding_length") : null;
    public long? BlockCount => Architecture is { } a ? Int(a + ".block_count") : null;
    public long? ExpertCount => Architecture is { } a ? Int(a + ".expert_count") : null;
    public string? ChatTemplate => Str("tokenizer.chat_template");
    public string? ProjectorType => Str("clip.projector_type") ?? Str("clip.vision.projector_type");
    public long? ProjectionDim => Int("clip.vision.projection_dim");

    /// <summary>O arquivo traz uma torre de visão embutida (formato não usado pelo llama.cpp, que quer um mmproj separado).</summary>
    public bool HasEmbeddedVision =>
        !IsProjector && (Tensors.Any(t => t.Name.StartsWith("v.", StringComparison.Ordinal)) ||
                         Kv.Keys.Any(k => Architecture is { } a && k.StartsWith(a + ".vision.", StringComparison.Ordinal)));

    public bool TemplateSupportsTools => ChatTemplate?.Contains("tools", StringComparison.Ordinal) == true;

    public bool TemplateSupportsThinking =>
        ChatTemplate is { } t && (t.Contains("<think>", StringComparison.Ordinal) || t.Contains("enable_thinking", StringComparison.Ordinal) ||
                                  t.Contains("reasoning", StringComparison.Ordinal) || t.Contains("<|channel|>", StringComparison.Ordinal));

    public static string QuantName(long? fileType) => fileType switch
    {
        0 => "F32", 1 => "F16", 2 => "Q4_0", 3 => "Q4_1", 7 => "Q8_0", 8 => "Q5_0", 9 => "Q5_1", 10 => "Q2_K", 11 => "Q3_K_S",
        12 => "Q3_K_M", 13 => "Q3_K_L", 14 => "Q4_K_S", 15 => "Q4_K_M", 16 => "Q5_K_S", 17 => "Q5_K_M", 18 => "Q6_K",
        19 => "IQ2_XXS", 20 => "IQ2_XS", 21 => "Q2_K_S", 22 => "IQ3_XS", 23 => "IQ3_XXS", 24 => "IQ1_S", 25 => "IQ4_NL",
        26 => "IQ3_S", 27 => "IQ3_M", 28 => "IQ2_S", 29 => "IQ2_M", 30 => "IQ4_XS", 31 => "IQ1_M", 32 => "BF16",
        36 => "TQ1_0", 37 => "TQ2_0", 38 => "MXFP4",
        _ => "?",
    };

    public static bool LooksLikeGguf(string path)
    {
        try
        {
            using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            Span<byte> magic = stackalloc byte[4];
            return fs.Read(magic) == 4 && magic.SequenceEqual("GGUF"u8);
        }
        catch (Exception) { return false; }
    }

    /// <summary>Lê o cabeçalho. Lança <see cref="InvalidDataException"/> se o arquivo não for GGUF válido.</summary>
    public static GgufFile Read(string path)
    {
        using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 20);
        var r = new BinaryReader(fs, Encoding.UTF8, leaveOpen: true);
        if (r.ReadUInt32() != 0x46554747) throw new InvalidDataException("não é GGUF");
        var version = r.ReadUInt32();
        if (version is < 2 or > 3) throw new InvalidDataException($"versão GGUF {version} não suportada");
        var tensorCount = r.ReadUInt64();
        var kvCount = r.ReadUInt64();
        if (tensorCount > 1_000_000 || kvCount > 100_000) throw new InvalidDataException("cabeçalho inválido");

        var kv = new Dictionary<string, GgufKv>(StringComparer.Ordinal);
        var order = new List<GgufKv>();
        for (ulong i = 0; i < kvCount; i++)
        {
            var start = fs.Position;
            var key = ReadString(r);
            var type = (GgufType)r.ReadUInt32();
            object? value;
            GgufType? elem = null;
            long count = 1;
            if (type == GgufType.Array)
            {
                elem = (GgufType)r.ReadUInt32();
                count = (long)r.ReadUInt64();
                // Arrays pequenos viram valores; os grandes (vocabulário) são só pulados.
                if (count <= 256)
                {
                    var arr = new object[count];
                    for (var j = 0; j < count; j++) arr[j] = ReadValue(r, elem.Value)!;
                    value = arr;
                }
                else
                {
                    SkipArray(r, elem.Value, count);
                    value = null;
                }
            }
            else value = ReadValue(r, type);
            var e = new GgufKv(key, type, value, elem, count, start, fs.Position);
            kv[key] = e;
            order.Add(e);
        }

        var tensors = new List<GgufTensor>((int)tensorCount);
        for (ulong i = 0; i < tensorCount; i++)
        {
            var name = ReadString(r);
            var nd = r.ReadUInt32();
            if (nd > 8) throw new InvalidDataException("tensor inválido");
            var dims = new ulong[nd];
            for (var j = 0; j < nd; j++) dims[j] = r.ReadUInt64();
            var t = r.ReadUInt32();
            var off = r.ReadUInt64();
            tensors.Add(new GgufTensor(name, dims, t, off));
        }
        var alignment = kv.TryGetValue("general.alignment", out var al) && al.Value is uint a && a > 0 ? (int)a : 32;
        var dataOffset = Align(fs.Position, alignment);
        return new GgufFile
        {
            Path = path, FileSize = fs.Length, Version = version, Kv = kv, KvOrder = order, Tensors = tensors,
            DataOffset = dataOffset, Alignment = alignment,
        };
    }

    internal static long Align(long v, int a) => (v + a - 1) / a * a;

    private static string ReadString(BinaryReader r)
    {
        var len = r.ReadUInt64();
        if (len > 64 * 1024 * 1024) throw new InvalidDataException("string gigante");
        return Encoding.UTF8.GetString(r.ReadBytes((int)len));
    }

    private static object? ReadValue(BinaryReader r, GgufType t) => t switch
    {
        GgufType.U8 => r.ReadByte(),
        GgufType.I8 => r.ReadSByte(),
        GgufType.U16 => r.ReadUInt16(),
        GgufType.I16 => r.ReadInt16(),
        GgufType.U32 => r.ReadUInt32(),
        GgufType.I32 => r.ReadInt32(),
        GgufType.F32 => r.ReadSingle(),
        GgufType.Bool => r.ReadByte() != 0,
        GgufType.String => ReadString(r),
        GgufType.U64 => r.ReadUInt64(),
        GgufType.I64 => r.ReadInt64(),
        GgufType.F64 => r.ReadDouble(),
        GgufType.Array => throw new InvalidDataException("array aninhado"),
        _ => throw new InvalidDataException($"tipo {t} desconhecido"),
    };

    private static void SkipArray(BinaryReader r, GgufType elem, long count)
    {
        var size = elem switch
        {
            GgufType.U8 or GgufType.I8 or GgufType.Bool => 1,
            GgufType.U16 or GgufType.I16 => 2,
            GgufType.U32 or GgufType.I32 or GgufType.F32 => 4,
            GgufType.U64 or GgufType.I64 or GgufType.F64 => 8,
            _ => 0,
        };
        if (size > 0)
        {
            r.BaseStream.Seek(size * count, SeekOrigin.Current);
            return;
        }
        if (elem != GgufType.String) throw new InvalidDataException("array inválido");
        for (long i = 0; i < count; i++)
        {
            var len = r.ReadUInt64();
            r.BaseStream.Seek((long)len, SeekOrigin.Current);
        }
    }
}

/// <summary>
/// Problemas conhecidos de GGUFs gerados por conversores antigos/alternativos que o llama.cpp atual recusa.
/// O reparo grava uma cópia corrigida (nunca altera o arquivo original do usuário).
/// </summary>
public static class GgufRepair
{
    public sealed record Plan(IReadOnlyList<string> Issues, IReadOnlyList<GgufKvEdit> KvEdits, IReadOnlySet<string> DropKeys,
        IReadOnlySet<string> DropTensors, IReadOnlyDictionary<string, string> RenameTensors)
    {
        public bool NeedsRepair => Issues.Count > 0;
    }

    public sealed record GgufKvEdit(string Key, GgufType Type, object Value, GgufType? ElementType = null);

    public static Plan Analyze(GgufFile f)
    {
        var issues = new List<string>();
        var edits = new List<GgufKvEdit>();
        var dropKeys = new HashSet<string>(StringComparer.Ordinal);
        var dropTensors = new HashSet<string>(StringComparer.Ordinal);
        var rename = new Dictionary<string, string>(StringComparer.Ordinal);
        var arch = f.Architecture;
        if (arch is null || f.IsProjector) return new Plan(issues, edits, dropKeys, dropTensors, rename);

        // Qwen3.5/3.6 (qwen35/qwen35moe) de conversores antigos.
        if (arch.StartsWith("qwen35", StringComparison.Ordinal))
        {
            var dsKey = arch + ".rope.dimension_sections";
            if (f.Kv.TryGetValue(dsKey, out var ds) && ds.Value is object[] sections && sections.Length is > 0 and < 4)
            {
                var padded = sections.Concat(Enumerable.Repeat<object>(0, 4 - sections.Length))
                    .Select(x => (object)Convert.ToInt32(x)).ToArray();
                edits.Add(new GgufKvEdit(dsKey, GgufType.Array, padded, ds.ElementType ?? GgufType.I32));
                issues.Add("rope.dimension_sections com 3 valores");
            }
            var kvKey = arch + ".attention.head_count_kv";
            if (f.Kv.TryGetValue(kvKey, out var hk) && hk.Value is object[] perLayer && perLayer.Length > 0)
            {
                var max = perLayer.Select(x => Convert.ToUInt32(x)).Max();
                edits.Add(new GgufKvEdit(kvKey, GgufType.U32, max));
                issues.Add("head_count_kv por camada");
            }
            foreach (var t in f.Tensors)
                if (System.Text.RegularExpressions.Regex.IsMatch(t.Name, @"^blk\.\d+\.ssm_dt$"))
                    rename[t.Name] = t.Name + ".bias";
            if (rename.Count > 0) issues.Add("nome antigo do tensor ssm_dt");
        }

        // Torre de visão/MTP embutidas com nomes que o llama.cpp não reconhece: o texto funciona sem elas
        // (a visão vem de um mmproj separado).
        var foreign = f.Tensors.Where(t => t.Name.StartsWith("v.", StringComparison.Ordinal) || t.Name.StartsWith("mtp.", StringComparison.Ordinal)).ToList();
        if (foreign.Count > 0)
        {
            foreach (var t in foreign) dropTensors.Add(t.Name);
            foreach (var k in f.Kv.Keys.Where(k => k.StartsWith(arch + ".vision.", StringComparison.Ordinal) ||
                                                   k is var s && (s.EndsWith("image_token_id") || s.EndsWith("vision_start_token_id") || s.EndsWith("vision_end_token_id"))))
                dropKeys.Add(k);
            issues.Add($"{foreign.Count} tensores embutidos fora do padrão (visão/MTP)");
        }
        return new Plan(issues, edits, dropKeys, dropTensors, rename);
    }

    /// <summary>Grava a cópia corrigida em <paramref name="dest"/> (via arquivo temporário). Informa o progresso de 0 a 1.</summary>
    public static async Task WriteAsync(GgufFile f, Plan plan, string dest, IProgress<double>? progress, CancellationToken ct)
    {
        var editByKey = plan.KvEdits.ToDictionary(e => e.Key);
        var keptTensors = f.Tensors.Where(t => !plan.DropTensors.Contains(t.Name)).ToList();

        // Tamanho de cada tensor = distância até o próximo offset (inclui o alinhamento, que se mantém válido).
        var byOffset = f.Tensors.OrderBy(t => t.Offset).ToList();
        var dataLen = f.FileSize - f.DataOffset;
        var sizes = new Dictionary<string, long>(StringComparer.Ordinal);
        for (var i = 0; i < byOffset.Count; i++)
        {
            var end = i + 1 < byOffset.Count ? (long)byOffset[i + 1].Offset : dataLen;
            sizes[byOffset[i].Name] = end - (long)byOffset[i].Offset;
        }

        var tmp = dest + ".part";
        await using (var src = new FileStream(f.Path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 1 << 20, useAsync: true))
        await using (var dst = new FileStream(tmp, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 20, useAsync: true))
        {
            var header = new MemoryStream();
            var w = new BinaryWriter(header, Encoding.UTF8, leaveOpen: true);
            var kvs = f.KvOrder.Where(k => !plan.DropKeys.Contains(k.Key)).ToList();
            w.Write(0x46554747u);
            w.Write(3u);
            w.Write((ulong)keptTensors.Count);
            w.Write((ulong)kvs.Count);
            var raw = new byte[1 << 20];
            foreach (var k in kvs)
            {
                if (editByKey.TryGetValue(k.Key, out var edit))
                {
                    WriteString(w, k.Key);
                    w.Write((uint)edit.Type);
                    if (edit.Type == GgufType.Array)
                    {
                        var arr = (object[])edit.Value;
                        w.Write((uint)edit.ElementType!.Value);
                        w.Write((ulong)arr.Length);
                        foreach (var x in arr) WriteValue(w, edit.ElementType.Value, x);
                    }
                    else WriteValue(w, edit.Type, edit.Value);
                    continue;
                }
                // copia os bytes originais da chave (preserva vocabulários enormes sem reinterpretar)
                w.Flush();
                src.Position = k.RawStart;
                var remaining = k.RawEnd - k.RawStart;
                while (remaining > 0)
                {
                    var n = await src.ReadAsync(raw.AsMemory(0, (int)Math.Min(raw.Length, remaining)), ct);
                    if (n == 0) throw new EndOfStreamException();
                    header.Write(raw, 0, n);
                    remaining -= n;
                }
            }
            ulong offset = 0;
            var copyPlan = new List<(long Src, long Len)>();
            foreach (var t in keptTensors)
            {
                WriteString(w, plan.RenameTensors.TryGetValue(t.Name, out var nn) ? nn : t.Name);
                w.Write((uint)t.Dims.Length);
                foreach (var d in t.Dims) w.Write(d);
                w.Write(t.Type);
                w.Write(offset);
                var len = sizes[t.Name];
                copyPlan.Add((f.DataOffset + (long)t.Offset, len));
                offset += (ulong)len;
            }
            w.Flush();
            var pad = (int)(GgufFile.Align(header.Length, f.Alignment) - header.Length);
            header.Write(new byte[pad]);
            header.Position = 0;
            await header.CopyToAsync(dst, ct);

            var total = copyPlan.Sum(c => c.Len);
            long done = 0;
            var buf = new byte[8 << 20];
            foreach (var (s, len) in copyPlan)
            {
                src.Position = s;
                var left = len;
                while (left > 0)
                {
                    var n = await src.ReadAsync(buf.AsMemory(0, (int)Math.Min(buf.Length, left)), ct);
                    if (n == 0) throw new EndOfStreamException();
                    await dst.WriteAsync(buf.AsMemory(0, n), ct);
                    left -= n;
                    done += n;
                }
                progress?.Report(total == 0 ? 1 : (double)done / total);
            }
        }
        File.Move(tmp, dest, overwrite: true);
    }

    private static void WriteString(BinaryWriter w, string s)
    {
        var b = Encoding.UTF8.GetBytes(s);
        w.Write((ulong)b.Length);
        w.Write(b);
    }

    private static void WriteValue(BinaryWriter w, GgufType t, object v)
    {
        switch (t)
        {
            case GgufType.U8: w.Write(Convert.ToByte(v)); break;
            case GgufType.I8: w.Write(Convert.ToSByte(v)); break;
            case GgufType.U16: w.Write(Convert.ToUInt16(v)); break;
            case GgufType.I16: w.Write(Convert.ToInt16(v)); break;
            case GgufType.U32: w.Write(Convert.ToUInt32(v)); break;
            case GgufType.I32: w.Write(Convert.ToInt32(v)); break;
            case GgufType.F32: w.Write(Convert.ToSingle(v)); break;
            case GgufType.Bool: w.Write((byte)(Convert.ToBoolean(v) ? 1 : 0)); break;
            case GgufType.String: WriteString(w, (string)v); break;
            case GgufType.U64: w.Write(Convert.ToUInt64(v)); break;
            case GgufType.I64: w.Write(Convert.ToInt64(v)); break;
            case GgufType.F64: w.Write(Convert.ToDouble(v)); break;
            default: throw new InvalidOperationException("tipo não suportado");
        }
    }
}
