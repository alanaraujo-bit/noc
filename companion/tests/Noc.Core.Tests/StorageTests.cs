using Noc.Core.Storage;

namespace Noc.Core.Tests;

[Collection("e2e")]
public class StorageTests
{
    [Fact]
    public void Settings_survive_a_zeroed_file_after_power_loss()
    {
        var old = AppPaths.Root;
        AppPaths.Root = Path.Combine(Path.GetTempPath(), "noc-st-" + Guid.NewGuid().ToString("N")[..8]);
        try
        {
            var s = CompanionSettings.Load();
            s.PcName = "Primeiro";
            s.LanPort = 47999;
            s.Save();
            s.PcName = "Segundo";
            s.Save(); // a versão anterior vira cópia de segurança

            // queda de energia: o arquivo principal fica cheio de zeros (como aconteceu de verdade)
            File.WriteAllBytes(AppPaths.Settings, new byte[660]);
            var loaded = CompanionSettings.Load();
            Assert.Equal("Primeiro", loaded.PcName);
            Assert.Equal(47999, loaded.LanPort);

            // a próxima gravação não copia o arquivo estragado por cima da cópia boa
            loaded.Save();
            Assert.Equal("Primeiro", CompanionSettings.Load().PcName);
        }
        finally { AppPaths.Root = old; }
    }
}
