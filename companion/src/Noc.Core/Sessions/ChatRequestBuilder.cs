using System.Text.Json.Nodes;

namespace Noc.Core.Sessions;

/// <summary>
/// Monta o corpo de /v1/chat/completions a partir do pedido do celular.
/// Só passa campos conhecidos e dentro de faixas válidas: o celular nunca injeta parâmetros arbitrários.
/// </summary>
public static class ChatRequestBuilder
{
    private const int MaxMessages = 2000;

    public static JsonObject Build(JsonObject p)
    {
        var messages = p["messages"] as JsonArray ?? throw new RpcException("bad_request", "sem mensagens");
        if (messages.Count == 0 || messages.Count > MaxMessages) throw new RpcException("bad_request", "quantidade de mensagens inválida");

        var outMessages = new JsonArray();
        foreach (var m in messages)
        {
            var role = m?["role"]?.GetValue<string>();
            if (role is not ("system" or "user" or "assistant")) throw new RpcException("bad_request", "papel inválido");
            var content = m!["content"];
            JsonNode outContent;
            if (content is JsonValue v && v.TryGetValue<string>(out var text))
            {
                outContent = text;
            }
            else if (content is JsonArray parts)
            {
                var arr = new JsonArray();
                foreach (var part in parts)
                {
                    var type = part?["type"]?.GetValue<string>();
                    if (type == "text")
                        arr.Add(new JsonObject { ["type"] = "text", ["text"] = part!["text"]?.GetValue<string>() ?? "" });
                    else if (type == "image_url")
                    {
                        var url = part!["image_url"]?["url"]?.GetValue<string>() ?? "";
                        if (!url.StartsWith("data:image/", StringComparison.Ordinal)) throw new RpcException("bad_request", "imagem inválida");
                        arr.Add(new JsonObject { ["type"] = "image_url", ["image_url"] = new JsonObject { ["url"] = url } });
                    }
                    else if (type == "image_ref")
                    {
                        // imagem já enviada ao PC (blob.put): o pedido só cita o hash
                        var hash = part!["hash"]?.GetValue<string>() ?? "";
                        var mime = part["mime"]?.GetValue<string>() ?? "image/jpeg";
                        if (!Jobs.BlobStore.ValidHash(hash) || !mime.StartsWith("image/", StringComparison.Ordinal))
                            throw new RpcException("bad_request", "imagem inválida");
                        arr.Add(new JsonObject { ["type"] = "image_ref", ["hash"] = hash, ["mime"] = mime });
                    }
                }
                outContent = arr;
            }
            else throw new RpcException("bad_request", "conteúdo inválido");
            outMessages.Add(new JsonObject { ["role"] = role, ["content"] = outContent });
        }

        var req = new JsonObject { ["messages"] = outMessages };
        var prm = p["params"] as JsonObject ?? new JsonObject();

        CopyDouble(prm, req, "temperature", 0, 5);
        CopyDouble(prm, req, "top_p", 0, 1);
        CopyInt(prm, req, "top_k", 0, 1000);
        CopyDouble(prm, req, "min_p", 0, 1);
        CopyDouble(prm, req, "repeat_penalty", 0.5, 3);
        CopyDouble(prm, req, "presence_penalty", -2, 2);
        CopyDouble(prm, req, "frequency_penalty", -2, 2);
        CopyInt(prm, req, "max_tokens", 1, 1_000_000);
        CopyInt(prm, req, "seed", int.MinValue, int.MaxValue);

        if (prm["stop"] is JsonArray stops)
        {
            var clean = new JsonArray();
            foreach (var s in stops.Take(8))
                if (s?.GetValue<string>() is { Length: > 0 and <= 64 } str) clean.Add(str);
            if (clean.Count > 0) req["stop"] = clean;
        }

        // Raciocínio: "off" desliga (reasoning_effort=none); low/medium/high ajustam o esforço; "on"/ausente = padrão do modelo.
        switch (prm["reasoning"]?.GetValue<string>())
        {
            case "off": req["reasoning_effort"] = "none"; break;
            case "low": req["reasoning_effort"] = "low"; break;
            case "medium": req["reasoning_effort"] = "medium"; break;
            case "high": req["reasoning_effort"] = "high"; break;
        }
        return req;
    }

    /// <summary>Quantas imagens o pedido carrega (para o estado "Processando imagem").</summary>
    public static int CountImages(JsonObject request) =>
        (request["messages"] as JsonArray)?.Sum(m => (m?["content"] as JsonArray)?.Count(p => p?["type"]?.GetValue<string>() is "image_url" or "image_ref") ?? 0) ?? 0;

    /// <summary>Troca as referências de imagem pelos bytes (data URL) na hora de mandar para o LM Studio.</summary>
    public static JsonObject ResolveImages(JsonObject request, Func<string, byte[]?> blob, bool vision)
    {
        var copy = (JsonObject)request.DeepClone();
        var messages = copy["messages"] as JsonArray;
        if (messages is null) return copy;
        var lastUser = messages.Select((m, i) => (m, i)).LastOrDefault(x => x.m?["role"]?.GetValue<string>() == "user").i;
        for (var i = 0; i < messages.Count; i++)
        {
            if (messages[i]?["content"] is not JsonArray parts) continue;
            var outParts = new JsonArray();
            var dropped = 0;
            foreach (var part in parts)
            {
                var type = part?["type"]?.GetValue<string>();
                if (type is "image_ref" or "image_url")
                {
                    if (!vision)
                    {
                        if (i == lastUser) throw new Jobs.JobFailure("no_vision", "Este modelo não entende imagens. Escolha um modelo com visão.");
                        dropped++;
                        continue;
                    }
                    if (type == "image_ref")
                    {
                        var hash = part!["hash"]!.GetValue<string>();
                        var bytes = blob(hash) ?? throw new Jobs.JobFailure("blob_missing", "Uma imagem desta conversa não está mais no PC.");
                        outParts.Add(new JsonObject
                        {
                            ["type"] = "image_url",
                            ["image_url"] = new JsonObject { ["url"] = $"data:{part["mime"]!.GetValue<string>()};base64,{Convert.ToBase64String(bytes)}" },
                        });
                        continue;
                    }
                }
                outParts.Add(part!.DeepClone());
            }
            if (dropped > 0) outParts.Add(new JsonObject { ["type"] = "text", ["text"] = $"\n[{dropped} imagem(ns) enviada(s) antes; este modelo não vê imagens]" });
            messages[i]!["content"] = outParts;
        }
        return copy;
    }

    private static void CopyDouble(JsonObject from, JsonObject to, string key, double min, double max)
    {
        if (from[key] is JsonValue v && v.TryGetValue<double>(out var d) && !double.IsNaN(d))
            to[key] = Math.Clamp(d, min, max);
    }

    private static void CopyInt(JsonObject from, JsonObject to, string key, long min, long max)
    {
        if (from[key] is JsonValue v && v.TryGetValue<double>(out var d) && !double.IsNaN(d))
            to[key] = (long)Math.Clamp(Math.Round(d), min, max);
    }
}
