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
