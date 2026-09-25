# Rola a janela de um processo com a roda do mouse: winscroll.ps1 -Name Noc -Clicks -10 (negativo = para baixo)
param([string]$Name = 'Noc', [int]$Clicks = -5, [int]$X = 600, [int]$Y = 400)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class S {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint dx, uint dy, int data, UIntPtr extra);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
"@
[S]::SetProcessDPIAware() | Out-Null
$p = Get-Process $Name | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
$r = New-Object S+RECT
[S]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
[S]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
[S]::SetCursorPos($r.L + $X, $r.T + $Y) | Out-Null
Start-Sleep -Milliseconds 150
$step = if ($Clicks -lt 0) { -120 } else { 120 }
for ($i = 0; $i -lt [Math]::Abs($Clicks); $i++) { [S]::mouse_event(0x0800, 0, 0, $step, [UIntPtr]::Zero); Start-Sleep -Milliseconds 40 }
