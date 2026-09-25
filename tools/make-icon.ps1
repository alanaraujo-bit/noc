# Gera o ícone do Noc (ponto de brasa + anel aberto) em PNG e ICO multi-tamanho.
Add-Type -AssemblyName System.Drawing
$out = Join-Path (Split-Path -Parent $PSScriptRoot) 'companion\src\Noc.Companion\Assets'
New-Item -ItemType Directory -Force $out | Out-Null

function Draw-Mark([int]$size, [bool]$tray) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    $s = $size / 108.0
    if (-not $tray) {
        $bg = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 20, 19, 17))
        $r = [int](24 * $s)
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $w = $size - 1
        $path.AddArc(0, 0, 2 * $r, 2 * $r, 180, 90); $path.AddArc($w - 2 * $r, 0, 2 * $r, 2 * $r, 270, 90)
        $path.AddArc($w - 2 * $r, $w - 2 * $r, 2 * $r, 2 * $r, 0, 90); $path.AddArc(0, $w - 2 * $r, 2 * $r, 2 * $r, 90, 90)
        $path.CloseFigure(); $g.FillPath($bg, $path)
    }
    # no ícone do app a marca ocupa a área segura; na bandeja, quase tudo
    $k = if ($tray) { 1.55 } else { 1.18 }
    $c = $size / 2.0
    $ember = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 240, 138, 75))
    $dot = 9 * $s * $k
    $g.FillEllipse($ember, [float]($c - $dot), [float]($c - $dot), [float](2 * $dot), [float](2 * $dot))
    $ringColor = if ($tray) { [System.Drawing.Color]::FromArgb(255, 240, 138, 75) } else { [System.Drawing.Color]::FromArgb(255, 238, 236, 232) }
    $pen = New-Object System.Drawing.Pen $ringColor, ([float]([Math]::Max(1.4, 5 * $s * $k)))
    $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
    $rr = 22 * $s * $k
    # arco aberto no canto superior direito (mesmo do Android: de ~-36° a ~300°)
    $g.DrawArc($pen, [float]($c - $rr), [float]($c - $rr), [float](2 * $rr), [float](2 * $rr), -36, 300)
    $g.Dispose()
    return $bmp
}

$sizes = 16, 20, 24, 32, 40, 48, 64, 128, 256
$pngs = @()
foreach ($sz in $sizes) {
    $b = Draw-Mark $sz $false
    $ms = New-Object System.IO.MemoryStream
    $b.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngs += , $ms.ToArray()
    if ($sz -eq 256) { $b.Save((Join-Path $out 'noc-256.png'), [System.Drawing.Imaging.ImageFormat]::Png) }
    $b.Dispose()
}
foreach ($t in 16, 32) {
    $b = Draw-Mark $t $true
    $b.Save((Join-Path $out "tray-$t.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $b.Dispose()
}

# ICO com entradas PNG
$fs = [System.IO.File]::Create((Join-Path $out 'noc.ico'))
$bw = New-Object System.IO.BinaryWriter $fs
$bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $sz = $sizes[$i]; $len = $pngs[$i].Length
    $bw.Write([byte]($(if ($sz -ge 256) { 0 } else { $sz }))); $bw.Write([byte]($(if ($sz -ge 256) { 0 } else { $sz })))
    $bw.Write([byte]0); $bw.Write([byte]0); $bw.Write([UInt16]1); $bw.Write([UInt16]32)
    $bw.Write([UInt32]$len); $bw.Write([UInt32]$offset)
    $offset += $len
}
foreach ($p in $pngs) { $bw.Write($p) }
$bw.Close()
Write-Output "ícones gerados em $out"
