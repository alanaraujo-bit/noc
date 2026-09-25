using System.Runtime.CompilerServices;
using Noc.Core.ImageEngine;

namespace Noc.Core.Tests;

internal static class TestSetup
{
    /// <summary>Os testes nunca usam o runtime do Companion instalado (ver ImagePaths.HostTag).</summary>
    [ModuleInitializer]
    internal static void Init() => ImagePaths.HostTag ??= "dev";
}
