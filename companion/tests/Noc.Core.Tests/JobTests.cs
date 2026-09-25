using System.Text.Json.Nodes;
using Noc.Core.Jobs;

namespace Noc.Core.Tests;

public class JobTests
{
    [Fact]
    public void Subscribe_replays_everything_in_order()
    {
        var job = new ChatJob { Id = "abcdefgh", Model = "m", DeviceId = "d" };
        job.AnnounceQueue(1);
        job.SetPhase(JobState.Loading, "starting");
        var got = new List<JsonObject>();
        using var sub = job.Subscribe(0, got.Add);
        job.SetPhase(JobState.Preparing, "preparing");
        Assert.Equal([1, 2, 3], got.Select(e => e["seq"]!.GetValue<int>()));
        Assert.Equal("queued", got[0]["phase"]!.GetValue<string>());
    }
}
