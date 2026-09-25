# Clica numa posição relativa à janela de um processo: winclick.ps1 -Name Noc -X 940 -Y 113
param([string]$Name = 'Noc', [int]$X, [int]$Y)
Add-Type @"
using System;
using System.Runtime.InteropServices;
public static class M {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
  [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
  [DllImport("user32.dll")] public static extern void mouse_event(uint f, uint x, uint y, uint d, UIntPtr e);
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
}
"@
[M]::SetProcessDPIAware() | Out-Null
$p = Get-Process -Name $Name | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
[M]::SetForegroundWindow($p.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 300
$r = New-Object M+RECT
[M]::GetWindowRect($p.MainWindowHandle, [ref]$r) | Out-Null
[M]::SetCursorPos($r.L + $X, $r.T + $Y) | Out-Null
Start-Sleep -Milliseconds 100
[M]::mouse_event(0x2, 0, 0, 0, [UIntPtr]::Zero)
[M]::mouse_event(0x4, 0, 0, 0, [UIntPtr]::Zero)
